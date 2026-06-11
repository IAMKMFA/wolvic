"use strict";

/*
 * Framatome VR — immersive media viewer.
 *
 * Renders launcher-provided media inside a WebXR (immersive-vr) session using
 * the same Gecko/OpenXR pipeline that powers 3D Vista tours. No external
 * libraries: the headset has no guaranteed network, so everything is vanilla
 * WebGL + WebXR and served locally by the player on :18080.
 *
 * Launch contract (see MediaLaunchPayload.mediaViewerUrl):
 *   /__viewer__/index.html?type=<token>&src=<relPath>&title=<t>
 *       [&projection=equirectangular|vr180][&stereo=mono|top_bottom|left_right]
 *       [&right=<relPath>][&playlist=<key>&index=<n>][&vr]
 * `src`/`right` are paths relative to the shared FramatomeVR storage tree and
 * are fetched through the player's /__media__/ route. `playlist` names a JSON
 * document at /__playlist__/<key> enabling in-headset prev/next navigation.
 */

const MEDIA_ROUTE = "/__media__/";
const PLAYLIST_ROUTE = "/__playlist__/";
const PANEL_DISTANCE = 2.8;   // metres in front of the viewer for flat media
const PANEL_HEIGHT = 2.0;     // metres tall for flat media
const SPHERE_RADIUS = 12.0;
const SEEK_RATE = 30;         // seconds of video seeked per second at full stick
const STICK_DEADZONE = 0.25;
const HUD_FADE_MS = 600;

// Free-form tokens arrive from manifests and filename hints; reduce them to
// the canonical set here (the one place validation happens) so the render
// path can switch on exact strings. Unknown tokens return null.
function normalizeProjection(token) {
  const t = String(token || "").trim().toLowerCase();
  if (!t) return "equirectangular";
  if (t === "vr180" || t === "180" || t === "equirect180" ||
      t === "half_equirect" || t === "half-equirect" ||
      t === "half_equirectangular" || t === "half-equirectangular") return "vr180";
  if (t === "equirectangular" || t === "equirect" || t === "360" || t === "spherical") {
    return "equirectangular";
  }
  return null;
}

function normalizeStereo(token) {
  const t = String(token || "").trim().toLowerCase();
  if (!t || t === "mono" || t === "none" || t === "2d") return "mono";
  if (t === "top_bottom" || t === "top-bottom" || t === "tb" ||
      t === "over_under" || t === "over-under" || t === "ou") return "top_bottom";
  if (t === "left_right" || t === "left-right" || t === "lr" || t === "sbs" ||
      t === "side_by_side" || t === "side-by-side") return "left_right";
  if (t === "pair" || t === "separate_left_right" || t === "separate") return "pair";
  return null;
}

const params = new URLSearchParams(location.search);
const rawProjection = params.get("projection") || "equirectangular";
const rawStereo = params.get("stereo") || "mono";
const config = {
  type: (params.get("type") || "2d_image").toLowerCase(),
  src: params.get("src") || "",
  right: params.get("right") || "",
  title: params.get("title") || "Media",
  projection: normalizeProjection(rawProjection) || "equirectangular",
  stereo: normalizeStereo(rawStereo) || "mono",
  id: params.get("id") || "",
  playlist: params.get("playlist") || "",
  playlistIndex: parseInt(params.get("index"), 10),
  autoVr: params.has("vr"),
  preview: params.has("preview"),
  demo: (params.get("demo") || "").toLowerCase(),
};

// Surfaced once the HUD exists (and pre-VR via the status line).
let pendingNotice = "";
if (normalizeProjection(rawProjection) === null) {
  pendingNotice = "Unknown projection “" + rawProjection + "” — showing as 360°";
  console.warn("[viewer] " + pendingNotice);
}
if (normalizeStereo(rawStereo) === null) {
  pendingNotice = "Unknown stereo mode “" + rawStereo + "” — showing in 2D";
  console.warn("[viewer] " + pendingNotice);
}
applyDemoConfig(); // no-op unless a ?demo=… scenario is requested (test harness only)

const dom = {
  body: document.body,
  canvas: document.getElementById("gl"),
  title: document.getElementById("title"),
  badge: document.getElementById("badge"),
  badgeText: document.getElementById("badgeText"),
  preview: document.getElementById("preview"),
  loader: document.getElementById("loader"),
  loaderLabel: document.getElementById("loaderLabel"),
  errorCard: document.getElementById("errorCard"),
  errorTitle: document.querySelector(".error-title"),
  errorMessage: document.getElementById("errorMessage"),
  errorFile: document.getElementById("errorFile"),
  retry: document.getElementById("retry"),
  errorExitHub: document.getElementById("errorExitHub"),
  exitHub: document.getElementById("exitHub"),
  controls: document.getElementById("controls"),
  playToggle: document.getElementById("playToggle"),
  curTime: document.getElementById("curTime"),
  scrub: document.getElementById("scrub"),
  durTime: document.getElementById("durTime"),
  mute: document.getElementById("mute"),
  enterVr: document.getElementById("enterVr"),
  status: document.getElementById("status"),
  vrHint: document.getElementById("vrHint"),
  vrHintText: document.getElementById("vrHintText"),
};

// Mutable so the gallery/carousel can switch between mixed item types. For a
// single production launch these are set once and never change.
let is360 = config.type === "360_image" || config.type === "360_video";
let isVideo = config.type === "2d_video" || config.type === "360_video";
// "mono" | "top_bottom" | "left_right" | "pair" — a pair without a right-eye
// source cannot render, so it degrades to mono up front.
let stereoMode = config.right ? "pair" : (config.stereo === "pair" ? "mono" : config.stereo);

let gl = null;
let program = null;
let attribs = null;
let uniforms = null;
let sphere = null;
let hemisphere = null;      // built lazily for vr180
let quad = null;
let leftTex = null;
let rightTex = null;
let maxTextureSize = 4096;  // real value queried in initGl
// Multiplied into the media draw (not HUD/panel): loading dim + item fade-in.
const sceneTint = new Float32Array([1, 1, 1, 1]);
const WHITE_TINT = new Float32Array([1, 1, 1, 1]);
let mediaEl = null;       // primary <img> or <video>
let rightImageEl = null;  // optional right-eye <img> for stereo pairs
let mediaAspect = 16 / 9;
let mediaReady = false;
let scrubbing = false;
let xrSession = null;
let xrRefSpace = null;
let glLayer = null;
let lastFrameTime = 0;

// In-VR heads-up display (lower-third caption rendered to a 2D canvas texture).
let hudCanvas = null;
let hudCtx = null;
let hudTex = null;
let hudVisibleUntil = 0;
const HUD_MODEL = (function () {
  const h = 0.30, w = h * 4; // canvas is 1024x256 (4:1)
  // World-locked in the session reference space: forward (-Z), below the eye
  // line. Drawn through the same projection*view*model path as the flat panel
  // so stereo separation stays geometrically correct and comfortable.
  return new Float32Array([w, 0, 0, 0, 0, h, 0, 0, 0, 0, 1, 0, 0, -0.7, -2.0, 1]);
})();

/* ----------------------------------------------------------------- ui ------ */

function showLoader(text) {
  dom.loaderLabel.textContent = text || "Loading\u2026";
  dom.loader.hidden = false;
}
function hideLoader() { dom.loader.hidden = true; }

function setStatus(message) { dom.status.textContent = message || ""; }

function setError(message, title) {
  dom.body.classList.add("error");
  if (title) dom.errorTitle.textContent = title;
  dom.errorMessage.textContent = message || "";
  setStatus("");
}

function mediaLabel() {
  const vr180 = config.projection === "vr180";
  const base = config.type === "360_video" ? (vr180 ? "VR180 Video" : "360\u00b0 Video")
    : config.type === "360_image" ? (vr180 ? "VR180 Image" : "360\u00b0 Image")
    : config.type === "2d_video" ? "Video"
    : "Image";
  return base + (stereoMode !== "mono" ? " \u00b7 3D" : "");
}

function setBadge() {
  dom.badgeText.textContent = mediaLabel();
  dom.badge.hidden = false;
}

function formatTime(s) {
  if (!isFinite(s) || s < 0) s = 0;
  const m = Math.floor(s / 60);
  const sec = Math.floor(s % 60);
  return m + ":" + String(sec).padStart(2, "0");
}

function mediaUrl(relPath) {
  const encoded = relPath.split("/").map(encodeURIComponent).join("/");
  return MEDIA_ROUTE + encoded;
}

/* ------------------------------------------------------------------ media -- */

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("Unable to load image: " + url));
    img.src = url;
  });
}

function describeMediaError(el) {
  const err = el && el.error;
  if (!err) return "Playback failed.";
  switch (err.code) {
    case 1: return "Playback was aborted.";
    case 2: return "Playback was interrupted — the file could no longer be read.";
    case 3: return "This video can't be decoded on this headset (codec or profile).";
    case 4: return "This video format isn't supported on this headset.";
    default: return "Playback failed.";
  }
}

function sourceFileName(src) {
  return String(src || "").split("/").pop() || "";
}

function createVideo(url) {
  const video = document.createElement("video");
  video.src = url;
  video.crossOrigin = "anonymous";
  video.loop = true;
  video.playsInline = true;
  video.preload = "auto";
  video.setAttribute("webkit-playsinline", "true");
  // Mid-playback failures (file deleted, decoder lost) must surface in-VR,
  // not just pre-VR. Initial-load failures are handled by loadMedia's reject.
  video.addEventListener("error", () => {
    if (!mediaReady || mediaEl !== video) return;
    showMediaError(describeMediaError(video), "Playback error");
  });
  return video;
}

// One place every media failure lands: branded DOM card pre-VR, HUD (and the
// in-VR panel's error mode, when present) inside a session.
function showMediaError(message, title) {
  const fileName = sourceFileName(config.src);
  const detail = fileName ? message + "  (" + fileName + ")" : message;
  setError(detail, title || "Unable to load media");
  if (dom.errorFile) dom.errorFile.textContent = fileName;
  if (xrSession) {
    if (typeof panelShowError === "function") panelShowError(message, fileName);
    flashHud(message, 4500);
  }
}

async function loadMedia() {
  if (config.demo) { await loadDemoMedia(); return; }
  if (!config.src) throw new Error("No media source provided.");
  const primaryUrl = mediaUrl(config.src);

  if (isVideo) {
    mediaEl = createVideo(primaryUrl);
    await new Promise((resolve, reject) => {
      mediaEl.addEventListener("loadedmetadata", resolve, { once: true });
      mediaEl.addEventListener(
        "error",
        () => reject(new Error(describeMediaError(mediaEl))),
        { once: true }
      );
    });
    if ((mediaEl.videoWidth || 0) > maxTextureSize || (mediaEl.videoHeight || 0) > maxTextureSize) {
      throw new Error(
        "This video is larger than the headset can display (" +
        mediaEl.videoWidth + "×" + mediaEl.videoHeight + ")."
      );
    }
    mediaAspect = (mediaEl.videoWidth || 16) / (mediaEl.videoHeight || 9);
    dom.preview.appendChild(mediaEl);
    mediaEl.muted = true; // allow an autoplaying muted preview before the VR gesture
    mediaEl.play().catch(() => { /* gesture required; resumed on Enter VR */ });
  } else {
    mediaEl = await loadImage(primaryUrl);
    mediaAspect = (mediaEl.naturalWidth || 16) / (mediaEl.naturalHeight || 9);
    dom.preview.appendChild(mediaEl);
    if (stereoMode === "pair" && config.right) {
      try {
        rightImageEl = await loadImage(mediaUrl(config.right));
      } catch (e) {
        // A broken pair is still a perfectly viewable mono image.
        rightImageEl = null;
        stereoMode = "mono";
        pendingNotice = "Right-eye image missing — showing in 2D";
        console.warn("[viewer] " + pendingNotice);
      }
    }
  }
  mediaReady = true;
}

// Pause and fully detach a <video> so its hardware decoder is released —
// Quest has a small decoder pool and gallery switching exhausts it otherwise.
function releaseVideo(el) {
  if (!el) return;
  try { el.pause(); } catch (e) { /* ignore */ }
  try {
    if (el.tagName === "VIDEO") {
      el.removeAttribute("src");
      el.load();
    }
  } catch (e) { /* ignore */ }
  try { if (el.parentNode) el.parentNode.removeChild(el); } catch (e) { /* ignore */ }
}

/* ------------------------------------------------------------- playlist ---- */
/* Production gallery: the launcher (or hub) publishes the item list at
 * /__playlist__/<key>; navigation swaps media in place — no page reload, so
 * the immersive session and its user activation survive. */

let playlistItems = [];
let playlistIndex = -1;
let itemLoading = false;

function normalizePlaylistItem(raw) {
  if (!raw || typeof raw.src !== "string" || !raw.src) return null;
  const type = String(raw.type || "").toLowerCase();
  if (type !== "2d_image" && type !== "2d_video" &&
      type !== "360_image" && type !== "360_video") return null;
  return {
    id: raw.id || "",
    title: raw.title || sourceFileName(raw.src),
    type,
    src: raw.src,
    projection: normalizeProjection(raw.projection) || "equirectangular",
    stereo: normalizeStereo(raw.stereo) || "mono",
    right: raw.right || "",
    badge: raw.badge || "",
    thumb: raw.thumb || "",
  };
}

// Fired alongside loadMedia and never awaited: a missing/broken playlist is
// exactly today's single-item behaviour, not an error.
async function fetchPlaylist() {
  if (!config.playlist || config.demo) return;
  try {
    const res = await fetch(PLAYLIST_ROUTE + encodeURIComponent(config.playlist));
    if (!res.ok) return;
    const doc = await res.json();
    if (!doc || doc.version !== 1 || !Array.isArray(doc.items)) return;
    adoptPlaylist(doc);
  } catch (e) {
    console.warn("[viewer] playlist unavailable: " + (e && e.message ? e.message : e));
  }
}

function adoptPlaylist(doc) {
  const items = doc.items.map(normalizePlaylistItem).filter(Boolean);
  if (items.length < 2) return;
  playlistItems = items;
  // Locate the already-displayed item: by content id, then launch-URL index,
  // then document index, then src — content may have shifted under the index.
  let index = config.id ? items.findIndex((it) => it.id && it.id === config.id) : -1;
  if (index < 0 && Number.isFinite(config.playlistIndex)) {
    index = Math.max(0, Math.min(items.length - 1, config.playlistIndex));
  }
  if (index < 0 && Number.isFinite(doc.index)) {
    index = Math.max(0, Math.min(items.length - 1, doc.index));
  }
  if (index < 0) index = Math.max(0, items.findIndex((it) => it.src === config.src));
  playlistIndex = index;
  if (xrSession) flashHud((index + 1) + " / " + items.length, 1600);
}

async function loadItemMedia(item) {
  if (config.playlistDemo) return demoItemMedia(item);
  const itemIsVideo = item.type === "2d_video" || item.type === "360_video";
  if (itemIsVideo) {
    const el = createVideo(mediaUrl(item.src));
    await new Promise((resolve, reject) => {
      el.addEventListener("loadedmetadata", resolve, { once: true });
      el.addEventListener("error", () => reject(new Error(describeMediaError(el))), { once: true });
    });
    if ((el.videoWidth || 0) > maxTextureSize || (el.videoHeight || 0) > maxTextureSize) {
      releaseVideo(el);
      throw new Error("This video is larger than the headset can display.");
    }
    return { el, rightEl: null, aspect: (el.videoWidth || 16) / (el.videoHeight || 9) };
  }
  const el = await loadImage(mediaUrl(item.src));
  let rightEl = null;
  if (item.right) {
    try { rightEl = await loadImage(mediaUrl(item.right)); } catch (e) { rightEl = null; }
  }
  return { el, rightEl, aspect: (el.naturalWidth || 16) / (el.naturalHeight || 9) };
}

function ensureRightTexture() {
  if (rightTex === leftTex) rightTex = createTexture();
  return rightTex;
}

// Swap the renderer to playlist item `targetIndex` (wraps around) without
// leaving the page. On failure the previous media stays on screen.
async function loadItem(targetIndex) {
  if (playlistItems.length < 2 || itemLoading) return;
  const total = playlistItems.length;
  const index = ((targetIndex % total) + total) % total;
  if (index === playlistIndex) return;
  const item = playlistItems[index];
  itemLoading = true;
  setSceneDim(true);
  if (typeof panelShowLoading === "function") panelShowLoading(item.title);
  flashHud("Loading — " + item.title, 30000);

  // Free the old hardware decoder before asking for a new one; the last
  // uploaded frame stays on the texture, so the scene never goes black.
  const oldWasVideo = isVideo;
  if (oldWasVideo) releaseVideo(mediaEl);

  try {
    const loaded = await loadItemMedia(item);
    if (!oldWasVideo && mediaEl && mediaEl.parentNode) {
      mediaEl.parentNode.removeChild(mediaEl);
    }
    mediaEl = loaded.el;
    rightImageEl = loaded.rightEl;
    mediaAspect = loaded.aspect;

    config.type = item.type;
    config.title = item.title;
    config.src = item.src;
    config.right = loaded.rightEl ? item.right : "";
    config.projection = item.projection;
    config.stereo = item.stereo;
    is360 = item.type === "360_image" || item.type === "360_video";
    isVideo = item.type === "2d_video" || item.type === "360_video";
    stereoMode = loaded.rightEl ? "pair" : (item.stereo === "pair" ? "mono" : item.stereo);
    if (is360) geometryFor(config.projection); // build the mesh before first draw

    try { dom.preview.appendChild(mediaEl); } catch (e) { /* ignore */ }
    if (isVideo) {
      mediaEl.muted = !xrSession; // in-session swaps keep audio live
      mediaEl.play().catch(() => { /* gesture may be required pre-VR */ });
      wireVideoControls();
      if (xrSession) dom.controls.hidden = true;
    } else {
      dom.controls.hidden = true;
      uploadImageTexture(leftTex, mediaEl);
      if (stereoMode === "pair" && rightImageEl) {
        uploadImageTexture(ensureRightTexture(), rightImageEl);
      }
    }

    playlistIndex = index;
    dom.title.textContent = config.title;
    document.title = "Framatome VR — " + config.title;
    setBadge();
    renderHud(config.title, (index + 1) + " / " + total);
    showHud(2600);
    if (typeof panelShowMedia === "function") panelShowMedia();
  } catch (err) {
    const message = (err && err.message) || "Unable to load this item.";
    if (typeof panelShowError === "function") panelShowError(message, sourceFileName(item.src));
    flashHud(message, 4500);
    console.warn("[viewer] item load failed: " + message);
  } finally {
    itemLoading = false;
    setSceneDim(false);
  }
}

// Procedural stand-ins so ?demo=playlist exercises the REAL playlist parse and
// loadItem swap paths without any media files (desktop test harness).
function demoItemMedia(item) {
  const itemIs360 = item.type === "360_image" || item.type === "360_video";
  const itemIsVideo = item.type === "2d_video" || item.type === "360_video";
  const w = itemIs360 ? 2048 : 1600;
  const h = itemIs360 ? 1024 : 900;
  if (itemIsVideo) {
    const el = createDemoVideo(w, h, itemIs360);
    el.muted = true;
    el.play();
    return Promise.resolve({ el, rightEl: null, aspect: w / h });
  }
  const draw = item.projection === "vr180" ? drawVr180Grid : drawEquirect;
  const packed = item.stereo === "pair" ? "mono" : item.stereo;
  const el = itemIs360 ? makeEquirectCanvas(w, h, packed, draw) : makeTestCardCanvas(w, h);
  return Promise.resolve({ el, rightEl: null, aspect: w / h });
}

function demoPlaylistDoc() {
  return {
    version: 1,
    id: "demo-playlist",
    index: 0,
    items: [
      { id: "p1", title: "Reactor Hall 360", type: "360_image", src: "demo/reactor-hall", projection: "equirectangular" },
      { id: "p2", title: "Primary Schematic", type: "2d_image", src: "demo/schematic" },
      { id: "p3", title: "Turbine Walkthrough", type: "360_video", src: "demo/turbine", projection: "equirectangular" },
      { id: "p4", title: "VR180 Inspection", type: "360_image", src: "demo/vr180-inspection", projection: "vr180" },
      { id: "p5", title: "Safety Briefing", type: "2d_video", src: "demo/briefing" },
      { id: "p6", title: "Stereo Pano (Top/Bottom)", type: "360_image", src: "demo/stereo-pano", stereo: "top_bottom" },
    ],
  };
}

/* Scene dim/fade: sceneTint eases toward its target each frame. */
let tintTarget = 1;
let tintCurrent = 1;

function setSceneDim(dim) { tintTarget = dim ? 0.35 : 1; }

function updateSceneTint(dt) {
  if (tintCurrent !== tintTarget) {
    tintCurrent += (tintTarget - tintCurrent) * Math.min(1, dt * 7);
    if (Math.abs(tintTarget - tintCurrent) < 0.005) tintCurrent = tintTarget;
  }
  sceneTint[0] = sceneTint[1] = sceneTint[2] = tintCurrent;
}

/* --------------------------------------------------------------- controls -- */

function updatePlayUi() {
  if (!mediaEl) return;
  dom.playToggle.classList.toggle("playing", !mediaEl.paused);
}
function updateMuteUi() {
  if (!mediaEl) return;
  dom.mute.classList.toggle("muted", !!mediaEl.muted);
}

function togglePlayback() {
  if (!isVideo || !mediaEl) return;
  if (mediaEl.paused) mediaEl.play().catch(() => {});
  else mediaEl.pause();
}

let controlsBound = false;

function onVideoTimeUpdate() {
  if (scrubbing || !mediaEl || !isFinite(mediaEl.duration) || mediaEl.duration <= 0) return;
  dom.scrub.value = String(Math.round((mediaEl.currentTime / mediaEl.duration) * 1000));
  dom.curTime.textContent = formatTime(mediaEl.currentTime);
}

// Button/scrubber listeners bind once to the persistent DOM controls and act on
// whatever `mediaEl` is current — so switching gallery items never stacks
// duplicate handlers.
function bindControlButtonsOnce() {
  if (controlsBound) return;
  controlsBound = true;
  dom.playToggle.addEventListener("click", togglePlayback);
  dom.mute.addEventListener("click", () => { if (mediaEl) { mediaEl.muted = !mediaEl.muted; updateMuteUi(); } });
  dom.scrub.addEventListener("input", () => {
    scrubbing = true;
    if (mediaEl && isFinite(mediaEl.duration)) dom.curTime.textContent = formatTime((dom.scrub.value / 1000) * mediaEl.duration);
  });
  dom.scrub.addEventListener("change", () => {
    if (mediaEl && isFinite(mediaEl.duration)) mediaEl.currentTime = (dom.scrub.value / 1000) * mediaEl.duration;
    scrubbing = false;
  });
}

function wireVideoControls() {
  dom.controls.hidden = false;
  bindControlButtonsOnce();
  updatePlayUi();
  updateMuteUi();
  dom.durTime.textContent = formatTime(mediaEl.duration);
  mediaEl.addEventListener("play", updatePlayUi);
  mediaEl.addEventListener("pause", updatePlayUi);
  mediaEl.addEventListener("loadedmetadata", () => { dom.durTime.textContent = formatTime(mediaEl.duration); });
  mediaEl.addEventListener("waiting", () => showLoader("Buffering\u2026"));
  mediaEl.addEventListener("playing", hideLoader);
  mediaEl.addEventListener("canplay", hideLoader);
  mediaEl.addEventListener("timeupdate", onVideoTimeUpdate);
}

/* --------------------------------------------------------------- webgl ----- */

const VERTEX_SRC = `
  attribute vec3 aPosition;
  attribute vec2 aUv;
  uniform mat4 uMvp;
  uniform vec2 uUvScale;
  uniform vec2 uUvOffset;
  varying vec2 vUv;
  void main() {
    vUv = aUv * uUvScale + uUvOffset;
    gl_Position = uMvp * vec4(aPosition, 1.0);
  }
`;

const FRAGMENT_SRC = `
  precision mediump float;
  uniform sampler2D uTex;
  uniform float uAlpha;
  uniform vec4 uTint;
  varying vec2 vUv;
  void main() {
    vec4 c = texture2D(uTex, vUv);
    gl_FragColor = vec4(c.rgb, c.a * uAlpha) * uTint;
  }
`;

function compileShader(type, source) {
  const shader = gl.createShader(type);
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    throw new Error("Shader compile failed: " + gl.getShaderInfoLog(shader));
  }
  return shader;
}

function initGl() {
  gl = dom.canvas.getContext("webgl", { xrCompatible: true, antialias: true, alpha: false });
  if (!gl) throw new Error("WebGL is not available.");

  program = gl.createProgram();
  gl.attachShader(program, compileShader(gl.VERTEX_SHADER, VERTEX_SRC));
  gl.attachShader(program, compileShader(gl.FRAGMENT_SHADER, FRAGMENT_SRC));
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error("Program link failed: " + gl.getProgramInfoLog(program));
  }

  attribs = {
    position: gl.getAttribLocation(program, "aPosition"),
    uv: gl.getAttribLocation(program, "aUv"),
  };
  uniforms = {
    mvp: gl.getUniformLocation(program, "uMvp"),
    uvScale: gl.getUniformLocation(program, "uUvScale"),
    uvOffset: gl.getUniformLocation(program, "uUvOffset"),
    tex: gl.getUniformLocation(program, "uTex"),
    alpha: gl.getUniformLocation(program, "uAlpha"),
    tint: gl.getUniformLocation(program, "uTint"),
  };

  maxTextureSize = gl.getParameter(gl.MAX_TEXTURE_SIZE) || 4096;
  // Standard equirect orientation: texture centre (u=0.5) on the initial gaze
  // (-Z) and u increasing to the viewer's right — the descending phi sweep is
  // what keeps text readable from inside the sphere.
  sphere = buildSphere(SPHERE_RADIUS, 48, 96, 2.5 * Math.PI, -2 * Math.PI);
  quad = buildQuad();

  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
  leftTex = createTexture();
  rightTex = stereoMode === "pair" ? createTexture() : leftTex;

  if (!isVideo) {
    uploadImageTexture(leftTex, mediaEl);
    if (stereoMode === "pair" && rightImageEl) uploadImageTexture(rightTex, rightImageEl);
  }

  initHud();
  initPanel();
}

// The hemisphere is only paid for when vr180 content actually shows.
function geometryFor(projection) {
  if (projection === "vr180") {
    if (!hemisphere) {
      // Front half only: texture centre dead ahead (-Z), u=0 at the viewer's
      // left (90°), u=1 at the right, open seam behind the head. Same
      // descending-phi convention as the full sphere so text stays readable.
      hemisphere = buildSphere(SPHERE_RADIUS, 48, 48, 2 * Math.PI, -Math.PI);
    }
    return hemisphere;
  }
  return sphere;
}

function createTexture() {
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  // 1x1 placeholder until real pixels arrive.
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([0, 0, 0, 255]));
  return tex;
}

function uploadTexture(tex, source) {
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
}

// Still-image uploads go through here (NOT the per-frame video path): clamps
// oversized panoramas to the GPU limit and verifies the first upload so an
// out-of-memory texture becomes a visible error instead of a black sphere.
function uploadImageTexture(tex, source) {
  let fitted = source;
  const w = source.naturalWidth || source.width || 0;
  const h = source.naturalHeight || source.height || 0;
  if (w > maxTextureSize || h > maxTextureSize) {
    const scale = maxTextureSize / Math.max(w, h);
    const cw = Math.max(1, Math.floor(w * scale));
    const ch = Math.max(1, Math.floor(h * scale));
    const cv = document.createElement("canvas");
    cv.width = cw;
    cv.height = ch;
    cv.getContext("2d").drawImage(source, 0, 0, cw, ch);
    fitted = cv;
    pendingNotice = "Downscaled for headset (" + cw + "×" + ch + ")";
    console.warn("[viewer] " + pendingNotice + " from " + w + "×" + h);
  }
  uploadTexture(tex, fitted);
  const glError = gl.getError();
  if (glError) {
    throw new Error("The headset ran out of texture memory for this image (GL 0x" +
      glError.toString(16) + ").");
  }
}

function buildBuffer(data, itemSize) {
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data), gl.STATIC_DRAW);
  return { buffer, itemSize };
}

function buildSphere(radius, lat, lon, phiStart, phiLength) {
  const sweepStart = phiStart === undefined ? 0 : phiStart;
  const sweep = phiLength === undefined ? Math.PI * 2 : phiLength;
  const positions = [];
  const uvs = [];
  const indices = [];
  for (let y = 0; y <= lat; y++) {
    const theta = (y * Math.PI) / lat;
    const sinTheta = Math.sin(theta);
    const cosTheta = Math.cos(theta);
    for (let x = 0; x <= lon; x++) {
      const phi = sweepStart + (x / lon) * sweep;
      const px = -radius * sinTheta * Math.cos(phi);
      const py = radius * cosTheta;
      const pz = radius * sinTheta * Math.sin(phi);
      positions.push(px, py, pz);
      uvs.push(x / lon, 1 - y / lat);
    }
  }
  for (let y = 0; y < lat; y++) {
    for (let x = 0; x < lon; x++) {
      const a = y * (lon + 1) + x;
      const b = a + lon + 1;
      indices.push(a, b, a + 1, b, b + 1, a + 1);
    }
  }
  const indexBuffer = gl.createBuffer();
  gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, indexBuffer);
  gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(indices), gl.STATIC_DRAW);
  return {
    position: buildBuffer(positions, 3),
    uv: buildBuffer(uvs, 2),
    indexBuffer,
    count: indices.length,
  };
}

function buildQuad() {
  const positions = [-1, -1, 0, 1, -1, 0, -1, 1, 0, 1, 1, 0];
  const uvs = [0, 0, 1, 0, 0, 1, 1, 1];
  return {
    position: buildBuffer(positions, 3),
    uv: buildBuffer(uvs, 2),
    count: 4,
  };
}

/* ------------------------------------------------------------------- hud --- */

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
  while (t.length > 1 && c.measureText(t + "\u2026").width > maxW) t = t.slice(0, -1);
  return t + "\u2026";
}

function initHud() {
  hudCanvas = document.createElement("canvas");
  hudCanvas.width = 1024;
  hudCanvas.height = 256;
  hudCtx = hudCanvas.getContext("2d");
  hudTex = createTexture();
  renderHud(config.title, defaultHudHint());
}

function defaultHudHint() {
  if (isVideo) return "Trigger \u2192 Play / Pause     Thumbstick \u2192 Seek";
  if (playlistItems.length > 1) return "Thumbstick \u2192 Next / Previous     Trigger \u2192 Show info";
  return "Trigger \u2192 Show info";
}

function renderHud(title, line) {
  if (!hudCtx) return;
  const c = hudCtx, w = hudCanvas.width, h = hudCanvas.height;
  c.clearRect(0, 0, w, h);
  // Glass chip: BlueSurface fill + frosted white stroke (launcher language).
  c.fillStyle = "rgba(22, 55, 97, 0.80)";
  roundRect(c, 16, 40, w - 32, h - 80, 28); c.fill();
  c.strokeStyle = "rgba(255, 255, 255, 0.24)"; c.lineWidth = 2;
  roundRect(c, 16, 40, w - 32, h - 80, 28); c.stroke();
  c.fillStyle = "#F04E23";
  roundRect(c, 48, 92, 8, 72, 4); c.fill();
  c.textBaseline = "middle";
  c.fillStyle = "#FFFFFF";
  c.font = "600 46px 'Segoe UI', Roboto, sans-serif";
  c.fillText(truncateText(c, title || "Media", w - 160), 84, 112);
  c.fillStyle = "#B8C7DC";
  c.font = "400 30px 'Segoe UI', Roboto, sans-serif";
  c.fillText(truncateText(c, line || "", w - 160), 84, 170);
  if (hudTex) uploadTexture(hudTex, hudCanvas);
}

function showHud(ms) { hudVisibleUntil = performance.now() + (ms || 4000); }
function flashHud(line, ms) { renderHud(config.title, line); showHud(ms || 1700); }
function hudAlpha(now) {
  const remain = hudVisibleUntil - now;
  if (remain <= 0) return 0;
  return remain < HUD_FADE_MS ? remain / HUD_FADE_MS : 1;
}

/* ------------------------------------------------------- in-VR controls ---- */
/* The control panel is the HUD pattern scaled up: one canvas rendered to one
 * world-locked quad, with a hit-region table for laser interaction. Layout
 * mirrors core-ui ProfessionalVideoControls (badge over title, orange accent,
 * seek with buffered bar, 5 s auto-hide). */

const PANEL_PX_W = 1024, PANEL_PX_H = 512;
const PANEL_W = 1.5, PANEL_H = 0.75;       // metres
const PANEL_Y = -0.55, PANEL_Z = -2.0;     // metres, in panel space
const PANEL_AUTO_HIDE_MS = 5000;           // matches VideoControlsVisibilityReducer
const PANEL_FADE_MS = 300;

let panelCanvas = null, panelCtx = null, panelTex = null;
let panelMode = "media";          // "media" | "loading" | "error"
let panelVisibleUntil = 0;
let panelYaw = 0;
let panelModelM = null;
let panelRegions = [];
let panelHover = null;
let panelScrubbing = false;
let panelScrubFraction = 0;
let panelError = null;            // { message, file }
let panelLoadingTitle = "";
let panelDirty = true;
let panelLastSecond = -1;
let headYaw = 0;                  // captured every XR frame for recentering
let activeInputSource = null;
let selectConsumedByReveal = false;
let pressedRegion = null;
let laserBuffer = null;
let whiteTex = null;
// FramatomeColors.Orange #F04E23 — keep in sync with BRANDING.md.
const ORANGE_TINT = new Float32Array([0.941, 0.306, 0.137, 0.9]);
const pointer = { active: false, origin: [0, 0, 0], dir: [0, 0, -1], hit: null };

function initPanel() {
  panelCanvas = document.createElement("canvas");
  panelCanvas.width = PANEL_PX_W;
  panelCanvas.height = PANEL_PX_H;
  panelCtx = panelCanvas.getContext("2d");
  panelTex = createTexture();
  laserBuffer = gl.createBuffer();
  whiteTex = createTexture();
  gl.bindTexture(gl.TEXTURE_2D, whiteTex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE,
    new Uint8Array([255, 255, 255, 255]));
  recenterPanel();
  renderPanel(performance.now());
}

function recenterPanel() {
  panelYaw = previewActive ? yaw : headYaw;
  panelModelM = multiply(
    rotationY(panelYaw),
    multiply(translationM(0, PANEL_Y, PANEL_Z), scaleM(PANEL_W / 2, PANEL_H / 2, 1))
  );
}

function panelAlpha(now) {
  if (!panelCanvas) return 0;
  if (panelMode !== "media" || panelScrubbing) return 1; // loading/error stay up
  const remain = panelVisibleUntil - now;
  if (remain <= 0) return 0;
  return remain < PANEL_FADE_MS ? remain / PANEL_FADE_MS : 1;
}

function showPanel(now, recenter) {
  const t = now === undefined ? performance.now() : now;
  const wasHidden = panelAlpha(t) <= 0;
  panelVisibleUntil = t + PANEL_AUTO_HIDE_MS;
  // A panel that pops up behind the user's back is a dead end: re-aim it at
  // the current gaze whenever it (re)appears.
  if (wasHidden || recenter) recenterPanel();
  panelDirty = true;
}

function hidePanel() {
  panelVisibleUntil = 0;
  panelScrubbing = false;
}

function panelShowLoading(title) {
  panelMode = "loading";
  panelLoadingTitle = title || "";
  panelDirty = true;
}

function panelShowError(message, file) {
  panelMode = "error";
  panelError = { message: message || "Something went wrong.", file: file || "" };
  panelDirty = true;
  showPanel(undefined, true);
}

function panelShowMedia() {
  panelMode = "media";
  panelError = null;
  panelDirty = true;
}

/* ---- panel canvas rendering --------------------------------------------- */

function addRegion(id, x, y, w, h) {
  panelRegions.push({ id, x, y, w, h });
}

function regionAt(cx, cy) {
  for (const r of panelRegions) {
    if (cx >= r.x && cx <= r.x + r.w && cy >= r.y && cy <= r.y + r.h) return r;
  }
  return null;
}

function panelButton(c, id, x, y, w, h, drawGlyph, label) {
  const hovered = panelHover === id;
  const pressed = pressedRegion === id;
  c.fillStyle = pressed ? "rgba(240, 78, 35, 0.32)" : hovered ? "rgba(240, 78, 35, 0.18)" : "rgba(255, 255, 255, 0.12)";
  roundRect(c, x, y, w, h, 18);
  c.fill();
  if (hovered) {
    c.strokeStyle = "#F04E23";
    c.lineWidth = 3;
    roundRect(c, x, y, w, h, 18);
    c.stroke();
  }
  c.fillStyle = hovered ? "#ffffff" : "#FFFFFF";
  drawGlyph(c, x + w / 2, y + h / 2);
  if (label) {
    c.textAlign = "center";
    c.textBaseline = "middle";
    c.font = "700 24px 'Segoe UI', Roboto, sans-serif";
    c.fillText(label, x + w / 2, y + h / 2);
  }
  addRegion(id, x, y, w, h);
}

const GLYPHS = {
  none: function () {},
  play: function (c, cx, cy) {
    c.beginPath(); c.moveTo(cx - 14, cy - 20); c.lineTo(cx - 14, cy + 20); c.lineTo(cx + 22, cy); c.closePath(); c.fill();
  },
  pause: function (c, cx, cy) {
    c.fillRect(cx - 18, cy - 20, 12, 40); c.fillRect(cx + 6, cy - 20, 12, 40);
  },
  prev: function (c, cx, cy) {
    c.fillRect(cx - 18, cy - 16, 7, 32);
    c.beginPath(); c.moveTo(cx + 18, cy - 16); c.lineTo(cx + 18, cy + 16); c.lineTo(cx - 8, cy); c.closePath(); c.fill();
  },
  next: function (c, cx, cy) {
    c.fillRect(cx + 11, cy - 16, 7, 32);
    c.beginPath(); c.moveTo(cx - 18, cy - 16); c.lineTo(cx - 18, cy + 16); c.lineTo(cx + 8, cy); c.closePath(); c.fill();
  },
  sound: function (c, cx, cy) {
    c.beginPath(); c.moveTo(cx - 16, cy - 8); c.lineTo(cx - 6, cy - 8); c.lineTo(cx + 4, cy - 17); c.lineTo(cx + 4, cy + 17); c.lineTo(cx - 6, cy + 8); c.lineTo(cx - 16, cy + 8); c.closePath(); c.fill();
    c.strokeStyle = c.fillStyle; c.lineWidth = 3.5;
    c.beginPath(); c.arc(cx + 8, cy, 11, -0.9, 0.9); c.stroke();
  },
  muted: function (c, cx, cy) {
    GLYPHS.sound(c, cx - 2, cy);
    c.strokeStyle = "#FFB4AB"; c.lineWidth = 4;
    c.beginPath(); c.moveTo(cx + 10, cy - 10); c.lineTo(cx + 22, cy + 10); c.stroke();
    c.beginPath(); c.moveTo(cx + 22, cy - 10); c.lineTo(cx + 10, cy + 10); c.stroke();
  },
  volDown: function (c, cx, cy) { c.fillRect(cx - 13, cy - 3.5, 26, 7); },
  volUp: function (c, cx, cy) { c.fillRect(cx - 13, cy - 3.5, 26, 7); c.fillRect(cx - 3.5, cy - 13, 7, 26); },
};

function wrapPanelText(c, text, maxW, maxLines) {
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

function renderPanel(now) {
  if (!panelCtx) return;
  const c = panelCtx, w = PANEL_PX_W, h = PANEL_PX_H;
  panelRegions = [];
  c.clearRect(0, 0, w, h);

  // Card
  // Glass card: vertical BlueSurface→BlueMidnight gradient with a frosted top
  // sheen and white glass stroke — the launcher panel language in canvas form.
  const cardGradient = c.createLinearGradient(0, 12, 0, h - 12);
  cardGradient.addColorStop(0, "rgba(22, 55, 97, 0.92)");
  cardGradient.addColorStop(1, "rgba(11, 23, 48, 0.94)");
  c.fillStyle = cardGradient;
  roundRect(c, 12, 12, w - 24, h - 24, 30); c.fill();
  const sheenGradient = c.createLinearGradient(0, 12, 0, 120);
  sheenGradient.addColorStop(0, "rgba(255, 255, 255, 0.10)");
  sheenGradient.addColorStop(1, "rgba(255, 255, 255, 0)");
  c.fillStyle = sheenGradient;
  roundRect(c, 12, 12, w - 24, 108, 30); c.fill();
  c.strokeStyle = "rgba(255, 255, 255, 0.24)"; c.lineWidth = 2;
  roundRect(c, 12, 12, w - 24, h - 24, 30); c.stroke();

  if (panelMode === "loading") { renderPanelLoading(c, w, h, now); return uploadPanel(); }
  if (panelMode === "error") { renderPanelError(c, w, h); return uploadPanel(); }

  // Header: orange accent bar, badge over title, playlist counter on the right.
  c.fillStyle = "#F04E23";
  roundRect(c, 48, 52, 8, 84, 4); c.fill();
  c.textBaseline = "middle";
  c.textAlign = "left";
  c.fillStyle = "#F04E23";
  c.font = "700 24px 'Segoe UI', Roboto, sans-serif";
  c.fillText(mediaLabel().toUpperCase(), 84, 72);
  if (playlistItems.length > 1) {
    c.textAlign = "right";
    c.fillStyle = "#B8C7DC";
    c.font = "600 26px 'Segoe UI', Roboto, sans-serif";
    c.fillText((playlistIndex + 1) + " / " + playlistItems.length, w - 60, 72);
    c.textAlign = "left";
  }
  c.fillStyle = "#FFFFFF";
  c.font = "600 40px 'Segoe UI', Roboto, sans-serif";
  c.fillText(truncateText(c, config.title || "Media", w - 200), 84, 118);

  const hasVideo = isVideo && mediaEl;
  if (hasVideo) {
    // Seek row: buffered bar under progress, thumb, time labels.
    const trackX = 84, trackW = w - 168, trackY = 196, trackH = 12;
    const duration = isFinite(mediaEl.duration) && mediaEl.duration > 0 ? mediaEl.duration : 0;
    const fraction = panelScrubbing
      ? panelScrubFraction
      : duration ? Math.min(1, mediaEl.currentTime / duration) : 0;
    c.fillStyle = "rgba(255,255,255,0.14)";
    roundRect(c, trackX, trackY, trackW, trackH, 6); c.fill();
    if (duration && mediaEl.buffered && mediaEl.buffered.length) {
      try {
        const buffered = Math.min(1, mediaEl.buffered.end(mediaEl.buffered.length - 1) / duration);
        c.fillStyle = "rgba(255,255,255,0.22)";
        roundRect(c, trackX, trackY, Math.max(trackH, trackW * buffered), trackH, 6); c.fill();
      } catch (e) { /* ignore */ }
    }
    c.fillStyle = "#F04E23";
    roundRect(c, trackX, trackY, Math.max(trackH, trackW * fraction), trackH, 6); c.fill();
    c.beginPath();
    c.arc(trackX + trackW * fraction, trackY + trackH / 2, 17, 0, Math.PI * 2);
    c.fillStyle = "#ffffff"; c.fill();
    // Generous hit slab around the visual track for laser accuracy.
    addRegion("seek", trackX - 10, trackY - 34, trackW + 20, trackH + 68);
    const previewTime = panelScrubbing && duration ? panelScrubFraction * duration : (mediaEl.currentTime || 0);
    c.fillStyle = panelScrubbing ? "#ffffff" : "#B8C7DC";
    c.font = "600 26px 'Segoe UI', Roboto, sans-serif";
    c.textAlign = "left";
    c.fillText(formatTime(previewTime), trackX, trackY + 52);
    c.textAlign = "right";
    c.fillText(formatTime(duration), trackX + trackW, trackY + 52);
    c.textAlign = "left";
  }

  // Button row.
  const rowY = hasVideo ? 296 : 230;
  const rowH = hasVideo ? 120 : 140;
  const hasNav = playlistItems.length > 1;
  let x = 60;
  if (hasNav) { panelButton(c, "prev", x, rowY, 110, rowH, GLYPHS.prev); x += 130; }
  if (hasVideo) {
    panelButton(c, "play", x, rowY, 150, rowH, mediaEl.paused ? GLYPHS.play : GLYPHS.pause);
    x += 170;
  }
  if (hasNav) { panelButton(c, "next", x, rowY, 110, rowH, GLYPHS.next); x += 130; }
  if (hasVideo) {
    x += 14;
    panelButton(c, "voldown", x, rowY, 86, rowH, GLYPHS.volDown); x += 100;
    panelButton(c, "mute", x, rowY, 110, rowH, mediaEl.muted ? GLYPHS.muted : GLYPHS.sound); x += 124;
    panelButton(c, "volup", x, rowY, 86, rowH, GLYPHS.volUp); x += 100;
    const volume = mediaEl.muted ? 0 : (typeof mediaEl.volume === "number" ? mediaEl.volume : 1);
    c.fillStyle = "#B8C7DC";
    c.font = "600 22px 'Segoe UI', Roboto, sans-serif";
    c.textAlign = "center";
    c.fillText(Math.round(volume * 100) + "%", x - 169, rowY + rowH + 26);
    c.textAlign = "left";
  }
  const exitW = 224;
  panelButton(c, "exit", w - 60 - exitW, rowY, exitW, rowH, GLYPHS.none, "EXIT TO HUB");

  uploadPanel();
}

function renderPanelLoading(c, w, h, now) {
  const angle = ((now || 0) / 900) * Math.PI * 2;
  c.strokeStyle = "rgba(255,255,255,0.12)";
  c.lineWidth = 10;
  c.beginPath(); c.arc(w / 2, 200, 52, 0, Math.PI * 2); c.stroke();
  c.strokeStyle = "#F04E23";
  c.beginPath(); c.arc(w / 2, 200, 52, angle, angle + Math.PI * 1.35); c.stroke();
  c.fillStyle = "#FFFFFF";
  c.textAlign = "center"; c.textBaseline = "middle";
  c.font = "600 34px 'Segoe UI', Roboto, sans-serif";
  c.fillText(truncateText(c, "Loading — " + (panelLoadingTitle || "media"), w - 160), w / 2, 320);
  c.fillStyle = "#B8C7DC";
  c.font = "400 24px 'Segoe UI', Roboto, sans-serif";
  c.fillText("Framatome VR", w / 2, 372);
  c.textAlign = "left";
}

function renderPanelError(c, w, h) {
  c.fillStyle = "rgba(255, 180, 171, 0.16)";
  c.beginPath(); c.arc(w / 2, 130, 46, 0, Math.PI * 2); c.fill();
  c.strokeStyle = "#FFB4AB"; c.lineWidth = 5;
  c.beginPath();
  c.moveTo(w / 2, 104); c.lineTo(w / 2, 140); c.stroke();
  c.beginPath(); c.arc(w / 2, 156, 3.4, 0, Math.PI * 2); c.fillStyle = "#FFB4AB"; c.fill();
  c.fillStyle = "#FFFFFF";
  c.textAlign = "center"; c.textBaseline = "middle";
  c.font = "700 36px 'Segoe UI', Roboto, sans-serif";
  c.fillText("Unable to play this media", w / 2, 218);
  c.fillStyle = "#B8C7DC";
  c.font = "400 27px 'Segoe UI', Roboto, sans-serif";
  const lines = wrapPanelText(c, panelError ? panelError.message : "", w - 220, 2);
  lines.forEach((line, i) => c.fillText(line, w / 2, 262 + i * 36));
  if (panelError && panelError.file) {
    c.font = "600 22px 'Segoe UI', Roboto, sans-serif";
    c.fillText(truncateText(c, panelError.file, w - 280), w / 2, 262 + lines.length * 36 + 8);
  }
  const rowY = 386, rowH = 86;
  const hasNav = playlistItems.length > 1;
  const buttons = hasNav ? ["retry", "back", "exit"] : ["retry", "exit"];
  const labels = { retry: "TRY AGAIN", back: "BACK", exit: "EXIT TO HUB" };
  const bw = 232, gap = 26;
  let x = (w - (buttons.length * bw + (buttons.length - 1) * gap)) / 2;
  for (const id of buttons) {
    panelButton(c, id, x, rowY, bw, rowH, GLYPHS.none, labels[id]);
    x += bw + gap;
  }
  c.textAlign = "left";
}

function uploadPanel() {
  if (panelTex) uploadTexture(panelTex, panelCanvas);
  panelDirty = false;
}

// Redraw only when something the user can see changed; ≤1 upload/s while a
// video simply plays. The loading spinner animates, so it redraws each frame.
function maybeRenderPanel(now) {
  if (!panelCtx) return;
  if (panelMode === "loading") { renderPanel(now); return; }
  if (panelMode === "media" && isVideo && mediaEl && !mediaEl.paused) {
    const second = Math.floor(mediaEl.currentTime || 0);
    if (second !== panelLastSecond) { panelLastSecond = second; panelDirty = true; }
  }
  if (panelScrubbing) panelDirty = true;
  if (panelDirty) renderPanel(now);
}

/* ---- ray ↔ panel hit testing (pure; shared with the desktop preview) ----- */

function panelHitFromRay(origin, dir) {
  if (!panelModelM) return null;
  const cos = Math.cos(-panelYaw), sin = Math.sin(-panelYaw);
  const ox = cos * origin[0] + sin * origin[2];
  const oz = -sin * origin[0] + cos * origin[2];
  const oy = origin[1];
  const dx = cos * dir[0] + sin * dir[2];
  const dz = -sin * dir[0] + cos * dir[2];
  const dy = dir[1];
  if (Math.abs(dz) < 1e-5) return null;
  const t = (PANEL_Z - oz) / dz;
  if (t <= 0.05 || t > 30) return null;
  const px = ox + dx * t;
  const py = oy + dy * t;
  const lx = px / (PANEL_W / 2);
  const ly = (py - PANEL_Y) / (PANEL_H / 2);
  if (lx < -1.06 || lx > 1.06 || ly < -1.06 || ly > 1.06) return null;
  const cx = (Math.max(-1, Math.min(1, lx)) * 0.5 + 0.5) * PANEL_PX_W;
  const cy = (1 - (Math.max(-1, Math.min(1, ly)) * 0.5 + 0.5)) * PANEL_PX_H;
  const region = regionAt(cx, cy);
  return {
    t,
    cx,
    cy,
    region: region ? region.id : null,
    point: [origin[0] + dir[0] * t, origin[1] + dir[1] * t, origin[2] + dir[2] * t],
  };
}

function seekFractionAt(cx) {
  const trackX = 84, trackW = PANEL_PX_W - 168;
  return Math.max(0, Math.min(1, (cx - trackX) / trackW));
}

function pulseHaptic(intensity, durationMs) {
  try {
    const gp = activeInputSource && activeInputSource.gamepad;
    const actuator = gp && gp.hapticActuators && gp.hapticActuators[0];
    if (actuator && actuator.pulse) actuator.pulse(intensity, durationMs);
  } catch (e) { /* haptics are best-effort */ }
}

function setPointerHit(hit, now) {
  const prevRegion = pointer.hit && pointer.hit.region;
  pointer.hit = hit;
  const region = hit && hit.region;
  if (region !== panelHover) {
    panelHover = region;
    panelDirty = true;
    if (region && region !== prevRegion) pulseHaptic(0.35, 12);
  }
  if (hit) showPanelKeepAlive(now);
  if (panelScrubbing && hit) {
    panelScrubFraction = seekFractionAt(hit.cx);
    panelDirty = true;
  }
}

// Pointing at the panel keeps it alive without recentering it mid-aim.
function showPanelKeepAlive(now) {
  const t = now === undefined ? performance.now() : now;
  if (panelAlpha(t) > 0) panelVisibleUntil = t + PANEL_AUTO_HIDE_MS;
}

function updateXrPointer(frame, now) {
  pointer.active = false;
  if (!xrRefSpace) { setPointerHit(null, now); return; }
  let source = null;
  try {
    const sources = frame.session.inputSources;
    for (const s of sources) {
      if (!s.targetRaySpace) continue;
      if (s === activeInputSource) { source = s; break; }
      if (!source) source = s;
    }
    if (!source) { setPointerHit(null, now); return; }
    const rayPose = frame.getPose(source.targetRaySpace, xrRefSpace);
    if (!rayPose) { setPointerHit(null, now); return; }
    const m = rayPose.transform.matrix;
    pointer.origin[0] = m[12]; pointer.origin[1] = m[13]; pointer.origin[2] = m[14];
    pointer.dir[0] = -m[8]; pointer.dir[1] = -m[9]; pointer.dir[2] = -m[10];
    pointer.active = true;
    setPointerHit(panelAlpha(now) > 0 ? panelHitFromRay(pointer.origin, pointer.dir) : null, now);
  } catch (e) {
    setPointerHit(null, now);
  }
}

/* ---- actions -------------------------------------------------------------- */

function setVolume(value) {
  if (!mediaEl) return;
  const v = Math.max(0, Math.min(1, value));
  try {
    mediaEl.volume = v;
    if (v > 0 && mediaEl.muted) { mediaEl.muted = false; updateMuteUi(); }
  } catch (e) { /* ignore */ }
  panelDirty = true;
}

function retryCurrent() {
  panelShowMedia();
  if (playlistItems.length > 1 && playlistIndex >= 0) {
    const index = playlistIndex;
    playlistIndex = -1; // force a reload of the same slot
    loadItem(index);
    return;
  }
  location.reload();
}

function exitToHub() {
  try { if (isVideo && mediaEl) mediaEl.pause(); } catch (e) { /* ignore */ }
  const navigate = () => location.replace("/");
  if (xrSession) {
    try {
      xrSession.addEventListener("end", navigate, { once: true });
      xrSession.end();
    } catch (e) {
      navigate();
    }
  } else {
    navigate();
  }
}

function panelAction(id) {
  switch (id) {
    case "play": togglePlayback(); panelDirty = true; break;
    case "prev": if (!itemLoading) loadItem(playlistIndex - 1); break;
    case "next": if (!itemLoading) loadItem(playlistIndex + 1); break;
    case "mute":
      if (mediaEl) { mediaEl.muted = !mediaEl.muted; updateMuteUi(); panelDirty = true; }
      break;
    case "voldown": setVolume((mediaEl && mediaEl.volume || 0) - 0.1); break;
    case "volup": setVolume((mediaEl && mediaEl.volume || 0) + 0.1); break;
    case "exit": exitToHub(); break;
    case "retry": retryCurrent(); break;
    case "back": panelShowMedia(); break;
    default: return;
  }
  showPanelKeepAlive();
  pulseHaptic(0.6, 24);
}

function onSelectStart(event) {
  activeInputSource = event.inputSource || activeInputSource;
  const now = performance.now();
  if (panelAlpha(now) <= 0) {
    // First press only reveals the panel — never also toggles playback.
    selectConsumedByReveal = true;
    showPanel(now, true);
    return;
  }
  const hit = pointer.hit;
  if (hit && hit.region === "seek") {
    panelScrubbing = true;
    panelScrubFraction = seekFractionAt(hit.cx);
    panelDirty = true;
    return;
  }
  pressedRegion = hit ? hit.region : null;
  if (pressedRegion) panelDirty = true;
}

function onSelectEnd() {
  const now = performance.now();
  if (selectConsumedByReveal) {
    selectConsumedByReveal = false;
    return;
  }
  if (panelScrubbing) {
    panelScrubbing = false;
    if (mediaEl && isFinite(mediaEl.duration) && mediaEl.duration > 0) {
      mediaEl.currentTime = panelScrubFraction * mediaEl.duration;
    }
    showPanelKeepAlive(now);
    pulseHaptic(0.6, 24);
    panelDirty = true;
    return;
  }
  const region = pointer.hit && pointer.hit.region;
  const pressed = pressedRegion;
  pressedRegion = null;
  panelDirty = true;
  if (region && region === pressed) {
    panelAction(region);
    return;
  }
  if (region) return; // released over a different control: do nothing
  // Off-panel select keeps the original contract.
  if (panelMode !== "media") return;
  try {
    if (isVideo) {
      const willPlay = mediaEl && mediaEl.paused;
      togglePlayback();
      panelDirty = true;
      flashHud(willPlay ? "Playing" : "Paused", 1400);
    } else {
      showPanel(now);
    }
  } catch (e) { /* ignore */ }
}

function onSqueezeStart(event) {
  activeInputSource = event.inputSource || activeInputSource;
  const now = performance.now();
  if (panelAlpha(now) > 0 && panelMode === "media") hidePanel();
  else showPanel(now, true);
}

/* ---- panel + laser drawing ------------------------------------------------ */

function drawPanel(projection, viewMatrix, alpha) {
  try {
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(program);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, panelTex);
    gl.uniform1i(uniforms.tex, 0);
    gl.uniform1f(uniforms.alpha, alpha);
    gl.uniform4fv(uniforms.tint, WHITE_TINT);
    gl.uniform2fv(uniforms.uvScale, [1, 1]);
    gl.uniform2fv(uniforms.uvOffset, [0, 0]);
    gl.uniformMatrix4fv(uniforms.mvp, false, multiply(multiply(projection, viewMatrix), panelModelM));
    bindGeometry(quad);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, quad.count);
    gl.uniform1f(uniforms.alpha, 1.0);
    gl.disable(gl.BLEND);
  } catch (e) { /* never break the scene */ }
}

// Wolvic draws no controller models inside immersive sessions — without a
// laser users aim blind. One GL_LINES segment + a small cursor dot. The
// desktop preview's ray starts at the camera, so only the cursor is drawn.
function drawLaser(projection, viewMatrix) {
  if (!pointer.active || !laserBuffer) return;
  try {
    const hit = pointer.hit;
    const reach = hit ? null : 3.0;
    const end = hit ? hit.point : [
      pointer.origin[0] + pointer.dir[0] * reach,
      pointer.origin[1] + pointer.dir[1] * reach,
      pointer.origin[2] + pointer.dir[2] * reach,
    ];
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(program);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, whiteTex);
    gl.uniform1i(uniforms.tex, 0);
    gl.uniform1f(uniforms.alpha, 1.0);
    gl.uniform4fv(uniforms.tint, ORANGE_TINT);
    gl.uniform2fv(uniforms.uvScale, [0, 0]);
    gl.uniform2fv(uniforms.uvOffset, [0.5, 0.5]);
    gl.uniformMatrix4fv(uniforms.mvp, false, multiply(projection, viewMatrix));
    if (!previewActive) {
      gl.bindBuffer(gl.ARRAY_BUFFER, laserBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
        pointer.origin[0], pointer.origin[1], pointer.origin[2],
        end[0], end[1], end[2],
      ]), gl.DYNAMIC_DRAW);
      gl.enableVertexAttribArray(attribs.position);
      gl.vertexAttribPointer(attribs.position, 3, gl.FLOAT, false, 0, 0);
      gl.disableVertexAttribArray(attribs.uv);
      gl.vertexAttrib2f(attribs.uv, 0.5, 0.5);
      gl.drawArrays(gl.LINES, 0, 2);
    }
    if (hit) {
      const cursorTint = hit.region ? WHITE_TINT : ORANGE_TINT;
      gl.uniform4fv(uniforms.tint, cursorTint);
      const s = 0.012;
      const model = multiply(
        rotationY(panelYaw),
        multiply(
          translationM(
            Math.cos(-panelYaw) * hit.point[0] + Math.sin(-panelYaw) * hit.point[2],
            hit.point[1],
            -Math.sin(-panelYaw) * hit.point[0] + Math.cos(-panelYaw) * hit.point[2] + 0.005
          ),
          scaleM(s, s, 1)
        )
      );
      gl.uniformMatrix4fv(uniforms.mvp, false, multiply(multiply(projection, viewMatrix), model));
      bindGeometry(quad);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, quad.count);
    }
    gl.uniform4fv(uniforms.tint, WHITE_TINT);
    gl.disable(gl.BLEND);
  } catch (e) { /* never break the scene */ }
}

/* --------------------------------------------------------------- matrices -- */

function multiply(a, b) {
  const out = new Float32Array(16);
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      out[c * 4 + r] =
        a[r] * b[c * 4] +
        a[4 + r] * b[c * 4 + 1] +
        a[8 + r] * b[c * 4 + 2] +
        a[12 + r] * b[c * 4 + 3];
    }
  }
  return out;
}

function withoutTranslation(m) {
  const out = m.slice();
  out[12] = 0;
  out[13] = 0;
  out[14] = 0;
  return out;
}

function panelModel() {
  const halfW = (PANEL_HEIGHT * mediaAspect) / 2;
  const halfH = PANEL_HEIGHT / 2;
  // column-major: scale on the diagonal, translation in the last column.
  return new Float32Array([
    halfW, 0, 0, 0,
    0, halfH, 0, 0,
    0, 0, 1, 0,
    0, 0, -PANEL_DISTANCE, 1,
  ]);
}

/* --------------------------------------------------------------- stereo ---- */

function stereoUv(eye) {
  const left = eye !== "right";
  switch (stereoMode) {
    case "top_bottom":
      // Top half of the frame carries the left eye (FLIP_Y puts top at v=1).
      return { scale: [1, 0.5], offset: [0, left ? 0.5 : 0] };
    case "left_right":
      return { scale: [0.5, 1], offset: [left ? 0 : 0.5, 0] };
    case "pair":
    case "mono":
    default:
      return { scale: [1, 1], offset: [0, 0] };
  }
}

/* --------------------------------------------------------------- drawing --- */

function bindGeometry(geometry) {
  gl.bindBuffer(gl.ARRAY_BUFFER, geometry.position.buffer);
  gl.enableVertexAttribArray(attribs.position);
  gl.vertexAttribPointer(attribs.position, 3, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, geometry.uv.buffer);
  gl.enableVertexAttribArray(attribs.uv);
  gl.vertexAttribPointer(attribs.uv, 2, gl.FLOAT, false, 0, 0);
}

function drawScene(projection, viewMatrix, eye) {
  const uv = stereoUv(eye);
  const tex = stereoMode === "pair" && eye === "right" ? rightTex : leftTex;

  gl.useProgram(program);
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.uniform1i(uniforms.tex, 0);
  gl.uniform1f(uniforms.alpha, 1.0);
  gl.uniform4fv(uniforms.tint, sceneTint);
  gl.uniform2fv(uniforms.uvScale, uv.scale);
  gl.uniform2fv(uniforms.uvOffset, uv.offset);

  if (is360) {
    const geometry = geometryFor(config.projection);
    const mvp = multiply(projection, withoutTranslation(viewMatrix));
    gl.uniformMatrix4fv(uniforms.mvp, false, mvp);
    bindGeometry(geometry);
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, geometry.indexBuffer);
    gl.drawElements(gl.TRIANGLES, geometry.count, gl.UNSIGNED_SHORT, 0);
  } else {
    const mvp = multiply(multiply(projection, viewMatrix), panelModel());
    gl.uniformMatrix4fv(uniforms.mvp, false, mvp);
    bindGeometry(quad);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, quad.count);
  }
}

function drawHud(projection, viewMatrix, alpha) {
  // The HUD must never break the scene: any failure is swallowed.
  try {
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(program);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, hudTex);
    gl.uniform1i(uniforms.tex, 0);
    gl.uniform1f(uniforms.alpha, alpha);
    gl.uniform4fv(uniforms.tint, WHITE_TINT);
    gl.uniform2fv(uniforms.uvScale, [1, 1]);
    gl.uniform2fv(uniforms.uvOffset, [0, 0]);
    gl.uniformMatrix4fv(uniforms.mvp, false, multiply(multiply(projection, viewMatrix), HUD_MODEL));
    bindGeometry(quad);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, quad.count);
    gl.uniform1f(uniforms.alpha, 1.0);
    gl.disable(gl.BLEND);
  } catch (e) { /* swallow */ }
}

function refreshVideoTexture() {
  if (!isVideo || !mediaEl || mediaEl.readyState < 2) return;
  uploadTexture(leftTex, mediaEl);
}

let stickNavReady = true;
let stickNavCooldownUntil = 0;

function handleControllerInput(session, dt, now) {
  try {
    for (const source of session.inputSources) {
      const gp = source.gamepad;
      if (!gp || !gp.axes || !gp.axes.length) continue;
      // Standard XR mapping puts the thumbstick on axes[2]/axes[3]; fall back to 0/1.
      const x = gp.axes.length > 2 ? gp.axes[2] : gp.axes[0];
      if (typeof x !== "number") continue;

      // Video keeps the established contract: stick X = seek.
      if (isVideo && mediaEl && isFinite(mediaEl.duration) && mediaEl.duration > 0) {
        if (Math.abs(x) < STICK_DEADZONE) continue;
        const t = Math.max(0, Math.min(mediaEl.duration, mediaEl.currentTime + x * SEEK_RATE * dt));
        mediaEl.currentTime = t;
        flashHud(formatTime(t) + "  /  " + formatTime(mediaEl.duration), 1100);
        return; // one stick is enough
      }

      // Images: an edge-triggered stick flick steps through the playlist.
      if (playlistItems.length > 1 && !itemLoading) {
        if (Math.abs(x) < 0.6) {
          if (Math.abs(x) < STICK_DEADZONE) stickNavReady = true;
          continue;
        }
        if (stickNavReady && now >= stickNavCooldownUntil) {
          stickNavReady = false;
          stickNavCooldownUntil = now + 400;
          loadItem(playlistIndex + (x > 0 ? 1 : -1));
        }
        return;
      }
    }
  } catch (e) { /* ignore input glitches */ }
}

function onXRFrame(time, frame) {
  const session = frame.session;
  session.requestAnimationFrame(onXRFrame);

  const dt = lastFrameTime ? Math.min(0.1, (time - lastFrameTime) / 1000) : 0;
  lastFrameTime = time;

  const pose = frame.getViewerPose(xrRefSpace);
  gl.bindFramebuffer(gl.FRAMEBUFFER, glLayer.framebuffer);
  gl.disable(gl.DEPTH_TEST);
  gl.disable(gl.CULL_FACE);
  gl.clearColor(0, 0, 0, 1);
  gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  if (!pose) return;

  const headM = pose.transform.matrix;
  headYaw = Math.atan2(headM[8], headM[10]);

  refreshVideoTexture();
  handleControllerInput(session, dt, time);
  updateXrPointer(frame, time);
  updateSceneTint(dt);

  const alpha = hudAlpha(time);
  const pAlpha = panelAlpha(time);
  if (pAlpha > 0) maybeRenderPanel(time);
  for (const view of pose.views) {
    const vp = glLayer.getViewport(view);
    gl.viewport(vp.x, vp.y, vp.width, vp.height);
    drawScene(view.projectionMatrix, view.transform.inverse.matrix, view.eye);
    if (alpha > 0) drawHud(view.projectionMatrix, view.transform.inverse.matrix, alpha);
    if (pAlpha > 0) {
      drawPanel(view.projectionMatrix, view.transform.inverse.matrix, pAlpha);
      drawLaser(view.projectionMatrix, view.transform.inverse.matrix);
    }
  }
}

/* --------------------------------------------------------------- session --- */

async function enterVR(silent) {
  if (xrSession) return;
  if (!navigator.xr) {
    if (!silent) setStatus("Immersive VR is not available on this device.");
    return;
  }
  try {
    if (isVideo && mediaEl) {
      mediaEl.muted = false;
      updateMuteUi();
      await mediaEl.play().catch(() => {});
    }
    xrSession = await navigator.xr.requestSession("immersive-vr", {
      optionalFeatures: ["local-floor", "bounded-floor"],
    });
    await gl.makeXRCompatible();
    glLayer = new XRWebGLLayer(xrSession, gl);
    xrSession.updateRenderState({ baseLayer: glLayer });
    xrRefSpace = await xrSession.requestReferenceSpace("local");
    xrSession.addEventListener("end", onSessionEnded);
    xrSession.addEventListener("selectstart", onSelectStart);
    xrSession.addEventListener("selectend", onSelectEnd);
    xrSession.addEventListener("squeezestart", onSqueezeStart);
    dom.body.classList.add("immersive");
    lastFrameTime = 0;
    renderHud(config.title, defaultHudHint());
    showHud(4800);
    if (pendingNotice) {
      flashHud(pendingNotice, 3600);
      pendingNotice = "";
    }
    showPanel(performance.now(), true); // orient users for the first 5 s
    xrSession.requestAnimationFrame(onXRFrame);
  } catch (err) {
    xrSession = null;
    if (!silent) setStatus("Could not start the immersive view. " + (err && err.message ? err.message : ""));
  }
}

function onSessionEnded() {
  xrSession = null;
  glLayer = null;
  pointer.active = false;
  pointer.hit = null;
  panelHover = null;
  panelScrubbing = false;
  activeInputSource = null;
  hidePanel();
  dom.body.classList.remove("immersive");
  setStatus("Immersive view closed. Press Enter VR to resume.");
}

/* --------------------------------------------------------------- bootstrap - */

async function main() {
  dom.title.textContent = config.title;
  document.title = "Framatome VR \u2014 " + config.title;
  setBadge();
  dom.retry.addEventListener("click", retryCurrent);
  if (dom.exitHub) dom.exitHub.addEventListener("click", exitToHub);
  if (dom.errorExitHub) dom.errorExitHub.addEventListener("click", exitToHub);

  if (config.type === "3d_model" || config.type === "point_cloud") {
    setError("3D models and point clouds are coming to Framatome Player soon.", "Coming soon");
    return;
  }

  showLoader(config.title ? "Loading \u2014 " + config.title + "\u2026" : "Loading media\u2026");
  fetchPlaylist(); // in parallel \u2014 first render never waits on the gallery
  try {
    await loadMedia();
  } catch (err) {
    showMediaError(err.message || "Unable to load media.");
    return;
  }

  if (isVideo) wireVideoControls();

  try {
    initGl();
  } catch (err) {
    hideLoader();
    setStatus("Flat preview only \u2014 " + (err.message || "WebGL unavailable."));
    return;
  }

  hideLoader();

  if (config.playlistDemo) {
    // Test harness: feed a synthetic document through the production playlist
    // path (adopt \u2192 loadItem), swapping in procedural media.
    adoptPlaylist(demoPlaylistDoc());
    playlistIndex = -1;
    loadItem(0);
  }

  const supported = navigator.xr && (await navigator.xr.isSessionSupported("immersive-vr").catch(() => false));
  if (supported && gl) {
    dom.enterVr.hidden = false;
    setStatus("Ready \u2014 put on your headset and press Enter VR.");
    dom.enterVr.addEventListener("click", () => enterVR(false));
    // Honour the launcher's auto-VR request: kiosk launches (like 3D Vista's
    // ?vr autostart) try to enter immediately; if the platform still demands a
    // user gesture, fall back to the first interaction without flashing an error.
    if (config.autoVr) {
      enterVR(true);
      const once = () => { window.removeEventListener("pointerdown", once); enterVR(false); };
      window.addEventListener("pointerdown", once, { once: true });
    }
    window.FramatomeEnterVR = enterVR; // hook for the player's kiosk auto-start
  } else {
    dom.vrHintText.textContent = "Open this media on the Framatome VR headset for the full immersive experience.";
    dom.vrHint.hidden = false;
    if (gl) {
      const previewBtn = document.createElement("button");
      previewBtn.className = "btn-primary";
      previewBtn.type = "button";
      previewBtn.textContent = "Preview in 3D";
      previewBtn.addEventListener("click", () => startPreview());
      dom.enterVr.parentNode.insertBefore(previewBtn, dom.enterVr);
      setStatus("Immersive VR is unavailable here \u2014 use the 3D preview.");
      if (config.preview) startPreview();
    } else {
      setStatus("");
    }
  }
}

/* ============================================================================
 * DEMO / DESKTOP PREVIEW  (non-production)
 *
 * Active only when the URL carries ?demo=<scenario> or ?preview. The launcher
 * never sends these, so production launches are unaffected. This renders the
 * exact same WebGL scene + HUD the headset shows, but through a mouse-look
 * perspective camera instead of WebXR — so the full look & feel can be checked
 * in a desktop browser. Sample media is generated procedurally (no binaries).
 * ========================================================================== */

function applyDemoConfig() {
  if (!config.demo) return;
  const SCENES = {
    "2d_image":       { type: "2d_image",  title: "2D Image \u2014 Test Card" },
    "2d_video":       { type: "2d_video",  title: "2D Video \u2014 Test Pattern" },
    "360_image":      { type: "360_image", title: "360\u00b0 Image \u2014 Equirect Grid", projection: "equirectangular" },
    "360_video":      { type: "360_video", title: "360\u00b0 Video \u2014 Equirect Motion", projection: "equirectangular" },
    "360_image_3d":   { type: "360_image", title: "360\u00b0 Stereo Image (Top/Bottom)", projection: "equirectangular", stereo: "top_bottom" },
    "vr180_image":    { type: "360_image", title: "VR180 Image \u2014 Front Grid", projection: "vr180" },
    "vr180_video":    { type: "360_video", title: "VR180 Video \u2014 Front Motion", projection: "vr180" },
    "vr180_image_3d": { type: "360_image", title: "VR180 Stereo Image (SBS)", projection: "vr180", stereo: "left_right" },
    "error_video":    { type: "2d_video",  title: "Decode Error Demo", failAfterMs: 2000 },
    "gallery":        { type: "2d_image",  title: "Framatome Media Library", gallery: true },
    "playlist":       { type: "2d_image",  title: "Framatome Media Library", playlistDemo: true },
  };
  const s = SCENES[config.demo];
  if (!s) return;
  config.type = s.type;
  config.projection = s.projection || config.projection;
  config.stereo = s.stereo || "mono";
  config.gallery = !!s.gallery;
  config.playlistDemo = !!s.playlistDemo;
  config.failAfterMs = s.failAfterMs || 0;
  config.src = "demo";
  if (!params.get("title")) config.title = s.title;
}

/* ---- procedural sample media ------------------------------------------- */

function fillBackdrop(ctx, w, h) {
  const g = ctx.createLinearGradient(0, 0, 0, h);
  g.addColorStop(0, "#1b3a5b");
  g.addColorStop(0.5, "#0f2436");
  g.addColorStop(1, "#0a1622");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, w, h);
}

function drawEquirect(ctx, w, h) {
  fillBackdrop(ctx, w, h);
  ctx.lineWidth = Math.max(1, w / 1024);
  ctx.strokeStyle = "rgba(255,255,255,0.16)";
  for (let i = 1; i < 12; i++) { const x = (i / 12) * w; ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
  for (let j = 1; j < 6; j++) { const y = (j / 6) * h; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
  ctx.strokeStyle = "#F04E23"; ctx.lineWidth = Math.max(2, w / 512);
  ctx.beginPath(); ctx.moveTo(0, h / 2); ctx.lineTo(w, h / 2); ctx.stroke();
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillStyle = "#FFFFFF"; ctx.font = "700 " + Math.round(h / 14) + "px 'Segoe UI', Roboto, sans-serif";
  // Standard equirect: image centre = FRONT, left quarter = LEFT, right = RIGHT.
  const labels = [["BACK", 0.0], ["LEFT", 0.25], ["FRONT", 0.5], ["RIGHT", 0.75]];
  for (const pair of labels) ctx.fillText(pair[0], pair[1] * w + w / 24, h / 2 - h / 8);
  ctx.fillStyle = "rgba(240, 78, 35,0.92)"; ctx.font = "800 " + Math.round(h / 18) + "px 'Segoe UI', Roboto, sans-serif";
  ctx.fillText("FRAMATOME VR \u00b7 360\u00b0 TEST", w / 2, h / 4);
}

// VR180 test pattern: the whole texture spans only the front hemisphere, so
// orientation problems (mirroring, wrong-centred seam) are instantly visible —
// CENTER must sit dead ahead with L-EDGE/R-EDGE at ±90° and text readable.
function drawVr180Grid(ctx, w, h) {
  fillBackdrop(ctx, w, h);
  ctx.lineWidth = Math.max(1, w / 1024);
  ctx.strokeStyle = "rgba(255,255,255,0.16)";
  for (let i = 1; i < 12; i++) { const x = (i / 12) * w; ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
  for (let j = 1; j < 6; j++) { const y = (j / 6) * h; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
  ctx.strokeStyle = "#F04E23"; ctx.lineWidth = Math.max(2, w / 512);
  ctx.beginPath(); ctx.moveTo(0, h / 2); ctx.lineTo(w, h / 2); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(w / 2, 0); ctx.lineTo(w / 2, h); ctx.stroke();
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillStyle = "#FFFFFF"; ctx.font = "700 " + Math.round(h / 14) + "px 'Segoe UI', Roboto, sans-serif";
  ctx.fillText("CENTER", w / 2, h / 2 - h / 8);
  ctx.textAlign = "left";
  ctx.fillText("L-EDGE", w / 40, h / 2 - h / 8);
  ctx.textAlign = "right";
  ctx.fillText("R-EDGE", w - w / 40, h / 2 - h / 8);
  ctx.textAlign = "center";
  ctx.fillStyle = "rgba(240, 78, 35,0.92)"; ctx.font = "800 " + Math.round(h / 18) + "px 'Segoe UI', Roboto, sans-serif";
  ctx.fillText("FRAMATOME VR · VR180 TEST", w / 2, h / 4);
}

function makeEquirectCanvas(w, h, stereo, drawFn) {
  const draw = drawFn || drawEquirect;
  const cv = document.createElement("canvas"); cv.width = w; cv.height = h;
  const ctx = cv.getContext("2d");
  if (stereo === "top_bottom") {
    const half = document.createElement("canvas"); half.width = w; half.height = h / 2;
    draw(half.getContext("2d"), w, h / 2);
    ctx.drawImage(half, 0, 0); ctx.drawImage(half, 0, h / 2);
  } else if (stereo === "left_right") {
    const half = document.createElement("canvas"); half.width = w / 2; half.height = h;
    draw(half.getContext("2d"), w / 2, h);
    ctx.drawImage(half, 0, 0); ctx.drawImage(half, w / 2, 0);
  } else {
    draw(ctx, w, h);
  }
  return cv;
}

function drawTestCard(ctx, w, h) {
  fillBackdrop(ctx, w, h);
  ctx.strokeStyle = "rgba(255,255,255,0.12)"; ctx.lineWidth = 1;
  for (let x = 0; x <= w + 1; x += w / 16) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
  for (let y = 0; y <= h + 1; y += h / 9) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
  ctx.strokeStyle = "#F04E23"; ctx.lineWidth = Math.max(3, w / 300); ctx.strokeRect(10, 10, w - 20, h - 20);
  ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillStyle = "#FFFFFF"; ctx.font = "800 " + Math.round(h / 9) + "px 'Segoe UI', Roboto, sans-serif";
  ctx.fillText("FRAMATOME VR", w / 2, h / 2 - h / 10);
  ctx.fillStyle = "#B8C7DC"; ctx.font = "600 " + Math.round(h / 20) + "px 'Segoe UI', Roboto, sans-serif";
  ctx.fillText(w + " \u00d7 " + h + " \u00b7 TEST MEDIA", w / 2, h / 2 + h / 12);
}

function makeTestCardCanvas(w, h) {
  const cv = document.createElement("canvas"); cv.width = w; cv.height = h;
  drawTestCard(cv.getContext("2d"), w, h);
  return cv;
}

function drawVideoFrame(ctx, w, h, t, dur, is360_) {
  if (is360_) {
    (config.projection === "vr180" ? drawVr180Grid : drawEquirect)(ctx, w, h);
    const x = ((t / dur) % 1) * w;
    ctx.strokeStyle = "#F04E23"; ctx.lineWidth = Math.max(3, w / 400);
    ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
  } else {
    drawTestCard(ctx, w, h);
    ctx.fillStyle = "rgba(255,255,255,0.18)"; ctx.fillRect(w * 0.1, h * 0.82, w * 0.8, h * 0.03);
    ctx.fillStyle = "#F04E23"; ctx.fillRect(w * 0.1, h * 0.82, w * 0.8 * ((t / dur) % 1), h * 0.03);
  }
  ctx.fillStyle = "#FFFFFF"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.font = "800 " + Math.round(h / 11) + "px 'Segoe UI', Roboto, sans-serif";
  ctx.fillText(formatTime(t) + " / " + formatTime(dur), w / 2, h * 0.66);
}

// A <canvas> that emulates the subset of HTMLVideoElement the viewer uses, so
// the full video transport (play/pause/scrub/time) can be demoed without a file.
function createDemoVideo(w, h, is360_) {
  const cv = document.createElement("canvas"); cv.width = w; cv.height = h;
  const ctx = cv.getContext("2d");
  const st = { paused: true, muted: true, duration: 60, last: 0, raf: 0, ct: 0 };
  cv.loop = true; cv.playsInline = true; cv.preload = "auto"; cv.crossOrigin = "anonymous"; cv.src = "demo";
  Object.defineProperty(cv, "paused", { get: () => st.paused });
  Object.defineProperty(cv, "duration", { get: () => st.duration });
  Object.defineProperty(cv, "videoWidth", { get: () => w });
  Object.defineProperty(cv, "videoHeight", { get: () => h });
  Object.defineProperty(cv, "readyState", { get: () => 4 });
  Object.defineProperty(cv, "muted", { get: () => st.muted, set: (v) => { st.muted = !!v; } });
  Object.defineProperty(cv, "currentTime", {
    get: () => st.ct,
    set: (v) => { st.ct = Math.max(0, Math.min(st.duration, v || 0)); draw(); cv.dispatchEvent(new Event("timeupdate")); },
  });
  function draw() { drawVideoFrame(ctx, w, h, st.ct, st.duration, is360_); }
  function step(now) {
    if (st.paused) return;
    const dt = st.last ? (now - st.last) / 1000 : 0; st.last = now;
    st.ct = (st.ct + dt) % st.duration;
    draw(); cv.dispatchEvent(new Event("timeupdate"));
    st.raf = requestAnimationFrame(step);
  }
  cv.play = function () {
    if (st.paused) { st.paused = false; st.last = 0; st.raf = requestAnimationFrame(step); cv.dispatchEvent(new Event("play")); }
    return Promise.resolve();
  };
  cv.pause = function () {
    if (!st.paused) { st.paused = true; cancelAnimationFrame(st.raf); cv.dispatchEvent(new Event("pause")); }
  };
  draw();
  setTimeout(() => cv.dispatchEvent(new Event("loadedmetadata")), 0);
  return cv;
}

async function loadDemoMedia() {
  if (config.gallery || config.playlistDemo) {
    mediaEl = makeTestCardCanvas(64, 40); // tiny placeholder so WebGL initialises
    mediaAspect = 1.6;
    mediaReady = true;
    return;
  }
  const w = is360 ? 2048 : 1600;
  const h = is360 ? 1024 : 900;
  if (isVideo) {
    mediaEl = createDemoVideo(w, h, is360);
    mediaEl.muted = true;
    mediaEl.play();
    if (config.failAfterMs) {
      // error_video scenario: exercise the branded mid-playback failure path.
      setTimeout(() => {
        showMediaError("This video can't be decoded on this headset (codec or profile).", "Playback error");
      }, config.failAfterMs);
    }
  } else {
    const draw360 = config.projection === "vr180" ? drawVr180Grid : drawEquirect;
    mediaEl = is360 ? makeEquirectCanvas(w, h, config.stereo, draw360) : makeTestCardCanvas(w, h);
  }
  mediaAspect = w / h;
  try { dom.preview.appendChild(mediaEl); } catch (e) { /* ignore */ }
  mediaReady = true;
}

/* ---- non-XR perspective camera ----------------------------------------- */

function perspective(fovy, aspect, near, far) {
  const f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
  const out = new Float32Array(16);
  out[0] = f / aspect; out[5] = f; out[10] = (far + near) * nf; out[11] = -1; out[14] = 2 * far * near * nf;
  return out;
}
function rotationY(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1]); }
function rotationX(a) { const c = Math.cos(a), s = Math.sin(a); return new Float32Array([1, 0, 0, 0, 0, c, s, 0, 0, -s, c, 0, 0, 0, 0, 1]); }

let previewActive = false;
let previewMode = "view";           // "view" | "gallery"
let dragging = false, dragMoved = 0, lastVel = 0;
let lastX = 0, lastY = 0, yaw = 0, pitch = 0;
let previewExitBtn = null, previewHintEl = null;

/* ---- coverflow carousel ------------------------------------------------ */

const CARD_W = 512, CARD_H = 320, CARD_ASPECT = CARD_W / CARD_H;
const GALLERY_VIEW = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
let gallery = [];
let galleryBuilt = false;
let focusF = 0;       // animated focus (float)
let focusTarget = 0;  // snap target (int)
let currentIndex = 0; // open item while in "view" mode
let lastFocusHud = -1;
let filmstripEl = null, fading = false;

function clampIdx(i) { return Math.max(0, Math.min(gallery.length - 1, i)); }
function translationM(x, y, z) { return new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1]); }
function scaleM(x, y, z) { return new Float32Array([x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1]); }

// Re-point the shared renderer at a different media item (mixed types).
function setActiveMedia(item) {
  config.type = item.type;
  config.title = item.title;
  config.stereo = item.stereo || "mono";
  config.projection = item.projection || config.projection;
  is360 = item.type === "360_image" || item.type === "360_video";
  isVideo = item.type === "2d_video" || item.type === "360_video";
  stereoMode = item.right ? "pair" : config.stereo;
}

function makeThumb(item, i) {
  const cv = document.createElement("canvas"); cv.width = CARD_W; cv.height = CARD_H;
  const c = cv.getContext("2d");
  const hue = (i * 47) % 360;
  const g = c.createLinearGradient(0, 0, 0, CARD_H);
  g.addColorStop(0, "hsl(" + hue + ",42%,30%)");
  g.addColorStop(1, "hsl(" + hue + ",48%,12%)");
  c.fillStyle = g; c.fillRect(0, 0, CARD_W, CARD_H);
  c.strokeStyle = "rgba(255,255,255,0.12)"; c.lineWidth = 1;
  if (item.is360) {
    for (let k = 1; k < 4; k++) { const yy = (k / 4) * CARD_H; c.beginPath(); c.moveTo(0, yy); c.lineTo(CARD_W, yy); c.stroke(); }
    c.strokeStyle = "rgba(240, 78, 35,0.6)"; c.beginPath(); c.moveTo(0, CARD_H / 2); c.lineTo(CARD_W, CARD_H / 2); c.stroke();
  } else {
    c.strokeRect(14, 14, CARD_W - 28, CARD_H - 28);
  }
  c.fillStyle = "rgba(255,255,255,0.14)"; c.textAlign = "center"; c.textBaseline = "middle";
  c.font = "800 150px 'Segoe UI', Roboto, sans-serif"; c.fillText(String(i + 1), CARD_W / 2, CARD_H / 2 - 18);
  c.textAlign = "left"; c.textBaseline = "alphabetic";
  c.fillStyle = "rgba(11, 23, 48,0.7)"; roundRect(c, 16, 16, item.is360 ? 78 : 58, 30, 8); c.fill();
  c.fillStyle = "#F04E23"; c.font = "700 16px 'Segoe UI', Roboto, sans-serif";
  c.fillText(item.is360 ? "360\u00b0" : "2D", 28, 37);
  if (item.isVideo) {
    c.fillStyle = "rgba(255,255,255,0.92)"; c.beginPath(); c.arc(CARD_W / 2, CARD_H / 2 - 18, 38, 0, Math.PI * 2); c.fill();
    c.fillStyle = "#0a1622"; c.beginPath();
    c.moveTo(CARD_W / 2 - 12, CARD_H / 2 - 38); c.lineTo(CARD_W / 2 - 12, CARD_H / 2 + 2); c.lineTo(CARD_W / 2 + 22, CARD_H / 2 - 18);
    c.closePath(); c.fill();
  }
  c.fillStyle = "rgba(11, 23, 48,0.86)"; c.fillRect(0, CARD_H - 60, CARD_W, 60);
  c.fillStyle = "#F04E23"; c.fillRect(0, CARD_H - 60, 6, 60);
  c.fillStyle = "#FFFFFF"; c.font = "600 26px 'Segoe UI', Roboto, sans-serif";
  c.fillText(truncateText(c, item.title, CARD_W - 44), 22, CARD_H - 24);
  return cv;
}

function buildDemoGallery() {
  if (galleryBuilt) return;
  galleryBuilt = true;
  const defs = [
    { type: "360_image", title: "Reactor Hall" },
    { type: "2d_image",  title: "Primary Schematic" },
    { type: "360_video", title: "Turbine Walkthrough" },
    { type: "2d_video",  title: "Safety Briefing" },
    { type: "360_image", title: "Control Room" },
    { type: "2d_image",  title: "Fuel Assembly" },
    { type: "360_video", title: "Cooling Loop Tour" },
    { type: "2d_image",  title: "Site Aerial" },
  ];
  gallery = defs.map((d, i) => {
    const item = {
      type: d.type, title: d.title, stereo: "mono", projection: "equirectangular",
      is360: d.type === "360_image" || d.type === "360_video",
      isVideo: d.type === "2d_video" || d.type === "360_video",
      tex: createTexture(),
    };
    item.thumbCanvas = makeThumb(item, i); // reused by the WebGL card and the DOM filmstrip
    uploadTexture(item.tex, item.thumbCanvas);
    return item;
  });
  focusF = 0; focusTarget = 0; lastFocusHud = -1;
  buildFilmstrip();
}

function cardModel(d) {
  const cd = Math.max(-3.2, Math.min(3.2, d));
  const x = cd * 1.15;
  const z = -3.0 - Math.abs(cd) * 0.55;
  const rot = -Math.sign(d) * Math.min(Math.abs(d), 1) * 0.85;
  const s = Math.max(0.5, 1.0 - Math.abs(d) * 0.16);
  const sx = s * 0.92, sy = (s * 0.92) / CARD_ASPECT;
  return multiply(translationM(x, -0.12, z), multiply(rotationY(rot), scaleM(sx, sy, 1)));
}

function drawCarousel(proj, view) {
  if (!gallery.length) return;
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
  gl.useProgram(program);
  gl.uniform4fv(uniforms.tint, WHITE_TINT);
  gl.uniform2fv(uniforms.uvScale, [1, 1]);
  gl.uniform2fv(uniforms.uvOffset, [0, 0]);
  // Painter's order: farthest from focus first so nearer cards layer on top.
  const order = gallery.map((_, i) => i).sort((a, b) => Math.abs(b - focusF) - Math.abs(a - focusF));
  for (const i of order) {
    const d = i - focusF;
    if (Math.abs(d) > 4.2) continue;
    const alpha = Math.max(0, Math.min(1, 1.35 - Math.abs(d) * 0.34));
    if (alpha <= 0.01) continue;
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, gallery[i].tex);
    gl.uniform1i(uniforms.tex, 0);
    gl.uniform1f(uniforms.alpha, alpha);
    gl.uniformMatrix4fv(uniforms.mvp, false, multiply(multiply(proj, view), cardModel(d)));
    bindGeometry(quad);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, quad.count);
  }
  gl.uniform1f(uniforms.alpha, 1.0);
  gl.disable(gl.BLEND);
}

function updateCarousel(dt) {
  if (dragging) {
    focusF = Math.max(-0.4, Math.min(gallery.length - 0.6, focusF));
    return;
  }
  focusTarget = clampIdx(focusTarget);
  focusF += (focusTarget - focusF) * Math.min(1, dt * 9);
  if (Math.abs(focusTarget - focusF) < 0.001) focusF = focusTarget;
}

function openItem(i) {
  if (!gallery.length) return;
  i = clampIdx(i);
  currentIndex = i;
  const item = gallery[i];
  if (mediaEl && typeof mediaEl.pause === "function") { try { mediaEl.pause(); } catch (e) { /* ignore */ } }
  setActiveMedia(item);
  const w = is360 ? 2048 : 1600, h = is360 ? 1024 : 900;
  if (isVideo) {
    mediaEl = createDemoVideo(w, h, is360);
    mediaEl.muted = false;
    mediaEl.play();
    wireVideoControls();
  } else {
    dom.controls.hidden = true;
    mediaEl = is360 ? makeEquirectCanvas(w, h, config.stereo) : makeTestCardCanvas(w, h);
    uploadTexture(leftTex, mediaEl);
  }
  mediaAspect = w / h;
  setBadge();
  dom.title.textContent = config.title;
  yaw = 0; pitch = 0;
  previewMode = "view";
  ensurePreviewChrome();
  renderHud(config.title, (i + 1) + " / " + gallery.length);
  showHud(3200);
  if (gallery.length) { showFilmstrip(); updateFilmstripActive(); }
}

function backToGallery() {
  if (mediaEl && typeof mediaEl.pause === "function") { try { mediaEl.pause(); } catch (e) { /* ignore */ } }
  dom.controls.hidden = true;
  hideFilmstrip();
  focusTarget = currentIndex;
  lastFocusHud = -1;
  previewMode = "gallery";
  ensurePreviewChrome();
}

/* In-view filmstrip — hop between items without leaving the open media. The
 * full carousel (exit / back) remains the "big view". */
function buildFilmstrip() {
  if (filmstripEl) return;
  filmstripEl = document.createElement("div");
  filmstripEl.className = "filmstrip";
  filmstripEl.hidden = true;
  gallery.forEach((item, i) => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "strip-item";
    b.setAttribute("aria-label", "Open " + item.title);
    if (item.thumbCanvas) b.appendChild(item.thumbCanvas);
    b.addEventListener("click", () => switchTo(i));
    item.stripBtn = b;
    filmstripEl.appendChild(b);
  });
  document.body.appendChild(filmstripEl);
}

function showFilmstrip() {
  if (!filmstripEl) return;
  filmstripEl.hidden = false;
  dom.body.classList.add("strip");
}
function hideFilmstrip() {
  if (filmstripEl) filmstripEl.hidden = true;
  dom.body.classList.remove("strip");
}
function updateFilmstripActive() {
  if (!filmstripEl) return;
  gallery.forEach((item, i) => { if (item.stripBtn) item.stripBtn.classList.toggle("active", i === currentIndex); });
  const b = gallery[currentIndex] && gallery[currentIndex].stripBtn;
  if (b && b.scrollIntoView) b.scrollIntoView({ inline: "center", block: "nearest", behavior: "smooth" });
}

// Brief opacity dip on the GL canvas so item switches feel like a cross-fade.
function crossfade(swap) {
  if (fading) { swap(); return; }
  fading = true;
  dom.canvas.style.transition = "opacity 0.16s ease";
  dom.canvas.style.opacity = "0.12";
  setTimeout(() => {
    swap();
    dom.canvas.style.opacity = "1";
    setTimeout(() => { fading = false; dom.canvas.style.transition = ""; }, 200);
  }, 160);
}

function switchTo(i) {
  if (i === currentIndex || !gallery.length) return;
  crossfade(() => openItem(i));
}

function sizePreviewCanvas() {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = Math.max(1, Math.floor(window.innerWidth * dpr));
  const h = Math.max(1, Math.floor(window.innerHeight * dpr));
  if (dom.canvas.width !== w || dom.canvas.height !== h) { dom.canvas.width = w; dom.canvas.height = h; }
}

function previewFrame(t) {
  if (!previewActive) return;
  requestAnimationFrame(previewFrame);
  sizePreviewCanvas();
  const dt = lastFrameTime ? Math.min(0.05, (t - lastFrameTime) / 1000) : 0;
  lastFrameTime = t;
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  gl.viewport(0, 0, dom.canvas.width, dom.canvas.height);
  gl.disable(gl.DEPTH_TEST);
  gl.disable(gl.CULL_FACE);
  gl.clearColor(0.03, 0.07, 0.11, 1);
  gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  const proj = perspective((70 * Math.PI) / 180, dom.canvas.width / dom.canvas.height, 0.1, 100);
  if (previewMode === "gallery") {
    updateCarousel(dt);
    drawCarousel(proj, GALLERY_VIEW);
    const fi = clampIdx(Math.round(focusF));
    if (fi !== lastFocusHud && gallery[fi]) {
      lastFocusHud = fi;
      renderHud(gallery[fi].title, "Click center / press Enter to open");
    }
    drawHud(proj, GALLERY_VIEW, 1);
  } else {
    refreshVideoTexture();
    updateSceneTint(dt);
    const view = multiply(rotationX(-pitch), rotationY(-yaw));
    drawScene(proj, view, "left");
    const alpha = hudAlpha(t);
    if (alpha > 0) drawHud(proj, view, alpha);
    const pAlpha = panelAlpha(t);
    if (pAlpha > 0) {
      maybeRenderPanel(t);
      drawPanel(proj, view, pAlpha);
      drawLaser(proj, view);
    }
  }
}

function isUiTarget(e) { return e.target && e.target.closest && e.target.closest("button,input,a"); }

// Mouse → world ray through the preview camera, feeding the SAME
// panelHitFromRay/dispatch path the headset uses.
function previewRay(clientX, clientY) {
  const rect = dom.canvas.getBoundingClientRect();
  const ndcX = ((clientX - rect.left) / Math.max(1, rect.width)) * 2 - 1;
  const ndcY = -(((clientY - rect.top) / Math.max(1, rect.height)) * 2 - 1);
  const fov = (70 * Math.PI) / 180;
  const aspect = dom.canvas.width / Math.max(1, dom.canvas.height);
  const tanHalf = Math.tan(fov / 2);
  let dx = ndcX * tanHalf * aspect;
  let dy = ndcY * tanHalf;
  let dz = -1;
  // Camera rotation = rotY(yaw)·rotX(pitch) (inverse of the view matrix).
  const cy2 = Math.cos(pitch), sy2 = Math.sin(pitch);
  const ry = cy2 * dy - sy2 * dz;
  const rz = sy2 * dy + cy2 * dz;
  dy = ry; dz = rz;
  const cx2 = Math.cos(yaw), sx2 = Math.sin(yaw);
  const rx = cx2 * dx + sx2 * dz;
  const rz2 = -sx2 * dx + cx2 * dz;
  const len = Math.sqrt(rx * rx + dy * dy + rz2 * rz2) || 1;
  return [rx / len, dy / len, rz2 / len];
}

let panelPointerActive = false;

function updatePreviewPointer(e) {
  if (previewMode !== "view") {
    pointer.active = false;
    setPointerHit(null);
    return;
  }
  const dir = previewRay(e.clientX, e.clientY);
  pointer.origin[0] = 0; pointer.origin[1] = 0; pointer.origin[2] = 0;
  pointer.dir[0] = dir[0]; pointer.dir[1] = dir[1]; pointer.dir[2] = dir[2];
  pointer.active = true;
  const now = performance.now();
  setPointerHit(panelAlpha(now) > 0 ? panelHitFromRay(pointer.origin, pointer.dir) : null, now);
}

function onPreviewPointerDown(e) {
  if (!previewActive || isUiTarget(e)) return;
  updatePreviewPointer(e);
  if (previewMode === "view" && pointer.hit && pointer.hit.region) {
    panelPointerActive = true;
    onSelectStart({});
    return; // panel interaction, not a look-drag
  }
  dragging = true; dragMoved = 0; lastVel = 0;
  lastX = e.clientX; lastY = e.clientY;
  dom.body.classList.add("grabbing");
}
function onPreviewPointerMove(e) {
  if (previewActive && !dragging) updatePreviewPointer(e);
  if (panelPointerActive) { updatePreviewPointer(e); return; }
  if (!dragging) return;
  const dx = e.clientX - lastX, dy = e.clientY - lastY;
  lastX = e.clientX; lastY = e.clientY;
  dragMoved += Math.abs(dx) + Math.abs(dy);
  if (previewMode === "gallery") {
    focusF -= dx * 0.012;
    lastVel = dx;
  } else {
    yaw += dx * 0.005;
    pitch = Math.max(-1.4, Math.min(1.4, pitch + dy * 0.005));
  }
}
function onPreviewPointerUp(e) {
  if (panelPointerActive) {
    panelPointerActive = false;
    updatePreviewPointer(e);
    onSelectEnd();
    return;
  }
  if (!dragging) return;
  dragging = false;
  dom.body.classList.remove("grabbing");
  if (previewMode !== "gallery") {
    // Quick click in view mode summons the panel, exactly like trigger in VR.
    if (dragMoved < 6) showPanel(performance.now(), panelAlpha(performance.now()) <= 0);
    return;
  }
  if (dragMoved < 6) {
    const third = e.clientX < window.innerWidth * 0.33 ? -1 : (e.clientX > window.innerWidth * 0.66 ? 1 : 0);
    if (third === 0) openItem(Math.round(focusF));
    else focusTarget = clampIdx(Math.round(focusF) + third);
  } else {
    focusTarget = clampIdx(Math.round(focusF - lastVel * 0.05));
  }
}
function onPreviewWheel(e) {
  if (!previewActive || previewMode !== "gallery") return;
  focusTarget = clampIdx(focusTarget + (e.deltaY > 0 ? 1 : -1));
}
function onPreviewKey(e) {
  if (!previewActive) return;
  if (previewMode === "gallery") {
    if (e.key === "ArrowLeft") focusTarget = clampIdx(focusTarget - 1);
    else if (e.key === "ArrowRight") focusTarget = clampIdx(focusTarget + 1);
    else if (e.code === "Space" || e.key === "Enter") { e.preventDefault(); openItem(Math.round(focusF)); }
    else if (e.key === "Escape") stopPreview();
    return;
  }
  if (!gallery.length && playlistItems.length > 1 && e.key === "ArrowRight") loadItem(playlistIndex + 1);
  else if (!gallery.length && playlistItems.length > 1 && e.key === "ArrowLeft") loadItem(playlistIndex - 1);
  else if (e.key === "ArrowRight" && gallery.length) openItem((currentIndex + 1) % gallery.length);
  else if (e.key === "ArrowLeft" && gallery.length) openItem((currentIndex - 1 + gallery.length) % gallery.length);
  else if (e.code === "Space") { e.preventDefault(); togglePlayback(); }
  else if (e.key === "p" || e.key === "P") {
    // Squeeze-equivalent: toggle (and recenter) the control panel.
    const now = performance.now();
    if (panelAlpha(now) > 0 && panelMode === "media") hidePanel();
    else showPanel(now, true);
  }
  else if (e.key === "r" || e.key === "R") { yaw = 0; pitch = 0; }
  else if (e.key === "g" || e.key === "G" || e.key === "Escape") { if (gallery.length) backToGallery(); else stopPreview(); }
}

function onExitButton() {
  if (previewMode === "view" && gallery.length) backToGallery();
  else stopPreview();
}

function ensurePreviewChrome() {
  if (!previewExitBtn) {
    previewExitBtn = document.createElement("button");
    previewExitBtn.className = "preview-exit btn-ghost";
    previewExitBtn.type = "button";
    previewExitBtn.addEventListener("click", onExitButton);
    document.body.appendChild(previewExitBtn);
    previewHintEl = document.createElement("div");
    previewHintEl.className = "preview-hint";
    document.body.appendChild(previewHintEl);
  }
  if (previewMode === "gallery") {
    previewExitBtn.textContent = "Exit";
    previewHintEl.textContent = "Drag to spin  \u00b7  Click center to open  \u00b7  \u2190/\u2192 browse  \u00b7  Esc: exit";
  } else {
    previewExitBtn.textContent = gallery.length ? "\u2190 Gallery" : "Exit preview";
    const lookHint = is360 ? "Drag to look  \u00b7  " : "Drag to orbit  \u00b7  ";
    const playHint = isVideo ? "Space: Play/Pause  \u00b7  " : "";
    const navHint = gallery.length ? "\u2190/\u2192 next/prev  \u00b7  G: gallery" : "Esc: exit";
    previewHintEl.textContent = lookHint + playHint + navHint;
  }
  previewExitBtn.hidden = false;
  previewHintEl.hidden = false;
}

function startPreview() {
  if (previewActive || !gl) return;
  previewActive = true;
  dom.body.classList.add("preview");
  if (config.gallery) {
    buildDemoGallery();
    previewMode = "gallery";
    const openAt = parseInt(params.get("item"), 10);
    if (!Number.isNaN(openAt)) openItem(openAt);
  } else {
    previewMode = "view";
    if (isVideo && mediaEl) { try { mediaEl.play(); } catch (e) { /* ignore */ } }
    renderHud(config.title, is360 ? "Drag to look around" : "3D preview");
    showHud(4200);
  }
  ensurePreviewChrome();
  window.addEventListener("pointerdown", onPreviewPointerDown);
  window.addEventListener("pointermove", onPreviewPointerMove);
  window.addEventListener("pointerup", onPreviewPointerUp);
  window.addEventListener("keydown", onPreviewKey);
  window.addEventListener("wheel", onPreviewWheel, { passive: true });
  lastFrameTime = 0;
  requestAnimationFrame(previewFrame);
}

function stopPreview() {
  previewActive = false;
  dom.body.classList.remove("preview", "grabbing");
  hideFilmstrip();
  dom.canvas.style.opacity = "";
  if (previewExitBtn) previewExitBtn.hidden = true;
  if (previewHintEl) previewHintEl.hidden = true;
  window.removeEventListener("pointerdown", onPreviewPointerDown);
  window.removeEventListener("pointermove", onPreviewPointerMove);
  window.removeEventListener("pointerup", onPreviewPointerUp);
  window.removeEventListener("keydown", onPreviewKey);
  window.removeEventListener("wheel", onPreviewWheel);
}

main();
