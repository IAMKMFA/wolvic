// In-VR glass control panel for the model viewer — a three.js-native port of
// the canvas-panel look verified in viewer.js (canonical source for the glass
// draw vocabulary; keep visual changes in sync — see BRANDING.md).

import * as THREE from "three";
import {
  ORANGE, TEXT, TEXT_SOFT, DANGER, FONT,
  GLASS_FILL_TOP, GLASS_FILL_BOTTOM, GLASS_STROKE,
  GLASS_BUTTON, GLASS_BUTTON_HOVER, GLASS_BUTTON_PRESSED,
} from "./brand.js";

const PX_W = 1024;
const PX_H = 512;
const PANEL_W = 1.15;            // metres
const PANEL_H = PANEL_W / 2;
const PANEL_DISTANCE = 1.45;
const PANEL_HEIGHT = 0.74;       // console position: below the model,
const PANEL_TILT = -0.40;        // tilted up toward the user (radians)
export const AUTO_HIDE_MS = 5000;
const FADE_MS = 300;

function roundRect(c, x, y, w, h, r) {
  c.beginPath();
  c.moveTo(x + r, y);
  c.arcTo(x + w, y, x + w, y + h, r);
  c.arcTo(x + w, y + h, x, y + h, r);
  c.arcTo(x, y + h, x, y, r);
  c.arcTo(x, y, x + w, y, r);
  c.closePath();
}

function truncateText(c, text, maxW) {
  if (c.measureText(text).width <= maxW) return text;
  let t = text;
  while (t.length > 1 && c.measureText(t + "…").width > maxW) t = t.slice(0, -1);
  return t + "…";
}

function wrapText(c, text, maxW, maxLines) {
  const words = String(text || "").split(/\s+/);
  const lines = [];
  let line = "";
  for (const word of words) {
    const probe = line ? line + " " + word : word;
    if (c.measureText(probe).width > maxW && line) {
      lines.push(line);
      line = word;
      if (lines.length === maxLines - 1) break;
    } else {
      line = probe;
    }
  }
  if (line && lines.length < maxLines) lines.push(truncateText(c, line, maxW));
  return lines;
}

const GLYPHS = {
  play(c, x, y) { c.beginPath(); c.moveTo(x - 12, y - 17); c.lineTo(x - 12, y + 17); c.lineTo(x + 19, y); c.closePath(); c.fill(); },
  pause(c, x, y) { c.fillRect(x - 15, y - 17, 10, 34); c.fillRect(x + 5, y - 17, 10, 34); },
  prev(c, x, y) { c.fillRect(x - 16, y - 14, 6, 28); c.beginPath(); c.moveTo(x + 16, y - 14); c.lineTo(x + 16, y + 14); c.lineTo(x - 7, y); c.closePath(); c.fill(); },
  next(c, x, y) { c.fillRect(x + 10, y - 14, 6, 28); c.beginPath(); c.moveTo(x - 16, y - 14); c.lineTo(x - 16, y + 14); c.lineTo(x + 7, y); c.closePath(); c.fill(); },
  spin(c, x, y) {
    c.lineWidth = 5; c.strokeStyle = c.fillStyle;
    c.beginPath(); c.arc(x, y, 14, -0.4, Math.PI * 1.45); c.stroke();
    c.beginPath(); c.moveTo(x + 18, y - 12); c.lineTo(x + 8, y - 13); c.lineTo(x + 15, y - 2); c.closePath(); c.fill();
  },
  reset(c, x, y) {
    c.lineWidth = 5; c.strokeStyle = c.fillStyle;
    c.beginPath(); c.arc(x, y, 13, Math.PI * 0.6, Math.PI * 2.25); c.stroke();
    c.beginPath(); c.moveTo(x - 16, y + 14); c.lineTo(x - 16, y + 2); c.lineTo(x - 4, y + 10); c.closePath(); c.fill();
  },
};

export class GlassPanel {
  constructor() {
    this.canvas = document.createElement("canvas");
    this.canvas.width = PX_W;
    this.canvas.height = PX_H;
    this.ctx = this.canvas.getContext("2d");
    this.texture = new THREE.CanvasTexture(this.canvas);
    this.texture.anisotropy = 4;

    const material = new THREE.MeshBasicMaterial({
      map: this.texture,
      transparent: true,
      depthTest: false,
      side: THREE.DoubleSide,
    });
    material.toneMapped = false;
    this.mesh = new THREE.Mesh(new THREE.PlaneGeometry(PANEL_W, PANEL_H), material);
    this.mesh.name = "FramatomePanel";
    this.mesh.renderOrder = 999;
    this.mesh.visible = false;

    this.regions = [];
    this.hover = null;
    this.pressed = null;
    this.visibleUntil = 0;
    this.dirty = true;
    this.lastSecondKey = "";

    this.state = {
      mode: "media",            // media | loading | error
      badge: "3D MODEL",
      title: "Model",
      counter: "",
      hasPlaylist: false,
      clipLabel: "",
      clipCount: 0,
      loop: false,
      playing: false,
      fraction: 0,
      timeLabel: "0.0s",
      durationLabel: "0.0s",
      trueScale: false,
      turntable: false,
      scrubbing: false,
      loadingLabel: "",
      loadingFraction: -1,      // <0 = indeterminate
      error: null,              // { message, file, hasBack }
    };
  }

  set(partial) {
    Object.assign(this.state, partial);
    this.dirty = true;
  }

  /* ---- visibility -------------------------------------------------------- */

  alpha(now) {
    if (this.state.mode !== "media" || this.state.scrubbing) {
      return this.mesh.visible ? 1 : 0;
    }
    const remain = this.visibleUntil - now;
    if (remain <= 0) return 0;
    return remain < FADE_MS ? remain / FADE_MS : 1;
  }

  show(now, camera, recenter) {
    const t = now === undefined ? performance.now() : now;
    const wasHidden = this.alpha(t) <= 0 || !this.mesh.visible;
    this.visibleUntil = t + AUTO_HIDE_MS;
    this.mesh.visible = true;
    if ((wasHidden || recenter) && camera) this.recenter(camera);
    this.dirty = true;
  }

  keepAlive(now) {
    const t = now === undefined ? performance.now() : now;
    if (this.alpha(t) > 0) this.visibleUntil = t + AUTO_HIDE_MS;
  }

  hide() {
    this.visibleUntil = 0;
    this.state.scrubbing = false;
  }

  /** Re-aims the panel at the user's current heading (panels behind the user
   *  are dead ends — same rule as the media viewer). Console placement: low
   *  and tilted up so it never occludes the model on its pedestal. */
  recenter(camera) {
    const forward = new THREE.Vector3();
    camera.getWorldDirection(forward);
    const yaw = Math.atan2(-forward.x, -forward.z);
    const px = -Math.sin(yaw) * PANEL_DISTANCE;
    const pz = -Math.cos(yaw) * PANEL_DISTANCE;
    this.mesh.position.set(camera.position.x + px, PANEL_HEIGHT, camera.position.z + pz);
    this.mesh.rotation.order = "YXZ";
    this.mesh.rotation.set(PANEL_TILT, yaw, 0);
  }

  /** Per-frame: fade + redraw policy (≤1 upload/s steady-state; the loading
   *  spinner animates so it redraws each frame). */
  update(now) {
    const a = this.alpha(now);
    this.mesh.visible = a > 0;
    this.mesh.material.opacity = a;
    if (!this.mesh.visible) return;
    if (this.state.mode === "loading") {
      // The spinner animates, but 30 fps is plenty — redrawing + uploading the
      // 1024x512 canvas every frame during a multi-second GLB load is wasteful.
      if (now - (this._lastSpinnerDraw || 0) >= 33) {
        this._lastSpinnerDraw = now;
        this.draw(now);
      }
      return;
    }
    if (this.state.mode === "media" && this.state.playing) {
      const key = this.state.timeLabel;
      if (key !== this.lastSecondKey) { this.lastSecondKey = key; this.dirty = true; }
    }
    if (this.dirty) this.draw(now);
  }

  /* ---- ray hit ----------------------------------------------------------- */

  /** Raycaster -> { region, px, py, point } or null. */
  hitFromRaycaster(raycaster) {
    if (!this.mesh.visible) return null;
    const hits = raycaster.intersectObject(this.mesh, false);
    if (!hits.length) return null;
    const hit = hits[0];
    const px = (hit.uv.x) * PX_W;
    const py = (1 - hit.uv.y) * PX_H;
    const region = this.regionAt(px, py);
    return { region: region ? region.id : null, px, py, point: hit.point, distance: hit.distance };
  }

  regionAt(px, py) {
    for (const r of this.regions) {
      if (px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h) return r;
    }
    return null;
  }

  seekFractionAt(px) {
    const { x, w } = this.timelineTrack;
    return Math.max(0, Math.min(1, (px - x) / w));
  }

  /* ---- drawing ----------------------------------------------------------- */

  addRegion(id, x, y, w, h) { this.regions.push({ id, x, y, w, h }); }

  button(id, x, y, w, h, drawGlyph, label, active) {
    const c = this.ctx;
    const hovered = this.hover === id;
    const pressed = this.pressed === id;
    c.fillStyle = pressed ? GLASS_BUTTON_PRESSED
      : hovered ? GLASS_BUTTON_HOVER
      : active ? "rgba(240, 78, 35, 0.24)"
      : GLASS_BUTTON;
    roundRect(c, x, y, w, h, 16); c.fill();
    if (hovered || active) {
      c.strokeStyle = ORANGE; c.lineWidth = 3;
      roundRect(c, x, y, w, h, 16); c.stroke();
    }
    c.fillStyle = hovered ? "#FFFFFF" : TEXT;
    if (drawGlyph) drawGlyph(c, x + w / 2, y + h / 2);
    if (label) {
      c.textAlign = "center"; c.textBaseline = "middle";
      c.font = "700 " + (label.length > 6 ? 20 : 24) + "px " + FONT;
      c.fillText(label, x + w / 2, y + h / 2);
    }
    this.addRegion(id, x, y, w, h);
  }

  draw(now) {
    const c = this.ctx, w = PX_W, h = PX_H, s = this.state;
    this.regions = [];
    c.clearRect(0, 0, w, h);

    // Glass card: gradient + frosted top sheen + glass stroke (viewer.js look).
    const grad = c.createLinearGradient(0, 12, 0, h - 12);
    grad.addColorStop(0, GLASS_FILL_TOP);
    grad.addColorStop(1, GLASS_FILL_BOTTOM);
    c.fillStyle = grad;
    roundRect(c, 12, 12, w - 24, h - 24, 30); c.fill();
    const sheen = c.createLinearGradient(0, 12, 0, 120);
    sheen.addColorStop(0, "rgba(255, 255, 255, 0.10)");
    sheen.addColorStop(1, "rgba(255, 255, 255, 0)");
    c.fillStyle = sheen;
    roundRect(c, 12, 12, w - 24, 108, 30); c.fill();
    c.strokeStyle = GLASS_STROKE; c.lineWidth = 2;
    roundRect(c, 12, 12, w - 24, h - 24, 30); c.stroke();

    if (s.mode === "loading") { this.drawLoading(now); this.upload(); return; }
    if (s.mode === "error") { this.drawError(); this.upload(); return; }

    // Header.
    c.fillStyle = ORANGE;
    roundRect(c, 44, 44, 8, 72, 4); c.fill();
    c.textBaseline = "middle"; c.textAlign = "left";
    c.fillStyle = ORANGE;
    c.font = "700 22px " + FONT;
    c.fillText(s.badge, 76, 62);
    if (s.counter) {
      c.textAlign = "right";
      c.fillStyle = TEXT_SOFT;
      c.font = "600 24px " + FONT;
      c.fillText(s.counter, w - 52, 62);
      c.textAlign = "left";
    }
    c.fillStyle = TEXT;
    c.font = "600 36px " + FONT;
    c.fillText(truncateText(c, s.title || "Model", w - 180), 76, 102);

    // Clip + timeline rows (only when the model has animations).
    let rowY = 150;
    if (s.clipCount > 0) {
      if (s.clipCount > 1) {
        this.button("clipPrev", 76, rowY, 58, 48, GLYPHS.prev);
        this.button("clipNext", w - 76 - 58 - 130, rowY, 58, 48, GLYPHS.next);
        c.fillStyle = TEXT;
        c.textAlign = "center";
        c.font = "700 24px " + FONT;
        c.fillText(truncateText(c, s.clipLabel, w - 480), (76 + (w - 76 - 130)) / 2, rowY + 24);
        c.textAlign = "left";
      } else {
        c.fillStyle = TEXT_SOFT;
        c.font = "700 24px " + FONT;
        c.fillText(s.clipLabel, 76, rowY + 24);
      }
      this.button("loop", w - 76 - 118, rowY, 118, 48, null, "LOOP", s.loop);
      rowY += 64;

      // Timeline (the explode slider).
      const trackX = 76, trackW = w - 152, trackY = rowY + 14, trackH = 10;
      this.timelineTrack = { x: trackX, w: trackW };
      c.fillStyle = "rgba(255,255,255,0.16)";
      roundRect(c, trackX, trackY, trackW, trackH, 5); c.fill();
      c.fillStyle = ORANGE;
      roundRect(c, trackX, trackY, Math.max(trackH, trackW * s.fraction), trackH, 5); c.fill();
      c.beginPath();
      c.arc(trackX + trackW * s.fraction, trackY + trackH / 2, 15, 0, Math.PI * 2);
      c.fillStyle = "#FFFFFF"; c.fill();
      this.addRegion("seek", trackX - 10, trackY - 26, trackW + 20, trackH + 52);
      c.fillStyle = s.scrubbing ? "#FFFFFF" : TEXT_SOFT;
      c.font = "600 22px " + FONT;
      c.textAlign = "left";
      c.fillText(s.timeLabel, trackX, trackY + 44);
      c.textAlign = "right";
      c.fillText(s.durationLabel, trackX + trackW, trackY + 44);
      c.textAlign = "left";
      rowY += 76;
    }

    // Point-cloud control row (point size stepper + point count). Additive —
    // only the cloud viewer sets s.cloud, so the model viewer is unaffected.
    if (s.cloud) {
      c.fillStyle = TEXT_SOFT;
      c.font = "700 24px " + FONT;
      c.textAlign = "left";
      c.fillText("POINT SIZE", 76, rowY + 24);
      this.button("ptMinus", 320, rowY, 56, 48, null, "−");
      c.fillStyle = TEXT;
      c.textAlign = "center";
      c.font = "600 26px " + FONT;
      c.fillText((s.pointSize || 0).toFixed(1) + "px", 432, rowY + 24);
      this.button("ptPlus", 488, rowY, 56, 48, null, "+");
      if (s.pointMeta) {
        c.fillStyle = TEXT_SOFT;
        c.textAlign = "right";
        c.font = "600 24px " + FONT;
        c.fillText(s.pointMeta, w - 76, rowY + 24);
      }
      c.textAlign = "left";
      rowY += 64;
    }

    // Button row.
    const btnY = Math.max(rowY + 8, 300);
    const btnH = h - btnY - 40;
    let x = 56;
    if (s.hasPlaylist) { this.button("prev", x, btnY, 86, btnH, GLYPHS.prev); x += 100; }
    if (s.clipCount > 0) {
      this.button("play", x, btnY, 112, btnH, s.playing ? GLYPHS.pause : GLYPHS.play);
      x += 126;
    }
    if (s.hasPlaylist) { this.button("next", x, btnY, 86, btnH, GLYPHS.next); x += 100; }
    x += 8;
    this.button("scale", x, btnY, 108, btnH, null, s.trueScale ? "1:1" : "FIT", s.trueScale); x += 122;
    this.button("spin", x, btnY, 86, btnH, GLYPHS.spin, null, s.turntable); x += 100;
    this.button("resetView", x, btnY, 86, btnH, GLYPHS.reset); x += 100;
    const exitW = w - 56 - x;
    this.button("exit", x, btnY, Math.max(exitW, 150), btnH, null, "EXIT");

    this.upload();
  }

  drawLoading(now) {
    const c = this.ctx, w = PX_W, s = this.state;
    const angle = ((now || 0) / 900) * Math.PI * 2;
    c.strokeStyle = "rgba(255,255,255,0.12)"; c.lineWidth = 10;
    c.beginPath(); c.arc(w / 2, 190, 52, 0, Math.PI * 2); c.stroke();
    c.strokeStyle = ORANGE;
    if (s.loadingFraction >= 0) {
      c.beginPath(); c.arc(w / 2, 190, 52, -Math.PI / 2, -Math.PI / 2 + s.loadingFraction * Math.PI * 2); c.stroke();
      c.fillStyle = TEXT;
      c.textAlign = "center"; c.textBaseline = "middle";
      c.font = "700 26px " + FONT;
      c.fillText(Math.round(s.loadingFraction * 100) + "%", w / 2, 190);
    } else {
      c.beginPath(); c.arc(w / 2, 190, 52, angle, angle + Math.PI * 1.35); c.stroke();
    }
    c.fillStyle = TEXT;
    c.textAlign = "center"; c.textBaseline = "middle";
    c.font = "600 32px " + FONT;
    c.fillText(truncateText(c, s.loadingLabel || "Loading model…", w - 160), w / 2, 308);
    c.fillStyle = TEXT_SOFT;
    c.font = "400 24px " + FONT;
    c.fillText("Framatome VR", w / 2, 358);
    c.textAlign = "left";
    // A model that never finishes loading otherwise leaves no way back: the
    // panel is pinned open in this mode and there is nothing else to aim at.
    this.button("exit", (w - 224) / 2, 396, 224, 68, null, "EXIT TO HUB");
  }

  drawError() {
    const c = this.ctx, w = PX_W, s = this.state;
    const err = s.error || {};
    c.fillStyle = "rgba(255, 180, 171, 0.16)";
    c.beginPath(); c.arc(w / 2, 120, 44, 0, Math.PI * 2); c.fill();
    c.strokeStyle = DANGER; c.lineWidth = 5;
    c.beginPath(); c.moveTo(w / 2, 96); c.lineTo(w / 2, 130); c.stroke();
    c.fillStyle = DANGER;
    c.beginPath(); c.arc(w / 2, 145, 3.4, 0, Math.PI * 2); c.fill();
    c.fillStyle = TEXT;
    c.textAlign = "center"; c.textBaseline = "middle";
    c.font = "700 34px " + FONT;
    c.fillText("Unable to load this model", w / 2, 204);
    c.fillStyle = TEXT_SOFT;
    c.font = "400 25px " + FONT;
    const lines = wrapText(c, err.message || "", w - 200, 2);
    lines.forEach((line, i) => c.fillText(line, w / 2, 246 + i * 34));
    if (err.file) {
      c.font = "600 21px " + FONT;
      c.fillText(truncateText(c, err.file, w - 280), w / 2, 246 + lines.length * 34 + 6);
    }
    const rowY = 372, rowH = 84;
    const ids = err.hasBack ? ["retry", "back", "exit"] : ["retry", "exit"];
    const labels = { retry: "TRY AGAIN", back: "BACK", exit: "EXIT TO HUB" };
    const bw = 232, gap = 26;
    let x = (w - (ids.length * bw + (ids.length - 1) * gap)) / 2;
    for (const id of ids) {
      this.button(id, x, rowY, bw, rowH, null, labels[id]);
      x += bw + gap;
    }
    c.textAlign = "left";
  }

  upload() {
    this.texture.needsUpdate = true;
    this.dirty = false;
  }
}

/* ---- part identification chip --------------------------------------------- */

export class PartChip {
  constructor() {
    this.canvas = document.createElement("canvas");
    this.canvas.width = 512;
    this.canvas.height = 128;
    this.ctx = this.canvas.getContext("2d");
    this.texture = new THREE.CanvasTexture(this.canvas);
    const material = new THREE.MeshBasicMaterial({
      map: this.texture,
      transparent: true,
      depthTest: false,
    });
    material.toneMapped = false;
    this.mesh = new THREE.Mesh(new THREE.PlaneGeometry(0.46, 0.115), material);
    this.mesh.renderOrder = 1000;
    this.mesh.visible = false;
    this.hideAt = 0;
  }

  show(name, worldPosition, now) {
    const c = this.ctx, w = 512, h = 128;
    c.clearRect(0, 0, w, h);
    c.fillStyle = "rgba(22, 55, 97, 0.92)";
    roundRect(c, 4, 4, w - 8, h - 8, 26); c.fill();
    c.strokeStyle = GLASS_STROKE; c.lineWidth = 2;
    roundRect(c, 4, 4, w - 8, h - 8, 26); c.stroke();
    c.fillStyle = ORANGE;
    roundRect(c, 26, 34, 6, 60, 3); c.fill();
    c.fillStyle = TEXT;
    c.textAlign = "left"; c.textBaseline = "middle";
    c.font = "600 34px " + FONT;
    c.fillText(truncateText(c, name, w - 110), 52, h / 2);
    this.texture.needsUpdate = true;
    this.mesh.position.copy(worldPosition);
    this.mesh.visible = true;
    this.hideAt = (now === undefined ? performance.now() : now) + 4000;
  }

  update(now, camera) {
    if (!this.mesh.visible) return;
    if (now >= this.hideAt) { this.mesh.visible = false; return; }
    const remain = this.hideAt - now;
    this.mesh.material.opacity = remain < 250 ? remain / 250 : 1;
    this.mesh.lookAt(camera.position);
  }

  hideNow() { this.mesh.visible = false; }
}
