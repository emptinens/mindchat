# MindChat status handoff

**Last updated:** 2026-08-14 (UTC)  
**Repository:** `/home/xxx/PROJECTS/mind/mindchat`  
**Branch:** `main`  
**HEAD:** `c700ad1` — merged transport/core/gateway/login fixes for the
always-Connecting bug, XEP-0184 receipts, and the Material 3 Expressive login
rework (see "2026-08-14 fixes" below).

This file is the current-session handoff. Read it together with `PLAN.md`, then
inspect the cited source before changing behavior. Historical release/audit
reports in `docs/` are evidence for their respective releases; they are not all
current product specifications.

## Current release identity

| Item | Current value | Source |
| --- | --- | --- |
| Rust crate | `mindchat-core 0.1.4` | `crates/mindchat-core/Cargo.toml` |
| Android version name | `0.1.4` | `app/build.gradle.kts` |
| Android version code | `5` | `app/build.gradle.kts` |
| Android app ID | `com.mindchat.app` | `app/build.gradle.kts` |
| Minimum / target SDK | 26 / 36 | `app/build.gradle.kts` |
| License | Apache-2.0 | `LICENSE`, `Cargo.toml` |

The most recent implementation commit is a reliability release. Its report is
`docs/release-0.1.4-report.md`.

## What is implemented

### Android application

- Kotlin + Jetpack Compose Material 3 Expressive UI with chat, contacts,
  settings, multi-account selection, a full-screen first-run login
  (`LoginScreen.kt`), account setup/reconnect dialogs, direct-chat creation,
  capability-gated group-chat creation, message composer, and EN/RU resources.
  - Main UI: `app/src/main/java/com/mindchat/app/MindChatApp.kt`
  - Login: `app/src/main/java/com/mindchat/app/LoginScreen.kt`
  - Entry activity: `app/src/main/java/com/mindchat/app/MainActivity.kt`
  - Theme: `app/src/main/java/com/mindchat/app/theme/Theme.kt`
- Customization persisted only as non-sensitive `SharedPreferences` values:
  dynamic color, comfortable layout, and app-lock preference.
  - `app/src/main/java/com/mindchat/app/MindChatPreferences.kt`
- Optional device biometric/device-credential screen lock. It locks after the
  application backgrounds and applies `FLAG_SECURE` when enabled.
  - `AppLock.kt`, `AndroidAppLockAuthenticator.kt`, `MainActivity.kt`
- Debug-only fallback (`PreviewMindChatGateway`) permits previews/isolated UI
  tests when the native ABI cannot be linked. Packaged release behavior uses
  `NativeMindChatGateway` and must include the Rust library.
  - `app/src/main/java/com/mindchat/app/MindChatGateway.kt`

### Rust domain core and XMPP transport

- A Rust workspace containing `mindchat-core`, built as `rlib` + Android
  `cdylib`.
  - Root: `Cargo.toml`; crate: `crates/mindchat-core/`
- Domain state projects multiple accounts, roster contacts/subscriptions and
  presence, conversations, text messages, replies, reactions, read state, and
  delivery state. The core owns validation, stable IDs, event emission, and
  outbox ordering.
  - `crates/mindchat-core/src/lib.rs`
- Concrete `TokioXmppTransport` runs behind the `XmppTransport` boundary;
  protocol crate types do not cross the domain or FFI boundary.
  - Boundary: `transport.rs`; implementation: `xmpp.rs`
- XMPP transport currently includes StartTLS/direct TLS connection handling,
  explicit endpoint or DNS/SRV resolution, XEP-0030 capability discovery,
  roster query/push projection and acknowledgement, contact presence, direct
  incoming/outgoing text, queued outgoing text flushes, and XEP-0184 message
  receipts (request on direct messages, incoming `<received/>` projection,
  best-effort `<received/>` acknowledgement).
- Connect phase is bounded: system DNS 8 s, SRV lookup 3 s, each candidate
  attempt 10 s, total connect phase 30 s. EOF after an established session is
  mapped to a recoverable `Disconnected` event, not credential failure.
  - Timeout/constants and event mapping: `crates/mindchat-core/src/xmpp.rs`
- A worker can never leave an account stuck on `Connecting`: the worker thread
  catches panics and emits a recoverable `Disconnected`; a closed event channel
  synthesizes one terminal `Disconnected` per tracked account; every worker-loop
  stanza send is bounded (10 s) so a dead socket cannot stall the worker; and
  the vendored `Suspended` stream event is surfaced as a terminal `Disconnected`
  instead of being silently dropped (network loss is observable, never stale
  "Online").
- `vendor/tokio-xmpp/` is a local patch selected by root
  `[patch.crates-io]`; retain it unless its changes are deliberately upstreamed
  or replaced. It addresses terminal auth failure/retry behavior described in
  `docs/release-0.1.2-0.1.3-report.md` and surfaces `Suspended` as a terminal
  event (`vendor/tokio-xmpp/src/client/worker.rs`).

### Kotlin ↔ Rust boundary

- UniFFI generated Kotlin bindings are produced during Gradle builds; generated
  code and native libraries are not committed.
  - Contract: `crates/mindchat-core/src/ffi.rs`
  - Generation: `scripts/generate-uniffi-kotlin.sh`
  - Android native build: `scripts/build-rust-android.sh`
  - Build wiring: `app/build.gradle.kts`
- `MindChatCoreHandle` is the native object owned by the Android gateway.
  UI receives immutable DTO snapshots and `FfiCoreEvent` notifications only;
  no XML, passwords, database handles, or cryptographic keys cross FFI.
- Current public session methods in `ffi.rs`:

  ```text
  new
  add_account
  set_connection_state
  set_capabilities
  upsert_contact
  open_conversation
  send_text
  receive_text
  add_reaction
  mark_conversation_read
  connect_account
  disconnect_account
  poll_transport_events
  flush_outbox
  snapshot
  drain_events
  save_state
  load_state
  ```

### Durable local state

- A versioned JSON snapshot persists accounts, contacts, conversations,
  messages, and reactions in app-private `filesDir/mindchat_state.json`.
  - Format/atomic I/O: `crates/mindchat-core/src/persistence.rs`
  - Android orchestration: `MindChatGateway.kt`
- Current schema version is `1`; maximum file size is `16 MiB`.
- Writes use a unique temporary file, `sync_all`, then atomic rename. Kotlin
  serializes lifecycle and poll-loop saves with a coroutine `Mutex`.
- The persistence tracker uses monotonic mutation/persisted epochs, so an older
  save cannot clear dirty state produced by a newer mutation; a successful save
  also prevents unchanged-state writes on every polling pass.
  - Unit coverage: `app/src/test/java/com/mindchat/app/PersistenceStateTrackerTest.kt`
- Load restores only durable projections. It sanitizes accounts to Offline with
  cleared errors/capabilities and contacts to Offline with cleared status.
  Corrupt/unsupported/oversize state is rejected; the Android gateway renames
  a failed load aside as `mindchat_state.json.corrupt-<timestamp>`.

## Security/data invariants — preserve these

1. **Passwords do not persist.** They are accepted only in ordinary,
   non-saveable Compose form state, passed directly to `connect_account`, and
   then cleared by the UI flow. They must not enter preferences, snapshots,
   events, logs, `rememberSaveable`, or persisted JSON.
2. `SecretString` deliberately redacts `Debug` output. Do not unwrap/log it.
   - `crates/mindchat-core/src/transport.rs`
3. Persisted JSON is intentionally **not encrypted** at present. It is bounded,
   atomic, and non-secret, but SQLCipher/Keystore-wrapped storage remains a
   roadmap item.
4. The extension code is only an internal Rust policy boundary: manifest
   validation, approved permission checks, ID-only event filtering, and
   policy-mediated commands. There is no plugin package parser, loader,
   sandbox, catalog, scripting engine, or third-party executable runtime.
   - `crates/mindchat-core/src/extension.rs`; `docs/EXTENSIONS.md`
5. Do not claim OMEMO, MUC/MAM, uploads, voice/media, registration,
   subscription commands, background push, or plugin runtime as delivered
   product functionality. Some have domain/capability shapes or UI affordances,
   but the end-to-end features are not implemented.

## 0.1.4 changes and reason

Commit `be77346` made these corrective changes:

1. Serialized native snapshot persistence using a shared coroutine `Mutex`.
2. Replaced an unsafe Boolean dirty state with epoch tracking, preventing lost
   mutations when a concurrent save completes.
3. Prevented repeated writes of an unchanged snapshot during transport polling.
4. Reclassified established-stream EOF as recoverable disconnect rather than
   authentication failure.
5. Feature-gated live transport integration tests so
   `cargo test --no-default-features` is a pure-core test configuration.
6. Made Android account creation return failure when its initial
   `connectAccount` request fails.

See `docs/release-0.1.4-report.md` for the release record.

## 2026-08-14 fixes (always-Connecting, receipts, login rework)

A full investigation (`docs/investigation-2026-08-14.md`) plus four parallel
fix branches addressed the reported "always Connecting" symptom and related
defects. Merged commits: `f3f8474` (transport), `465ec81` (core/FFI),
`89950f3` (gateway), `90dee75` (login UI), plus small follow-ups.

1. **Terminal events are now guaranteed.** A panicked worker emits a
   recoverable `Disconnected`; a closed event channel synthesizes one terminal
   `Disconnected` per tracked account; network loss after Online is surfaced
   (`Suspended` is no longer silently dropped), so the account state can never
   freeze on `Connecting` or stale `Online`.
2. **The worker can no longer stall on a dead socket.** Every stanza send in
   the worker loop (presence, roster, disco, receipt acknowledgement, message
   sends, stream close) is bounded by `WORKER_SEND_TIMEOUT` (10 s).
3. **Malformed stanzas cannot abort polling.** Roster/presence/incoming-text
   events with invalid JIDs or unknown accounts are skipped, and the FFI poll
   loop continues past a failing event, so `Connected`/`Disconnected` events
   queued behind a bad stanza are always applied.
4. **The Android poll loop cannot die.** `pollTransport()` catches every
   `Throwable` (rethrows coroutine cancellation), survives binding errors by
   showing the latest snapshot, and persists events applied before a failed
   batch instead of losing them.
5. **Outbox flushing is bounded.** `flush_outbox` caps at 32 messages per call
   with a 10 s per-message budget, bounding core-lock hold time.
6. **The login flow was reworked (Material 3 Expressive only).** A full-screen
   `LoginScreen` appears when no account exists (JID, auto-derived server,
   optional display name, password visibility toggle, inline validation,
   connecting progress, inline error + retry). Add/Reconnect dialogs share the
   expressive language. While connecting, the top bar and account chips show a
   spinner; after 35 s a stalled connection offers Cancel/Retry.
   `disconnectAccount` was added to the gateway interface for cancelling.
7. **UX fixes:** system back returns from chat detail to the list, group-chat
   creation failures show an inline error, unused dangerous permissions
   (`POST_NOTIFICATIONS`, `RECORD_AUDIO`) were removed, and the UniFFI CLI
   version is checked in `generate-uniffi-kotlin.sh`.
8. **CI additions:** `:app:testDebugUnitTest` and `cargo test
   --no-default-features` now run in `verify.yml`.

## 0.1.5 in progress (registration, management, profiles; 2026-08-14)

Three parallel agents delivered the first 0.1.5 slice; merged commits
`0cf29fb` (UI: management/profiles), `4a64ada` (M3 Expressive theme),
`6709f4e` (Rust: registration + management primitives).

1. **XEP-0077 in-band registration** (`crates/mindchat-core/src/xmpp.rs`):
   a bounded one-shot registration session reusing the DNS/TLS machinery,
   gated on the server advertising `jabber:iq:register`; no captcha or xdata
   form support by design (a server that requires extra fields gets a UI-safe
   refusal). Live run against jabber.ru (`MINDCHAT_LIVE_REGISTER=1`)
   completed in ~1.2 s with the terminal detail "server requires additional
   registration fields"; legacy username/password submission is unit-tested
   (construct/parse round trip).
2. **New FFI methods on `MindChatCoreHandle`**: `register_account`
   (creates the account and connects it; empty inputs rejected),
   `delete_account`, `update_account_display_name`, `delete_conversation`;
   deletion removes conversations/messages/reactions from the snapshot and
   emits the existing change events. `ffi.rs` documents the full method list.
3. **Account management UI (Telegram-style)**: M3 Expressive modal drawer
   opened by a top-bar avatar chip; per-account rows with connection state,
   one-tap switching, overflow (Edit profile, Reconnect, Disconnect, Rename,
   Delete with confirmation), "Add account" reuses the existing dialog.
4. **Profiles**: per-account avatar (local image picker, copied into app
   storage), status message, display name, and a fixed M3 Expressive accent
   set plumbed through `MindChatTheme(accentSeed)`; prefs keyed by account id
   in `MindChatPreferences`.
5. **Chat/group management**: conversation overflow (Mark read, Open as
   group, Delete with confirmation), group-chat Leave/Delete; pinning and
   archiving are documented as a domain gap (no core support yet).
6. **M3 Expressive theme pass**: `theme/` split into Color/Shape/Type/Theme;
   static light/dark schemes generated from the brand seed with the full
   tonal surface role set (WCAG AA-verified on* pairs), expressive type
   scale, expressive shape scale, dark launch theme in `values-night`.
7. **Strings**: EN/RU parity maintained (98/98 after registration strings).

Verification on the merged tree: Rust all-features 76 lib unit + 7
integration tests (2 live FFI + 4 live transport + 1 live registration),
no-default-features 41, clippy `-D warnings`, fmt; Android
`compileDebugKotlin`, `testDebugUnitTest` (9), `lintDebug`,
`compileDebugAndroidTestKotlin` all pass with the regenerated UniFFI
bindings (`app/build/generated/source/uniffi/`).

The management surface is additionally pinned through its public
`MindChatGateway` contract in `GatewayManagementContractTest` (11 tests):
registration validation and CONNECTING creation, account deletion cascading
to conversations/contacts/messages and the stored per-account profile (new
`MindChatPreferences.removeProfile`), rename trimming, conversation
deletion, and profile persistence. This also fixed two contract
inconsistencies between the preview and native gateways (blank-JID
registration rejection, stale profile left behind by account deletion).

The shared decision rules now live in one place (`GatewayInput.kt`:
`validateRegistration`, `nextActiveAccountId`), so both gateway
implementations are structurally identical instead of merely similar;
`GatewayInputTest` (9 tests) pins the rules, including the exact UI-safe
refusal strings.

The Kotlin↔Rust data contract is pinned by `SnapshotMappingTest` (16 tests):
the pure `mapSnapshotToUiState` mapping from the generated `FfiCoreSnapshot`
DTOs to the Compose UI model covers presence/connection translation,
CONNECTING stall tracking (35 s threshold, cleanup on terminal state),
active-account resolution, message direction/delivery, reaction grouping by
emoji, conversation preview/unread/group derivation, contact presence, and
profile/customization flow-through. Time and the timestamp formatter are
injected, so the rules are deterministic; the native gateway now delegates
to the same pure function with wall-clock time and the locale formatter.

The Kotlin↔Rust integration boundary is re-validated against the current
Rust source: `scripts/generate-uniffi-kotlin.sh` rebuilt the release cdylib
and regenerated the Kotlin bindings with uniffi-bindgen 0.32.0 (task
`generateUniffiKotlin` executed; output byte-identical to the committed
binding set), `registerAccount`/`deleteAccount`/`updateAccountDisplayName`/
`deleteConversation` confirmed present with the exact signatures
`NativeMindChatGateway` calls, and a full `--rerun-tasks` compile + unit
tests + lint against the regenerated bindings passed (39/39).

Caveats: the local APK embeds the previously built native `.so` (no NDK on
this host), so the new Rust methods are compiled into the Kotlin bindings
but not yet into the embedded library; CI performs the full native assembly.
Registration UI on `LoginScreen` (sign-in/register toggle) is wired to
`gateway.registerAccount`.

## 0.1.6 (floating dock, detailed settings, optimization; 2026-08-14)

Two parallel agents delivered the 0.1.6 slice; merged commits `8060781`
(fix/dock) and `4f49503` (fix/optim), merge commits `190ecc8`, `4122c16`.

1. **Floating M3E dock** (`MindChatApp.kt`): the edge-to-edge `NavigationBar`
   is replaced with a raised, centered pill dock (surfaceContainerHighest,
   28dp shape, shadow, `navigationBarsPadding` + bottom offset) with an
   animated `secondaryContainer` selected pill. It lives only on the shell
   scaffold, so chat-detail screens stay full-height. Same three
   destinations: Chats / Contacts / Settings.
2. **Detailed settings** (`SettingsScreen.kt`, new public composable) with
   M3 Expressive categories: Appearance (dynamic color, comfortable layout,
   accent row linked to the active profile sheet), Accounts (app lock,
   manage-accounts drawer, add account, edit profile), Privacy (search /
   encryption switches with explicit not-implemented-yet text, no fake
   backing), Notifications (coming-later rows), Storage (local size estimate
   of avatars + state file, clear profile images with confirmation that also
   drops stale avatar refs), About (version, licenses dialog, repository
   link). `SettingsStorageTest` covers the estimate/clear helpers.
3. **Poll-path optimization** (`MindChatGateway.kt`): `refresh()` compares
   the raw `FfiCoreSnapshot` structurally against the last mapped one
   (allocation-free data-class equals) and skips the full UI rebuild and
   recomposition when nothing changed; CONNECTING accounts always rebuild so
   wall-clock stall detection keeps working. `core.drainEvents()` still runs
   every poll. Reactions are indexed by message id once, making message
   mapping O(messages + reactions) instead of O(messages x reactions).
   Measured: ~53 us per unchanged poll (vs full rebuild), reaction mapping
   3.59 ms -> 0.49 ms. 15 new unit tests (`SnapshotDiffingTest`).
4. **Strings**: EN/RU parity maintained (125/125).

Verification on the merged tree: Rust all-targets tests + clippy
`-D warnings` + `cargo fmt --check` green; Android `compileDebugKotlin`,
`testDebugUnitTest` (28 tests, 4 suites), `lintDebug` (0 errors),
`assembleDebug` all pass. APK SHA-256 `54876b1e…`.

The settings surface is additionally pinned through the public
`MindChatGateway` contract: `GatewayCustomizationContractTest` (6 tests)
covers the dynamic-color / comfortable-layout / app-lock toggles (state flip
+ preferences persistence + flag independence + gateway restart with the
same preferences + non-default initialization), complementing
`SettingsStorageTest` (storage estimate, avatar-image clearing) and
`SnapshotDiffingTest` (unchanged-poll fast path). Total unit suite: 70
tests across 8 suites.

Caveats: privacy/notification rows are placeholders with explicit supporting
text; the locally built APK still embeds the previously built native `.so`
(no NDK on this host, CI does the native assembly).

## Release 0.1.6 (2026-08-14)

- Version bump: `versionCode` 6, `versionName` "0.1.6" (About now shows
  0.1.6).
- Tag: `v0.1.6`.
- Release artifact (debug-certificate-signed locally, source tree
  `main@v0.1.6`):

```text
app/build/outputs/apk/release/mindchat-0.1.6.apk
SHA-256: a26d5a18047678aeab4901a860a94f3184f87524425cf1ea45f53b1313d39197
size: 38,622,526 bytes
signer: Android Debug certificate (local signing only)
```

- Repository hygiene: removed unreferenced scratch artifacts
  (`project-dashboard.html` and the one-off research/audit reports in
  `docs/`); canonical docs are listed in the reference map below.
- Added `CONTRIBUTING.md` with the build/test setup, definition of done,
  coding rules, branch/commit style, and the release procedure.

## Verification already completed for 0.1.4

All listed Rust checks were run successfully against the current source using
an offline Cargo cache (the prior operator used
`CARGO_TARGET_DIR=/tmp/mindchat-014-target`):

```sh
CARGO_TARGET_DIR=/tmp/mindchat-014-target \
  cargo check --offline --workspace --all-features

CARGO_TARGET_DIR=/tmp/mindchat-014-target \
  cargo test --offline --workspace --all-features

CARGO_TARGET_DIR=/tmp/mindchat-014-target \
  cargo clippy --offline --workspace --all-targets --all-features -- -D warnings

CARGO_TARGET_DIR=/tmp/mindchat-014-target \
  cargo test --offline --workspace --no-default-features

cargo fmt --all -- --check
git diff --check
```

Recorded results (re-run 2026-08-14 on the merged fix tree):

- all-features: 63 unit tests plus 6 feature-gated live tests passed (4
  `live_login` transport tests and 2 new `live_ffi` tests that drive the exact
  `MindChatCoreHandle` boundary the Android app uses);
- live observations against jabber.ru (real network): bogus credentials reach
  terminal `Failed` with `NotAuthorized` in ~0.6 s (retry: ~0.45 s), never
  stuck on `Connecting`; `disconnect_account` during an in-flight connect to a
  non-routable host completes in ~0.6 ms and projects the account `Offline`;
- no-default-features: 37 tests passed;
- clippy, rustfmt, and `git diff --check` passed.

The Rust toolchain is pinned in `rust-toolchain.toml` to `1.97.1` with clippy
and rustfmt.

## Android build and APK status — important

### Current host toolchain

The host now has a working JDK 21 and Android SDK installation. The project
cannot be built with the legacy Java 8 plugin path:

- Android Gradle Plugin `8.11.0` requires JDK 11 or newer;
- this project explicitly compiles Kotlin/Java at target `17`;
- use **JDK 17 or newer** (JDK 17 is the intended/recommended choice).

The successful build used OpenJDK `17.0.20+8` (Microsoft build, JDK 17 as
recommended), Android SDK Platform 36, Build-Tools 35.0.0 + 36.0.0, Gradle
8.14.3, and UniFFI CLI 0.32.x (pre-generated bindings are valid; the FFI
surface is unchanged). The NDK is not installed on this host, so the Rust
`cdylib` packaging step was skipped and the previously built `.so` files were
reused; CI performs the full native assembly. The APK and generated sources
remain ignored build output.

### Current debug APK

Current generated artifact (2026-08-14, 0.1.6 tree; note the embedded native
library is still the 0.1.4 `.so` because this host has no NDK):

```text
app/build/outputs/apk/debug/app-debug.apk
versionCode: 5
versionName: 0.1.4
SHA-256: 54876b1efe4ec197aaed7c614c980ee8edcb29cfdabf5bad464cce6242baf4f9
size: 43,153,701 bytes
```

The 0.1.4 release artifact (source `d79f0d5`) was:

```text
SHA-256: f939cf7df4e3477d6b90a2ef0fa9cab6a6d2cb26e80d5b46a4aff5265f7d1336
```

`aapt dump badging` confirms application ID `com.mindchat.app`, compile SDK 36,
min SDK 26, and target SDK 36. `apksigner verify --verbose` confirms a valid
APK Signature Scheme v2 signature.

### Rebuild the 0.1.4 APK

```sh
cd /home/xxx/PROJECTS/mind/mindchat
export JAVA_HOME="<absolute path to JDK 17 or newer>"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

./gradlew \
  :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin \
  :app:lintDebug \
  :app:assembleDebug \
  --offline --no-daemon --max-workers=2

grep -E '"versionCode"|"versionName"' \
  app/build/outputs/apk/debug/output-metadata.json
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
```

Build result (2026-08-14, JDK 17.0.20+8, Gradle 8.14.3, offline):

```text
versionCode: 5
versionName: 0.1.4
SHA-256: f939cf7df4e3477d6b90a2ef0fa9cab6a6d2cb26e80d5b46a4aff5265f7d1336
```

Commands completed successfully (2026-08-14):

```text
:app:compileDebugKotlin
:app:compileDebugAndroidTestKotlin
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

Unit tests passed (9 test cases: `AppLockStateMachineTest` 6,
`PersistenceStateTrackerTest` 3). Lint passed with 0 errors.

After a successful build, update this file with build date, `java -version`,
APK SHA-256, metadata, commands/results, and the commit that records the
updated status. Do not commit APKs or generated build output.

## Build pipeline and dependencies

- Gradle invokes UniFFI generation before Kotlin compile tasks and the Rust ABI
  build at `preBuild`.
- Android ABI targets: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- Build configuration: `app/build.gradle.kts`.
- GitHub Actions CI uses Temurin 17, Android 36, NDK 29, Rust 1.97.1, installs
  `cargo-ndk 4.1.2` and `uniffi 0.32.0`, then runs lint, Android test compile,
  and debug assembly.
  - `.github/workflows/verify.yml`
- Root `.gitignore` intentionally excludes `build/`, `target/`, APK/AAB files,
  local properties, native staging libraries, and local diagnostics.

## Current known limitations / next work

The following are real gaps, not regressions to paper over:

1. **Android validation/APK:** the 2026-08-14 fix tree builds cleanly
   (`compileDebugKotlin`, unit tests 9/9, lint 0 errors, `assembleDebug`);
   see the fresh hash above. The host lacks the NDK, so the Rust `cdylib`
   packaging step was skipped locally (pre-built `.so` reused); CI performs
   the full native assembly.
2. **Account lifecycle:** no server account registration and no XMPP
   subscribe/unsubscribe commands. Reconnect requires re-entering the password
   after a process restart.
3. **Messaging protocol:** no completed end-to-end MUC join/room lifecycle,
   MAM history, markers/typing/corrections/retractions/replies over the wire,
   HTTP upload, attachments, voice media, or background delivery. XEP-0184
   message receipts (request/acknowledgement/delivery projection) are now
   implemented. Domain DTOs/capabilities do not mean protocol support is
   complete.
4. **Security/storage:** no OMEMO implementation or key UX; persisted JSON is
   not SQLCipher encrypted; startup restore currently runs during gateway
   construction rather than as asynchronous startup work.
5. **Transport/concurrency:** sends are bounded per message (10 s) and the
   flush batch is capped (32), but `flush_outbox` still holds the core lock
   while a send waits; at-least-once delivery without wire-level dedup remains
   (a timed-out send can later complete and be re-sent on reconnect).
6. **Vendored regression test not in workspace CI:** the vendored
   `tokio-xmpp` unit test for `Suspended` → terminal `Disconnected`
   (`vendor/tokio-xmpp/src/client/worker.rs`) cannot run through the workspace
   (the vendor crate is a patched dependency, not a member, and its standalone
   offline resolution needs `ktls` metadata absent from the local registry
   cache). The behavior is covered by source review, the workspace
   terminal-event unit tests, and the live FFI tests.
7. **Extensions:** no actual plugin runtime/package sandbox/catalog/consent or
   signing/revocation flow; only a policy seam exists.
8. **Test coverage:** Android instrumented coverage is limited; add device or
   emulator persistence and lifecycle tests when Android builds are available.

`PLAN.md` remains the aspirational implementation contract. Do not mass-rewrite
historical docs solely because they contain 0.1.0–0.1.3 version references.

## Suggested next-session procedure

1. Start with `git status --short --branch`; preserve unrelated user changes
   and do not kill unrelated terminal sessions/processes.
2. Read this file, `PLAN.md`, `docs/release-0.1.4-report.md`, and the specific
   source files named above before choosing the next vertical slice.
3. If JDK 17+ is available, execute the Android build sequence, validate output
   metadata/version, compute the fresh APK hash, update this file, and commit
   only source/documentation changes.
4. For Rust changes, retain the no-password, typed-FFI, bounded-connect, and
   atomic-persistence invariants; rerun focused tests plus the full Rust checks
   listed above before committing.
5. Keep modifications narrowly scoped. Generated files, APKs, caches,
   `local.properties`, state files, credentials, and logs must stay uncommitted.

## Reference map

| Need | Primary location |
| --- | --- |
| Product scope / roadmap | `PLAN.md` |
| Overview / local setup | `README.md` |
| Contribution guide | `CONTRIBUTING.md` |
| Release 0.1.4 details | `docs/release-0.1.4-report.md` |
| Earlier transport + persistence rationale | `docs/release-0.1.2-0.1.3-report.md` |
| Native ABI contract | `docs/NATIVE_BINDING.md`, `crates/mindchat-core/src/ffi.rs` |
| Extension boundary | `docs/EXTENSIONS.md`, `crates/mindchat-core/src/extension.rs` |
| Domain model | `crates/mindchat-core/src/lib.rs` |
| Transport boundary / implementation | `transport.rs`, `xmpp.rs` |
| Persistence | `persistence.rs`, `MindChatGateway.kt` |
| Android UI | `MindChatApp.kt`, `MindChatGateway.kt`, `MainActivity.kt` |
| Android/CI build setup | `app/build.gradle.kts`, `.github/workflows/verify.yml` |
