# MindChat 0.1.3 — Local Persistence of Non-Secret State Spec

**Goal:** the app restores accounts, conversations, messages, and roster after a
process restart. Passwords are NEVER stored; reconnecting after restart requires
re-entering the password (existing `ReconnectDialog` flow already covers this).

Assumes the 0.1.2 connect-path work is merged (bounded terminal events). This
spec adds a clean, minimal JSON-file persistence layer on top of the existing
`CoreSnapshot` model. No SQLCipher, no new platform storage, no secrets.

---

## 1. Findings that shape the design (verified against the code)

- `MindChatCore` already has a complete snapshot/restore round trip:
  `snapshot()` (lib.rs:410) and `from_snapshot()` (lib.rs:383) serialize all
  durable state into `CoreSnapshot { accounts, contacts, conversations,
  messages, reactions }` (lib.rs:230), and the test
  `snapshot_round_trip_keeps_stable_ids` (lib.rs:1596) proves stable IDs
  survive. Persistence is therefore: persist `CoreSnapshot`, restore via
  `from_snapshot`. No new state machine needed.
- `CoreStore` (lib.rs:343) and `InMemoryStore` (lib.rs:350) exist but are
  unused by the FFI layer. The FFI handle owns
  `Mutex<TransportCoordinator<TokioXmppTransport>>` (ffi.rs:418-432), and every
  mutation is serialized through that mutex, so a snapshot taken under the lock
  is always a consistent state.
- Passwords cannot leak: `Account`/`Contact`/`Conversation`/`Message`/`Reaction`
  contain no secret fields; passwords exist only as `SecretString` in
  `ConnectionRequest` and as `String` in the worker (`WorkerConnection`), never
  in the core or the snapshot. `ffi.rs::connect_account` (ffi.rs:563) is the
  only password entry point. This stays true after this release.
- `CoreSnapshot.connection_state` and `account.capabilities` are session
  ephemera. Restoring them verbatim would project a false "Online"/"Connecting"
  or stale capability set after restart, so load must sanitize (Section 3.1).
- `serde` 1.0.229 and `serde_json` 1.0.151 are already in `Cargo.lock`
  (transitive), so adding them as direct dependencies adds no new downloads.
- The app has one natural persistence choke point: every state change flows
  through `NativeMindChatGateway` (mutations) or through `pollTransport()`
  (transport events), which already runs on `Dispatchers.IO`
  (MindChatGateway.kt:224). A dirty-flag flush inside `pollTransport` persists
  at most ~750 ms of lag with zero new coroutine machinery.
- UniFFI Kotlin generation (scripts/generate-uniffi-kotlin.sh) rebuilds the
  binding from the `uniffi` feature; adding two methods to
  `MindChatCoreHandle` requires no script changes, only regeneration on build.

---

## 2. On-disk format (versioned, atomic, bounded)

Single JSON file in `context.filesDir`: `mindchat_state.json`.

```
PersistedState {
  "schema_version": 1,
  "snapshot": { accounts: [...], contacts: [...], conversations: [...],
                messages: [...], reactions: [...] }
}
```

- Schema versioning: `CURRENT_SCHEMA_VERSION = 1`. Any other version is refused
  on load (never silently migrated in 0.1.3).
- Atomicity: serialize to bytes, write to `<path>.tmp`, `sync_all()`, then
  `std::fs::rename(tmp, path)` (same-directory rename is atomic on Android).
  A crash mid-write leaves the previous file intact and only a stale `.tmp`
  which the next save overwrites.
- Size bound: refuse files larger than 64 MiB on load (corrupt/hostile file
  safety; a chat history of this scale is far beyond 0.1.3).
- Corrupt handling: invalid JSON, bad schema version, oversized file, or
  unmappable enum value → typed `PersistenceError`; the caller starts fresh and
  renames the bad file to `<path>.corrupt-<unix-ts>` for diagnostics (Kotlin
  side, best effort).

---

## 3. Per-file changes

### 3.1 `crates/mindchat-core/src/lib.rs`

1. **serde derives on the snapshot model.** Add
   `use serde::{Deserialize, Serialize};` and derive `Serialize, Deserialize`
   on: `ConnectionState`, `ContactPresence`, `RosterSubscription`,
   `ConversationKind`, `MessageDirection`, `DeliveryState`, `MessageKind`,
   `ProtocolCapability`, `Account`, `Contact`, `Conversation`, `Attachment`,
   `Message`, `Reaction`, and `CoreSnapshot`. Do NOT derive on `SecretString`,
   `ConnectionRequest`, `OutgoingMessage`, `TransportEvent`, or anything else
   that could carry secrets; they are not part of the snapshot anyway.
2. **New module `pub mod persistence;`** — new file
   `crates/mindchat-core/src/persistence.rs` containing:
   - `pub const CURRENT_SCHEMA_VERSION: u32 = 1;`
   - `pub const MAX_STATE_FILE_BYTES: u64 = 64 * 1024 * 1024;`
   - `#[derive(Serialize, Deserialize)] pub struct PersistedState { pub schema_version: u32, pub snapshot: CoreSnapshot }`
   - `#[derive(Debug)] pub enum PersistenceError { Io(std::io::Error), Corrupt(String), UnsupportedVersion(u32), TooLarge(u64) }`
     with `Display`/`std::error::Error` impls (UI-safe strings).
   - `pub fn save_state(snapshot: &CoreSnapshot, path: &std::path::Path) -> Result<(), PersistenceError>`
     — serialize `PersistedState { schema_version: CURRENT_SCHEMA_VERSION, snapshot: snapshot.clone() }`
     with `serde_json::to_vec_pretty`, write tmp, `sync_all`, rename; on error,
     remove the tmp file best-effort.
   - `pub fn load_state(path: &std::path::Path) -> Result<Option<CoreSnapshot>, PersistenceError>`
     — missing file → `Ok(None)`; oversized → `TooLarge`; parse
     `PersistedState` (→ `Corrupt` on failure); `schema_version != CURRENT`
     → `UnsupportedVersion`; otherwise **sanitize** and return `Ok(Some(...))`.
   - `fn sanitize_snapshot(snapshot: CoreSnapshot) -> CoreSnapshot` — the only
     place session ephemera are cleared on restore:
     - `accounts`: `connection_state = ConnectionState::Offline`,
       `connection_error = None`, `capabilities = BTreeSet::new()`.
     - `contacts`: `presence = ContactPresence::Offline`, `status = None`.
     - `conversations`, `messages`, `reactions`: unchanged (durable: titles,
       addresses, unread counts, bodies, timestamps, delivery states,
       in-reply-to links, reactions). Pending/Failed outgoing messages remain
       pending for the retry path (`pending_outgoing_messages`).
   - `#[cfg(test)]` unit tests (Section 5.1).
3. `MindChatCore::from_snapshot` is unchanged (persistence sanitization stays in
   the persistence layer so existing tests that expect preserved state keep
   passing).

### 3.2 `crates/mindchat-core/Cargo.toml`

- Add to `[dependencies]` (non-optional; the snapshot derives are unconditional):
  `serde = { version = "1", features = ["derive"] }`, `serde_json = "1"`.
  Both are already in the lockfile.
- `version = "0.1.3"` (matches the app versionName; feeds
  `mindchat_binding_version()`).

### 3.3 `crates/mindchat-core/src/ffi.rs`

1. `use crate::persistence::{load_state, save_state, PersistenceError};`
2. `impl From<PersistenceError> for MindChatBindingError` — map
   `Io(e)` → `Internal { detail: format!("state persistence I/O error: {e}") }`,
   `Corrupt(d)` → `Internal { detail: format!("corrupt state file: {d}") }`,
   `UnsupportedVersion(v)` → `Internal { detail: format!("unsupported state schema version {v}") }`,
   `TooLarge(n)` → `Internal { detail: format!("state file too large: {n} bytes") }`.
   (No new FFI error variant needed; Kotlin already catches
   `MindChatBindingException` for all of these.)
3. Two new methods on `MindChatCoreHandle`:
   - `pub fn save_state(&self, path: String) -> Result<(), MindChatBindingError>`
     — lock, clone `session.core().snapshot()`, drop the lock, then
     `save_state(&snapshot, Path::new(&path))`. Taking the snapshot under the
     lock guarantees consistency; writing outside the lock keeps file I/O off
     the core mutex. Callers must serialize `save_state` calls (Kotlin does).
   - `pub fn load_state(&self, path: String) -> Result<bool, MindChatBindingError>`
     — lock; guard: if `!session.core().accounts().is_empty()` return
     `Internal { detail: "core already contains accounts; load_state must run before any mutation" }`
     (covers the "restoring when accounts already exist" edge: refuse rather
     than merge or clobber); if
     `!session.transport().connected_accounts().is_empty()` return the same
     style of error. Otherwise `load_state(&snapshot, Path::new(&path))`; on
     `Ok(None)` return `Ok(false)`; on `Ok(Some(snapshot))` replace the core:
     `*session.core_mut() = MindChatCore::from_snapshot(snapshot);` and return
     `Ok(true)`. The lock is held across the file read; that is fine because
     this runs once at startup before any UI mutation.
4. Doc comments on both methods: passwords are never written; the file contains
   no secrets; call `load_state` once at startup.

### 3.4 `app/src/main/java/com/mindchat/app/MindChatGateway.kt`

1. `MindChatGateway` interface: add `suspend fun persistNow()` (explicit flush
   used by the lifecycle hook and tests). `PreviewMindChatGateway` implements it
   as `Unit` (no storage in previews).
2. `NativeMindChatGateway` constructor: add
   `private val stateFile: File = File(context.filesDir, STATE_FILE_NAME)`-style
   parameter (a `File`, required, supplied by the factory; tests pass a temp
   file). Add `private const val STATE_FILE_NAME = "mindchat_state.json"` and a
   `companion object` in the class.
3. **Restore on startup:** in the constructor `init` block (before first
   `state` read), run:
   ```
   runCatching { core.loadState(stateFile.absolutePath) }
       .onFailure { if (stateFile.exists()) stateFile.renameTo(File(stateFile.path + ".corrupt-" + System.currentTimeMillis())) }
   ```
   then `refresh()`. `Ok(true)` restores; `Ok(false)` (no file) starts fresh;
   failure renames the bad file aside and starts fresh. Because
   `MindChatCoreHandle()` starts empty, the load-state guard in 3.3 is
   satisfied.
4. **Persist on mutation:** add `@Volatile private var dirty = false`; set
   `dirty = true` in `addAccount` (after the core mutation), `reconnectAccount`
   (only after `core.connectAccount` succeeds — a failed connect is already
   projected by the core and will be persisted via the poll path),
   `addContact`, `sendText`, `openLocalConversation`. (Customization toggles
   already persist via `MindChatPreferences`; do not touch them.)
5. **Persist transport-event state:** in `pollTransport` (MindChatGateway.kt:217),
   inside the `withContext(Dispatchers.IO)` block, after
   `core.pollTransportEvents(32U)` and the outbox flush, compute:
   ```
   val userDirty = dirty
   dirty = false
   val stateChanged = userDirty || processedEvents > 0U || flushedAccounts.isNotEmpty()
   if (stateChanged) {
       runCatching { core.saveState(stateFile.absolutePath) }
   }
   ```
   placed BEFORE the existing early-return (`processedEvents == 0U && ...`),
   so a pure user mutation without transport events still persists. This keeps
   all file I/O off the main thread and caps persistence lag at one poll cycle
   (~750 ms).
6. `persistNow()`:
   ```
   override suspend fun persistNow() {
       withContext(Dispatchers.IO) {
           runCatching { core.saveState(stateFile.absolutePath) }
           dirty = false
       }
   }
   ```
7. `MindChatGatewayFactory.create` (MindChatGateway.kt:106): pass
   `stateFile = File(context.filesDir, "mindchat_state.json")` to
   `NativeMindChatGateway`.

### 3.5 `app/src/main/java/com/mindchat/app/MindChatApp.kt`

- Add a lifecycle flush hook so the most recent mutations survive a fast kill
  between polls: in the `MindChatApp(gateway, appLockHost)` composable, register
  a `LifecycleEventObserver` on the `LocalLifecycleOwner` that calls
  `gateway.persistNow()` on `ON_STOP` (launched via the owner's `lifecycleScope`,
  already available through `androidx.lifecycle:lifecycle-runtime-ktx`), removed
  in `onDispose`. The 750 ms poll-loop flush in 3.4 remains the primary
  persistence mechanism; this hook is best-effort hardening, never a
  correctness dependency.

### 3.6 `app/build.gradle.kts`

- `versionCode = 4`, `versionName = "0.1.3"`.

---

## 4. Edge cases (and the chosen behavior)

| Case | Behavior |
| --- | --- |
| No state file on first run | `load_state` → `Ok(false)`; fresh empty core. |
| Corrupt / truncated file | Typed error → Kotlin renames to `.corrupt-<ts>`, starts fresh, continues working. |
| Partial write / crash mid-save | tmp+rename: old file survives; stale `.tmp` overwritten next save. |
| Oversized file (corrupt growth) | Refused above 64 MiB; treated like corrupt. |
| Future schema version | Refused with `UnsupportedVersion`; treated like corrupt (no migration in 0.1.3). |
| Concurrent saves | Rust takes the snapshot under the mutex and writes outside it; Kotlin serializes saves (single `Dispatchers.IO` block per poll + lifecycle hook) so writes never interleave; last executed save reflects the latest state. |
| Restore when core already has accounts | `load_state` refuses (guard in 3.3); Kotlin calls it exactly once at startup on an empty handle. |
| Restored account shows stale Online/Connecting | Sanitized to `Offline`, `connection_error` cleared, capabilities cleared (re-discovered on next connect). |
| Restored pending outgoing messages | Kept `Pending`/`Failed`; `flush_outbox` retries them after the user reconnects (unchanged core behavior). |
| Password after restart | Not stored anywhere; UI shows the account `Offline`; `ReconnectDialog` asks for the password again. |
| Backup rules (`res/xml/backup_rules.xml`) | The JSON contains conversation content (non-secret by design). Leave default backup behavior in 0.1.3; if the product decides to exclude it later, nothing in the code changes. |

---

## 5. Test plan

### 5.1 Rust unit tests (in `persistence.rs`, no network)

- `snapshot_json_round_trip_preserves_all_records`: build a populated core
  (accounts ×2, contacts with subscription/presence, conversations direct+MUC,
  messages with in-reply-to and delivery states, reactions), `save_state` to a
  temp file, `load_state`, compare sanitized snapshot field-by-field.
- `load_state_returns_none_for_missing_file` (temp dir, nonexistent path).
- `load_state_rejects_corrupt_json`.
- `load_state_rejects_unsupported_schema_version` (valid JSON, version 99).
- `load_state_rejects_oversized_file`.
- `save_state_is_atomic_temp_renamed`: after save, `<path>.tmp` does not exist;
  content is valid JSON with `"schema_version": 1`.
- `load_state_sanitizes_session_ephemera`: snapshot containing
  `Online`/`Connecting` accounts with capabilities and `connection_error`, and
  contacts with non-default presence/status → restored accounts are `Offline`,
  empty capabilities, `None` error; contact presence `Offline`, status `None`;
  messages/conversations/reactions unchanged.

### 5.2 FFI unit tests (in `ffi.rs`)

- `bridge_save_and_load_state_round_trip`:
  `MindChatCoreHandle::new()`, add account/conversation/message, `save_state`
  to a temp path, create a second `MindChatCoreHandle`, `load_state`, assert
  `Ok(true)` and the restored snapshot matches (accounts present, Offline).
- `bridge_load_state_refuses_non_empty_core`: `load_state` after adding an
  account → `Err(MindChatBindingError::Internal { .. })`.
- `bridge_load_state_missing_file_returns_false`.

### 5.3 Kotlin tests

- Instrumented (`app/src/androidTest`), gated by the native lib being present:
  `persistence_round_trip_across_gateway_instances` — create gateway A with a
  file in `context.cacheDir`, add account + conversation + message, call
  `persistNow()`, create gateway B with the same file, assert accounts,
  conversations, and messages are restored and connection state is `OFFLINE`.
  `corrupt_state_file_starts_fresh` — write garbage to the file, construct the
  gateway, assert no crash and empty accounts, and that the file was renamed
  aside.
- JVM unit tests continue to use `PreviewMindChatGateway` (its `persistNow` is
  a no-op); no native lib needed there.

### 5.4 Live tests (gated by `MINDCHAT_LIVE_TESTS=1`)

- Re-run the 0.1.2 live suite (transport changes are unaffected by
  persistence). No new live test is required for persistence: file I/O is
  deterministic and covered by unit tests.

### 5.5 Manual Android verification

1. Add account A, open a conversation, send a message, add a contact. Kill the
   app (swipe away). Relaunch: account A, conversation, message, and contact
   are present; account shows `Offline`; no password prompt is shown until
   Reconnect is tapped.
2. Wrong-password reconnect → `Failed` with detail; relaunch → still `Offline`,
   error text cleared.
3. Corrupt the file (`adb shell` write garbage to
   `/data/data/com.mindchat.app/files/mindchat_state.json`), relaunch → fresh
   empty state, file renamed to `.corrupt-*`, no crash.
4. Send a message and immediately background the app (ON_STOP flush); relaunch
   within seconds → message persisted.
5. Two accounts with distinct conversations/roster; restart preserves both
   scopes (`contacts` keyed by `(account_id, jid)`).
6. `adb backup`/restore not exercised; file lives under `filesDir` and is
   governed by existing `backup_rules.xml` (unchanged).

### 5.6 Verification commands

```
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

---

## 6. Invariants that must not be violated

- **Passwords never persisted and never cross FFI:** the JSON file, `Ffi*`
  records, events, and errors contain no secret. `connect_account` remains the
  only password entry point; `SecretString` Debug stays redacted.
- **No transport types across FFI:** persistence lives entirely on the
  `CoreSnapshot` side; `save_state`/`load_state` accept only a `String` path.
- **Restore never lies about connection state:** loaded accounts are always
  `Offline` with cleared error and capabilities until a real connect happens.
- **Restore is idempotent and refuse-to-clobber:** `load_state` runs once on an
  empty handle; a non-empty core refuses the load.
- **Atomic single-file format, versioned:** schema version 1; corrupt/unknown
  files never crash the app.
- **No new platform storage:** plain file in `filesDir`; no SQLCipher, no
  DataStore, no Room.
- **Workspace lints stay green:** `unsafe_code = "forbid"`, `missing_docs =
  "warn"` (document `persistence.rs` public items and the two FFI methods),
  clippy pedantic clean, `cargo fmt` clean.
- **Deps stay minimal:** only `serde` + `serde_json` added (already in the
  lockfile); nothing else.

---

## 7. Non-goals for 0.1.3

- Encrypted storage / SQLCipher / Android Keystore (passwords remain
  user-entered per session by design).
- Schema migration, multi-file history, or incremental writes (full-snapshot
  JSON is fine at this scale).
- Auto-reconnect on startup (restored accounts stay `Offline`; the user
  re-enters the password).
- Backing up/restoring message attachments or remote URLs beyond the existing
  `Attachment` metadata.
