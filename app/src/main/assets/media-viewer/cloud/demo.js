// Procedural point clouds for the Framatome cloud viewer — shaped exactly like
// loaders.js output ({ root: THREE.Points, animations: [], fileName,
// pointCount }) so the viewer can be exercised on the desktop with no scan
// files. Mimics a laser-scan aesthetic: equipment surfaces sampled as points,
// coloured by height the way Cyclone/FARO elevation maps read.

import * as THREE from "three";
import { DEFAULT_POINT_SIZE } from "./loaders.js";

// Deterministic PRNG so the demo cloud is identical every run (no Math.random,
// which also keeps it reproducible under the workflow harness).
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// 5-stop elevation ramp (blue → cyan → green → amber → red).
const RAMP = [
  [0.10, 0.22, 0.62], [0.10, 0.70, 0.80], [0.22, 0.78, 0.32],
  [0.93, 0.78, 0.22], [0.90, 0.28, 0.20],
];
function elevationColor(t, out) {
  t = Math.max(0, Math.min(1, t));
  const seg = Math.min(RAMP.length - 2, Math.floor(t * (RAMP.length - 1)));
  const f = t * (RAMP.length - 1) - seg;
  const a = RAMP[seg], b = RAMP[seg + 1];
  out[0] = a[0] + (b[0] - a[0]) * f;
  out[1] = a[1] + (b[1] - a[1]) * f;
  out[2] = a[2] + (b[2] - a[2]) * f;
}

// Builds a "reactor bay" scan: floor, a central vessel, a pipe run and two back
// walls — `count` points total, coloured by height with scan-like jitter.
function buildScanGeometry(count, seed) {
  const rnd = mulberry32(seed || 0x5eed);
  const positions = new Float32Array(count * 3);
  const colors = new Float32Array(count * 3);
  const yMin = 0, yMax = 3.0;
  const rgb = [0, 0, 0];
  let i = 0;

  function put(x, y, z) {
    const j = i * 3;
    // A little positional jitter reads as scan noise.
    positions[j] = x + (rnd() - 0.5) * 0.012;
    positions[j + 1] = y + (rnd() - 0.5) * 0.012;
    positions[j + 2] = z + (rnd() - 0.5) * 0.012;
    elevationColor((positions[j + 1] - yMin) / (yMax - yMin), rgb);
    colors[j] = rgb[0]; colors[j + 1] = rgb[1]; colors[j + 2] = rgb[2];
    i++;
  }

  const floor = Math.floor(count * 0.30);
  const vessel = Math.floor(count * 0.34);
  const pipe = Math.floor(count * 0.16);
  const walls = count - floor - vessel - pipe;

  // Floor: 7×7 m slab centred at origin.
  for (let n = 0; n < floor; n++) put((rnd() - 0.5) * 7, 0, (rnd() - 0.5) * 7);

  // Vessel: vertical cylinder shell (r≈1.0, h≈2.6) sitting on the floor.
  for (let n = 0; n < vessel; n++) {
    const a = rnd() * Math.PI * 2;
    put(Math.cos(a) * 1.0, 0.1 + rnd() * 2.6, Math.sin(a) * 1.0);
  }

  // Pipe: horizontal run (along +X) at mid height, r≈0.22.
  for (let n = 0; n < pipe; n++) {
    const a = rnd() * Math.PI * 2;
    put(-3 + rnd() * 6, 1.4 + Math.sin(a) * 0.22, 1.6 + Math.cos(a) * 0.22);
  }

  // Two back walls (planes) forming an L behind the vessel.
  for (let n = 0; n < walls; n++) {
    if (n % 2 === 0) put((rnd() - 0.5) * 7, rnd() * yMax, -3.4);
    else put(-3.4, rnd() * yMax, (rnd() - 0.5) * 7);
  }

  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute("position", new THREE.BufferAttribute(positions, 3));
  geometry.setAttribute("color", new THREE.BufferAttribute(colors, 3));
  geometry.computeBoundingBox();
  geometry.computeBoundingSphere();
  return geometry;
}

function pointsFrom(geometry, fileName) {
  const material = new THREE.PointsMaterial({
    size: DEFAULT_POINT_SIZE, sizeAttenuation: false, vertexColors: true,
  });
  const points = new THREE.Points(geometry, material);
  points.name = fileName;
  points.frustumCulled = false; // always-drawn focal object (see loaders.js)
  return {
    root: points, animations: [], fileName,
    pointCount: geometry.getAttribute("position").count,
  };
}

export function buildDemoCloud() {
  return pointsFrom(buildScanGeometry(260000, 0x5eed), "demo-scan.ply");
}

export function buildHeavyDemoCloud() {
  return pointsFrom(buildScanGeometry(1300000, 0xb16d47a), "demo-scan-heavy.ply");
}

export function loadDemoCloud(src) {
  const kind = String(src || "").replace(/^demo:/, "");
  if (kind === "heavy") return Promise.resolve(buildHeavyDemoCloud());
  if (kind === "error") {
    const err = new Error("This point cloud can't be decoded on this headset (demo failure).");
    err.fileName = "demo-broken.ply";
    return Promise.reject(err);
  }
  return Promise.resolve(buildDemoCloud());
}

export function demoCloudPlaylistDoc() {
  return {
    version: 1,
    id: "demo-clouds",
    index: 0,
    items: [
      { id: "scan-a", type: "point_cloud", src: "demo:scan", title: "Reactor Bay — Scan A", badge: "POINT CLOUD" },
      { id: "scan-b", type: "point_cloud", src: "demo:scan", title: "Reactor Bay — Scan B", badge: "POINT CLOUD" },
      { id: "scan-c", type: "point_cloud", src: "demo:scan", title: "Reactor Bay — Scan C", badge: "POINT CLOUD" },
    ],
  };
}

export const DEMO_SCENES = {
  cloud: { title: "Reactor Bay Scan", src: "demo:scan" },
  cloud_playlist: { title: "Reactor Bay Scan", src: "demo:scan", playlist: true },
  cloud_error: { title: "Scan Decode Error", src: "demo:error" },
  cloud_heavy: { title: "Dense Scan (1.3M pts)", src: "demo:heavy" },
};
