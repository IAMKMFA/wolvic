// Procedural demo content: a named-part flanged-pipe assembly with generated
// explode AnimationClips, shaped exactly like GLTFLoader output — so every
// production code path (framing, clips, part-ID, playlist, errors, budgets)
// is testable on desktop with zero model files.

import * as THREE from "three";
import { ORANGE } from "./brand.js";

const STEEL = { color: 0xb9c2cc, metalness: 0.75, roughness: 0.35 };
const DARK_STEEL = { color: 0x5b6b7d, metalness: 0.7, roughness: 0.45 };
const GASKET = { color: 0xf04e23, metalness: 0.1, roughness: 0.7 };

function steel(opts) { return new THREE.MeshStandardMaterial({ ...STEEL, ...opts }); }

/** Flanged pipe coupling: body, two flanges, gasket, 8 bolts — all named. */
export function buildDemoAssembly() {
  const root = new THREE.Group();
  root.name = "Flanged Pipe Coupling";

  const body = new THREE.Mesh(new THREE.CylinderGeometry(0.16, 0.16, 0.9, 32), steel());
  body.name = "Pipe Body";
  body.rotation.z = Math.PI / 2;
  root.add(body);

  const flangeGeo = new THREE.CylinderGeometry(0.3, 0.3, 0.06, 32);
  const flangeA = new THREE.Mesh(flangeGeo, steel(DARK_STEEL));
  flangeA.name = "Flange A";
  flangeA.rotation.z = Math.PI / 2;
  flangeA.position.x = -0.48;
  root.add(flangeA);

  const flangeB = flangeA.clone();
  flangeB.name = "Flange B";
  flangeB.position.x = 0.48;
  root.add(flangeB);

  const gasket = new THREE.Mesh(
    new THREE.TorusGeometry(0.21, 0.025, 12, 32),
    new THREE.MeshStandardMaterial(GASKET)
  );
  gasket.name = "Gasket";
  gasket.rotation.y = Math.PI / 2;
  gasket.position.x = -0.54;
  root.add(gasket);

  const boltGeo = new THREE.CylinderGeometry(0.02, 0.02, 0.14, 10);
  const headGeo = new THREE.CylinderGeometry(0.035, 0.035, 0.03, 6);
  for (let i = 0; i < 8; i++) {
    const angle = (i / 8) * Math.PI * 2;
    const bolt = new THREE.Group();
    bolt.name = "Bolt " + (i + 1);
    const shaft = new THREE.Mesh(boltGeo, steel(DARK_STEEL));
    shaft.name = bolt.name + " Shaft";
    const head = new THREE.Mesh(headGeo, steel());
    head.name = bolt.name + " Head";
    head.position.y = 0.085;
    bolt.add(shaft, head);
    bolt.rotation.z = Math.PI / 2;
    bolt.position.set(-0.48, Math.cos(angle) * 0.25, Math.sin(angle) * 0.25);
    bolt.userData.angle = angle;
    root.add(bolt);
  }

  return { root, animations: buildDemoClips(root), fileName: "demo-assembly.glb" };
}

/** Two clips: axial/radial "Explode" plus a sequential "Bolt Pattern". */
function buildDemoClips(root) {
  const explodeTracks = [];
  const D = 3; // seconds
  const times = [0, D];

  const pushMove = (node, dx, dy, dz) => {
    const p = node.position;
    explodeTracks.push(new THREE.VectorKeyframeTrack(
      node.name + ".position",
      times,
      [p.x, p.y, p.z, p.x + dx, p.y + dy, p.z + dz]
    ));
  };

  for (const child of root.children) {
    if (child.name === "Flange A") pushMove(child, -0.35, 0, 0);
    else if (child.name === "Flange B") pushMove(child, 0.35, 0, 0);
    else if (child.name === "Gasket") pushMove(child, -0.18, 0, 0);
    else if (child.name.startsWith("Bolt")) {
      const angle = child.userData.angle || 0;
      pushMove(child, -0.55, Math.cos(angle) * 0.16, Math.sin(angle) * 0.16);
    }
  }
  // One rotation track proves quaternion interpolation works end-to-end.
  const q0 = new THREE.Quaternion();
  const q1 = new THREE.Quaternion().setFromEuler(new THREE.Euler(0, Math.PI / 2, 0));
  const gasket = root.getObjectByName("Gasket");
  const gq = gasket.quaternion.clone();
  const gq1 = gq.clone().multiply(q1);
  explodeTracks.push(new THREE.QuaternionKeyframeTrack(
    "Gasket.quaternion", times, [gq.x, gq.y, gq.z, gq.w, gq1.x, gq1.y, gq1.z, gq1.w]
  ));
  void q0;
  const explode = new THREE.AnimationClip("Explode", D, explodeTracks);

  const boltTracks = [];
  root.children.filter((c) => c.name.startsWith("Bolt")).forEach((bolt, i) => {
    const p = bolt.position;
    const t0 = i * 0.3;
    boltTracks.push(new THREE.VectorKeyframeTrack(
      bolt.name + ".position",
      [t0, t0 + 0.3, 2.7],
      [p.x, p.y, p.z, p.x - 0.4, p.y, p.z, p.x - 0.4, p.y, p.z]
    ));
  });
  const boltPattern = new THREE.AnimationClip("Bolt Pattern", 2.7, boltTracks);

  return [explode, boltPattern];
}

/** Variant assemblies for the demo playlist. */
export function buildDemoVariant(kind) {
  if (kind === "valve") {
    const root = new THREE.Group();
    root.name = "Gate Valve";
    const body = new THREE.Mesh(new THREE.BoxGeometry(0.4, 0.45, 0.3), steel(DARK_STEEL));
    body.name = "Valve Body";
    body.position.y = 0.22;
    const stem = new THREE.Mesh(new THREE.CylinderGeometry(0.03, 0.03, 0.5, 12), steel());
    stem.name = "Stem";
    stem.position.y = 0.7;
    const wheel = new THREE.Mesh(new THREE.TorusGeometry(0.16, 0.025, 10, 28), new THREE.MeshStandardMaterial({ color: new THREE.Color(ORANGE).getHex(), metalness: 0.3, roughness: 0.5 }));
    wheel.name = "Handwheel";
    wheel.rotation.x = Math.PI / 2;
    wheel.position.y = 0.95;
    root.add(body, stem, wheel);
    const tracks = [
      new THREE.VectorKeyframeTrack("Stem.position", [0, 2.5], [0, 0.7, 0, 0, 1.0, 0]),
      new THREE.VectorKeyframeTrack("Handwheel.position", [0, 2.5], [0, 0.95, 0, 0, 1.35, 0]),
    ];
    return { root, animations: [new THREE.AnimationClip("Explode", 2.5, tracks)], fileName: "demo-valve.glb" };
  }
  if (kind === "pump") {
    const root = new THREE.Group();
    root.name = "Coolant Pump";
    const volute = new THREE.Mesh(new THREE.CylinderGeometry(0.3, 0.3, 0.24, 28), steel());
    volute.name = "Volute Casing";
    volute.rotation.x = Math.PI / 2;
    const impeller = new THREE.Mesh(new THREE.ConeGeometry(0.18, 0.2, 14), steel(DARK_STEEL));
    impeller.name = "Impeller";
    impeller.rotation.x = -Math.PI / 2;
    const motor = new THREE.Mesh(new THREE.CylinderGeometry(0.16, 0.16, 0.5, 24), steel(DARK_STEEL));
    motor.name = "Motor";
    motor.rotation.x = Math.PI / 2;
    motor.position.z = 0.42;
    root.add(volute, impeller, motor);
    const tracks = [
      new THREE.VectorKeyframeTrack("Impeller.position", [0, 2.5], [0, 0, 0, 0, 0, -0.45]),
      new THREE.VectorKeyframeTrack("Motor.position", [0, 2.5], [0, 0, 0.42, 0, 0, 0.95]),
    ];
    return { root, animations: [new THREE.AnimationClip("Explode", 2.5, tracks)], fileName: "demo-pump.glb" };
  }
  return buildDemoAssembly();
}

/** ~300 draw calls to trip the budget toast. */
export function buildHeavyDemo() {
  const root = new THREE.Group();
  root.name = "Stress Lattice";
  const geo = new THREE.BoxGeometry(0.05, 0.05, 0.05);
  for (let i = 0; i < 300; i++) {
    const mesh = new THREE.Mesh(geo, steel({ color: 0x6f8db3 + (i % 40) * 64 }));
    mesh.name = "Cell " + i;
    mesh.position.set(((i % 10) - 4.5) * 0.08, (Math.floor(i / 10) % 6) * 0.08, (Math.floor(i / 60) - 2) * 0.08);
    root.add(mesh);
  }
  return { root, animations: [], fileName: "demo-heavy.glb" };
}

export function demoPlaylistDoc() {
  return {
    version: 1,
    id: "demo-models",
    index: 0,
    items: [
      { id: "demo/pipe", title: "Flanged Pipe Coupling", type: "3d_model", src: "demo:pipe", badge: "3D MODEL" },
      { id: "demo/valve", title: "Gate Valve", type: "3d_model", src: "demo:valve", badge: "3D MODEL" },
      { id: "demo/pump", title: "Coolant Pump", type: "3d_model", src: "demo:pump", badge: "3D MODEL" },
    ],
  };
}

/** Resolves a demo: src token to a loaded-model-shaped object. */
export function loadDemoModel(src) {
  const kind = String(src || "").replace(/^demo:/, "");
  if (kind === "heavy") return Promise.resolve(buildHeavyDemo());
  if (kind === "error") {
    const err = new Error("This model can't be decoded on this headset (demo failure).");
    err.fileName = "demo-broken.glb";
    return Promise.reject(err);
  }
  return Promise.resolve(buildDemoVariant(kind));
}

export const DEMO_SCENES = {
  model: { title: "Flanged Pipe Coupling", src: "demo:pipe" },
  model_playlist: { title: "Flanged Pipe Coupling", src: "demo:pipe", playlist: true },
  model_error: { title: "Decode Error Demo", src: "demo:error" },
  model_heavy: { title: "Stress Lattice", src: "demo:heavy" },
};
