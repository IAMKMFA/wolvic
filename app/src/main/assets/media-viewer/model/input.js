// Input for the model viewer.
//
// XR contract (extends the media-viewer conventions):
//   trigger  — panel UI when pointing at the panel; on the model: quick click
//              = part identify, hold+move = grab rotate; elsewhere = reveal panel
//   squeeze  — grab rotate when pointing at the model, panel toggle otherwise
//   2nd hand — grabbing while grabbed = two-hand scale (+ yaw)
//   stick X  — orbit the model (the MODEL turns, never the camera — comfort)
//   stick Y  — dolly the model nearer/farther (0.7–6 m)
//   stick click — reset view
// Desktop parity: OrbitControls + pointer raycasts + keyboard.

import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";

const STICK_DEADZONE = 0.25;
const ORBIT_SPEED = Math.PI / 2;   // 90°/s at full stick
const DOLLY_SPEED = 1.6;           // m/s at full stick
const DOLLY_MIN = 0.7;
const DOLLY_MAX = 6.0;
const GRAB_GAIN = 1.4;
const SCALE_MIN_FACTOR = 0.25;
const SCALE_MAX_FACTOR = 8.0;
const CLICK_MAX_MS = 220;
const CLICK_MAX_ANGLE = 0.06;      // radians of controller travel before it's a drag

const ORANGE = new THREE.Color("#F04E23");
const WHITE = new THREE.Color("#FFFFFF");

// Per-frame scratch for the XR input loop (runs every frame, per controller).
// updateXR calls watchSelectCandidates → applyGrab → pollSticks → (per
// controller) rayFromController strictly in sequence, so these slots can be
// shared: no two simultaneously-live values use the same slot, and nothing
// escapes (raycaster.set and quaternion.copy take value copies). Persistent
// state (grab.lastQuat) is copied INTO an owned quaternion, never aliased here.
const _sv0 = new THREE.Vector3();
const _sv1 = new THREE.Vector3();
const _sq0 = new THREE.Quaternion();
const _sq1 = new THREE.Quaternion();
const _sq2 = new THREE.Quaternion();
const _UP = new THREE.Vector3(0, 1, 0);

export class ModelInput {
  constructor({ renderer, scene, camera, pivot, panel, chip, onAction }) {
    this.renderer = renderer;
    this.scene = scene;
    this.camera = camera;
    this.pivot = pivot;
    this.panel = panel;
    this.chip = chip;
    this.onAction = onAction;

    this.raycaster = new THREE.Raycaster();
    this.modelSphere = new THREE.Sphere(new THREE.Vector3(), 1);
    this.modelRoot = null;
    this.partsEnabled = true;

    this.grab = null;          // { controller, lastQuat }
    this.scaleGrab = null;     // { startDist, startScale, startAzimuth, startQuat }
    this.scrubbing = false;
    this.pressedRegion = null;
    this.revealConsumed = false;
    this.stickReady = [true, true];

    this.controllers = [];
    if (renderer.xr) {
      for (let i = 0; i < 2; i++) {
        const controller = renderer.xr.getController(i);
        controller.userData.index = i;
        controller.userData.hover = null;
        controller.addEventListener("selectstart", (e) => this.onSelectStart(controller, e));
        controller.addEventListener("selectend", (e) => this.onSelectEnd(controller, e));
        controller.addEventListener("squeezestart", (e) => this.onSqueezeStart(controller, e));
        controller.addEventListener("squeezeend", (e) => this.onSqueezeEnd(controller, e));
        controller.add(this.buildLaser());
        scene.add(controller);
        this.controllers.push(controller);
      }
    }

    // Desktop.
    this.controls = null;
    this.pointerPanel = false;
    this.pointerDownAt = 0;
    this.pointerMoved = 0;
    this.lastPointer = { x: 0, y: 0 };
  }

  /* ---- shared helpers ----------------------------------------------------- */

  setModel(root) {
    this.modelRoot = root;
    this.refreshBounds();
  }

  refreshBounds() {
    if (!this.modelRoot) return;
    const box = new THREE.Box3().setFromObject(this.pivot);
    if (!box.isEmpty()) box.getBoundingSphere(this.modelSphere);
  }

  buildLaser() {
    const group = new THREE.Group();
    group.name = "FramatomeLaser";
    const lineGeo = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0, 0, 0), new THREE.Vector3(0, 0, -1),
    ]);
    const line = new THREE.Line(lineGeo, new THREE.LineBasicMaterial({
      color: ORANGE, transparent: true, opacity: 0.85, depthTest: false,
    }));
    line.material.toneMapped = false;
    line.name = "beam";
    line.scale.z = 3;
    line.renderOrder = 998;
    const cursor = new THREE.Mesh(
      new THREE.SphereGeometry(0.011, 10, 10),
      new THREE.MeshBasicMaterial({ color: WHITE, depthTest: false })
    );
    cursor.material.toneMapped = false;
    cursor.name = "cursor";
    cursor.visible = false;
    cursor.renderOrder = 998;
    group.add(line);
    group.userData.cursor = cursor;
    this.sceneCursor(group, cursor);
    return group;
  }

  sceneCursor(group, cursor) {
    // The cursor lives in world space (placed at the hit point each frame).
    this.scene.add(cursor);
    group.userData.cursor = cursor;
  }

  rayFromController(controller) {
    // raycaster.set copies origin/direction into the ray's own vectors, so the
    // scratch is free to reuse the moment this returns.
    controller.getWorldPosition(_sv0);
    _sv1.set(0, 0, -1).applyQuaternion(controller.getWorldQuaternion(_sq0));
    this.raycaster.set(_sv0, _sv1);
    return this.raycaster;
  }

  modelHover() {
    if (!this.modelRoot) return false;
    return this.raycaster.ray.intersectsSphere(this.modelSphere);
  }

  pickPart(raycaster) {
    if (!this.modelRoot || !this.partsEnabled) return null;
    const hits = raycaster.intersectObject(this.modelRoot, true);
    if (!hits.length) return null;
    return { mesh: hits[0].object, point: hits[0].point };
  }

  pulse(controller, intensity, ms) {
    try {
      const source = controller && controller.userData.inputSource;
      const actuator = source && source.gamepad && source.gamepad.hapticActuators
        && source.gamepad.hapticActuators[0];
      if (actuator && actuator.pulse) actuator.pulse(intensity, ms);
    } catch (e) { /* best-effort */ }
  }

  /* ---- XR event handlers --------------------------------------------------- */

  onSelectStart(controller, event) {
    controller.userData.inputSource = event.data || controller.userData.inputSource;
    const now = performance.now();
    const ray = this.rayFromController(controller);
    const panelHit = this.panel.hitFromRaycaster(ray);

    if (this.panel.alpha(now) <= 0) {
      this.revealConsumed = true;
      this.panel.show(now, this.camera, true);
      return;
    }
    if (panelHit && panelHit.region === "seek") {
      this.scrubbing = true;
      this.panel.set({ scrubbing: true });
      this.onAction("seek", this.panel.seekFractionAt(panelHit.px));
      return;
    }
    if (panelHit && panelHit.region) {
      this.pressedRegion = panelHit.region;
      this.panel.pressed = panelHit.region;
      this.panel.dirty = true;
      return;
    }
    if (this.modelHover()) {
      // Becomes a grab on movement; a quick clean release is a part-ID click.
      controller.userData.selectGrab = {
        startQuat: controller.getWorldQuaternion(new THREE.Quaternion()),
        startedAt: now,
        promoted: false,
      };
      return;
    }
    this.panel.show(now, this.camera, false);
  }

  onSelectEnd(controller) {
    const now = performance.now();
    if (this.revealConsumed) { this.revealConsumed = false; return; }

    if (this.scrubbing) {
      this.scrubbing = false;
      this.panel.set({ scrubbing: false });
      this.onAction("seekCommit");
      this.panel.keepAlive(now);
      this.pulse(controller, 0.6, 24);
      return;
    }

    const candidate = controller.userData.selectGrab;
    controller.userData.selectGrab = null;
    if (candidate && !candidate.promoted) {
      // Clean click on the model: part identification.
      const ray = this.rayFromController(controller);
      const pick = this.pickPart(ray);
      if (pick) {
        this.onAction("partPick", pick);
        this.pulse(controller, 0.5, 18);
      }
      return;
    }
    if (candidate && candidate.promoted) {
      this.endGrab(controller);
      return;
    }

    const ray = this.rayFromController(controller);
    const panelHit = this.panel.hitFromRaycaster(ray);
    const region = panelHit && panelHit.region;
    const pressed = this.pressedRegion;
    this.pressedRegion = null;
    this.panel.pressed = null;
    this.panel.dirty = true;
    if (region && region === pressed) {
      this.onAction(region);
      this.panel.keepAlive(now);
      this.pulse(controller, 0.6, 24);
    }
  }

  onSqueezeStart(controller, event) {
    controller.userData.inputSource = event.data || controller.userData.inputSource;
    const now = performance.now();
    this.rayFromController(controller);
    if (this.modelHover()) {
      this.beginGrab(controller);
      this.pulse(controller, 0.4, 14);
      return;
    }
    if (this.panel.alpha(now) > 0 && this.panel.state.mode === "media") this.panel.hide();
    else this.panel.show(now, this.camera, true);
  }

  onSqueezeEnd(controller) {
    this.endGrab(controller);
  }

  /* ---- grab / scale -------------------------------------------------------- */

  beginGrab(controller) {
    if (this.grab && this.grab.controller !== controller) {
      // Second hand joins: two-hand scale.
      const a = new THREE.Vector3(); this.grab.controller.getWorldPosition(a);
      const b = new THREE.Vector3(); controller.getWorldPosition(b);
      this.scaleGrab = {
        other: controller,
        startDist: Math.max(0.05, a.distanceTo(b)),
        startScale: this.pivot.scale.x,
        startAzimuth: Math.atan2(b.x - a.x, b.z - a.z),
        startQuat: this.pivot.quaternion.clone(),
      };
      return;
    }
    this.grab = {
      controller,
      lastQuat: controller.getWorldQuaternion(new THREE.Quaternion()),
    };
  }

  endGrab(controller) {
    if (this.scaleGrab && this.scaleGrab.other === controller) {
      this.scaleGrab = null;
      return;
    }
    if (this.grab && this.grab.controller === controller) {
      this.grab = null;
      // If the other hand was scaling, it becomes the rotating hand.
      if (this.scaleGrab) {
        this.grab = {
          controller: this.scaleGrab.other,
          lastQuat: this.scaleGrab.other.getWorldQuaternion(new THREE.Quaternion()),
        };
        this.scaleGrab = null;
      }
      this.refreshBounds();
    }
  }

  applyGrab() {
    if (!this.grab) return;
    const controller = this.grab.controller;
    const current = controller.getWorldQuaternion(_sq0);
    // delta = current * inverse(lastQuat)
    _sq1.copy(this.grab.lastQuat).invert();
    const delta = _sq2.copy(current).multiply(_sq1);
    // Gain: scale the delta's angle.
    const angle = 2 * Math.acos(Math.min(1, Math.abs(delta.w)));
    if (angle > 1e-4) {
      _sv0.set(delta.x, delta.y, delta.z).normalize();
      _sq1.setFromAxisAngle(_sv0, angle * GRAB_GAIN * Math.sign(delta.w >= 0 ? 1 : -1));
      this.pivot.quaternion.premultiply(_sq1);
    }
    this.grab.lastQuat.copy(current); // persist into the owned quaternion

    // Promote a trigger click into a grab once it moves.
    const candidate = controller.userData.selectGrab;
    if (candidate && !candidate.promoted) candidate.promoted = true;

    if (this.scaleGrab) {
      controller.getWorldPosition(_sv0);
      this.scaleGrab.other.getWorldPosition(_sv1);
      const dist = Math.max(0.05, _sv0.distanceTo(_sv1));
      const base = this.scaleGrab.startScale;
      const next = THREE.MathUtils.clamp(
        base * (dist / this.scaleGrab.startDist),
        base * SCALE_MIN_FACTOR,
        base * SCALE_MAX_FACTOR
      );
      this.pivot.scale.setScalar(next);
      const azimuth = Math.atan2(_sv1.x - _sv0.x, _sv1.z - _sv0.z);
      const yaw = azimuth - this.scaleGrab.startAzimuth;
      // current (_sq0) is no longer needed after lastQuat.copy above — reuse it.
      this.pivot.quaternion.copy(this.scaleGrab.startQuat)
        .premultiply(_sq0.setFromAxisAngle(_UP, yaw));
    }
  }

  /** Trigger-drag promotion: while a select candidate exists, watch travel. */
  watchSelectCandidates(now) {
    for (const controller of this.controllers) {
      const candidate = controller.userData.selectGrab;
      if (!candidate || candidate.promoted) continue;
      const current = controller.getWorldQuaternion(_sq0);
      const angle = current.angleTo(candidate.startQuat);
      if (angle > CLICK_MAX_ANGLE || now - candidate.startedAt > CLICK_MAX_MS * 2.5) {
        candidate.promoted = true;
        // lastQuat is persistent state — give the grab its own owned quaternion.
        this.grab = { controller, lastQuat: controller.getWorldQuaternion(new THREE.Quaternion()) };
      }
    }
  }

  /* ---- sticks --------------------------------------------------------------- */

  pollSticks(session, dt) {
    if (!session) return;
    let index = 0;
    for (const source of session.inputSources) {
      const gp = source.gamepad;
      if (!gp || !gp.axes || gp.axes.length < 2) { index++; continue; }
      const x = gp.axes.length > 2 ? gp.axes[2] : gp.axes[0];
      const y = gp.axes.length > 3 ? gp.axes[3] : gp.axes[1];

      if (Math.abs(x) >= STICK_DEADZONE) {
        this.pivot.quaternion.premultiply(
          _sq0.setFromAxisAngle(_UP, -x * ORBIT_SPEED * dt)
        );
      }
      if (Math.abs(y) >= STICK_DEADZONE) {
        _sv0.copy(this.pivot.position).sub(this.camera.position);
        _sv0.y = 0;
        const dist = Math.max(0.001, _sv0.length());
        _sv0.normalize(); // now the horizontal direction to the model
        const next = THREE.MathUtils.clamp(dist + y * DOLLY_SPEED * dt, DOLLY_MIN, DOLLY_MAX);
        this.pivot.position.x = this.camera.position.x + _sv0.x * next;
        this.pivot.position.z = this.camera.position.z + _sv0.z * next;
      }

      const clickButton = gp.buttons && (gp.buttons[3] || gp.buttons[2]);
      const pressed = !!(clickButton && clickButton.pressed);
      if (pressed && this.stickReady[index]) {
        this.stickReady[index] = false;
        this.onAction("resetView");
      } else if (!pressed) {
        this.stickReady[index] = true;
      }
      index++;
    }
  }

  /* ---- per-frame ------------------------------------------------------------ */

  updateXR(now, dt, frame) {
    const session = this.renderer.xr.getSession();
    if (!session) return;
    this.watchSelectCandidates(now);
    this.applyGrab();
    if (!this.grab) this.pollSticks(session, dt);

    for (const controller of this.controllers) {
      const beam = controller.getObjectByName("beam");
      const cursor = controller.userData.cursor;
      if (!beam) continue;
      const ray = this.rayFromController(controller);
      const panelHit = this.panel.hitFromRaycaster(ray);
      if (panelHit) {
        beam.scale.z = panelHit.distance;
        if (cursor) {
          cursor.visible = true;
          cursor.position.copy(panelHit.point);
          cursor.material.color.copy(panelHit.region ? WHITE : ORANGE);
        }
        if (panelHit.region !== controller.userData.hover) {
          controller.userData.hover = panelHit.region;
          this.panel.hover = panelHit.region;
          this.panel.dirty = true;
          if (panelHit.region) this.pulse(controller, 0.3, 10);
        }
        this.panel.keepAlive(now);
        if (this.scrubbing) {
          this.onAction("seek", this.panel.seekFractionAt(panelHit.px));
        }
      } else {
        beam.scale.z = this.modelHover() ? Math.max(0.4, this.modelSphere.center.distanceTo(ray.ray.origin)) : 3;
        if (cursor) cursor.visible = false;
        if (controller.userData.hover) {
          controller.userData.hover = null;
          this.panel.hover = null;
          this.panel.dirty = true;
        }
      }
    }
  }

  /* ---- desktop -------------------------------------------------------------- */

  enableDesktop(canvas) {
    this.controls = new OrbitControls(this.camera, canvas);
    this.controls.target.copy(this.pivot.position).setY(Math.max(1.1, this.pivot.position.y + 0.4));
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.minDistance = 0.4;
    this.controls.maxDistance = 12;

    canvas.addEventListener("pointerdown", (e) => this.onPointerDown(e, canvas));
    canvas.addEventListener("pointermove", (e) => this.onPointerMove(e, canvas));
    canvas.addEventListener("pointerup", (e) => this.onPointerUp(e, canvas));
    window.addEventListener("keydown", (e) => this.onKey(e));
  }

  raycasterFromPointer(event, canvas) {
    const rect = canvas.getBoundingClientRect();
    const ndc = new THREE.Vector2(
      ((event.clientX - rect.left) / rect.width) * 2 - 1,
      -(((event.clientY - rect.top) / rect.height) * 2 - 1)
    );
    this.raycaster.setFromCamera(ndc, this.camera);
    return this.raycaster;
  }

  onPointerDown(event, canvas) {
    const now = performance.now();
    this.pointerDownAt = now;
    this.pointerMoved = 0;
    this.lastPointer = { x: event.clientX, y: event.clientY };
    const ray = this.raycasterFromPointer(event, canvas);
    const panelHit = this.panel.hitFromRaycaster(ray);
    if (panelHit && panelHit.region) {
      this.pointerPanel = true;
      if (this.controls) this.controls.enabled = false;
      if (panelHit.region === "seek") {
        this.scrubbing = true;
        this.panel.set({ scrubbing: true });
        this.onAction("seek", this.panel.seekFractionAt(panelHit.px));
      } else {
        this.pressedRegion = panelHit.region;
        this.panel.pressed = panelHit.region;
        this.panel.dirty = true;
      }
    }
  }

  onPointerMove(event, canvas) {
    this.pointerMoved += Math.abs(event.clientX - this.lastPointer.x) + Math.abs(event.clientY - this.lastPointer.y);
    this.lastPointer = { x: event.clientX, y: event.clientY };
    const ray = this.raycasterFromPointer(event, canvas);
    const panelHit = this.panel.hitFromRaycaster(ray);
    const region = panelHit ? panelHit.region : null;
    if (region !== this.panel.hover) {
      this.panel.hover = region;
      this.panel.dirty = true;
    }
    if (panelHit) this.panel.keepAlive(performance.now());
    if (this.scrubbing && panelHit) {
      this.onAction("seek", this.panel.seekFractionAt(panelHit.px));
    }
  }

  onPointerUp(event, canvas) {
    const now = performance.now();
    if (this.controls) this.controls.enabled = true;
    if (this.scrubbing) {
      this.scrubbing = false;
      this.panel.set({ scrubbing: false });
      this.onAction("seekCommit");
      this.pointerPanel = false;
      return;
    }
    const ray = this.raycasterFromPointer(event, canvas);
    if (this.pointerPanel) {
      this.pointerPanel = false;
      const panelHit = this.panel.hitFromRaycaster(ray);
      const region = panelHit && panelHit.region;
      const pressed = this.pressedRegion;
      this.pressedRegion = null;
      this.panel.pressed = null;
      this.panel.dirty = true;
      if (region && region === pressed) this.onAction(region);
      return;
    }
    // Quick click: part pick on the model, else summon the panel.
    if (this.pointerMoved < 6 && now - this.pointerDownAt < 400) {
      const pick = this.pickPart(ray);
      if (pick) this.onAction("partPick", pick);
      else this.panel.show(now, this.camera, this.panel.alpha(now) <= 0);
    }
  }

  onKey(event) {
    switch (event.key) {
      case " ": event.preventDefault(); this.onAction("play"); break;
      case "p": case "P": {
        const now = performance.now();
        if (this.panel.alpha(now) > 0 && this.panel.state.mode === "media") this.panel.hide();
        else this.panel.show(now, this.camera, true);
        break;
      }
      case "r": case "R": this.onAction("resetView"); break;
      case "t": case "T": this.onAction("spin"); break;
      case "1": this.onAction("scale"); break;
      case "l": case "L": this.onAction("loop"); break;
      case "ArrowRight": this.onAction("next"); break;
      case "ArrowLeft": this.onAction("prev"); break;
      case "]": this.onAction("clipNext"); break;
      case "[": this.onAction("clipPrev"); break;
      default: break;
    }
  }

  updateDesktop() {
    if (this.controls) this.controls.update();
  }
}
