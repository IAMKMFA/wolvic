// Scene/stage for the Framatome model viewer: renderer, branded gradient dome,
// glass pedestal disc, neutral IBL (RoomEnvironment — no HDR asset), model
// framing (pedestal vs TRUE SCALE), turntable, and reset.

import * as THREE from "three";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import { BLUE_MIDNIGHT, BLUE_PRIMARY } from "./brand.js";

export const FLOOR_Y = 0;                // local-floor reference: y=0 is the floor
export const PEDESTAL_SIZE = 1.2;        // metres, fitted max dimension
export const PEDESTAL_DISTANCE = 2.0;    // metres ahead of the viewer
export const MIN_CENTER_HEIGHT = 1.2;    // keep small models at chest height,
                                         // clear of the low console panel
export const TURNTABLE_SPEED = (12 * Math.PI) / 180; // 12°/s

export function createRenderer(canvas) {
  const renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: true,          // MSAA is near-free on Quest's tiled GPU
    alpha: false,
    powerPreference: "high-performance",
  });
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  renderer.shadowMap.enabled = false;
  renderer.xr.enabled = true;
  if (renderer.xr.setFoveation) renderer.xr.setFoveation(1.0);
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  return renderer;
}

export function createStage(renderer) {
  const scene = new THREE.Scene();

  // Neutral studio IBL with zero shipped assets. Flatters SolidWorks metals;
  // analytic lights alone look dead on PBR materials.
  try {
    const pmrem = new THREE.PMREMGenerator(renderer);
    scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
    pmrem.dispose();
  } catch (e) {
    console.warn("[model] RoomEnvironment failed, falling back to lights", e);
    scene.add(new THREE.HemisphereLight(0xdfe8f5, 0x10213d, 1.1));
    const key = new THREE.DirectionalLight(0xffffff, 1.4);
    key.position.set(2, 4, 3);
    scene.add(key);
  }

  scene.add(buildDome());
  scene.add(buildFloor());

  // The pivot owns ALL user transforms (grab/turntable/scale). glTF nodes are
  // never touched, so animation tracks can't fight the interaction state.
  const pivot = new THREE.Group();
  pivot.name = "FramatomePivot";
  scene.add(pivot);

  const camera = new THREE.PerspectiveCamera(70, 1, 0.05, 120);
  camera.position.set(0, 1.55, 0.9); // desktop start; XR poses override this

  return { scene, camera, pivot };
}

function buildDome() {
  // Vertical gradient dome in the launcher's blue family; toneMapped:false on
  // every brand surface so ACES doesn't shift the palette.
  const uniforms = {
    topColor: { value: new THREE.Color(BLUE_MIDNIGHT).multiplyScalar(0.5) },
    midColor: { value: new THREE.Color(BLUE_PRIMARY).multiplyScalar(0.55) },
    bottomColor: { value: new THREE.Color("#020409") },
  };
  const material = new THREE.ShaderMaterial({
    uniforms,
    side: THREE.BackSide,
    depthWrite: false,
    fog: false,
    vertexShader: /* glsl */ `
      varying vec3 vWorld;
      void main() {
        vWorld = (modelMatrix * vec4(position, 1.0)).xyz;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }
    `,
    fragmentShader: /* glsl */ `
      uniform vec3 topColor;
      uniform vec3 midColor;
      uniform vec3 bottomColor;
      varying vec3 vWorld;
      void main() {
        float h = normalize(vWorld).y;
        vec3 c = h >= 0.0
          ? mix(midColor, topColor, smoothstep(0.0, 0.7, h))
          : mix(midColor, bottomColor, smoothstep(0.0, 0.45, -h));
        gl_FragColor = vec4(c, 1.0);
      }
    `,
  });
  material.toneMapped = false;
  const dome = new THREE.Mesh(new THREE.SphereGeometry(40, 32, 24), material);
  dome.name = "FramatomeDome";
  return dome;
}

function buildFloor() {
  // Soft contact-shadow disc + faint glass ring: the "pedestal" read.
  const group = new THREE.Group();
  group.name = "FramatomeFloor";

  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 512;
  const ctx = canvas.getContext("2d");
  const gradient = ctx.createRadialGradient(256, 256, 20, 256, 256, 250);
  gradient.addColorStop(0, "rgba(0, 0, 0, 0.45)");
  gradient.addColorStop(0.75, "rgba(0, 0, 0, 0.18)");
  gradient.addColorStop(1, "rgba(0, 0, 0, 0)");
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, 512, 512);
  ctx.strokeStyle = "rgba(255, 255, 255, 0.18)";
  ctx.lineWidth = 3;
  ctx.beginPath();
  ctx.arc(256, 256, 238, 0, Math.PI * 2);
  ctx.stroke();

  const texture = new THREE.CanvasTexture(canvas);
  const material = new THREE.MeshBasicMaterial({
    map: texture,
    transparent: true,
    depthWrite: false,
  });
  material.toneMapped = false;
  const disc = new THREE.Mesh(new THREE.CircleGeometry(1.2, 48), material);
  disc.rotation.x = -Math.PI / 2;
  disc.position.set(0, FLOOR_Y + 0.002, -PEDESTAL_DISTANCE);
  group.add(disc);
  group.userData.disc = disc;
  return group;
}

/**
 * Centers the loaded root inside the pivot and computes both presentation
 * transforms. Pedestal: fitted to PEDESTAL_SIZE on the floor disc. True scale:
 * exporter units (1:1), same floor placement. Returns the framing state used
 * by reset/toggles.
 */
export function frameModel(pivot, root) {
  pivot.clear();
  pivot.position.set(0, 0, 0);
  pivot.quaternion.identity();
  pivot.scale.setScalar(1);

  const box = new THREE.Box3().setFromObject(root);
  if (box.isEmpty()) box.set(new THREE.Vector3(-0.5, -0.5, -0.5), new THREE.Vector3(0.5, 0.5, 0.5));
  const size = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());
  const maxDim = Math.max(size.x, size.y, size.z) || 1;

  // Inner group: model centered with its base at y=0 in pivot space.
  const inner = new THREE.Group();
  inner.name = "FramatomeModelInner";
  inner.add(root);
  root.position.set(-center.x, -box.min.y, -center.z);
  pivot.add(inner);

  const pedestalScale = PEDESTAL_SIZE / maxDim;

  const framing = {
    maxDim,
    size: size.clone(),
    pedestalScale,
    trueScale: false,
    home: null, // filled by applyPresentation
  };
  applyPresentation(pivot, framing, false);
  framing.home = {
    position: pivot.position.clone(),
    quaternion: pivot.quaternion.clone(),
    scale: pivot.scale.x,
  };
  return framing;
}

/** Switches pedestal (fitted) <-> true scale (exporter units). */
export function applyPresentation(pivot, framing, trueScale) {
  framing.trueScale = trueScale;
  const scale = trueScale ? 1 : framing.pedestalScale;
  pivot.scale.setScalar(scale);
  pivot.quaternion.identity();

  const height = framing.size.y * scale;
  const depth = Math.max(framing.size.x, framing.size.z) * scale;
  // Big true-scale equipment needs more standoff to read comfortably.
  const distance = trueScale
    ? Math.max(PEDESTAL_DISTANCE, depth / 2 + 1.2)
    : PEDESTAL_DISTANCE;
  let y = FLOOR_Y;
  if (height < MIN_CENTER_HEIGHT * 2) {
    y = Math.max(FLOOR_Y, MIN_CENTER_HEIGHT - height / 2);
  }
  pivot.position.set(0, y, -distance);
  return distance;
}

export function resetView(pivot, framing) {
  if (!framing || !framing.home) return;
  applyPresentation(pivot, framing, framing.trueScale);
  if (!framing.trueScale) {
    pivot.position.copy(framing.home.position);
    pivot.quaternion.copy(framing.home.quaternion);
    pivot.scale.setScalar(framing.home.scale);
  }
}

/** Reads renderer stats after the first rendered frame for the budget toast. */
export function readBudget(renderer) {
  const info = renderer.info.render;
  return { triangles: info.triangles, calls: info.calls };
}
