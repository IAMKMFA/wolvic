# Framatome VR Pro

Full-immersion 3DVista tour platform for Meta Quest 3, built on a custom WebXR rendering engine.

## What This Is

Framatome VR Pro delivers offline, fully immersive VR training tours to field technicians on Meta Quest 3 headsets. Tours are authored in 3DVista, exported as web packages, and deployed to headsets via Arbor XR or sideloading. The app handles everything from import to playback — no browser chrome, no setup, no internet required.

### Why It Exists

Standard browsers on Quest can render 3DVista tours, but they lack true WebXR immersion — the "Enter VR" button either doesn't work or leads to a broken experience. Framatome VR Pro solves this with a custom-built GeckoView engine patched with Igalia's WebXR extensions, wrapped in a purpose-built VR shell with zero browser UI exposed to the end user.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                 Framatome VR Pro                 │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │        Framatome VR shell (OpenXR)        │  │
│  │  - OpenXR runtime for Quest 3             │  │
│  │  - No browser chrome / no dialogs         │  │
│  │  - Single-window kiosk mode               │  │
│  └─────────────────┬─────────────────────────┘  │
│                    │                             │
│  ┌─────────────────▼─────────────────────────┐  │
│  │     Custom GeckoView (Firefox 128 ESR)    │  │
│  │  - Igalia WebXR patches applied           │  │
│  │  - navigator.xr.requestSession works      │  │
│  │  - Built from source (aarch64-android)    │  │
│  └─────────────────┬─────────────────────────┘  │
│                    │                             │
│  ┌─────────────────▼─────────────────────────┐  │
│  │       Framatome Player Subsystem (Kotlin) │  │
│  │  - NanoHTTPD on localhost:18080           │  │
│  │  - Serves /sdcard/FramatomeVR/Tours/      │  │
│  │  - 3DVista/FME skin injection             │  │
│  │  - Native media viewers (360/2D)          │  │
│  │  - Unified LAUNCH_CONTENT routing         │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

### Key Components

| Component | Location | Purpose |
|---|---|---|
| `LocalWebServer` | `tours/LocalWebServer.kt` | NanoHTTPD on `localhost:18080`; serves tours + `/health`; injects 3DVista/FME overrides |
| `FramatomeInitializer` | `tours/FramatomeInitializer.kt` | Starts the player web server on app boot |
| `MediaLaunchRouter` | `immersive/MediaLaunchRouter.kt` | Routes `LAUNCH_CONTENT` to WebXR or native viewer Activities |
| `FramatomeImmersiveActivity` | `immersive/FramatomeImmersiveActivity.java` | Framatome entry point; kiosk tour relaunch on new intents |
| `TourIndexServer` | `tours/TourIndexServer.kt` | Optional branded HTML tour menu at `/` |

Zip ingest and the operator menu live in the **launcher APK** (`com.framatome.vr`). This
player APK receives typed launch intents and handles all playback.

Framatome-owned code lives under `app/src/main/java/com/framatome/vr/`.

## User Experience

1. **Launch** — App opens with a 3-5 second branded intro animation (particles, logo reveal, tap to skip).
2. **Tour Menu** — Dark-themed grid of available tours with thumbnails, WebXR badges, file sizes, and dates. Rescan button refreshes from disk.
3. **Tour Playback** — Tapping a tour card loads it through the local server. A large branded "Enter VR" button (bottom-right, Framatome goggles icon) triggers full WebXR immersion. A "Home" button (bottom-left) returns to the menu.
4. **Immersion** — Full stereoscopic VR via WebXR/OpenXR. Exit VR returns to the 2D browser panel with the button visible again.

## Tour Management

### Deploying Tours

Tours are standard 3DVista web exports (folder with `index.html` + assets). Production
delivery is through the **Framatome VR launcher** (`com.framatome.vr`), which ingests
zips into `/sdcard/FramatomeVR/Tours/`. Framatome Player serves those folders on
`:18080` when a tour is launched.

**Via Arbor XR (production)**
1. Install both launcher and Framatome Player APKs on the headset group
2. Export tour from 3DVista as a web package (`.zip`)
3. Upload to Arbor XR under *Content > Files*
4. Target path: `/sdcard/FramatomeVR/Data/`
5. The launcher ingests on next scan; tap the tour card to open in Framatome Player

**Via ADB (development)**
```bash
# Push a zip to the launcher drop folder
adb push MyTour.zip /sdcard/FramatomeVR/Data/

# Or push an already-extracted tour folder
adb push ./MyTourExport/ /sdcard/FramatomeVR/Tours/MyTour
```

**Via USB file transfer**
Drop `.zip` files into `/sdcard/FramatomeVR/Data/` on the headset's shared storage.

### Tour Requirements

Each tour folder must contain:
- `index.html` or `index.htm` (required)
- `thumbnail.jpg` or `thumbnail.png` (optional, shown on menu card)
- All assets with relative paths (no absolute `http://` URLs)

## Building

### Prerequisites

- Android Studio (latest stable)
- JDK 17+
- The custom GeckoView AAR is already integrated via local Maven repository

### Build the APK

```bash
cd framatome-vr-wolvic
./gradlew assembleOculusvrArm64GeckoGenericDebug
```

Output: `app/build/outputs/apk/oculusvrArm64GeckoGeneric/debug/FramatomeVR-oculusvr-arm64-gecko-generic-debug.apk`

For a signed release build, use Android Studio's *Build > Generate Signed APK* workflow.

### Install on Quest

```bash
adb install -r app/build/outputs/apk/oculusvrArm64GeckoGeneric/debug/FramatomeVR-oculusvr-arm64-gecko-generic-debug.apk
```

Or install via Arbor XR / SideQuest for fleet deployment.

## Custom GeckoView

The WebXR immersion depends on a custom build of GeckoView (Firefox 128 ESR) with Igalia's WebXR patches. This provides `navigator.xr.requestSession('immersive-vr')` support that standard GeckoView does not have.

- Source: `firefox-128.14.0` with patches from [Igalia/ASharpYard](https://phabricator.nicepfp.org/)
- Target: `aarch64-unknown-linux-android` (Quest 3)
- Published as a local Maven snapshot: `org.mozilla.geckoview:geckoview-default:128.14.20260314224701-SNAPSHOT`

The GeckoView build only needs to be rebuilt if updating Firefox ESR or applying new WebXR patches. The snapshot AAR is referenced in `app/build.gradle` and resolved from a local Maven repo.

## Branding

User-facing branding is Framatome throughout:
- App name: **Framatome VR** (launcher) / **Framatome VR Pro** (full title)
- Package ID: `com.framatome.vr.pro`
- App icons: Custom Framatome VR headset logo
- In-app: Custom splash screen, tour menu, intro animation, VR button with Framatome goggles image
- No first-run dialogs, terms of service, or privacy prompts

String replacements are in `res/values/strings.xml`, `res/values/non_L10n.xml`, and `res/values/options_values.xml`.

## Project Structure

```
framatome-vr-wolvic/
├── app/
│   ├── src/main/java/com/framatome/vr/tours/   # Framatome tour subsystem
│   ├── src/main/java/com/igalia/wolvic/        # VR browser shell (upstream-derived, modified)
│   ├── src/main/res/                           # Resources (Framatome branded)
│   │   ├── drawable/                           # Logos, splash, backgrounds
│   │   ├── raw/                                # VR goggles image asset
│   │   └── values/                             # Strings, styles, colors
│   └── build.gradle                            # Dependencies (custom GeckoView)
├── gradle.properties                           # JVM heap (8GB for GeckoView AAR)
└── build.gradle                                # Repositories (local Maven + Mozilla)
```

## Release Checklist

- [ ] Ensure all tours are tested on-device with WebXR immersion
- [ ] Build signed release APK via Android Studio
- [ ] Upload APK to Arbor XR under *Apps*, assign to headset group
- [ ] Upload tour `.zip` files to Arbor XR under *Files* (extract enabled)
- [ ] Verify tours auto-import and appear in the menu on target headsets
- [ ] Confirm "Enter VR" button triggers full immersion on each tour

## Technical Notes

- The app runs in `FRAMATOME_MODE` (set via `BuildConfig`), which disables first-run legal dialogs, terms of service, and session restoration
- Default VR environment is set to `void` (dark) to avoid distracting backgrounds
- The local server injects Quest device shims and 3DVista goggle overrides (FME-compatible) into tour HTML at serve time
- JVM heap is set to 8GB in `gradle.properties` to handle the large GeckoView AAR during Jetifier transformation
- Only the `oculusvrArm64GeckoGeneric` build variant is used for Quest 3 deployment
