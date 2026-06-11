// Framatome VR — point-cloud viewer bootstrap.
//
// Launch contract (built by content-contract MediaViewerUrls):
//   /__viewer__/cloud.html?type=point_cloud&src=<rel>.ply&title=…&id=…
//       [&playlist=<key>&index=<n>][&vr][&preview][&demo=<scene>]
//
// Reuses the model viewer's generic 3D infrastructure (scene/renderer/camera/XR,
// glass control panel, XR+desktop input, branding) and forks only the
// point-cloud specifics (PLY/PCD loaders, procedural demo, this orchestration).

import * as THREE from "three";
import {
  createRenderer, createStage, frameModel, applyPresentation, resetView,
  TURNTABLE_SPEED,
} from "../model/scene.js";
import { GlassPanel, PartChip } from "../model/panel.js";
import { ModelInput } from "../model/input.js";
import {
  createCloudLoaderRig, loadPointCloud, disposePointCloud, setPointSize,
  sourceFileName, DEFAULT_POINT_SIZE,
} from "./loaders.js";
import { fetchCloudPlaylist, adoptCloudPlaylist } from "./playlist.js";
import { DEMO_SCENES, loadDemoCloud, demoCloudPlaylistDoc } from "./demo.js";

/* ----------------------------------------------------------------- config -- */

const params = new URLSearchParams(location.search);
const config = {
  type: (params.get("type") || "point_cloud").toLowerCase(),
  src: params.get("src") || "",
  title: params.get("title") || "Point Cloud",
  id: params.get("id") || "",
  playlist: params.get("playlist") || "",
  playlistIndex: parseInt(params.get("index"), 10),
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
let rig = null;
let panel = null;
let chip = null;           // unused (part-pick disabled) but satisfies ModelInput
let input = null;
let framing = null;
let currentRoot = null;
let turntable = false;
let xrSession = null;
let loadEpoch = 0;
let playlistItems = [];
let playlistIndex = -1;
let pointSize = DEFAULT_POINT_SIZE;
let pointCount = 0;
let budgetCheckAt = 0;

/* ------------------------------------------------------------------- ui ---- */

function showLoader(label, fraction) {
  dom.loaderLabel.textContent = label || "Loading point cloud…";
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
  const message = (error && error.message) || "Unable to load this point cloud.";
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

/* -------------------------------------------------------------- cloud load -- */

function fetchCloud(src, onProgress) {
  if (config.demo || String(src).startsWith("demo:")) return loadDemoCloud(src);
  return loadPointCloud(rig, src, onProgress);
}

function pointCountLabel(n) {
  if (n >= 1e6) return (n / 1e6).toFixed(1) + "M pts";
  if (n >= 1e3) return Math.round(n / 1e3) + "K pts";
  return n + " pts";
}

async function presentCloud(src, title) {
  const epoch = ++loadEpoch;
  clearError();
  showLoader("Loading — " + title + "…", -1);
  if (panel) {
    panel.set({ mode: "loading", loadingLabel: "Loading — " + title, loadingFraction: -1 });
    panel.show(performance.now(), stage.camera, false);
  }

  if (currentRoot) { disposePointCloud(currentRoot); currentRoot = null; }
  renderer.renderLists.dispose();

  let loaded;
  try {
    loaded = await fetchCloud(src, (loadedBytes, totalBytes) => {
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
  if (epoch !== loadEpoch) { disposePointCloud(loaded.root); return; }

  currentRoot = loaded.root;
  pointCount = loaded.pointCount || 0;
  setPointSize(currentRoot, pointSize); // honour the active size across swaps
  framing = frameModel(stage.pivot, currentRoot);
  input.setModel(currentRoot);
  input.refreshBounds();
  turntable = false;
  budgetCheckAt = performance.now() + 700;

  config.src = src;
  config.title = title;
  dom.title.textContent = title;
  document.title = "Framatome VR — " + title;

  hideLoader();
  panel.set({
    mode: "media",
    title,
    badge: "POINT CLOUD",
    counter: playlistItems.length > 1 ? (playlistIndex + 1) + " / " + playlistItems.length : "",
    hasPlaylist: playlistItems.length > 1,
    clipCount: 0,           // no animation row for point clouds
    cloud: true,            // enables the point-size row + cloud meta line
    pointSize,
    pointMeta: pointCountLabel(pointCount),
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
  await presentCloud(item.src, item.title);
}

/* ------------------------------------------------------------- panel actions */

function applyPointSize(next) {
  pointSize = setPointSize(currentRoot, next);
  panel.set({ pointSize });
}

function onAction(action) {
  switch (action) {
    case "ptMinus": applyPointSize(pointSize - 0.5); break;
    case "ptPlus": applyPointSize(pointSize + 0.5); break;
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
        presentCloud(config.src, config.title);
      }
      break;
    case "back":
      clearError();
      panel.set({ mode: "media" });
      break;
    default: break;
  }
}

/* ------------------------------------------------------------------ loop ---- */

let lastTime = 0;
const _turntableQ = new THREE.Quaternion();
const _UP = new THREE.Vector3(0, 1, 0);

function tick(time, frame) {
  const dt = lastTime ? Math.min(0.1, (time - lastTime) / 1000) : 0;
  lastTime = time;

  if (turntable && !input.grab) {
    stage.pivot.quaternion.premultiply(
      _turntableQ.setFromAxisAngle(_UP, TURNTABLE_SPEED * dt)
    );
  }

  if (renderer.xr.isPresenting) input.updateXR(time, dt, frame);
  else input.updateDesktop();
  panel.update(time);
  chip.update(time, stage.camera);

  renderer.render(stage.scene, stage.camera);

  if (budgetCheckAt && time >= budgetCheckAt && currentRoot) {
    budgetCheckAt = 0;
    if (pointCount > 5e6) {
      setStatus("Dense cloud: " + pointCountLabel(pointCount) +
        " in memory — decimate or crop for smoother VR (see SCAN_PIPELINE.md).");
      console.warn("[cloud] heavy point count", pointCount);
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
  setStatus("Drag to orbit · stick/scroll to dolly · P toggles controls");
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
  rig = createCloudLoaderRig();
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
  input.partsEnabled = false; // no part-ID for point clouds
  renderer.setAnimationLoop(tick);
  sizeCanvas();

  if (config.playlistDemo) {
    const adopted = adoptCloudPlaylist(demoCloudPlaylistDoc(), config);
    if (adopted) { playlistItems = adopted.items; playlistIndex = adopted.index; }
  } else {
    fetchCloudPlaylist(config).then((adopted) => {
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
    showError(new Error("No point-cloud source provided."));
    return;
  }
  await presentCloud(config.src, config.title);

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
    dom.vrHintText.textContent = "Open this scan on the Framatome VR headset for the full immersive experience.";
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

// Verification/debug hook (desktop test harness; harmless in kiosk).
window.FramatomeCloudDebug = {
  action: (id) => onAction(id),
  panel: () => panel,
  input: () => input,
  renderer: () => renderer,
  stage: () => stage,
  state: () => ({
    playlistIndex,
    playlistLen: playlistItems.length,
    title: config.title,
    pointCount,
    pointSize,
    trueScale: framing ? framing.trueScale : null,
  }),
};

main();
