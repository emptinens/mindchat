# MindChat 0.1.4 — Reliability Release Report

Release date: 2026-08-11

## Scope

0.1.4 is a reliability maintenance release over 0.1.3. It updates the Rust
core and Android application version to `0.1.4` (`versionCode 5`) and contains
only verified corrective changes.

## Corrected defects

1. **Concurrent persistence writers** — `pollTransport()` and the lifecycle
   `persistNow()` path could run at the same time on `Dispatchers.IO`.
   `NativeMindChatGateway` now serializes native snapshot writes with a shared
   coroutine `Mutex`.
2. **Lost dirty state after overlapping save/mutation** — a Boolean dirty flag
   could be cleared by an older save after a newer mutation. The gateway now
   tracks monotonic mutation and persisted epochs, so only the snapshot epoch
   is acknowledged; later mutations remain queued for persistence.
3. **Repeated snapshot writes** — a successful save now advances the persisted
   epoch, preventing an unchanged state from being rewritten on every polling
   interval.
4. **EOF incorrectly shown as credential failure** — an established XMPP stream
   ending without a terminal server error now emits a recoverable disconnect,
   allowing the account to return to Offline rather than Failed.
5. **Pure-core test configuration** — the network integration test target is
   now explicitly feature-gated. `cargo test --no-default-features` runs the
   pure core suite without compiling XMPP-only test dependencies.
6. **Account setup result** — Android account creation now reports failure if
   the initial connection request is rejected, rather than always reporting
   success after creating the local record.

## Verification

Completed locally using the lockfile and offline Cargo cache:

```text
cargo check --offline --workspace --all-features
cargo test --offline --workspace --all-features
cargo clippy --offline --workspace --all-targets --all-features -- -D warnings
cargo test --offline --workspace --no-default-features
cargo fmt --all -- --check
git diff --check
```

Results:

- Rust all-feature suite: 49 unit tests + 4 feature-gated transport integration
  tests passed.
- Rust no-default-features suite: 30 tests passed.
- Clippy and rustfmt passed with no diagnostics.

Android Gradle verification was invoked with the requested tasks, but this host
only has Java 8 available. Android Gradle Plugin 8.11.0 requires Java 11+;
therefore Android compilation, lint, unit tests, ABI build, and APK packaging
must be rerun on JDK 17+:

```text
JAVA_HOME=<jdk-17-or-newer> ./gradlew \
  :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:lintDebug \
  :app:assembleDebug \
  --offline --no-daemon --max-workers=2
```
