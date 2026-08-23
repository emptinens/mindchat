#!/usr/bin/env bash
set -euo pipefail

# MindChat one-command local verification. Mirrors CI (.github/workflows/
# verify.yml) closely enough to catch regressions before pushing; CI remains
# the authoritative native assembly gate.
#
# Usage:
#   scripts/verify-local.sh               # Rust checks + full Android build
#   scripts/verify-local.sh --skip-android # Rust checks only
#
# Requirements: cargo/rustc (rust-toolchain.toml pins the toolchain) and,
# unless --skip-android, an Android SDK with sdk.dir set in local.properties
# plus a JDK under ~/tools/jdk-* (newest match is used automatically).

START_SECONDS=${SECONDS}

SKIP_ANDROID=false
if [[ "${1:-}" == "--skip-android" ]]; then
    SKIP_ANDROID=true
elif [[ -n "${1:-}" ]]; then
    echo "usage: $0 [--skip-android]" >&2
    exit 64
fi

step() {
    printf '\n===== [%s/%s] %s =====\n' "$1" "$TOTAL_STEPS" "$2"
}

TOTAL_STEPS=4
$SKIP_ANDROID && TOTAL_STEPS=3

cd "$(dirname "$0")/.."

step 1 "cargo fmt --all -- --check"
cargo fmt --all -- --check

step 2 "cargo clippy --workspace --all-targets -- -D warnings"
cargo clippy --workspace --all-targets -- -D warnings

step 3 "cargo test --workspace"
cargo test --workspace

if $SKIP_ANDROID; then
    printf '\nOK: Rust verification passed in %ss (Android skipped).\n' \
        "$((SECONDS - START_SECONDS))"
    exit 0
fi

# Newest JDK match under ~/tools/jdk-*, used inline for Gradle only so the
# rest of the environment keeps whatever JAVA_HOME it already has.
JDK_DIR="$(ls -d "$HOME"/tools/jdk-* 2>/dev/null | sort -V | tail -n 1 || true)"
if [[ -z "$JDK_DIR" ]]; then
    echo "error: no JDK found under ~/tools/jdk-*; install JDK 17+ there or run with --skip-android" >&2
    exit 1
fi
echo "using JAVA_HOME=$JDK_DIR"

step 4 "./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug"
JAVA_HOME="$JDK_DIR" ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

printf '\nOK: all checks passed in %ss.\n' "$((SECONDS - START_SECONDS))"
