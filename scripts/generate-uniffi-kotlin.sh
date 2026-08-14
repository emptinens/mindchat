#!/usr/bin/env sh

set -eu

if ! command -v uniffi-bindgen >/dev/null 2>&1; then
    echo "uniffi-bindgen is required; install it with: cargo install uniffi --version 0.32.0 --features cli --locked" >&2
    exit 1
fi

# The generated Kotlin must match the UniFFI version pinned in Cargo.toml and
# CI; a mismatched CLI produces bindings that fail at link time.
if ! uniffi-bindgen --version 2>/dev/null | grep -q "0\.32\."; then
    echo "uniffi-bindgen 0.32.x is required; found: $(uniffi-bindgen --version 2>&1)" >&2
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
