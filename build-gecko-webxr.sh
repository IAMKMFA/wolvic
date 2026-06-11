#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Framatome Player — build the WebXR-patched GeckoView engine (Firefox ESR 140.8.0)
# =============================================================================
#
# Produces the geckoview-default + geckoview-exoplayer2-default AAR pair that the
# fork consumes from immersive/gecko-maven/. This is the engine that makes
# "Enter VR" work — stock GeckoView lacks the immersive-vr device surface; the
# Igalia WebXR patches add it (and bump the GeckoView<->Wolvic ExternalVR
# protocol to v21, matching app/src/main/cpp/moz_external_vr.h).
#
# DESIGN: fail LOUD and EARLY. Every assumption is checked. The expensive 2-3h
# compile is gated behind a cheap preflight that verifies the source hash, GPG
# signature, and that all 13 patches apply with ZERO fuzz. Run --check first.
#
# Host:   Apple Silicon macOS (M-series).  Target: Android arm64-v8a.
# Disk:   ~60 GB free.   RAM: 16 GB+ (48 GB comfortable).   Time: 2-4 h cold.
#
# Usage:
#   ./build-gecko-webxr.sh --check     # preflight ONLY: verify+extract+patch dry-run+ABI. No compile.
#   ./build-gecko-webxr.sh             # full build: preflight, then bootstrap+build+publish+vendor.
#   ./build-gecko-webxr.sh --help
#
# See ../BUILD_PLAN.md (superproject root) for the step-by-step operator runbook,
# and ENGINE_OWNERSHIP.md for why this exists.
# =============================================================================

# ----------------------------- PINNED CONSTANTS ------------------------------
# Firefox ESR source version. NOTE the 'esr' suffix is load-bearing in Mozilla
# paths; the Maven/AAR coordinate drops it (Wolvic marketing version = 140.8.0).
MOZ_VERSION="140.8.0esr"     # download / verify / extract
GV_VERSION="140.8.0"         # AAR + Maven coordinate dropped into gecko-maven

# archive.mozilla.org pins. SHA256 is the HARD integrity gate (verified even if
# gpg is absent). Source: SHA256SUMS at the release root (covers all platforms).
SRC_TARBALL="firefox-${MOZ_VERSION}.source.tar.xz"
SRC_URL="https://archive.mozilla.org/pub/firefox/releases/${MOZ_VERSION}/source/${SRC_TARBALL}"
SRC_SHA256="57a7f339ef68273f6597d8074a841fa053f63a21d1f609ab0074a26c063282e6"
REL_ROOT_URL="https://archive.mozilla.org/pub/firefox/releases/${MOZ_VERSION}"

# Mozilla's CURRENT release-signing GPG key (the 2021 subkey expired 2023-05-17).
# gpg --verify returns 0 for "good but untrusted" — so we assert the FINGERPRINT,
# not just the exit code. KEY file shipped in the release dir is authoritative.
GPG_PRIMARY_FPR="14F26682D0916CDD81E37B6D61B7B526D98F0353"
GPG_SUBKEY_FPR="09BEED63F3462A2DFFAB3B875ECB6497C1A20256"

# Expected ABI after patch 0016 — MUST match app/src/main/cpp/moz_external_vr.h.
EXPECT_SHMEM_VERSION="0.0.13"
EXPECT_VREXTERNAL_VERSION="21"

# Number of WebXR patches we vendor (gaps in 0001/0007/0008/0011 are expected).
EXPECT_PATCH_COUNT=13

# ------------------------------- PATHS ---------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PATCHES_DIR="$SCRIPT_DIR/gecko-patches/gecko-140esr"
PINNED_FILE="$SCRIPT_DIR/gecko-patches/PINNED.txt"
# Mozilla's mach build FORBIDS spaces in the source path, so the work dir must
# default OUTSIDE the (possibly space-containing) repo. $HOME is space-free on a
# normal macOS install. Override with FRAMATOME_GECKO_WORK=<space-free path>.
WORK_DIR="${FRAMATOME_GECKO_WORK:-$HOME/.framatome-gecko-build}"
DL_DIR="$WORK_DIR/downloads"
SRC_DIR="$WORK_DIR/firefox-${MOZ_VERSION%esr}"                    # tarball extracts to firefox-140.8.0 (no 'esr')
GECKO_MAVEN="$SCRIPT_DIR/gecko-maven"
APP_HEADER="$SCRIPT_DIR/app/src/main/cpp/moz_external_vr.h"
PROVENANCE="$SCRIPT_DIR/gecko-patches/BUILD_PROVENANCE.txt"
PATCH_SENTINEL="$SRC_DIR/.framatome-patches-applied"
M2="$HOME/.m2/repository/org/mozilla/geckoview"

MODE="full"
[ "${1:-}" = "--check" ] && MODE="check"
[ "${1:-}" = "--help" ] && { sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

# ------------------------------- HELPERS -------------------------------------
c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_blu=$'\033[34m'; c_0=$'\033[0m'
step() { printf '\n%s== %s ==%s\n' "$c_blu" "$*" "$c_0"; }
ok()   { printf '%s  ✓ %s%s\n' "$c_grn" "$*" "$c_0"; }
warn() { printf '%s  ! %s%s\n' "$c_yel" "$*" "$c_0"; }
die()  { printf '\n%s  ✗ FAIL: %s%s\n' "$c_red" "$*" "$c_0" >&2; exit 1; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1  ($2)"; }

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

printf '%s\n' "============================================================="
printf 'Framatome Player — GeckoView WebXR engine build\n'
printf '  source : firefox-%s  (sha256 pinned)\n' "$MOZ_VERSION"
printf '  coord  : org.mozilla.geckoview:geckoview-default:%s\n' "$GV_VERSION"
printf '  patches: %s  (pinned, see PINNED.txt)\n' "$PATCHES_DIR"
printf '  work   : %s\n' "$WORK_DIR"
printf '  mode   : %s\n' "$MODE"
printf '%s\n' "============================================================="

# =============================================================================
# PHASE 0 — Host preflight
# =============================================================================
step "Phase 0: host preflight"

[ "$(uname -s)" = "Darwin" ] || die "this script targets macOS (Apple Silicon). Host: $(uname -s)"
HOST_ARCH="$(uname -m)"
[ "$HOST_ARCH" = "arm64" ] || warn "host arch is $HOST_ARCH (expected arm64/Apple Silicon)"
ok "host: macOS $HOST_ARCH"

need_cmd curl "system curl"
need_cmd shasum "system shasum"
need_cmd python3 "Xcode CLT or pyenv"
need_cmd tar "system tar"
need_cmd git "Xcode CLT — used to apply the WebXR patches (they are git-diff format)"
need_cmd unzip "system unzip"
xcode-select -p >/dev/null 2>&1 || die "Xcode Command Line Tools missing. Run: xcode-select --install"
ok "Xcode CLT present: $(xcode-select -p)"

# Rosetta: mach bootstrap may fetch an x86_64 host NDK toolchain (the prior 128
# build embedded a darwin-x86_64 NDK path). Detect; instruct, don't auto-install
# (it's a system change with a license prompt — the operator runs it).
if [ "$HOST_ARCH" = "arm64" ]; then
  if /usr/bin/pgrep -q oahd 2>/dev/null || arch -x86_64 /usr/bin/true 2>/dev/null; then
    ok "Rosetta 2 available"
  else
    warn "Rosetta 2 not detected. mach bootstrap may need it for x86_64 host tooling."
    warn "If a later step fails with a bad-CPU/exec error, run:"
    warn "    softwareupdate --install-rosetta --agree-to-license"
  fi
fi

# SPACE GUARD (hard): mach's build system aborts if the source path contains a
# space ("contains a space, which is not supported"). The work dir holds the
# source tree, so it MUST be space-free. Fail loud here, not 0 phases into mach.
case "$WORK_DIR" in
  *" "*) die "build work dir contains a space: '$WORK_DIR'
  Mozilla's mach build does NOT support spaces in the source path. Point the work
  dir at a space-free location and re-run, e.g.:
    export FRAMATOME_GECKO_WORK=\"\$HOME/.framatome-gecko-build\"" ;;
esac
ok "work dir is space-free: $WORK_DIR"

# mach Python: Firefox 140's mach supports Python <= 3.12 ("Consider running Mach
# with Python 3.12 or lower"). The host `python3` may be newer (3.13/3.14), which
# warns and risks a mid-build failure. Select the best <=3.12 interpreter and run
# mach under it explicitly. Fall back (with a warning) if none is installed.
PYBIN=""
for c in python3.12 python3.11 python3.10 python3.9; do
  command -v "$c" >/dev/null 2>&1 && { PYBIN="$(command -v "$c")"; break; }
done
if [ -z "$PYBIN" ]; then
  for c in python3.13 python3; do command -v "$c" >/dev/null 2>&1 && { PYBIN="$(command -v "$c")"; break; }; done
  warn "no Python <=3.12 found; using $("$PYBIN" --version 2>&1) — mach prefers <=3.12."
  warn "If the build fails on a Python error, install the recommended one: brew install python@3.12"
fi
[ -n "$PYBIN" ] || die "no python3 interpreter found at all"
ok "mach Python: $("$PYBIN" --version 2>&1)  ($PYBIN)"

# Patches are applied with `git apply` — they are git-diff format, so git apply is
# the native tool: zero-fuzz, atomic (all-or-nothing, no .rej), fails loud on any
# mismatch, and never drops to an interactive skip-prompt. (BSD `patch` on macOS
# returns exit 0 when it SKIPS a not-found file — a silent false-positive we must
# not build on. The source tree gets its OWN git repo in Phase 3 so git apply is
# not a silent no-op when the work dir happens to sit inside another repo.)
ok "patch applier: git apply (zero-fuzz, fail-loud)"

# gpg is a soft gate (SHA256 is the hard gate). Full sig verification if present.
HAVE_GPG="no"
if command -v gpg >/dev/null 2>&1; then HAVE_GPG="yes"; ok "gpg present — will verify signature + fingerprint"
else warn "gpg not found — skipping signature check; relying on pinned SHA256 (brew install gnupg to enable)"; fi

# Disk (need ~60 GB). df -g → whole GB available on the work-dir filesystem.
mkdir -p "$WORK_DIR"
FREE_GB="$(df -g "$WORK_DIR" | tail -1 | awk '{print $4}')"
if [ "${FREE_GB:-0}" -lt 60 ]; then
  die "only ${FREE_GB} GB free on $(df -g "$WORK_DIR" | tail -1 | awk '{print $NF}'); need ~60 GB. Free space or set FRAMATOME_GECKO_WORK to a larger volume."
fi
ok "disk: ${FREE_GB} GB free (need ~60)"

# Vendored patch set sanity.
[ -d "$PATCHES_DIR" ] || die "patch dir missing: $PATCHES_DIR (did the submodule check out gecko-patches/?)"
PATCH_COUNT="$(find "$PATCHES_DIR" -maxdepth 1 -name '*.patch' | wc -l | tr -d ' ')"
[ "$PATCH_COUNT" = "$EXPECT_PATCH_COUNT" ] || die "expected $EXPECT_PATCH_COUNT patches, found $PATCH_COUNT in $PATCHES_DIR"
ok "$PATCH_COUNT vendored patches present"

# =============================================================================
# PHASE 1 — Download + verify source (idempotent)
# =============================================================================
step "Phase 1: source tarball download + integrity"
mkdir -p "$DL_DIR"
TARBALL_PATH="$DL_DIR/$SRC_TARBALL"

if [ -f "$TARBALL_PATH" ] && [ "$(sha256_of "$TARBALL_PATH")" = "$SRC_SHA256" ]; then
  ok "tarball already present and hash-verified — skipping download"
else
  [ -f "$TARBALL_PATH" ] && warn "existing tarball failed hash — re-downloading"
  echo "  downloading $SRC_URL  (~604 MB) ..."
  curl -fL --retry 3 --proto '=https' --tlsv1.2 -o "$TARBALL_PATH" "$SRC_URL" \
    || die "download failed: $SRC_URL"
  GOT="$(sha256_of "$TARBALL_PATH")"
  [ "$GOT" = "$SRC_SHA256" ] || die "SHA256 MISMATCH for $SRC_TARBALL
    expected: $SRC_SHA256
    got:      $GOT
  The pinned hash did not match — do NOT proceed. Either the download is
  corrupt/tampered, or the upstream artifact changed. Investigate before retry."
  ok "tarball SHA256 verified: $SRC_SHA256"
fi

# GPG signature + fingerprint (soft gate — only if gpg present).
if [ "$HAVE_GPG" = "yes" ]; then
  echo "  verifying GPG signature ..."
  curl -fsSL -o "$DL_DIR/KEY"                  "$REL_ROOT_URL/KEY"                        || die "could not fetch KEY"
  curl -fsSL -o "$DL_DIR/SHA256SUMS"           "$REL_ROOT_URL/SHA256SUMS"                 || die "could not fetch SHA256SUMS"
  curl -fsSL -o "$DL_DIR/SHA256SUMS.asc"       "$REL_ROOT_URL/SHA256SUMS.asc"             || die "could not fetch SHA256SUMS.asc"
  GNUPGHOME="$(mktemp -d)"; export GNUPGHOME
  gpg --quiet --import "$DL_DIR/KEY" 2>/dev/null || die "gpg import of KEY failed"
  # Assert the shipped key carries Mozilla's primary fingerprint.
  gpg --quiet --fingerprint 2>/dev/null | tr -d ' ' | grep -q "$GPG_PRIMARY_FPR" \
    || die "shipped KEY does not contain expected Mozilla primary fingerprint $GPG_PRIMARY_FPR"
  # Verify the signature over SHA256SUMS, and assert it was made by our key.
  if gpg --quiet --status-fd 1 --verify "$DL_DIR/SHA256SUMS.asc" "$DL_DIR/SHA256SUMS" 2>/dev/null \
       | grep -Eq "VALIDSIG (${GPG_SUBKEY_FPR}|${GPG_PRIMARY_FPR})"; then
    ok "GPG: SHA256SUMS signed by Mozilla key (fingerprint asserted)"
  else
    die "GPG signature on SHA256SUMS did NOT validate against the pinned Mozilla fingerprint. Aborting."
  fi
  # Cross-check our tarball line is in the signed SHA256SUMS.
  grep -q "$SRC_SHA256  source/$SRC_TARBALL" "$DL_DIR/SHA256SUMS" \
    || grep -q "$SRC_SHA256" "$DL_DIR/SHA256SUMS" \
    || warn "could not find our tarball line in SHA256SUMS (format may differ); pinned-hash check already passed"
  unset GNUPGHOME
else
  warn "GPG skipped — integrity rests on the pinned SHA256 (already verified)"
fi

# =============================================================================
# PHASE 2 — Extract (idempotent)
# =============================================================================
step "Phase 2: extract source"
if [ -d "$SRC_DIR" ] && [ -f "$SRC_DIR/mach" ]; then
  ok "source already extracted: $SRC_DIR"
else
  echo "  extracting $SRC_TARBALL (~5-6 GB) ..."
  ( cd "$WORK_DIR" && tar -xf "$TARBALL_PATH" )
  # Mozilla drops the 'esr' suffix in the top dir; locate it robustly.
  if [ ! -f "$SRC_DIR/mach" ]; then
    FOUND="$(find "$WORK_DIR" -maxdepth 1 -type d -name 'firefox-140*' | head -1)"
    [ -n "$FOUND" ] && [ -f "$FOUND/mach" ] || die "extracted tree not found / no mach in $WORK_DIR"
    SRC_DIR="$FOUND"; PATCH_SENTINEL="$SRC_DIR/.framatome-patches-applied"
  fi
  ok "extracted to: $SRC_DIR"
fi

# =============================================================================
# PHASE 3 — Apply the 13 WebXR patches (SEQUENTIAL, fail-loud, idempotent)
# =============================================================================
step "Phase 3: apply WebXR patches (sequential, fail-loud)"
cd "$SRC_DIR"

# CRITICAL: give the source tree its OWN git repo. Two hard-won reasons:
#   (1) `git apply` run INSIDE an outer repo (this build dir is gitignored under
#       the superproject) silently NO-OPS on untracked files — exit 0, zero
#       changes. A patchless "build" would compile a stock engine with no WebXR.
#       An inner .git makes git apply resolve against THIS tree (correct).
#   (2) A committed stock baseline lets us `git reset --hard` to recover from a
#       partial/failed apply, and enables `git apply --3way` for re-pinning.
GIT="git -c user.email=build@framatome.local -c user.name=FramatomeBuild"
if [ ! -e .git ]; then
  echo "  initializing stock baseline (git) — one-time, ~1-2 min ..."
  git init -q
  $GIT add -A
  $GIT commit -qm "stock firefox-$MOZ_VERSION" >/dev/null
  ok "stock baseline committed"
else
  ok "git baseline already present"
fi

if [ -f "$PATCH_SENTINEL" ]; then
  ok "patches already applied (sentinel present) — skipping"
else
  # Restore pristine stock (clears any partial apply from a prior failed run).
  $GIT reset -q --hard >/dev/null; $GIT clean -qfd >/dev/null

  # SEQUENTIAL apply — ORDER MATTERS. Later patches build on lines added by
  # earlier ones (e.g. 0012/0013/0014 extend the controller switch 0010 opens;
  # 0016 bumps kVRExternalVersion 20->21 only AFTER an earlier patch moves stock
  # 19->20). An INDEPENDENT per-patch --check against stock falsely rejects ~7 of
  # 13 — so we do NOT pre-check; the in-order real apply IS the gate. git apply
  # is atomic per patch; the first failure resets to stock and aborts loud.
  echo "  applying $EXPECT_PATCH_COUNT patches in order ..."
  while IFS= read -r p; do
    if $GIT apply -p1 --whitespace=nowarn "$p"; then
      printf '    %s✓%s  %s\n' "$c_grn" "$c_0" "$(basename "$p")"
    else
      printf '    %s✗%s  %s\n' "$c_red" "$c_0" "$(basename "$p")"
      $GIT apply -p1 --whitespace=nowarn "$p" 2>&1 | sed 's/^/        /' | head -4
      $GIT reset -q --hard >/dev/null; $GIT clean -qfd >/dev/null
      die "patch $(basename "$p") failed to apply IN SEQUENCE to $MOZ_VERSION.
  Tree was reset to pristine stock. This patch set does not match this source.
  Options: (a) confirm the source is exactly $MOZ_VERSION (hash already checked);
  (b) re-pin gecko-patches/ to a commit cut against this point release;
  (c) 3-way merge the offender: 'git apply --3way $(basename "$p")' then resolve.
  See BUILD_PLAN.md > Troubleshooting > patch reject."
    fi
  done < <(find "$PATCHES_DIR" -maxdepth 1 -name '*.patch' | sort)

  REJ="$(find . -name '*.rej' 2>/dev/null | head -5)"
  [ -z "$REJ" ] || die "found .rej files after apply: $REJ"
  touch "$PATCH_SENTINEL"
  ok "all $EXPECT_PATCH_COUNT patches applied cleanly, in sequence"
fi

# =============================================================================
# PHASE 4 — ABI assertion (the protocol must match the app)
# =============================================================================
step "Phase 4: assert ExternalVR ABI matches the app"
HDR="gfx/vr/external_api/moz_external_vr.h"
[ -f "$HDR" ] || die "patched header not found: $HDR"
# Extract the value AFTER the '=' — NOT the first number on the line, which would
# wrongly grab the 32 in "int32_t" (the type) from `... int32_t kVRExternalVersion = 21;`.
GOT_VER="$(sed -nE 's/.*kVRExternalVersion[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$HDR" | head -1)"
GOT_SHMEM="$(sed -nE 's/.*SHMEM_VERSION[[:space:]]+"([0-9.]+)".*/\1/p' "$HDR" | head -1)"
[ "$GOT_VER" = "$EXPECT_VREXTERNAL_VERSION" ] \
  || die "kVRExternalVersion is '$GOT_VER', expected '$EXPECT_VREXTERNAL_VERSION'. Patch 0016 did not take, or the patch set bumped the protocol. The app header ($APP_HEADER) must move in lockstep — see ENGINE_OWNERSHIP.md Track 2."
[ "$GOT_SHMEM" = "$EXPECT_SHMEM_VERSION" ] \
  || die "SHMEM_VERSION is '$GOT_SHMEM', expected '$EXPECT_SHMEM_VERSION'."
ok "patched engine ABI: kVRExternalVersion=$GOT_VER SHMEM_VERSION=$GOT_SHMEM (matches app)"

# Cross-check the app header still agrees (catches drift the other direction).
if [ -f "$APP_HEADER" ]; then
  APP_VER="$(sed -nE 's/.*kVRExternalVersion[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$APP_HEADER" | head -1)"
  [ "$APP_VER" = "$GOT_VER" ] || die "app header $APP_HEADER reports kVRExternalVersion=$APP_VER but engine is $GOT_VER — ABI MISMATCH. Reconcile before building."
  ok "app header agrees: kVRExternalVersion=$APP_VER"
fi

# ----- preflight complete -----
if [ "$MODE" = "check" ]; then
  printf '\n%s================ PREFLIGHT PASSED ================%s\n' "$c_grn" "$c_0"
  printf 'Source verified, all %s patches apply cleanly, ABI matches the app.\n' "$EXPECT_PATCH_COUNT"
  printf 'Ready for the full build. Re-run WITHOUT --check to compile (2-4 h).\n'
  exit 0
fi

# =============================================================================
# PHASE 5 — Toolchain prep + mozconfig
# =============================================================================
step "Phase 5: toolchain + mozconfig"

# Rust via rustup (NOT Homebrew). Add the Android target we ship (aarch64).
if command -v rustup >/dev/null 2>&1; then
  rustup target add aarch64-linux-android >/dev/null 2>&1 || true
  ok "rust: $(rustc --version 2>/dev/null || echo 'present') (+ aarch64-linux-android target)"
else
  warn "rustup not found. Install: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
  warn "mach bootstrap can also provision Rust; continuing and letting bootstrap handle it."
fi

# mozconfig — OMNI build (NOT --enable-geckoview-lite). Verified empirically:
# our shipping geckoview-default AAR contains assets/omni.ja + jni/.../libxul.so,
# i.e. it's an omni WithGeckoBinaries build renamed to geckoview-default. We
# reproduce that and normalize the coordinate in Phase 9 — we do NOT change the
# build flavor (lite would produce a genuinely different, runtime-different bundle).
cat > "$SRC_DIR/mozconfig" <<EOF
ac_add_options --enable-project=mobile/android
ac_add_options --target=aarch64
ac_add_options --enable-linker=lld
ac_add_options --disable-debug
ac_add_options --enable-optimize
ac_add_options --enable-release
mk_add_options MOZ_OBJDIR=@TOPSRCDIR@/obj-aarch64
EOF
ok "wrote mozconfig (Android arm64, opt, omni)"

# =============================================================================
# PHASE 6 — mach bootstrap (non-interactive; provisions JDK17/SDK/NDK r28b)
# =============================================================================
step "Phase 6: mach bootstrap (GeckoView/Firefox for Android)"
echo "  this provisions JDK 17, Android SDK, NDK r28b, and Rust targets into ~/.mozbuild ..."
"$PYBIN" mach --no-interactive bootstrap --application-choice="GeckoView/Firefox for Android" \
  || die "mach bootstrap failed. If it errored fetching host tooling, install Rosetta and retry."
ok "bootstrap complete"

# =============================================================================
# PHASE 7 — Compile (the long pole: ~2-4 h cold)
# =============================================================================
step "Phase 7: mach build (cross-compile to Android arm64 — hours)"
echo "  start: $(date)"
"$PYBIN" mach build || die "mach build failed — see the error above. Incremental rebuilds are fast; fix and re-run."
echo "  end:   $(date)"
ok "compile complete"

# =============================================================================
# PHASE 8 — Locate the published AAR pair
# =============================================================================
step "Phase 8: locate the published GeckoView AARs"

# `mach build` already publishes geckoview + exoplayer2 to the objdir's own maven
# repo ($topobjdir/gradle/maven), per geckoview/build.gradle's `repositories { maven
# { url = "${topobjdir}/gradle/maven" } }`. So normally NO publish task is needed.
# (NB: GeckoView 140 dropped the old `withGeckoBinaries<Variant>` flavor — the
# publish tasks are now geckoview:publish{Release,Debug}PublicationToMavenLocal,
# used only as the fallback below.)
OBJDIR="$(ls -d "$SRC_DIR"/obj-* 2>/dev/null | head -1)"
SRC_MAVEN="$OBJDIR/gradle/maven/org/mozilla/geckoview"

if ! find "$SRC_MAVEN" -name 'geckoview-*.aar' 2>/dev/null | grep -q .; then
  warn "no AARs in objdir maven ($SRC_MAVEN) — publishing explicitly to ~/.m2 ..."
  rm -rf "$M2" 2>/dev/null || true
  ( cd "$SRC_DIR" && "$PYBIN" mach gradle \
        geckoview:publishReleasePublicationToMavenLocal \
        exoplayer2:publishReleasePublicationToMavenLocal ) \
  || ( cd "$SRC_DIR" && "$PYBIN" mach gradle \
        geckoview:publishDebugPublicationToMavenLocal \
        exoplayer2:publishDebugPublicationToMavenLocal ) \
  || die "publish failed. List the real task names with:
    $PYBIN mach gradle geckoview:tasks --all | grep -i publish"
  SRC_MAVEN="$M2"
fi
ok "AAR source repo: $SRC_MAVEN"

# =============================================================================
# PHASE 9 — Locate, verify internals, normalize coordinate
# =============================================================================
step "Phase 9: verify + normalize the published artifacts"

# 9a. Find the geckoview AAR that actually embeds the native engine (libxul.so).
GV_AAR=""
while IFS= read -r a; do
  if unzip -l "$a" 2>/dev/null | grep -q 'jni/arm64-v8a/libxul.so'; then GV_AAR="$a"; break; fi
done < <(find "$SRC_MAVEN" -name 'geckoview*-*.aar' ! -name 'geckoview-exoplayer2*' | sort)
[ -n "$GV_AAR" ] || die "no published geckoview AAR contains jni/arm64-v8a/libxul.so — WithGeckoBinaries did not embed the engine. Check the publish task variant."
ok "engine AAR: $(basename "$GV_AAR")"

# 9b. Assert it also carries the omnijar (the JS resources) — proves a real omni bundle.
unzip -l "$GV_AAR" 2>/dev/null | grep -q 'assets/omni.ja' \
  || die "engine AAR lacks assets/omni.ja — not a usable omni bundle. Aborting."
ok "engine AAR contains assets/omni.ja + libxul.so"

# 9c. Find the exoplayer2 AAR (may or may not be a separate artifact).
EXO_AAR="$(find "$SRC_MAVEN" -name 'geckoview-exoplayer2*-*.aar' | sort | head -1)"
HAVE_EXO="no"; [ -n "$EXO_AAR" ] && HAVE_EXO="yes"
[ "$HAVE_EXO" = "yes" ] && ok "exoplayer2 AAR: $(basename "$EXO_AAR")" \
                        || warn "no separate exoplayer2 AAR — classes are bundled in geckoview; will drop the POM dependency"

# 9d. Stage the canonical coordinate into gecko-maven, renaming to geckoview-default:GV_VERSION.
GV_DST="$GECKO_MAVEN/org/mozilla/geckoview/geckoview-default/$GV_VERSION"
EXO_DST="$GECKO_MAVEN/org/mozilla/geckoview/geckoview-exoplayer2-default/$GV_VERSION"
# Remove any prior 140 staging (keep the 128 artifact untouched until the bump).
rm -rf "$GV_DST" "$EXO_DST"
mkdir -p "$GV_DST" "$EXO_DST"

cp "$GV_AAR" "$GV_DST/geckoview-default-$GV_VERSION.aar"
# Preserve Mozilla's generated POM next to ours, in case a transitive dep is needed later.
GV_SRC_POM="${GV_AAR%.aar}.pom"
[ -f "$GV_SRC_POM" ] && cp "$GV_SRC_POM" "$GV_DST/geckoview-default-$GV_VERSION.mozilla-generated.pom.bak"

# 9e. Write the geckoview-default POM by NORMALIZING Mozilla's generated POM.
#     This is load-bearing: GeckoView 140's runtime (GeckoRuntime.init ->
#     DebugConfig.fromFile) needs org.yaml:snakeyaml, plus androidx core/lifecycle
#     and play-services-fido. A minimal POM drops those -> ClassNotFoundException
#     at launch. We transform the generated POM's coordinate (omni->default,
#     snapshot version -> $GV_VERSION for the project AND the exoplayer2 dep) and
#     keep ALL its transitive dependencies.
GV_POM="$GV_DST/geckoview-default-$GV_VERSION.pom"
SRC_ART="$(sed -nE 's@.*<artifactId>(geckoview-[a-z0-9-]*)</artifactId>.*@\1@p' "$GV_SRC_POM" 2>/dev/null | head -1)"
SRC_VER="$(sed -nE 's@.*<version>([0-9][^<]*-SNAPSHOT)</version>.*@\1@p' "$GV_SRC_POM" 2>/dev/null | head -1)"
if [ -f "$GV_SRC_POM" ] && [ -n "$SRC_ART" ] && [ -n "$SRC_VER" ]; then
  sed -e "s/${SRC_ART}/geckoview-default/g" -e "s/${SRC_VER}/$GV_VERSION/g" "$GV_SRC_POM" > "$GV_POM"
  # sanity: must name geckoview-default at GV_VERSION, carry snakeyaml, no SNAPSHOT left.
  grep -q "<artifactId>geckoview-default</artifactId>" "$GV_POM" \
    && grep -q "snakeyaml" "$GV_POM" \
    && ! grep -q "SNAPSHOT" "$GV_POM" \
    || die "POM normalization failed (artifactId/snakeyaml/SNAPSHOT check). Inspect $GV_POM"
  ok "geckoview POM derived from Mozilla's generated POM (full transitive deps incl. snakeyaml)"
else
  warn "Mozilla generated POM unavailable — writing MINIMAL POM (RISK: GeckoRuntime needs snakeyaml et al.)"
  cat > "$GV_POM" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.mozilla.geckoview</groupId>
  <artifactId>geckoview-default</artifactId>
  <version>$GV_VERSION</version>
  <packaging>aar</packaging>
  <dependencies>
    <dependency><groupId>org.mozilla.geckoview</groupId><artifactId>geckoview-exoplayer2-default</artifactId><version>$GV_VERSION</version><type>aar</type><scope>runtime</scope></dependency>
    <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><version>2.2</version></dependency>
  </dependencies>
</project>
EOF
fi

if [ "$HAVE_EXO" = "yes" ]; then
  cp "$EXO_AAR" "$EXO_DST/geckoview-exoplayer2-default-$GV_VERSION.aar"
  cat > "$EXO_DST/geckoview-exoplayer2-default-$GV_VERSION.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.mozilla.geckoview</groupId>
  <artifactId>geckoview-exoplayer2-default</artifactId>
  <version>$GV_VERSION</version>
  <packaging>aar</packaging>
  <name>GeckoView ExoPlayer2 support</name>
  <description>Framatome WebXR-patched GeckoView ExoPlayer2 backend (Firefox ESR $MOZ_VERSION).</description>
</project>
EOF
else
  rmdir "$EXO_DST" 2>/dev/null || true
fi
ok "staged geckoview-default:$GV_VERSION into gecko-maven"

# 9f. Provenance + checksums (release version → no maven-metadata.xml needed; file repo resolves by path).
GV_AAR_SHA="$(sha256_of "$GV_DST/geckoview-default-$GV_VERSION.aar")"
echo "$GV_AAR_SHA  geckoview-default-$GV_VERSION.aar" > "$GV_DST/geckoview-default-$GV_VERSION.aar.sha256"
EXO_AAR_SHA="(none)"
if [ "$HAVE_EXO" = "yes" ]; then
  EXO_AAR_SHA="$(sha256_of "$EXO_DST/geckoview-exoplayer2-default-$GV_VERSION.aar")"
  echo "$EXO_AAR_SHA  geckoview-exoplayer2-default-$GV_VERSION.aar" > "$EXO_DST/geckoview-exoplayer2-default-$GV_VERSION.aar.sha256"
fi
NDK_DIR="$(find "$HOME/.mozbuild" -maxdepth 1 -type d -name 'android-ndk-*' 2>/dev/null | head -1)"
{
  echo "# Framatome Player engine — build provenance (auto-generated by build-gecko-webxr.sh)"
  echo "built_on            = $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "firefox_version     = $MOZ_VERSION"
  echo "source_tarball      = $SRC_TARBALL"
  echo "source_sha256       = $SRC_SHA256"
  echo "patch_set_commit    = $(grep -E '^pinned_commit ' "$PINNED_FILE" | awk '{print $3}')"
  echo "patch_count         = $EXPECT_PATCH_COUNT"
  echo "abi_version         = kVRExternalVersion=$GOT_VER SHMEM_VERSION=$GOT_SHMEM"
  echo "host                = macOS $HOST_ARCH"
  echo "rustc               = $(rustc --version 2>/dev/null || echo unknown)"
  echo "ndk                 = $(basename "${NDK_DIR:-unknown}")"
  echo "gv_coordinate       = org.mozilla.geckoview:geckoview-default:$GV_VERSION"
  echo "geckoview_aar_sha256= $GV_AAR_SHA"
  echo "exoplayer2_aar_sha256= $EXO_AAR_SHA"
} > "$PROVENANCE"
ok "wrote provenance: $PROVENANCE"

# =============================================================================
# PHASE 10 — Done; next step is the one-line consumer bump
# =============================================================================
printf '\n%s================ BUILD COMPLETE ================%s\n' "$c_grn" "$c_0"
cat <<EOF
Vendored engine:
  $GV_DST/geckoview-default-$GV_VERSION.aar
  sha256 $GV_AAR_SHA
$( [ "$HAVE_EXO" = "yes" ] && echo "  + geckoview-exoplayer2-default-$GV_VERSION.aar" )

FINAL STEP (one line) — point the app at the new engine in app/build.gradle:
  geckoImplementation "org.mozilla.geckoview:geckoview-default:$GV_VERSION"
(currently pinned to 128.14.20260314224701-SNAPSHOT)

Then compile + install on the headset and run the immersive-vr smoke test:
  ./gradlew assembleOculusvrArm64GeckoGenericDebug
  # adb install, Enter VR — a successful v21 handshake is the real ABI proof.

See ../BUILD_PLAN.md for verification and troubleshooting.
EOF
