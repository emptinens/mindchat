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
