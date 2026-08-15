# Contributing to MindChat

Thanks for considering a contribution. MindChat is a small, deliberately
opinionated project: a Kotlin/Compose Android shell, a Rust domain core, and a
UniFFI boundary between them. The guidelines below keep the codebase
reviewable, the verification honest, and the release bar consistent.

## Project shape

- `crates/mindchat-core/` — the Rust domain core: account, conversation,
  message, reaction, roster, capability, and persistence logic plus the
  Tokio/XMPP transport. All network and state logic lives here.
- `app/` — the Android app. Kotlin + Jetpack Compose, **Material 3
  Expressive only** (no legacy Material components, no
  `material-icons-extended`; the icons-core set is enough).
- `app/src/main/java/com/mindchat/app/MindChatGateway.kt` — the single
  presentation contract the UI talks to. `NativeMindChatGateway` wraps the
  generated UniFFI `MindChatCoreHandle`; `PreviewMindChatGateway` is the
  JVM-runnable twin used by previews and unit tests.
- Decision logic that both gateways must share lives in one place (for
  example `GatewayInput.kt`). Keep it that way: duplicated inline logic
  between the two implementations has already drifted once.
- `vendor/tokio-xmpp/` — a deliberate, patched vendored copy of the XMPP
  library, selected via `[patch.crates-io]` in the root `Cargo.toml` (see the
  rationale comment there): the patch surfaces terminal auth failure as
  `Suspended` instead of silent retry. Keep vendor diffs minimal and marked
  `// MindChat patch:`. Upstreaming the patch is tracked in `ROADMAP.md`.
- The repository has **no `docs/` directory by design**. Canonical knowledge
  lives in `README.md`, `ROADMAP.md`, `CHANGELOG.md`, this file, and code
  comments; session notes belong in git commit messages.
- `app/build/generated/source/uniffi/` — generated Kotlin bindings, produced
  by `scripts/generate-uniffi-kotlin.sh` (uniffi-bindgen 0.32.x). Never edit
  generated files; regenerate and commit the source side instead.

## Development setup

- JDK 17 or newer (the project compiles at target 17).
- Android SDK Platform 36 and Build-Tools 35.0.0 / 36.0.0.
- Rust toolchain pinned by `rust-toolchain.toml`.
- `uniffi-bindgen` 0.32.0 on `PATH` for Kotlin binding regeneration:
  `cargo install uniffi --version 0.32.0 --features cli --locked`.
- NDK (plus `cargo-ndk`) is required for the native Android build; without it
  you can still build the app with the previously built `jniLibs` and the
  regenerated bindings — but CI (`scripts/build-rust-android.sh`) is the
  authoritative native assembly.

## Build and test

Native build (ABI targets: `aarch64-linux-android`, `armv7-linux-androideabi`,
`x86_64-linux-android`; UniFFI pinned at 0.32.0; see
`rust-toolchain.toml` and `gradle/libs.versions.toml`):

```sh
scripts/build-rust-android.sh   # cargo-ndk cross-build + staging jniLibs
scripts/generate-uniffi-kotlin.sh  # uniffi-bindgen -> generated Kotlin
```

Rust:

```sh
cargo test --all --all-targets
cargo test --no-default-features
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

Live suites are opt-in and hit real servers; the registration gate skips
unless enabled:

```sh
MINDCHAT_LIVE=1 cargo test --package mindchat-core --test live_ffi --test live_login
MINDCHAT_LIVE_REGISTER=1 cargo test --package mindchat-core --test live_register
# MINDCHAT_LIVE_SERVER overrides the default (jabber.ru)
```

Android (JVM unit tests and lint; the UniFFI regen task runs first):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug \
    :app:compileDebugAndroidTestKotlin
./gradlew :app:assembleDebug
```

Regenerate the Kotlin bindings after any change to the FFI surface:

```sh
scripts/generate-uniffi-kotlin.sh
```

## Definition of done for any change

1. Rust: all-features and no-default tests pass, `clippy -- -D warnings` is
   clean, `cargo fmt --check` passes. FFI changes come with handle-level unit
   tests; network behavior that cannot be faked gets a gated live test.
2. Android: `compileDebugKotlin`, `testDebugUnitTest`, `lintDebug`, and
   `compileDebugAndroidTestKotlin` pass. New gateway behavior is pinned
   through the public `MindChatGateway` contract (JVM-runnable via
   `PreviewMindChatGateway`), not only through private helpers.
3. Strings: every new UI string exists in both `values/strings.xml` and
   `values-ru/strings.xml` (keys must match 1:1).
4. Material rule: M3 Expressive only, icons-core only.
5. Zero-log rule: no logging statements of any kind (no `println`,
   `eprintln`, `android.util.Log`, `Timber`, `env_logger`, `tracing`, log
   files, or diagnostics zips) anywhere in the tracked tree. Debugging
   happens through typed errors, unit tests, and the gated live suite.
   Enforced mechanically by `scripts/check-zero-log.sh` in CI (see the
   "Zero-log gate" section below) and by the compile-time kill switch
   (`max_level_off`/`release_max_level_off` on the direct `log` and
   `tracing` deps of `mindchat-core`).
6. Docs: user-visible behavior changes are reflected in `CHANGELOG.md`
   (Unreleased section), and `ROADMAP.md` when the roadmap moves.

## Zero-log gate

The product contract (ROADMAP 3.4) forbids every logging emission point.
`scripts/check-zero-log.sh` enforces it mechanically:

- **Source scan.** `git ls-files` over tracked Rust/Kotlin/Gradle/shell
  sources, rejecting `log::`, `tracing::`, `println!`, `eprintln!`, `dbg!`,
  `android.util.Log`, `Timber`, `System.out`/`System.err`, and the
  vestigial `mindchat-diagnostics` name. Failures print `file:line`.
  Documentation prose that quotes these strings is exempt (only source
  extensions are scanned).
- **`.so` strings pass.** Every `libmindchat_core.so` under
  `app/src/main/jniLibs`, `app/build`, or an explicit `--so-dir` must not
  contain the purged vendor format strings (`RECV`, `SEND `,
  `Attempting connection`). A host without an NDK prints
  `SKIPPED: no .so found` and exits 0; `--strict-so` turns that into a
  failure (CI always has the libraries).
- **Kill-switch confirmation** (optional `--cargo-tree`): verifies
  `cargo tree -p mindchat-core --offline` shows `max_level_off` and
  `release_max_level_off` unified into the `log` and `tracing` crates.

```sh
bash scripts/check-zero-log.sh                        # local (grep + .so if present)
bash scripts/check-zero-log.sh --strict-so            # CI-style: missing .so fails
bash scripts/check-zero-log.sh --strict-so --so-dir jniLibs --cargo-tree
```

In CI (`verify.yml`) the `zero-log` job depends on the `android` job, which
uploads the freshly built `app/build/generated/jniLibs`; the job downloads
it and runs with `--strict-so` plus `--cargo-tree`. Debugging happens
through typed errors, unit tests, and the gated live suite, never through
runtime logging.

## Vendored tokio-xmpp patch

`vendor/tokio-xmpp/` is a deliberate, patched vendored copy of tokio-xmpp
6.0.0, selected via `[patch.crates-io]` in the root `Cargo.toml`. Beyond the
transport fixes noted in the root manifest comment, the tree carries the
**0.1.8 zero-log purge** (ROADMAP 6.1). Every diff from upstream is marked
`// MindChat patch:` where it touches control flow; deletions below are
unmarked because upstream simply does not contain them:

- **Deleted the raw-stanza capture path**
  (`src/xmlstream/capture.rs`, its call sites in
  `src/xmlstream/common.rs`, and the `LogXsoBuf`/syntect syntax-highlighting
  machinery in `src/xmlstream/mod.rs`). This was the only code able to
  serialize message bodies (the `RECV`/`SEND` trace path). It is deleted,
  not gated: no env var can resurrect it.
- **Removed all `log::*` call sites** in `src/stanzastream/*`,
  `src/connect/*`, `src/client/iq.rs`, and the XML-stream layer (~75
  sites; control flow that only fed a log arm was simplified, e.g. the
  unused `error` field on `WorkerEvent::Disconnected`).
- **Deleted `examples/`** (six demo binaries that printed JIDs and
  passwords) and the `[[example]]` sections from both `Cargo.toml` and
  `Cargo.toml.orig`.
- **Manifest pruning** in `Cargo.toml`/`Cargo.toml.orig`: dropped
  `tokio-rustls` feature `logging`, `xmpp-parsers` feature `log`,
  `[dependencies.log]`, `[dev-dependencies.env_logger]`, the optional
  `syntect` dep (`syntax-highlighting` feature), and the unused `signal`
  dev-feature.
- **Live tests** in `crates/mindchat-core/tests/` no longer init
  `env_logger` and replaced every `eprintln!` with a silent skip; failures
  speak through assert messages.

The compile-time kill switch lives in `crates/mindchat-core/Cargo.toml`:
`log` and `tracing` are direct dependencies with `max_level_off` and
`release_max_level_off`, so feature unification compiles every transitive
`log::*`/`tracing::*` call site (rustls, hickory, vendor) to nothing in
debug and release builds. Keep it: never remove those two deps, and never
re-introduce a `log`/`tracing` emission point in the vendored tree.

## Security invariants

The following are not style preferences; they are the product's privacy
contract and are enforced by tests where possible:

- **Passwords never persist.** The Android side hands credentials straight
  to the Rust session startup call and never stores them (no preferences,
  no saved Compose state, no vault, no logs).
- **`SecretString` Debug output is redacted** and regression-tested; secrets
  never appear in snapshots, errors, events, or diagnostics.
- **The connect phase is bounded**: per-attempt 10 s, whole phase 30 s, and
  every branch of the worker emits a terminal event, so an account can never
  stick in Connecting.
- **Typed errors only.** UI-safe detail strings from the binding layer are
  display-only; classification and control flow come from typed enums.
- **Local state is atomic and versioned** with a size bound and corrupt-file
  quarantine; the state file contains no secrets by design (storage
  encryption lands in 0.1.9).

## Coding rules

- Errors surfaced to the UI are UI-safe detail strings from the binding
  layer; don't leak internal error plumbing into Compose.
- Keep the gateway fast path honest: a poll cycle with an unchanged snapshot
  must not rebuild UI state (see `shouldSkipUiRebuild` and its tests).
- Prefer extracting pure, injectable decision logic over testing through
  Android framework mocks; the project has no mocking library and does not
  want one.

## Branching and commits

- Branch names: `fix/<topic>` or `feat/<topic>` off `main`.
- Commit style: `type(scope): summary`, types `feat|fix|refactor|test|docs|
  perf|chore`, summary imperative and under ~72 characters. Examples:
  `feat(0.1.6): floating M3E dock`, `test(0.1.5): pin gateway contract`.
- Multiple focused commits are fine; merge into `main` with a plain merge
  commit and push.
- Build outputs, generated bindings, APKs, `local.properties`, keystores,
  logs, and IDE state stay untracked (see `.gitignore`).

## Releases

Releases are tagged `v<version>` after the Android version bump
(`versionCode`/`versionName` in `app/build.gradle.kts`), a full green
verification run, and a `CHANGELOG.md` entry recording the release (with the
artifact hash in the release report / GitHub release description). Release
notes are assembled from `CHANGELOG.md`; keep its Unreleased section current
as features land.
Local hosts without an NDK produce an APK with the previously built native
library; the CI-native assembly is the authoritative artifact. The release
process will be formalized in scripts and a CI release job at 0.2.0 (see
`ROADMAP.md`).
