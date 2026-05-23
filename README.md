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
│  │       Framatome Tour Subsystem (Kotlin)   │  │
│  │  - NanoHTTPD on localhost:18080           │  │
│  │  - Zip import pipeline (auto-extract)     │  │
│  │  - HTML tour menu with intro animation    │  │
│  │  - Branded "Enter VR" overlay injection   │  │
│  │  - Tour enable/disable, delete, rescan    │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

### Key Components

| Component | Location | Purpose |
|---|---|---|
| `LocalWebServer` | `tours/LocalWebServer.kt` | NanoHTTPD server on `localhost:18080`, serves tour files and injects the VR button overlay |
| `TourIndexServer` | `tours/TourIndexServer.kt` | Generates the branded HTML tour menu with intro animation |
| `PublicInboxImporter` | `tours/PublicInboxImporter.kt` | Scans external storage for `.zip` tour packages, extracts and validates them |
| `FramatomeInitializer` | `tours/FramatomeInitializer.kt` | Bootstraps the server and runs initial import on app launch |
| `TourListViewModel` | `tours/TourListViewModel.kt` | Tour state management — rescan, delete, enable/disable |
| `TourSettingsStore` | `tours/TourSettingsStore.kt` | Persists per-tour enable/disable preferences |

All Framatome code lives under `app/src/main/java/com/framatome/vr/tours/`.

## User Experience

1. **Launch** — App opens with a 3-5 second branded intro animation (particles, logo reveal, tap to skip).
2. **Tour Menu** — Dark-themed grid of available tours with thumbnails, WebXR badges, file sizes, and dates. Rescan button refreshes from disk.
3. **Tour Playback** — Tapping a tour card loads it through the local server. A large branded "Enter VR" button (bottom-right, Framatome goggles icon) triggers full WebXR immersion. A "Home" button (bottom-left) returns to the menu.
4. **Immersion** — Full stereoscopic VR via WebXR/OpenXR. Exit VR returns to the 2D browser panel with the button visible again.

## Tour Management

### Deploying Tours

Tours are standard 3DVista web exports (folder with `index.html` + assets). Three delivery methods:

**Via Arbor XR (production)**
1. Export tour from 3DVista as a web package (`.zip`)
2. Upload to Arbor XR under *Content > Files*
3. Enable "Extract Zip on Device"
4. Target path: device external storage under `FramatomeVR/Tours/` or `FramatomeVRPro/Tours/`
5. The app auto-detects and imports on next launch or rescan

**Via ADB (development)**
```bash
# Push a zip — app will auto-extract it
adb push MyTour.zip /sdcard/FramatomeVR/inbox/

# Or push an already-extracted tour folder
adb push ./MyTourExport/ /sdcard/Android/data/com.framatome.vr.pro/files/tours/MyTour
```

**Via USB file transfer**
Drop `.zip` files into `FramatomeVR/`, `FramatomeVR/inbox/`, `FramatomeVR/Tours/`, or `FramatomeVRPro/` on the headset's shared storage.

### Import Pipeline

The `PublicInboxImporter` handles all the complexity:
- Scans multiple well-known directories on external storage
- Extracts zips with 4GB safety cap and path traversal protection
- Handles nested zips (up to 5 levels deep)
- Flattens unnecessary wrapper folders to find `index.html`
- Strips macOS/Windows junk files (`__MACOSX`, `.DS_Store`, `Thumbs.db`)
- Deduplication via import ledger — won't re-extract unchanged zips
- Purges invalid tours (no `index.html` = removed)

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
- The local server injects a custom "Enter VR" button and "Home" button into every tour HTML page at serve time
- JVM heap is set to 8GB in `gradle.properties` to handle the large GeckoView AAR during Jetifier transformation
- Only the `oculusvrArm64GeckoGeneric` build variant is used for Quest 3 deployment
