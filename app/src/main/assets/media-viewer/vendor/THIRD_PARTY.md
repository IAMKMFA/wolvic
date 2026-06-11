# Vendored third-party runtime — Framatome Player media & model viewers

Fetched by [`scripts/vendor-three.sh`](../../../../../../../scripts/vendor-three.sh)
(model viewer) and [`scripts/vendor-omnitone.sh`](../../../../../../../scripts/vendor-omnitone.sh)
(spatial audio) — each pins a version + fixed file list. The headset has no
network: everything the viewers load ships in the APK from this directory.
Never edit vendored files; bump the version in the relevant script, re-run it,
and refresh the matching manifest below.

## Components & licenses

| Component | Files | Version | License |
|-----------|-------|---------|---------|
| three.js (core + addons) | `three/**` | npm `three@0.180.0` | MIT © 2010-2025 three.js authors |
| Draco decoder | `draco/*` | bundled with three 0.180.0 | Apache-2.0 © Google LLC |
| Basis Universal transcoder | `basis/*` | bundled with three 0.180.0 | Apache-2.0 © Binomial LLC |
| meshoptimizer decoder | `three/addons/libs/meshopt_decoder.module.js` (if present) | bundled with three 0.180.0 | MIT © Arseny Kapoulkine |
| ktx-parse | `three/addons/libs/ktx-parse.module.js` | bundled with three 0.180.0 | MIT © Don McCurdy |
| zstddec (embedded zstd wasm) | `three/addons/libs/zstddec.module.js` | bundled with three 0.180.0 | MIT wrapper; zstd BSD-3-Clause © Meta Platforms |
| Omnitone (spatial/ambisonic audio) | `omnitone/omnitone.min.js`, `omnitone/LICENSE` | npm `omnitone@1.3.0` | Apache-2.0 © Google Inc. |

All licenses permit redistribution inside a commercial APK provided notices are
preserved — this file is that notice. Upstream license texts:
<https://github.com/mrdoob/three.js/blob/dev/LICENSE>,
<https://github.com/google/draco/blob/main/LICENSE>,
<https://github.com/BinomialLLC/basis_universal/blob/master/LICENSE>,
<https://github.com/zeux/meshoptimizer/blob/master/LICENSE.md>,
<https://github.com/GoogleChrome/omnitone/blob/master/LICENSE>.

**Omnitone HRIR is offline-safe.** The First-Order Ambisonic head-related
impulse responses are embedded as base64 WAV inside `omnitone.min.js`;
`createFOARenderer(ctx)` with no config decodes them locally, so no `.wav`
resources are fetched and nothing needs a network. The viewer lazy-loads this
bundle only when an item is flagged `audio: "ambisonic"` — the default audio
path never touches it.

## SHA-256 manifest (three@0.180.0)

```
8478b5b6d6b74e7d3082b89f6417321d8d1dc0307f2b30d4484bb11b441696a1  basis/basis_transcoder.js
6cf17dc889352c42e9acf8897107978d127005fe3386c36a0e3845e27967630a  basis/basis_transcoder.wasm
a680d927bed9cb864ddbd63521868891af2bfbe755092761b4837487618df8ac  draco/draco_decoder.wasm
8bb2952d2ba7d67e1414f8df819410cb0434a666be53f671fff75f68843d76f6  draco/draco_wasm_wrapper.js
b97879c748170baadeb3fb84cea1ffdf4674e283dc06042f34e2acb95a76042c  three/addons/controls/OrbitControls.js
c20f0b4677f6128a138d7152b85cbef9091f4f45cdfa05adca04d40c1697c7ae  three/addons/environments/RoomEnvironment.js
f40c491f6c44dde511268121f778a0050e73b1a15fd844c1ae2c78c73213eafc  three/addons/libs/ktx-parse.module.js
5cbf818e842628a4464e748594a6deae18ceddda3c2f541e7b3a0ff5fc7611e2  three/addons/libs/zstddec.module.js
7a4a51c694a6c9f983be452cc82b365e69ce08424654e3f245b4115e6efef258  three/addons/loaders/DRACOLoader.js
67ac5551fdafa6e349bd80c8f8e5e39c136d6b2fb1ad647db9abb21dc86f9e4a  three/addons/loaders/GLTFLoader.js
c43052b95310199d50935bdc41fcd0fc347f25eac3b4f0245e6e4de1ef6e1d93  three/addons/loaders/KTX2Loader.js
e3ceb6a8ed48ca146049a8f0879c7acbf884d430f2e728779af26c6ea3a57c65  three/addons/loaders/OBJLoader.js
cc35c01c793cd17ccded7bc8142abffd3ce0d60dd6de8d5d216983bd05aee262  three/addons/math/ColorSpaces.js
fda7e946b8e0b5ab39b779206589e7a1079a22eb24efb89d7223e03fdfb1f751  three/addons/utils/BufferGeometryUtils.js
5ac7095fd566bc9ae48376055fd66edf27cb9ebbf9e1269dc206bfd4933ae9eb  three/addons/utils/WorkerPool.js
61ba0df005b05991361d040d8ff670e1aadfd0ce7aeebd1fdb0725957a8957de  three/three.core.min.js
e2b5ee6bccd38fd6d8a2428546b83c5f2426d84b152ef82be8055556e3b40eb6  three/three.module.min.js
```

## SHA-256 manifest (omnitone@1.3.0)

```
6dd846d6373bc9eeee3ac951b0f2c918b65cc405dca6bbeca29ded68339a6787  omnitone/LICENSE
8000ccca03511b3fbe1734f8a9da3314da7ab00439573edae0d7a25995ddfd9c  omnitone/omnitone.min.js
```
