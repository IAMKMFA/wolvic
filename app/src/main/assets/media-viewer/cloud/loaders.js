// Point-cloud loaders for the Framatome cloud viewer: PLY, PCD, and
// Draco-compressed (.drc) scans.
//
// Threading: parsing a multi-million-point file on the main thread freezes the
// XR render loop for seconds, so PLY/PCD are fetched here (with progress) and
// parsed in cloud/parse-worker.js; only finished typed arrays cross back as
// transferables. Rare shapes the worker doesn't handle (PLY list properties,
// LZF-compressed PCD) fall back to the vendored three.js loaders — correct but
// blocking. Draco decodes inside DRACOLoader's own worker pool.
//
// Robustness: organized PCDs (and some exports) encode invalid points as NaN,
// and a single NaN poisons THREE's bounding box — the entire cloud silently
// renders nothing. Every load path therefore filters non-finite points (the
// worker inline, the fallbacks via sanitizeGeometry) and reports total vs kept.
//
// A converted/decimated scan loads whole into one BufferGeometry — ideal for a
// cropped review region (see SCAN_PIPELINE.md budgets). Out-of-core octree
// streaming for raw datasets is a documented future enhancement.

import * as THREE from "three";
import { PLYLoader } from "three/addons/loaders/PLYLoader.js";
import { PCDLoader } from "three/addons/loaders/PCDLoader.js";
import { DRACOLoader } from "three/addons/loaders/DRACOLoader.js";

const MEDIA_ROUTE = "/__media__/";

/** Storage-relative path -> /__media__/ URL; absolute http(s)/rooted passes
 *  through (desktop testing). Mirrors model/loaders.js. */
export function mediaUrl(relPath) {
  if (/^https?:\/\//i.test(relPath) || relPath.startsWith("/")) return relPath;
  return MEDIA_ROUTE + relPath.split("/").map(encodeURIComponent).join("/");
}

export function sourceFileName(src) {
  return String(src || "").split("/").pop() || "";
}

export function extensionOf(src) {
  const name = sourceFileName(src).toLowerCase();
  const dot = name.lastIndexOf(".");
  return dot >= 0 ? name.slice(dot + 1) : "";
}

// Uncolored scans (geometry-only) render in a soft Framatome steel-blue so
// they still read against the dome; colored scans use their per-point RGB.
const DEFAULT_POINT_COLOR = 0xB8C7DC;
export const DEFAULT_POINT_SIZE = 2.4; // logical px (sizeAttenuation off)

export function createCloudLoaderRig() {
  const drc = new DRACOLoader();
  drc.setDecoderPath("./vendor/draco/");
  drc.setDecoderConfig({ type: "wasm" });
  // Draco stores point colors as quantized uint8; asking the decoder for
  // Float32 readback of that attribute yields garbage-scale values. Read it in
  // its native type — pointsFromGeometry marks it normalized for the shader.
  drc.defaultAttributeTypes = {
    position: "Float32Array", normal: "Float32Array", color: "Uint8Array", uv: "Float32Array",
  };
  return { ply: new PLYLoader(), pcd: new PCDLoader(), drc };
}

// Screen-space constant point size (sizeAttenuation: false) keeps points
// visible and predictable regardless of how far the cloud is dollied or how
// hard it is scaled on the pedestal — the comfortable default for VR review.
function makePointsMaterial(hasColor) {
  return new THREE.PointsMaterial({
    size: DEFAULT_POINT_SIZE,
    sizeAttenuation: false,
    vertexColors: hasColor,
    color: hasColor ? 0xffffff : DEFAULT_POINT_COLOR,
  });
}

function finishPoints(points, fileName) {
  points.name = fileName || "point-cloud";
  // The cloud is always the focal object; per-object frustum culling on a
  // single re-centred/re-scaled Points node is fragile (stale boundingSphere vs
  // matrixWorld) and offers nothing here — keep it always drawn.
  points.frustumCulled = false;
  if (points.geometry) {
    points.geometry.computeBoundingBox();
    points.geometry.computeBoundingSphere();
  }
  const pos = points.geometry && points.geometry.getAttribute("position");
  return { root: points, animations: [], fileName, pointCount: pos ? pos.count : 0, droppedCount: 0 };
}

function decorate(err, fileName) { err.fileName = fileName; return err; }

function describeLoadError(error, fileName) {
  const raw = error && (error.message || (error.target && "network error"));
  const message = /404|not found/i.test(String(raw))
    ? "The point-cloud file could not be found on the headset."
    : "The point cloud could not be parsed (" + (raw || "unknown error") + ").";
  return decorate(new Error(message), fileName);
}

function noValidPointsError(fileName) {
  return decorate(new Error(
    "This cloud contains no valid points (all positions are NaN/invalid) — re-export it (see SCAN_PIPELINE.md)."
  ), fileName);
}

/* --------------------------------------------------------- NaN filtering -- */

// Compacts every attribute of a geometry to only the points whose positions
// are finite. Returns { geometry, total, kept } (geometry unchanged when all
// points are valid). One linear pass — ~10ms per million points.
export function sanitizeGeometry(geometry) {
  const pos = geometry.getAttribute("position");
  if (!pos) throw new Error("the cloud has no point positions");
  const a = pos.array, n = pos.count;
  let kept = 0;
  for (let i = 0; i < n; i++) {
    const j = i * 3;
    if (Number.isFinite(a[j]) && Number.isFinite(a[j + 1]) && Number.isFinite(a[j + 2])) kept++;
  }
  if (kept === n) return { geometry, total: n, kept };
  const compacted = new THREE.BufferGeometry();
  for (const name of Object.keys(geometry.attributes)) {
    const attr = geometry.attributes[name];
    const item = attr.itemSize;
    const src = attr.array;
    const dst = new src.constructor(kept * item);
    let w = 0;
    for (let i = 0; i < n; i++) {
      const j = i * 3;
      if (Number.isFinite(a[j]) && Number.isFinite(a[j + 1]) && Number.isFinite(a[j + 2])) {
        for (let k = 0; k < item; k++) dst[w * item + k] = src[i * item + k];
        w++;
      }
    }
    compacted.setAttribute(name, new THREE.BufferAttribute(dst, item, attr.normalized));
  }
  geometry.dispose();
  return { geometry: compacted, total: n, kept };
}

/* ----------------------------------------------------- fetch + worker IO -- */

// XHR (not fetch) so byte-level progress drives the loading bar.
function fetchArrayBuffer(url, onProgress, fileName) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("GET", url);
    xhr.responseType = "arraybuffer";
    xhr.onprogress = (e) => { if (onProgress) onProgress(e.loaded || 0, e.total || 0); };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300 && xhr.response) resolve(xhr.response);
      else reject(describeLoadError(new Error(xhr.status === 404 ? "404" : "HTTP " + xhr.status), fileName));
    };
    xhr.onerror = () => reject(describeLoadError(new Error("network error"), fileName));
    xhr.send();
  });
}

let _parseWorker = null;
let _workerSeq = 0;

// Sends the buffer to the parse worker (zero-copy transfer). Resolves with the
// worker's message; the buffer comes BACK (transferred again) on failure so the
// caller can run the synchronous fallback without refetching. A dead/missing
// worker resolves as unsupported with the original buffer (never sent).
function parseInWorker(ext, buffer) {
  return new Promise((resolve) => {
    if (!_parseWorker) {
      try { _parseWorker = new Worker("cloud/parse-worker.js"); }
      catch (e) { resolve({ ok: false, unsupported: "worker unavailable", buffer }); return; }
    }
    const worker = _parseWorker;
    const id = ++_workerSeq;
    const cleanup = () => {
      worker.removeEventListener("message", onMessage);
      worker.removeEventListener("error", onError);
    };
    const onMessage = (ev) => {
      if (!ev.data || ev.data.id !== id) return;
      cleanup();
      resolve(ev.data);
    };
    const onError = () => {
      cleanup();
      try { worker.terminate(); } catch (e) { /* ignore */ }
      _parseWorker = null;
      // Buffer was transferred to the dead worker — no fallback data.
      resolve({ ok: false, error: "parse worker crashed" });
    };
    worker.addEventListener("message", onMessage);
    worker.addEventListener("error", onError);
    worker.postMessage({ id, ext, buffer }, [buffer]);
  });
}

/* --------------------------------------------------------------- loading -- */

function pointsFromArrays(positions, colors, fileName) {
  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  if (colors) geometry.setAttribute("color", new THREE.BufferAttribute(colors, 3));
  return new THREE.Points(geometry, makePointsMaterial(!!colors));
}

function pointsFromGeometry(geometry, fileName) {
  const s = sanitizeGeometry(geometry);
  // Integer color attributes (Draco uint8) must be flagged normalized so the
  // shader maps 0..255 -> 0..1 instead of clamping everything to white.
  const color = s.geometry.getAttribute("color");
  if (color && !color.normalized && !(color.array instanceof Float32Array)) {
    s.geometry.setAttribute("color", new THREE.BufferAttribute(color.array, color.itemSize, true));
  }
  const hasColor = !!s.geometry.getAttribute("color");
  const points = new THREE.Points(s.geometry, makePointsMaterial(hasColor));
  const out = finishPoints(points, fileName);
  out.droppedCount = s.total - s.kept;
  return out;
}

// Fallback: the vendored three.js loaders parse the (returned) buffer
// synchronously on the main thread — correct for every format quirk, blocking,
// only reached for rare shapes the worker declines.
function parseWithThreeLoaders(rig, ext, buffer, fileName) {
  if (ext === "ply") {
    const geometry = rig.ply.parse(buffer);
    const out = pointsFromGeometry(geometry, fileName);
    if (!out.pointCount) throw noValidPointsError(fileName);
    return out;
  }
  // PCDLoader.parse returns a THREE.Points with its own material; normalise it
  // to our screen-space sizing while keeping per-point colors, then sanitize.
  const parsed = rig.pcd.parse(buffer);
  const geometry = parsed.geometry;
  if (parsed.material) parsed.material.dispose();
  const out = pointsFromGeometry(geometry, fileName);
  if (!out.pointCount) throw noValidPointsError(fileName);
  return out;
}

/**
 * Loads a point cloud by storage-relative path. Resolves to
 * `{ root: THREE.Points, animations: [], fileName, pointCount, droppedCount }`
 * — the same shape model/loaders.js returns, so main.js orchestration is
 * shared. onProgress receives (loadedBytes, totalBytes|0) during transfer.
 */
export async function loadPointCloud(rig, src, onProgress) {
  const ext = extensionOf(src);
  const url = mediaUrl(src);
  const fileName = sourceFileName(src);

  if (ext === "ply" || ext === "pcd") {
    const buffer = await fetchArrayBuffer(url, onProgress, fileName);
    const res = await parseInWorker(ext, buffer);
    if (res.ok) {
      if (!res.kept) throw noValidPointsError(fileName);
      const points = pointsFromArrays(res.positions, res.colors, fileName);
      const out = finishPoints(points, fileName);
      out.droppedCount = res.total - res.kept;
      if (out.droppedCount > 0) {
        console.warn("[cloud] dropped " + out.droppedCount + " invalid (NaN) points of " + res.total);
      }
      return out;
    }
    if (res.buffer) {
      console.warn("[cloud] worker declined (" + (res.unsupported || res.error) + ") — parsing on main thread");
      try {
        return parseWithThreeLoaders(rig, ext, res.buffer, fileName);
      } catch (e) {
        throw e.fileName ? e : describeLoadError(e, fileName);
      }
    }
    throw describeLoadError(new Error(res.error || "parse failed"), fileName);
  }

  if (ext === "drc") {
    // Draco point clouds (5-10x smaller than PLY; see SCAN_PIPELINE.md).
    // DRACOLoader decodes in its own worker pool — already off-thread.
    return new Promise((resolve, reject) => {
      rig.drc.load(
        url,
        (geometry) => {
          try {
            const out = pointsFromGeometry(geometry, fileName);
            if (!out.pointCount) { reject(noValidPointsError(fileName)); return; }
            resolve(out);
          } catch (e) {
            reject(e.fileName ? e : describeLoadError(e, fileName));
          }
        },
        (event) => { if (onProgress) onProgress(event.loaded || 0, event.total || 0); },
        (error) => reject(describeLoadError(error, fileName))
      );
    });
  }

  const err = new Error(
    ext === "e57" || ext === "las" || ext === "laz"
      ? "Raw scan formats (E57/LAS/LAZ) aren't read on the headset — convert to PLY, PCD, or Draco (see SCAN_PIPELINE.md)."
      : "This point-cloud format isn't supported — deliver PLY, PCD, or Draco (.drc) (see SCAN_PIPELINE.md)."
  );
  err.fileName = fileName;
  return Promise.reject(err);
}

/** Frees the cloud's GPU buffers before the next one allocates (Quest memory). */
export function disposePointCloud(root) {
  if (!root) return;
  root.traverse((node) => {
    if (node.geometry) node.geometry.dispose();
    const materials = Array.isArray(node.material) ? node.material : node.material ? [node.material] : [];
    for (const material of materials) { if (material) material.dispose(); }
  });
  if (root.parent) root.parent.remove(root);
}

/**
 * Sets the logical point size (clamped 1..8 px) scaled by `scale` — the XR
 * density correction (the Quest eye buffer is denser than the dpr-1 page, so
 * unscaled sizes render visibly thinner in-headset than on desktop). Returns
 * the clamped LOGICAL px for the panel display.
 */
export function setPointSize(root, px, scale) {
  const logical = Math.max(1, Math.min(8, px));
  const effective = logical * (scale || 1);
  if (root) {
    root.traverse((node) => {
      if (node.isPoints && node.material) node.material.size = effective;
    });
  }
  return logical;
}
