// Loader rig for the Framatome model viewer: GLTF (+Draco, +KTX2, +meshopt)
// and best-effort OBJ. Singletons live for the page lifetime — only loaded
// MODELS are disposed between playlist items, never the decoders/workers.

import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { DRACOLoader } from "three/addons/loaders/DRACOLoader.js";
import { KTX2Loader } from "three/addons/loaders/KTX2Loader.js";
import { OBJLoader } from "three/addons/loaders/OBJLoader.js";
import { MeshoptDecoder } from "three/addons/libs/meshopt_decoder.module.js";

const MEDIA_ROUTE = "/__media__/";

/** Storage-relative path -> /__media__/ URL; absolute http(s) passes through
 *  (desktop testing convenience, mirrors viewer.js mediaUrl). */
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

export function createLoaderRig(renderer) {
  const gltf = new GLTFLoader();

  const draco = new DRACOLoader();
  draco.setDecoderPath("./vendor/draco/");
  draco.setDecoderConfig({ type: "wasm" });
  gltf.setDRACOLoader(draco);

  const ktx2 = new KTX2Loader();
  ktx2.setTranscoderPath("./vendor/basis/");
  ktx2.detectSupport(renderer);
  gltf.setKTX2Loader(ktx2);

  gltf.setMeshoptDecoder(MeshoptDecoder);

  const obj = new OBJLoader();

  return { gltf, obj, ktx2 };
}

/**
 * Loads a model by storage-relative path. Resolves to
 * `{ root, animations, fileName }`. onProgress receives (loadedBytes,
 * totalBytes|0) during transfer.
 */
export function loadModel(rig, src, onProgress) {
  const ext = extensionOf(src);
  const url = mediaUrl(src);
  const fileName = sourceFileName(src);

  if (ext === "glb" || ext === "gltf") {
    if (ext === "gltf") {
      // Multi-file glTF: buffers/textures resolve next to the .gltf.
      const base = url.slice(0, url.lastIndexOf("/") + 1);
      rig.gltf.setResourcePath(base);
    } else {
      rig.gltf.setResourcePath("");
    }
    return new Promise((resolve, reject) => {
      rig.gltf.load(
        url,
        (gltf) => resolve({ root: gltf.scene, animations: gltf.animations || [], fileName }),
        (event) => { if (onProgress) onProgress(event.loaded || 0, event.total || 0); },
        (error) => reject(describeLoadError(error, fileName))
      );
    });
  }

  if (ext === "obj") {
    return new Promise((resolve, reject) => {
      rig.obj.load(
        url,
        (root) => {
          // Neutral PBR steel so untextured OBJ parts read well under the IBL.
          const material = new THREE.MeshStandardMaterial({
            color: 0xb9c2cc,
            metalness: 0.2,
            roughness: 0.6,
          });
          root.traverse((node) => { if (node.isMesh) node.material = material; });
          resolve({ root, animations: [], fileName });
        },
        (event) => { if (onProgress) onProgress(event.loaded || 0, event.total || 0); },
        (error) => reject(describeLoadError(error, fileName))
      );
    });
  }

  const err = new Error(
    ext === "fbx"
      ? "FBX isn't supported on the headset — convert this model to GLB using the SolidWorks XR Exporter or gltfpack (see MODEL_PIPELINE.md)."
      : "This model format isn't supported — deliver GLB/glTF (preferred) or OBJ."
  );
  err.fileName = fileName;
  return Promise.reject(err);
}

function describeLoadError(error, fileName) {
  const raw = error && (error.message || (error.target && "network error"));
  const message = /404|not found/i.test(String(raw))
    ? "The model file could not be found on the headset."
    : "The model could not be parsed (" + (raw || "unknown error") + ").";
  const err = new Error(message);
  err.fileName = fileName;
  return err;
}

/**
 * Frees everything a loaded model holds on the GPU and CPU. Quest kills tabs
 * that leak texture memory across playlist swaps — this must be thorough:
 * geometry, every material texture slot, AND the ImageBitmap CPU copies.
 */
export function disposeModel(root) {
  if (!root) return;
  root.traverse((node) => {
    if (node.geometry) node.geometry.dispose();
    const materials = Array.isArray(node.material) ? node.material : node.material ? [node.material] : [];
    for (const material of materials) disposeMaterial(material);
  });
  if (root.parent) root.parent.remove(root);
}

const TEXTURE_SLOTS = [
  "map", "normalMap", "metalnessMap", "roughnessMap", "aoMap", "emissiveMap",
  "alphaMap", "envMap", "lightMap", "bumpMap", "displacementMap",
  "clearcoatMap", "clearcoatNormalMap", "clearcoatRoughnessMap",
  "sheenColorMap", "sheenRoughnessMap", "specularMap", "specularColorMap",
  "specularIntensityMap", "transmissionMap", "thicknessMap", "iridescenceMap",
  "iridescenceThicknessMap", "anisotropyMap",
];

export function disposeMaterial(material) {
  if (!material) return;
  for (const slot of TEXTURE_SLOTS) {
    const texture = material[slot];
    if (texture && texture.isTexture) {
      const data = texture.source && texture.source.data;
      if (data && typeof data.close === "function") {
        try { data.close(); } catch (e) { /* already closed */ }
      }
      texture.dispose();
    }
  }
  material.dispose();
}
