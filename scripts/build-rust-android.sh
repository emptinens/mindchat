#!/usr/bin/env sh

set -eu

if ! command -v cargo-ndk >/dev/null 2>&1 && ! cargo ndk --version >/dev/null 2>&1; then
    echo "cargo-ndk is required; install it with: cargo install cargo-ndk --locked" >&2
    exit 1
fi

OUT_DIR=${1:-app/src/main/jniLibs}
mkdir -p "$OUT_DIR"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    if [ -z "$SDK_ROOT" ] && [ "$(uname -s)" = "Darwin" ]; then
        SDK_ROOT="$HOME/Library/Android/sdk"
    fi
    if [ -z "$SDK_ROOT" ]; then
        # Fall back to the SDK location Gradle itself uses: sdk.dir in the
        # repo-root local.properties (never committed).
        REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
        if [ -f "$REPO_ROOT/local.properties" ]; then
            SDK_ROOT=$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties")
        fi
    fi
    PINNED_NDK="$SDK_ROOT/ndk/29.0.14206865"
    if [ -d "$PINNED_NDK" ]; then
        export ANDROID_NDK_HOME="$PINNED_NDK"
    fi
fi

cargo ndk \
    --platform 26 \
    --target arm64-v8a \
    --target armeabi-v7a \
    --target x86_64 \
    --output-dir "$OUT_DIR" \
    build --release --package mindchat-core --features uniffi

# Strip ONLY the staged jniLibs copy (ROADMAP 6.4). target/ artifacts stay
# unstripped: bindgen still reads exported symbols from the host dylib, and
# touching the cross artifacts would invalidate incremental rebuilds.
# `--strip-all` keeps the dynamic symbol table (.dynsym) by definition, so
# the exported ffi_mindchat_core_* / uniffi_mindchat_core_* names survive;
# scripts/verify-release.sh re-checks the count with readelf.
LLVM_STRIP=""
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    for candidate in "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
        if [ -x "$candidate" ]; then
            LLVM_STRIP="$candidate"
            break
        fi
    done
fi

if [ -n "$LLVM_STRIP" ]; then
    STRIPPED=0
    for so in "$OUT_DIR"/*/libmindchat_core.so; do
        if [ -f "$so" ]; then
            "$LLVM_STRIP" --strip-all "$so"
            STRIPPED=$((STRIPPED + 1))
        fi
    done
    echo "build-rust-android.sh: stripped $STRIPPED staged jniLibs with $LLVM_STRIP"
else
    # NDK ships llvm-strip, so this only fires on a broken toolchain setup.
    # Keep going (the .so is still usable, just bigger); CI always strips and
    # verify-release.sh enforces the exported-symbol count there.
    echo "build-rust-android.sh: llvm-strip not found under \$ANDROID_NDK_HOME/toolchains/llvm/prebuilt; staged jniLibs left unstripped (CI strips and verifies)" >&2
fi
