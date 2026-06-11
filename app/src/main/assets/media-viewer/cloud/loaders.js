// Point-cloud loaders for the Framatome cloud viewer: PLY and PCD via three's
// addon loaders. Deliberately self-contained (no GLTF/Draco/KTX2 decoders) so
// the cloud viewer stays lean — a laser scan is just positions + optional
// vertex colors rendered as a single THREE.Points draw.
//
// A converted/decimated scan loads whole into one BufferGeometry. That covers
// the practical VR-review case (a cropped region up to a few million points);
// out-of-core octree streaming for raw multi-hundred-million-point datasets is
// a documented future enhancement (see SCAN_PIPELINE.md).

import * as THREE from "three";
import { PLYLoader } from "three/addons/loaders/PLYLoader.js";
import { PCDLoader } from "three/addons/loaders/PCDLoader.js";

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

// Uncolored scans (geometry-only PLY) render in a soft Framatome steel-blue so
// they still read against the dome; colored scans use their per-point RGB.
const DEFAULT_POINT_COLOR = 0xB8C7DC;
export const DEFAULT_POINT_SIZE = 2.4; // screen px (sizeAttenuation off)

export function createCloudLoaderRig() {
  return { ply: new PLYLoader(), pcd: new PCDLoader() };
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
  return { root: points, animations: [], fileName, pointCount: pos ? pos.count : 0 };
}

/**
 * Loads a point cloud by storage-relative path. Resolves to
 * `{ root: THREE.Points, animations: [], fileName, pointCount }` — the same
 * shape model/loaders.js returns, so main.js orchestration is shared.
 */
export function loadPointCloud(rig, src, onProgress) {
  const ext = extensionOf(src);
  const url = mediaUrl(src);
  const fileName = sourceFileName(src);

  if (ext === "ply") {
    return new Promise((resolve, reject) => {
      rig.ply.load(
        url,
        (geometry) => {
          const hasColor = !!geometry.getAttribute("color");
          if (!geometry.getAttribute("position")) {
            reject(decorate(new Error("This PLY has no point positions."), fileName));
            return;
          }
          const points = new THREE.Points(geometry, makePointsMaterial(hasColor));
          resolve(finishPoints(points, fileName));
        },
        (event) => { if (onProgress) onProgress(event.loaded || 0, event.total || 0); },
        (error) => reject(describeLoadError(error, fileName))
      );
    });
  }

  if (ext === "pcd") {
    return new Promise((resolve, reject) => {
      rig.pcd.load(
        url,
        (points) => {
          // PCDLoader returns a THREE.Points with its own material; normalise
          // it to our screen-space sizing while keeping any per-point colors.
          const hasColor = !!(points.geometry && points.geometry.getAttribute("color"));
          if (points.material) points.material.dispose();
          points.material = makePointsMaterial(hasColor);
          resolve(finishPoints(points, fileName));
        },
        (event) => { if (onProgress) onProgress(event.loaded || 0, event.total || 0); },
        (error) => reject(describeLoadError(error, fileName))
      );
    });
  }

  const err = new Error(
    ext === "e57" || ext === "las" || ext === "laz"
      ? "Raw scan formats (E57/LAS/LAZ) aren't read on the headset — convert to PLY or PCD (see SCAN_PIPELINE.md)."
      : "This point-cloud format isn't supported — deliver PLY or PCD (see SCAN_PIPELINE.md)."
  );
  err.fileName = fileName;
  return Promise.reject(err);
}

function decorate(err, fileName) { err.fileName = fileName; return err; }

function describeLoadError(error, fileName) {
  const raw = error && (error.message || (error.target && "network error"));
  const message = /404|not found/i.test(String(raw))
    ? "The point-cloud file could not be found on the headset."
    : "The point cloud could not be parsed (" + (raw || "unknown error") + ").";
  return decorate(new Error(message), fileName);
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

/** Set the screen-space point size on a loaded cloud; returns the clamped px. */
export function setPointSize(root, px) {
  const size = Math.max(1, Math.min(8, px));
  if (root) {
    root.traverse((node) => {
      if (node.isPoints && node.material) node.material.size = size;
    });
  }
  return size;
}
