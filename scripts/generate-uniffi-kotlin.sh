#!/usr/bin/env sh

set -eu

if ! command -v uniffi-bindgen >/dev/null 2>&1; then
    echo "uniffi-bindgen is required; install it with: cargo install uniffi --features cli --locked" >&2
    exit 1
fi

OUT_DIR=${1:-app/build/generated/source/uniffi/main/kotlin}
case "$(uname -s)" in
    Darwin) DEFAULT_LIBRARY=target/release/libmindchat_core.dylib ;;
    *) DEFAULT_LIBRARY=target/release/libmindchat_core.so ;;
esac
LIB_PATH=${2:-$DEFAULT_LIBRARY}

cargo build --release --package mindchat-core --features uniffi
mkdir -p "$OUT_DIR"

uniffi-bindgen generate \
    --library \
    "$LIB_PATH" \
    --language kotlin \
    --out-dir "$OUT_DIR" \
    --no-format
