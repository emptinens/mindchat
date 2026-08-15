#!/usr/bin/env bash
#
# verify-release.sh - release artifact gates for MindChat (ROADMAP 6.4).
#
# Usage: scripts/verify-release.sh [--strict] [--apk-dir DIR] [--jni-dir DIR]
# (run from the repository root)
#
#   --strict      Treat every missing input (APKs, native libraries, dex
#                 tooling) as a failure. CI always has all of them. Without
#                 --strict a local host without an NDK skips the .so gates
#                 with "SKIPPED", mirroring scripts/check-zero-log.sh.
#   --apk-dir DIR APK directory to audit
#                 (default: app/build/outputs/apk/release).
#   --jni-dir DIR Additional search root for libmindchat_core.so; repeatable.
#
# Checks:
#   1. APK size budgets: universal <= 33MB, arm64-v8a <= 16.5MB,
#      x86_64 <= 16.5MB, armeabi-v7a <= 13.5MB.
#   2. Signature: apksigner verify on every APK; the v2 scheme must verify
#      true (release signing is v2-only, but a local debug-signed APK also
#      carries v2, so the gate is portable).
#   3. Keep-rule audit: mapping.txt / seeds.txt / usage.txt exist, and the
#      JNA keep rules survived R8 (Structure, Structure$FieldOrder and the
#      generated com/mindchat/core/RustBuffer are present in the dex,
#      checked with dexdump).
#   4. Exported-symbol audit: readelf --dyn-syms on the stripped jniLibs
#      .so; the ffi_mindchat_core_* / uniffi_mindchat_core_* exports must
#      survive llvm-strip --strip-all.
#   5. Writes sha256sums.txt next to the audited APKs.
#
# Exit codes: 0 = pass, 1 = any gate failed, 2 = usage error.

set -euo pipefail

STRICT=0
APK_DIR="app/build/outputs/apk/release"
SO_DIRS=(app/build/generated/jniLibs app/src/main/jniLibs)

while [ "$#" -gt 0 ]; do
    case "$1" in
        --strict) STRICT=1 ;;
        --apk-dir)
            [ "$#" -ge 2 ] || { echo "verify-release.sh: --apk-dir requires a directory" >&2; exit 2; }
            APK_DIR="$2"; shift ;;
        --apk-dir=*) APK_DIR="${1#--apk-dir=}" ;;
        --jni-dir)
            [ "$#" -ge 2 ] || { echo "verify-release.sh: --jni-dir requires a directory" >&2; exit 2; }
            SO_DIRS+=("$2"); shift ;;
        --jni-dir=*) SO_DIRS+=("${1#--jni-dir=}") ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *)
            echo "verify-release.sh: unknown argument: $1" >&2
            echo "usage: scripts/verify-release.sh [--strict] [--apk-dir DIR] [--jni-dir DIR]" >&2
            exit 2
            ;;
    esac
    shift
done

SCRIPT_PATH="scripts/verify-release.sh"
FAILED=0

# apksigner is a JVM tool; make sure the configured JDK is reachable even
# when the caller only exported JAVA_HOME (the standard local setup).
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi

# ---------------------------------------------------------------------------
# Tool discovery: apksigner/dexdump from the SDK build-tools (newest wins),
# readelf from the system. Each gate SKIPs when its tool is missing and
# --strict is off. SDK roots come from ANDROID_HOME, ANDROID_SDK_ROOT, and
# the sdk.dir line of local.properties (Android Studio / local setup).
# ---------------------------------------------------------------------------
SDK_ROOTS=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}")
if [ -f local.properties ]; then
    SDK_LOCAL=$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)
    [ -n "$SDK_LOCAL" ] && SDK_ROOTS+=("$SDK_LOCAL")
fi
TOOL_APKSIGNER=""
TOOL_DEXDUMP=""
for sdk in "${SDK_ROOTS[@]}"; do
    [ -n "$sdk" ] || continue
    for bt in "$sdk"/build-tools/*; do
        [ -d "$bt" ] || continue
        # Later entries overwrite, so the newest build-tools version wins.
        if [ -x "$bt/apksigner" ]; then TOOL_APKSIGNER="$bt/apksigner"; fi
        if [ -x "$bt/dexdump" ]; then TOOL_DEXDUMP="$bt/dexdump"; fi
    done
done
[ -n "$TOOL_APKSIGNER" ] || TOOL_APKSIGNER=$(command -v apksigner 2>/dev/null || true)
[ -n "$TOOL_DEXDUMP" ] || TOOL_DEXDUMP=$(command -v dexdump 2>/dev/null || true)
TOOL_READELF=$(command -v readelf 2>/dev/null || true)

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# ---------------------------------------------------------------------------
# 1. APK size budgets (ROADMAP 6.4 hard gates).
# ---------------------------------------------------------------------------
# Only the release variants AGP actually emits for this project: the three
# per-ABI splits, the universal APK, and the plain (non-split) fallback name.
# Stale files from older build configs in the same dir are ignored.
mapfile -t APKS < <(find "$APK_DIR" -maxdepth 1 -name '*.apk' -type f 2>/dev/null \
    | grep -E '/(app-universal-release|app-(arm64-v8a|armeabi-v7a|x86_64)-release|app-release)\.apk$' \
    | sort || true)

if [ "${#APKS[@]}" -eq 0 ]; then
    if [ "$STRICT" -eq 1 ]; then
        echo "verify-release.sh: --strict set but no APKs found under $APK_DIR" >&2
        exit 1
    fi
    echo "verify-release.sh: SKIPPED: no APKs under $APK_DIR (run :app:assembleRelease first)"
    exit 0
fi

check_size() {
    local apk="$1" label="$2" max_bytes="$3" human="$4"
    local size mb
    size=$(stat -c%s "$apk")
    mb=$(awk -v s="$size" 'BEGIN { printf "%.2f", s / 1048576 }')
    echo "verify-release.sh: size $label: ${mb}MB (budget ${human})"
    if [ "$size" -gt "$max_bytes" ]; then
        echo "verify-release.sh: FAIL: $label APK is ${mb}MB, over the ${human} budget" >&2
        FAILED=1
    fi
}

find_apk() {
    local needle="$1"
    for apk in "${APKS[@]}"; do
        [ "$(basename "$apk")" = "$needle" ] && { echo "$apk"; return 0; }
    done
    return 1
}

UNIVERSAL=$(find_apk "app-universal-release.apk" || true)
ARM64=$(find_apk "app-arm64-v8a-release.apk" || true)
X86_64=$(find_apk "app-x86_64-release.apk" || true)
ARM32=$(find_apk "app-armeabi-v7a-release.apk" || true)

# 1 MiB = 1048576 bytes.
B_UNIVERSAL=$((33 * 1048576))
B_ARM64=$((165 * 1048576 / 10))
B_ARM32=$((135 * 1048576 / 10))

if [ -n "$UNIVERSAL" ]; then check_size "$UNIVERSAL" "universal" "$B_UNIVERSAL" "33.0MB"; else
    echo "verify-release.sh: universal APK missing (was assembleRelease run?)"
    [ "$STRICT" -eq 1 ] && { echo "verify-release.sh: FAIL: universal APK missing under $APK_DIR" >&2; FAILED=1; }
fi
if [ -n "$ARM64" ]; then check_size "$ARM64" "arm64-v8a" "$B_ARM64" "16.5MB"; else
    echo "verify-release.sh: SKIPPED: arm64-v8a split APK not found (local host without NDK still emits splits; verify in CI)"
    [ "$STRICT" -eq 1 ] && { echo "verify-release.sh: FAIL: arm64-v8a split APK missing under $APK_DIR" >&2; FAILED=1; }
fi
if [ -n "$X86_64" ]; then check_size "$X86_64" "x86_64" "$B_ARM64" "16.5MB"; else
    echo "verify-release.sh: SKIPPED: x86_64 split APK not found"
    [ "$STRICT" -eq 1 ] && { echo "verify-release.sh: FAIL: x86_64 split APK missing under $APK_DIR" >&2; FAILED=1; }
fi
if [ -n "$ARM32" ]; then check_size "$ARM32" "armeabi-v7a" "$B_ARM32" "13.5MB"; else
    echo "verify-release.sh: SKIPPED: armeabi-v7a split APK not found"
    [ "$STRICT" -eq 1 ] && { echo "verify-release.sh: FAIL: armeabi-v7a split APK missing under $APK_DIR" >&2; FAILED=1; }
fi

# ---------------------------------------------------------------------------
# 2. Signature gate: apksigner verify, v2 must verify true.
# ---------------------------------------------------------------------------
if [ -z "$TOOL_APKSIGNER" ]; then
    echo "verify-release.sh: SKIPPED: apksigner not found (set ANDROID_HOME or add it to PATH)"
    [ "$STRICT" -eq 1 ] && { echo "verify-release.sh: FAIL: apksigner not found" >&2; FAILED=1; }
else
    for apk in "${APKS[@]}"; do
        if ! "$TOOL_APKSIGNER" verify --print-certs --verbose "$apk" >"$TMP_DIR/sig.out" 2>&1; then
            echo "verify-release.sh: FAIL: apksigner verify rejected $(basename "$apk"):" >&2
            sed 's/^/  /' "$TMP_DIR/sig.out" >&2
            FAILED=1
            continue
        fi
        if ! grep -q "Verified using v2 scheme.*: true" "$TMP_DIR/sig.out"; then
            echo "verify-release.sh: FAIL: $(basename "$apk") has no verifying v2 signature" >&2
            sed 's/^/  /' "$TMP_DIR/sig.out" >&2
            FAILED=1
        else
            v1=$(grep -oE "Verified using v1 scheme[^:]*: (true|false)" "$TMP_DIR/sig.out" | head -1 || echo "no v1 line")
            echo "verify-release.sh: signature OK: $(basename "$apk") ($v1, v2 verified)"
            grep "Signer #1 certificate DN" "$TMP_DIR/sig.out" | sed 's/^/  /' || true
        fi
    done
fi

# ---------------------------------------------------------------------------
# 3. Keep-rule audit: mapping/seeds/usage + JNA classes in the dex.
# ---------------------------------------------------------------------------
MAPPING_DIR="$APK_DIR/../../mapping/release"

if [ -f "$MAPPING_DIR/mapping.txt" ] && [ -f "$MAPPING_DIR/seeds.txt" ] && [ -f "$MAPPING_DIR/usage.txt" ]; then
    echo "verify-release.sh: keep-rule audit: mapping.txt, seeds.txt, usage.txt present"
else
    echo "verify-release.sh: FAIL: expected mapping.txt, seeds.txt and usage.txt under $MAPPING_DIR" >&2
    FAILED=1
fi

if [ -z "$TOOL_DEXDUMP" ]; then
    echo "verify-release.sh: SKIPPED: dexdump not found (set ANDROID_HOME or add it to PATH)"
    [ "$STRICT" -eq 1 ] && { echo "verify-release.sh: FAIL: dexdump not found" >&2; FAILED=1; }
elif [ -z "$UNIVERSAL" ]; then
    echo "verify-release.sh: SKIPPED: universal APK missing, cannot audit dex contents"
else
    rm -rf "$TMP_DIR/dex"; mkdir -p "$TMP_DIR/dex"
    unzip -o -q "$UNIVERSAL" 'classes*.dex' -d "$TMP_DIR/dex"
    : > "$TMP_DIR/dexdump.txt"
    for dex in "$TMP_DIR"/dex/classes*.dex; do
        [ -f "$dex" ] || continue
        "$TOOL_DEXDUMP" "$dex" >> "$TMP_DIR/dexdump.txt" 2>/dev/null || true
    done
    for needle in "com/mindchat/core/RustBuffer" "com/sun/jna/Structure" "FieldOrder"; do
        if grep -q "$needle" "$TMP_DIR/dexdump.txt"; then
            echo "verify-release.sh: keep-rule audit: '$needle' present in dex"
        else
            echo "verify-release.sh: FAIL: '$needle' missing from dex (keep rule dropped?)" >&2
            FAILED=1
        fi
    done
fi

# ---------------------------------------------------------------------------
# 4. Exported-symbol audit after strip (readelf --dyn-syms on staged .so).
#    The binding surface is 109 exported names today; require a healthy
#    majority and both prefixes so a silent strip/link regression trips the
#    gate long before the count drops to zero.
# ---------------------------------------------------------------------------
MIN_EXPORTS=80
SO_FILES=()
for dir in "${SO_DIRS[@]}"; do
    while IFS= read -r so; do
        SO_FILES+=("$so")
    done < <(find "$dir" -name 'libmindchat_core.so' -type f 2>/dev/null || true)
done

if [ "${#SO_FILES[@]}" -eq 0 ]; then
    if [ "$STRICT" -eq 1 ]; then
        echo "verify-release.sh: --strict set but no libmindchat_core.so found under: ${SO_DIRS[*]}" >&2
        exit 1
    fi
    echo "verify-release.sh: SKIPPED: no libmindchat_core.so found (host without NDK; CI runs the authoritative check)"
elif [ -z "$TOOL_READELF" ]; then
    echo "verify-release.sh: SKIPPED: readelf not found"
    [ "$STRICT" -eq 1 ] && { echo "verify-release.sh: FAIL: readelf not found" >&2; FAILED=1; }
else
    for so in "${SO_FILES[@]}"; do
        total=$("$TOOL_READELF" --dyn-syms --wide "$so" | awk '{print $8}' | grep -Ec '^(ffi_mindchat_core_|uniffi_mindchat_core_)' || true)
        ffi=$("$TOOL_READELF" --dyn-syms --wide "$so" | awk '{print $8}' | grep -Ec '^ffi_mindchat_core_' || true)
        uniffi=$("$TOOL_READELF" --dyn-syms --wide "$so" | awk '{print $8}' | grep -Ec '^uniffi_mindchat_core_' || true)
        echo "verify-release.sh: exported symbols $(basename "$so"): $total total ($ffi ffi_mindchat_core_, $uniffi uniffi_mindchat_core_)"
        if [ "$total" -lt "$MIN_EXPORTS" ] || [ "$ffi" -eq 0 ] || [ "$uniffi" -eq 0 ]; then
            echo "verify-release.sh: FAIL: $so exported-symbol count below threshold ($MIN_EXPORTS) or a prefix is missing" >&2
            FAILED=1
        fi
    done
fi

# ---------------------------------------------------------------------------
# 5. sha256sums.txt next to the audited APKs.
# ---------------------------------------------------------------------------
if [ "${#APKS[@]}" -gt 0 ]; then
    SHA_FILE="$APK_DIR/sha256sums.txt"
    (
        cd "$APK_DIR"
        sha256sum ./*.apk
    ) > "$SHA_FILE"
    echo "verify-release.sh: wrote $SHA_FILE:"
    sed 's/^/  /' "$SHA_FILE"
fi

if [ "$FAILED" -eq 1 ]; then
    echo "verify-release.sh: FAIL" >&2
    exit 1
fi
echo "verify-release.sh: PASS"
exit 0
