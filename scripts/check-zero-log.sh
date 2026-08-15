#!/usr/bin/env bash
#
# check-zero-log.sh - mechanical zero-log gate for MindChat (ROADMAP 3.4/6.1).
#
# Usage: scripts/check-zero-log.sh [--strict-so] [--so-dir DIR] [--cargo-tree]
# (run from the repository root)
#
#   (default)   Grep every tracked Rust/Kotlin/Gradle/shell source file for
#               logging emission patterns and fail with file:line diagnostics
#               on any hit. Then run a `strings` pass over any release .so
#               found under app/src/main/jniLibs or app/build. If no .so is
#               present (e.g. a host without an NDK), print
#               "SKIPPED: no .so found" and exit 0.
#   --strict-so Treat a missing .so as a failure. CI uses this because the
#               android job always builds the native libraries.
#   --so-dir DIR Add DIR as an additional search root for libmindchat_core.so
#               (e.g. the CI artifact download directory).
#   --cargo-tree Also verify that `cargo tree` shows max_level_off +
#               release_max_level_off unified into the log and tracing crates
#               (off by default: it runs cargo and is slow on cold caches).
#
# Exit codes: 0 = clean, 1 = violations found (or --strict-so with no .so),
# 2 = usage error. No external dependencies beyond git/grep/strings/find.

set -euo pipefail

STRICT_SO=0
CARGO_TREE=0
SO_DIRS=(app/src/main/jniLibs app/build)

for arg in "$@"; do
    case "$arg" in
        --strict-so) STRICT_SO=1 ;;
        --cargo-tree) CARGO_TREE=1 ;;
        --so-dir)
            echo "check-zero-log.sh: --so-dir requires a directory argument" >&2
            exit 2
            ;;
        --so-dir=*)
            SO_DIRS+=("${arg#--so-dir=}")
            ;;
        -h|--help)
            sed -n '2,24p' "$0"
            exit 0
            ;;
        *)
            echo "check-zero-log.sh: unknown argument: $arg" >&2
            echo "usage: scripts/check-zero-log.sh [--strict-so] [--so-dir DIR] [--cargo-tree]" >&2
            exit 2
            ;;
    esac
done

SCRIPT_PATH="scripts/check-zero-log.sh"
FAILED=0

# ---------------------------------------------------------------------------
# 1. Source scan: git ls-files + grep.
# ---------------------------------------------------------------------------
# Patterns are the emission points the product contract forbids: Rust macros,
# the log/tracing facade call syntax, Kotlin Log/Timber/System.* emission, and
# the vestigial diagnostics-zip name (no producer exists; a reintroduction is
# a regression). Only source extensions are scanned so ROADMAP/CONTRIBUTING
# prose that quotes these strings stays documentation, not a tripwire.
PATTERNS='log::|tracing::|println!|eprintln!|dbg!|android\.util\.Log|Timber\.|System\.(out|err)\.|mindchat-diagnostics'

check_file() {
    local file="$1"
    if grep -HnE "$PATTERNS" "$file" 2>/dev/null; then
        FAILED=1
    fi
}

mapfile -t FILES < <(git ls-files | grep -E '\.(rs|kt|kts|gradle|sh)$' | grep -v "^$SCRIPT_PATH$" || true)
for file in "${FILES[@]}"; do
    check_file "$file"
done

if [ "$FAILED" -eq 1 ]; then
    echo "check-zero-log.sh: source scan failed (see diagnostics above)" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# 2. strings pass over the shipped native libraries.
# ---------------------------------------------------------------------------
# The purged vendor paths serialized raw stanzas: RECV/SEND traces and the
# "Attempting connection" DNS log. Their format strings must not survive in
# the compiled .so, in any ABI.
SO_FILES=()
for dir in "${SO_DIRS[@]}"; do
    while IFS= read -r so; do
        SO_FILES+=("$so")
    done < <(find "$dir" -name 'libmindchat_core.so' -type f 2>/dev/null || true)
done

if [ "${#SO_FILES[@]}" -eq 0 ]; then
    if [ "$STRICT_SO" -eq 1 ]; then
        echo "check-zero-log.sh: --strict-so set but no libmindchat_core.so found under: ${SO_DIRS[*]}" >&2
        exit 1
    fi
    echo "check-zero-log.sh: SKIPPED: no .so found (host without NDK; CI runs the authoritative check)"
else
    SO_PATTERNS='RECV|SEND |Attempting connection'
    for so in "${SO_FILES[@]}"; do
        if strings "$so" 2>/dev/null | grep -E "$SO_PATTERNS" >/dev/null 2>&1; then
            echo "check-zero-log.sh: $so: contains forbidden log format strings:" >&2
            strings "$so" 2>/dev/null | grep -E "$SO_PATTERNS" | sed 's/^/  /' >&2
            FAILED=1
        fi
    done

    if [ "$FAILED" -eq 1 ]; then
        echo "check-zero-log.sh: .so strings check failed (see diagnostics above)" >&2
        exit 1
    fi
    echo "check-zero-log.sh: .so strings check passed (${#SO_FILES[@]} libraries)"
fi

# ---------------------------------------------------------------------------
# 3. (Optional) compile-time kill switch via cargo tree.
# ---------------------------------------------------------------------------
if [ "$CARGO_TREE" -eq 1 ]; then
    TREE_OUT=$(cargo tree -p mindchat-core --offline -e features 2>/dev/null || true)
    if [ -z "$TREE_OUT" ]; then
        echo "check-zero-log.sh: --cargo-tree could not run cargo tree (network/offline?)" >&2
        exit 1
    fi
    for crate in 'log v' 'tracing v'; do
        case "$TREE_OUT" in
            *"$crate"*) ;;
            *)
                echo "check-zero-log.sh: cargo tree: $crate not in the tree" >&2
                FAILED=1
                ;;
        esac
    done
    for feature in 'max_level_off' 'release_max_level_off'; do
        case "$TREE_OUT" in
            *"feature \"$feature\""*) ;;
            *)
                echo "check-zero-log.sh: cargo tree: feature \"$feature\" not unified" >&2
                FAILED=1
                ;;
        esac
    done
    if [ "$FAILED" -eq 1 ]; then
        echo "check-zero-log.sh: compile-time kill switch not confirmed (see diagnostics above)" >&2
        exit 1
    fi
    echo "check-zero-log.sh: cargo tree: max_level_off + release_max_level_off unified"
fi

echo "check-zero-log.sh: PASS"
exit 0
