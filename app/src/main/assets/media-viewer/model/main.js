// Framatome VR — 3D model viewer bootstrap.
//
// Launch contract (built by content-contract MediaViewerUrls):
//   /__viewer__/model.html?type=3d_model&src=<rel>.glb&title=…&id=…
//       [&playlist=<key>&index=<n>][&parts=0][&vr][&preview][&demo=<scene>]

import * as THREE from "three";
import {
  createRenderer, createStage, frameModel, applyPresentation, resetView,
  readBudget, TURNTABLE_SPEED,
} from "./scene.js";
import { createLoaderRig, loadModel, disposeModel, sourceFileName } from "./loaders.js";
import { AnimRig } from "./animation.js";
import { GlassPanel, PartChip } from "./panel.js";
import { ModelInput } from "./input.js";
import { fetchModelPlaylist } from "./playlist.js";
import { DEMO_SCENES, loadDemoModel, demoPlaylistDoc, buildDemoAssembly } from "./demo.js";
import { ORANGE } from "./brand.js";

/* ----------------------------------------------------------------- config -- */

const params = new URLSearchParams(location.search);
const config = {
  type: (params.get("type") || "3d_model").toLowerCase(),
  src: params.get("src") || "",
  title: params.get("title") || "3D Model",
  id: params.get("id") || "",
  playlist: params.get("playlist") || "",
  playlistIndex: parseInt(params.get("index"), 10),
  parts: params.get("parts") !== "0",
  autoVr: params.has("vr"),
  preview: params.has("preview"),
  demo: (params.get("demo") || "").toLowerCase(),
};
const demoScene = DEMO_SCENES[config.demo];
if (demoScene) {
  config.src = demoScene.src;
  if (!params.get("title")) config.title = demoScene.title;
  config.playlistDemo = !!demoScene.playlist;
}

const dom = {
  body: document.body,
  canvas: document.getElementById("gl"),
  title: document.getElementById("title"),
  badgeText: document.getElementById("badgeText"),
  loader: document.getElementById("loader"),
  loaderLabel: document.getElementById("loaderLabel"),
  progressTrack: document.querySelector(".progress-track"),
  progressFill: document.getElementById("progressFill"),
  errorMessage: document.getElementById("errorMessage"),
  errorFile: document.getElementById("errorFile"),
  retry: document.getElementById("retry"),
  errorExitHub: document.getElementById("errorExitHub"),
  exitHub: document.getElementById("exitHub"),
  enterVr: document.getElementById("enterVr"),
  status: document.getElementById("status"),
  vrHint: document.getElementById("vrHint"),
  vrHintText: document.getElementById("vrHintText"),
};

/* ------------------------------------------------------------------ state -- */

let renderer = null;
let stage = null;          // { scene, camera, pivot }
let rig = null;            // loader rig
let panel = null;
let chip = null;
let input = null;
let anim = null;           // AnimRig
let framing = null;
let currentRoot = null;
let turntable = false;
let xrSession = null;
let loadEpoch = 0;         // stale-async guard across swaps/exits
let playlistItems = [];
let playlistIndex = -1;
let highlighted = null;    // { mesh, original }
let budgetCheckAt = 0;     // schedule the budget read a few frames after present
let budgetNotice = "";

/* ------------------------------------------------------------------- ui ---- */

function showLoader(label, fraction) {
  dom.loaderLabel.textContent = label || "Loading model…";
  dom.loader.hidden = false;
  if (fraction === undefined || fraction < 0) {
    dom.progressTrack.hidden = true;
  } else {
    dom.progressTrack.hidden = false;
    dom.progressFill.style.width = Math.round(fraction * 100) + "%";
  }
}
function hideLoader() { dom.loader.hidden = true; }
function setStatus(message) { dom.status.textContent = message || ""; }

function showError(error) {
  const message = (error && error.message) || "Unable to load this model.";
  const file = (error && error.fileName) || sourceFileName(config.src);
  dom.body.classList.add("error");
  dom.errorMessage.textContent = message;
  dom.errorFile.textContent = file;
  setStatus("");
  if (panel) {
    panel.set({ mode: "error", error: { message, file, hasBack: playlistItems.length > 1 } });
    panel.show(performance.now(), stage.camera, true);
  }
}
function clearError() {
  dom.body.classList.remove("error");
  if (panel) panel.set({ mode: "media", error: null });
}

function exitToHub() {
  const navigate = () => location.replace("/");
  if (xrSession) {
    try {
      xrSession.addEventListener("end", navigate, { once: true });
      xrSession.end();
    } catch (e) { navigate(); }
  } else {
    navigate();
  }
}

/* -------------------------------------------------------------- model load -- */

function fetchModel(src, onProgress) {
  if (config.demo || String(src).startsWith("demo:")) return loadDemoModel(src);
  return loadModel(rig, src, onProgress);
}

async function presentModel(src, title) {
  const epoch = ++loadEpoch;
  clearError();
  showLoader("Loading — " + title + "…", -1);
  if (panel) {
    panel.set({ mode: "loading", loadingLabel: "Loading — " + title, loadingFraction: -1 });
    panel.show(performance.now(), stage.camera, false);
  }

  // Dispose the outgoing model BEFORE the next allocates (Quest GPU memory).
  clearHighlight();
  chip && chip.hideNow();
  if (anim) { anim.dispose(); anim = null; }
  if (currentRoot) { disposeModel(currentRoot); currentRoot = null; }
  renderer.renderLists.dispose();

  let loaded;
  try {
    loaded = await fetchModel(src, (loadedBytes, totalBytes) => {
      if (epoch !== loadEpoch) return;
      const fraction = totalBytes > 0 ? loadedBytes / totalBytes : -1;
      const mb = (loadedBytes / (1024 * 1024)).toFixed(1);
      showLoader("Loading — " + title + "… " + (fraction >= 0 ? Math.round(fraction * 100) + "%" : mb + " MB"), fraction);
      if (panel) panel.set({ loadingFraction: fraction });
    });
  } catch (error) {
    if (epoch !== loadEpoch) return;
    hideLoader();
    showError(error);
    return;
  }
  if (epoch !== loadEpoch) { disposeModel(loaded.root); return; }

  currentRoot = loaded.root;
  framing = frameModel(stage.pivot, currentRoot);
  anim = new AnimRig(currentRoot, loaded.animations);
  input.setModel(currentRoot);
  input.refreshBounds();
  turntable = false;
  budgetCheckAt = performance.now() + 700; // let a real frame render first
  budgetNotice = "";

  config.src = src;
  config.title = title;
  dom.title.textContent = title;
  document.title = "Framatome VR — " + title;

  hideLoader();
  panel.set({
    mode: "media",
    title,
    counter: playlistItems.length > 1 ? (playlistIndex + 1) + " / " + playlistItems.length : "",
    hasPlaylist: playlistItems.length > 1,
    clipCount: anim.clips.length,
    clipLabel: anim.clipLabel(),
    loop: anim.loop,
    playing: false,
    fraction: 0,
    timeLabel: "0.0s",
    durationLabel: anim.duration.toFixed(1) + "s",
    trueScale: false,
    turntable: false,
  });
  panel.show(performance.now(), stage.camera, true);
}

async function loadPlaylistItem(targetIndex) {
  if (playlistItems.length < 2) return;
  const total = playlistItems.length;
  const index = ((targetIndex % total) + total) % total;
  if (index === playlistIndex) return;
  playlistIndex = index;
  const item = playlistItems[index];
  await presentModel(item.src, item.title);
}

/* ----------------------------------------------------------- part picking -- */

function clearHighlight() {
  if (!highlighted) return;
  highlighted.mesh.material = highlighted.original;
  // The highlight material is a clone we own — dispose only it.
  if (highlighted.clone) {
    highlighted.clone.dispose();
  }
  highlighted = null;
}

function partName(mesh) {
  let node = mesh;
  while (node && node !== currentRoot) {
    const name = (node.name || "").trim();
    if (name && !/^(mesh|node|object|group)[\s_\d]*$/i.test(name)) return name;
    node = node.parent;
  }
  return (mesh.name || "Part").trim() || "Part";
}

function onPartPick(pick) {
  if (!config.parts || !pick || !pick.mesh) return;
  clearHighlight();
  const mesh = pick.mesh;
  if (mesh.material) {
    const original = mesh.material;
    const clone = Array.isArray(original) ? null : original.clone();
    if (clone) {
      if ("emissive" in clone) {
        clone.emissive = new THREE.Color(ORANGE);
        clone.emissiveIntensity = 0.35;
      }
      mesh.material = clone;
      highlighted = { mesh, original, clone };
    }
  }
  const top = new THREE.Box3().setFromObject(stage.pivot).max.y;
  const position = pick.point.clone();
  position.y = Math.max(position.y + 0.12, top + 0.08);
  chip.show(partName(mesh), position, performance.now());
}

/* ------------------------------------------------------------- panel actions */

function onAction(action, payload) {
  switch (action) {
    case "play":
      if (anim && anim.hasClips) { anim.togglePlay(); panel.set({ playing: anim.playing }); }
      break;
    case "seek":
      if (anim && anim.hasClips) {
        anim.setFraction(payload);
        panel.set({ playing: false, fraction: anim.fraction(), timeLabel: (anim.fraction() * anim.duration).toFixed(1) + "s" });
      }
      break;
    case "seekCommit":
      panel.set({ scrubbing: false });
      break;
    case "clipNext":
      if (anim) { anim.setClip(anim.index + 1); syncAnimPanel(); }
      break;
    case "clipPrev":
      if (anim) { anim.setClip(anim.index - 1); syncAnimPanel(); }
      break;
    case "loop":
      if (anim) { anim.setLoop(!anim.loop); panel.set({ loop: anim.loop }); }
      break;
    case "scale":
      if (framing) {
        applyPresentation(stage.pivot, framing, !framing.trueScale);
        input.refreshBounds();
        panel.set({ trueScale: framing.trueScale });
      }
      break;
    case "spin":
      turntable = !turntable;
      panel.set({ turntable });
      break;
    case "resetView":
      if (framing) {
        applyPresentation(stage.pivot, framing, false);
        resetView(stage.pivot, framing);
        turntable = false;
        input.refreshBounds();
        panel.set({ trueScale: false, turntable: false });
      }
      break;
    case "prev": loadPlaylistItem(playlistIndex - 1); break;
    case "next": loadPlaylistItem(playlistIndex + 1); break;
    case "exit": exitToHub(); break;
    case "retry":
      if (playlistItems.length > 1 && playlistIndex >= 0) {
        const index = playlistIndex;
        playlistIndex = -1;
        loadPlaylistItem(index);
      } else {
        presentModel(config.src, config.title);
      }
      break;
    case "back":
      clearError();
      panel.set({ mode: "media" });
      break;
    case "partPick": onPartPick(payload); break;
    default: break;
  }
}

function syncAnimPanel() {
  if (!anim) return;
  panel.set({
    clipCount: anim.clips.length,
    clipLabel: anim.clipLabel(),
    playing: anim.playing,
    loop: anim.loop,
    fraction: anim.fraction(),
    timeLabel: (anim.fraction() * anim.duration).toFixed(1) + "s",
    durationLabel: anim.duration.toFixed(1) + "s",
  });
}

/* ------------------------------------------------------------------ loop ---- */

let lastTime = 0;
// Per-frame scratch for the turntable spin (avoids allocating each frame).
const _turntableQ = new THREE.Quaternion();
const _UP = new THREE.Vector3(0, 1, 0);
let lastAnimSecond = -1;

function tick(time, frame) {
  const dt = lastTime ? Math.min(0.1, (time - lastTime) / 1000) : 0;
  lastTime = time;

  if (anim && anim.playing) {
    anim.update(dt);
    // The panel only redraws when the 0.1s time label changes, so only push a
    // state update at that cadence — avoids a per-frame object + string alloc.
    const fraction = anim.fraction();
    const tenths = Math.round(fraction * anim.duration * 10);
    if (tenths !== lastAnimSecond) {
      lastAnimSecond = tenths;
      panel.set({
        fraction,
        timeLabel: (tenths / 10).toFixed(1) + "s",
        playing: anim.playing,
      });
    }
  }
  if (turntable && !input.grab) {
    stage.pivot.quaternion.premultiply(
      _turntableQ.setFromAxisAngle(_UP, TURNTABLE_SPEED * dt)
    );
  }

  if (renderer.xr.isPresenting) {
    input.updateXR(time, dt, frame);
  } else {
    input.updateDesktop();
  }
  panel.update(time);
  chip.update(time, stage.camera);

  renderer.render(stage.scene, stage.camera);

  if (budgetCheckAt && time >= budgetCheckAt && currentRoot) {
    budgetCheckAt = 0;
    const budget = readBudget(renderer);
    if (budget.triangles > 1.5e6 || budget.calls > 200) {
      budgetNotice = "Heavy model: " + Math.round((budget.triangles / 1e6) * 10) / 10 +
        "M triangles, " + budget.calls + " draw calls — consider gltfpack (see MODEL_PIPELINE.md).";
      setStatus(budgetNotice);
      console.warn("[model] budget", budget);
    }
  }
}

function sizeCanvas() {
  if (renderer.xr.isPresenting) return;
  const width = window.innerWidth;
  const height = window.innerHeight;
  renderer.setSize(width, height, false);
  stage.camera.aspect = width / Math.max(1, height);
  stage.camera.updateProjectionMatrix();
}

/* ------------------------------------------------------------------- VR ----- */

async function enterVR(silent) {
  if (xrSession) return;
  if (!navigator.xr) {
    if (!silent) setStatus("Immersive VR is not available on this device.");
    return;
  }
  try {
    const session = await navigator.xr.requestSession("immersive-vr", {
      optionalFeatures: ["local-floor", "bounded-floor"],
    });
    xrSession = session;
    session.addEventListener("end", () => {
      xrSession = null;
      dom.body.classList.remove("immersive");
      setStatus("Immersive view closed. Press Enter VR to resume.");
      sizeCanvas();
    });
    renderer.xr.setReferenceSpaceType("local-floor");
    await renderer.xr.setSession(session);
    dom.body.classList.add("immersive");
    panel.show(performance.now(), stage.camera, true);
  } catch (error) {
    xrSession = null;
    if (!silent) setStatus("Could not start the immersive view. " + (error && error.message ? error.message : ""));
  }
}

function startPreview() {
  dom.body.classList.add("preview");
  input.enableDesktop(dom.canvas);
  sizeCanvas();
  panel.show(performance.now(), stage.camera, true);
  setStatus("Drag to orbit · click a part to identify it · P toggles controls");
}

/* ------------------------------------------------------------------ boot ---- */

async function main() {
  dom.title.textContent = config.title;
  document.title = "Framatome VR — " + config.title;
  dom.retry.addEventListener("click", () => onAction("retry"));
  dom.exitHub.addEventListener("click", exitToHub);
  dom.errorExitHub.addEventListener("click", exitToHub);
  window.addEventListener("resize", sizeCanvas);

  renderer = createRenderer(dom.canvas);
  stage = createStage(renderer);
  rig = createLoaderRig(renderer);
  panel = new GlassPanel();
  chip = new PartChip();
  stage.scene.add(panel.mesh);
  stage.scene.add(chip.mesh);
  input = new ModelInput({
    renderer,
    scene: stage.scene,
    camera: stage.camera,
    pivot: stage.pivot,
    panel,
    chip,
    onAction,
  });
  input.partsEnabled = config.parts;
  renderer.setAnimationLoop(tick);
  sizeCanvas();

  // Playlist (parallel; never blocks first render).
  if (config.playlistDemo) {
    const adopted = (await import("./playlist.js")).adoptModelPlaylist(demoPlaylistDoc(), config);
    if (adopted) { playlistItems = adopted.items; playlistIndex = adopted.index; }
  } else {
    fetchModelPlaylist(config).then((adopted) => {
      if (!adopted) return;
      playlistItems = adopted.items;
      playlistIndex = adopted.index;
      panel.set({
        hasPlaylist: true,
        counter: (playlistIndex + 1) + " / " + playlistItems.length,
      });
    });
  }

  if (!config.src) {
    showError(new Error("No model source provided."));
    return;
  }
  await presentModel(config.src, config.title);

  const supported = navigator.xr &&
    (await navigator.xr.isSessionSupported("immersive-vr").catch(() => false));
  if (supported) {
    dom.enterVr.hidden = false;
    setStatus("Ready — put on your headset and press Enter VR.");
    dom.enterVr.addEventListener("click", () => enterVR(false));
    if (config.autoVr) {
      enterVR(true);
      const once = () => { window.removeEventListener("pointerdown", once); enterVR(false); };
      window.addEventListener("pointerdown", once, { once: true });
    }
    window.FramatomeEnterVR = enterVR;
  } else {
    dom.vrHintText.textContent = "Open this model on the Framatome VR headset for the full immersive experience.";
    dom.vrHint.hidden = false;
    const previewBtn = document.createElement("button");
    previewBtn.className = "btn-primary";
    previewBtn.type = "button";
    previewBtn.textContent = "Preview in 3D";
    previewBtn.addEventListener("click", startPreview);
    dom.enterVr.parentNode.insertBefore(previewBtn, dom.enterVr);
    setStatus("Immersive VR is unavailable here — use the 3D preview.");
    if (config.preview) startPreview();
  }
}

void buildDemoAssembly; // referenced by demo scenes through loadDemoModel

// Verification/debug hook (used by the desktop test harness; harmless in kiosk).
window.FramatomeModelDebug = {
  action: (id, payload) => onAction(id, payload),
  panel: () => panel,
  input: () => input,
  anim: () => anim,
  renderer: () => renderer,
  stage: () => stage,
  state: () => ({
    playlistIndex,
    playlistLen: playlistItems.length,
    title: config.title,
    clips: anim ? anim.clips.map((c) => c.name) : [],
    trueScale: framing ? framing.trueScale : null,
    memory: renderer ? renderer.info.memory : null,
  }),
};

main();
