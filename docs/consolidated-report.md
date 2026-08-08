# MindChat Consolidated Report

Generated 2026-08-08. Consolidation of four researcher reports:
`docs/android-presentation-report.md`, `docs/rust-core-report.md`,
`docs/build-pipeline-report.md`, `docs/verification-report.md`, cross-referenced
against `PLAN.md` and `README.md`. Source spot-checks were limited to
`app/src/main/java/com/mindchat/app/MindChatGateway.kt` and `MindChatApp.kt`
(FFI call surface, threading, credentials); all other facts come from the
reports.

---

## 1. Architecture summary

MindChat is a single-activity Android 8+ XMPP client: a Kotlin/Jetpack Compose
Material 3 shell in `app/`, a Rust domain core in `crates/mindchat-core`
(4041 LOC across `lib.rs`, `xmpp.rs`, `transport.rs`, `extension.rs`, `ffi.rs`),
and a UniFFI-generated Kotlin contract (`com.mindchat.core`, 17 functions) that
is the only crossing point between the two. XMPP XML, database handles,
passwords, and crypto material never cross that boundary.

### Command path (UI → server)

```
Compose UI (5 screens, 23 composables)
  │  gateway.addAccount / connectAccount / upsertContact / openConversation / sendText
  ▼
MindChatGateway (NativeMindChatGateway, @Stable, state = mutableStateOf)
  │  synchronous FFI calls via JNA
  ▼
UniFFI (17-fn contract, FfiCoreSnapshot/Ffi* DTOs, MindChatBindingError)
  ▼
ffi.rs (MindChatCoreHandle wrapping Mutex<TransportCoordinator<TokioXmppTransport>>,
        password wrapped in SecretString, empty password rejected)
  ▼
MindChatCore (state machine: accounts/contacts/conversations/messages/reactions,
              16 model types, CoreCommand validation, capability gates)
  ▼
TransportCoordinator<T> (connect → ConnectionRequest, flush_outbox, poll_next_event,
                         no password stored in core)
  ▼
TokioXmppTransport (per-account worker thread, tokio-xmpp 6.0, StartTLS via rustls,
                    SRV or explicit host/port)
  ▼
XMPP server (StartTLS + SASL, presence, roster IQ get, disco#info IQ get)
```

### Event path (server → UI)

```
XMPP server (stanzas: roster result/push, disco result, presence, chat messages)
  ▼
TransportEvent (8 variants: Connected, Disconnected, CapabilitiesDiscovered,
                RosterContactUpsert/Removed, ContactPresenceUpdated, IncomingText,
                DeliveryUpdated)  ← one mpsc channel per worker
  ▼
MindChatCore::apply_transport_event (single projection path: Online+capabilities,
                                     roster upsert/remove, presence normalize,
                                     auto-create conversation, delivery states)
  ▼
CoreEvent (5 ID-only variants: AccountChanged, RosterChanged, ConversationChanged,
           MessageAdded, MessageChanged)  → drained by drain_events()
  ▼
FfiCoreEvent (5 events, named fields)
  ▼
UI snapshot (FfiCoreSnapshot → MindChatUiState projection in snapshotToUiState,
             750 ms poll → Compose recomposition)
```

### Threading model

- **Rust**: one dedicated thread per account, `mindchat-xmpp-{account_id}`,
  each running a current-thread Tokio runtime (`xmpp.rs:102-118`). The sync
  `XmppTransport` trait talks to workers via an unbounded command channel plus
  `mpsc` hand-off response channels; events flow back over one `mpsc` channel.
  `disconnect` uses a 5 s timeout, `send` a 15 s timeout; workers exit on
  disconnect and their slot is freed (reconnect is app-driven).
- **Kotlin**: no threads are spawned. A `LaunchedEffect` loop calls
  `gateway.pollTransport(); delay(750)` (`MindChatApp.kt:94-99`).
  `pollTransport()` is `suspend` and wraps all native work in
  `withContext(Dispatchers.IO)` (`MindChatGateway.kt:211`): it polls up to 32
  transport events, snapshots, flushes the outbox for online accounts, then
  snapshots again. Snapshot state is assigned only after the coroutine resumes
  on the main dispatcher, so Compose always sees a consistent main-thread
  projection. Commands (`addAccount`, `connectAccount`, `upsertContact`,
  `openConversation`, `sendText`) and `refresh()` run synchronously on the
  Compose main thread.
- **FFI boundary**: every entry point serializes through the
  `Mutex<TransportCoordinator<...>>` inside `MindChatCoreHandle`; a poisoned
  lock maps to `MindChatBindingError::Internal`. Polling is bounded at 128
  events per call (`MAX_TRANSPORT_EVENTS_PER_POLL`); outbox flushing is gated
  on `Online` connection state.
- Errors never crash the UI: `MindChatBindingException` is caught per call and
  swallowed in favor of a refreshed snapshot.

---

## 2. Contract integrity (Kotlin calls vs FFI surface)

### Exposed surface: 17 functions (`ffi.rs:432-634`)

1 constructor (`new`) + 15 methods (`add_account`, `set_connection_state`,
`set_capabilities`, `upsert_contact`, `open_conversation`, `send_text`,
`receive_text`, `add_reaction`, `mark_conversation_read`, `connect_account`,
`disconnect_account`, `poll_transport_events`, `flush_outbox`, `snapshot`,
`drain_events`) + 1 free function (`mindchat_binding_version`).

### Used by Kotlin: 10 (verified at call sites, MindChatGateway.kt)

| Call | Call site | Notes |
|---|---|---|
| `MindChatCoreHandle()` | MindChatGateway.kt:118 | constructor |
| `snapshot()` | :145, :218, :233, :284, :295 | full-state read |
| `addAccount(jid, server, displayName)` | :151 | validation in core |
| `connectAccount(id, password)` | :157 | password path, §below |
| `upsertContact(...)` | :170 | |
| `sendText(convId, sender, body, null, now)` | :186 | |
| `pollTransportEvents(32U)` | :213 | IO dispatcher |
| `flushOutbox(id)` | :228 | only for online accounts |
| `openConversation(...)` | :269 | |
| `drainEvents()` | :286 | after snapshot in `refresh()` |

### Exposed but unused by Kotlin: 7

`set_connection_state`, `set_capabilities`, `receive_text`, `add_reaction`,
`mark_conversation_read`, `disconnect_account`, `mindchat_binding_version`.
These are simulation/testing helpers (`set_connection_state`, `set_capabilities`,
`receive_text`), future-UI commands (`add_reaction`, `mark_conversation_read`,
`disconnect_account`), and an unused version probe. **No mismatch**: the used
set is a strict subset of the exposed surface; names, arities, and DTO types at
every Kotlin call site match the Rust signatures. There is no Kotlin call to a
function the FFI does not export.

### Thread-safety

- All FFI entry points funnel through the handle's `Mutex`; records are
  immutable (`generate_immutable_records = true`); the Kotlin layer performs no
  cross-thread mutation of native state (snapshot writes happen only on the
  main dispatcher).
- **Concern (verified)**: commands and `refresh()` hit the FFI synchronously on
  the Compose main thread (`MindChatApp.kt:184, 195, 217, 296`). Only the poll
  loop is IO-dispatched. A blocking `snapshot()`/`flushOutbox()` on the UI
  thread can jank or ANR during slow native calls; see Risk 5.
- Rust-side data races are ruled out by the Mutex plus `unsafe_code = "forbid"`
  (`Cargo.toml`); the transport's per-account worker threads are isolated from
  the handle by the trait boundary.

### Credentials handling (password path)

`AddAccountDialog` keeps the password in plain Compose state (not
`rememberSaveable`, so it is never persisted and dies with the composable).
On submit, `MindChatGateway.addAccount` calls `core.connectAccount(id, password)`
directly; `ffi.rs:561-572` rejects empty passwords (`InvalidInput`), wraps the
value in `SecretString` (Debug output redacted, tested), and hands it only to
the `ConnectionRequest`. The core never stores the password (`lib.rs:1099-1102`);
the transport worker owns the credential for its session. The form is cleared
when startup is accepted. Consequences: no credential material at rest in the
shell or core, but the user must re-enter the password after every process
restart (documented in README.md:92-95 and PLAN.md) until M2 persistence lands.

---

## 3. Milestone table

Estimates from the verification report (matching the dashboard mstones block).
Legend: D = done, P = partial, M = missing.

| Milestone | % | D | P | M | Item breakdown | Biggest blocker |
|---|---|---|---|---|---|---|
| **M1 Foundation** | **100%** | 10 | 0 | 0 | Workspace, Android shell, Rust state core, typed interface, CI, linting, test fixtures, localization (51/51 ru/en), accessibility semantics, no-telemetry policy | None (100%). Caveat: accessibility semantics and diagnostics policy are code/policy-only, not test-verified |
| **M2 Protocol + persistence** | **55%** | 5 | 2 | 4 | D: transport adapter, StartTLS, SRV, XEP-0030, login, roster (pull+push), no FFI leak. P: MUC (capability + groupchat send), Stream Management (detect-only). M: XEP-0077 registration, MAM, encrypted DB migrations, media storage | Encrypted persistence is a from-scratch build: only the `CoreStore` trait + `InMemoryStore` exist; no SQLCipher dependency, no migration framework; `CoreSnapshot` is the only migration input |
| **M3 Messaging** | **33%** | 1 | 4 | 4 | D: reconnection-safe outgoing queues. P: HTTP upload (capability + `attach()` projection), receipts, reactions, replies (model only). M: markers, typing, corrections/retractions, shared pins | The transport never emits delivery/receipt/reaction stanzas: `DeliveryUpdated` is defined and projected but nothing produces it, so every "partial" item lacks a wire path |
| **M4 Security + background** | **20%** | 1 | 0 | 4 | D: optional biometric/device-credential lock (+6 unit tests, FLAG_SECURE). M: OMEMO device trust UX, Keystore-wrapped storage keys, UnifiedPush registration, background-sync degradation | OMEMO from zero: capability mapping only (`xmpp.rs:490-492`), no sessions/device list/trust UI, no crypto stack, and no Android Keystore usage anywhere in the repo |
| **M5 Extension readiness** | **100%** (in scope) | 5 | 0 | 0 | Stabilized boundaries, data-only manifest, 7 scoped permissions, ID-only event filtering, policy-mediated commands with account-owned attribution | None: runtime work (package parsing, sandboxing, consent, SDK, signing, revocation, catalog) is explicitly out of scope for the base release |

---

## 4. Feature matrix (XMPP / XEP)

Status legend: implemented (wire-level code present, usually tested),
partial (capability detection and/or core projection only, no complete wire
path), missing (no code beyond the capability enum or nothing at all).
Evidence is `file:line`; all files in `crates/mindchat-core/src/` unless noted.

| Feature | Status | Evidence |
|---|---|---|
| RFC 6120 StartTLS | **implemented** (no live test) | `Client::new_starttls` xmpp.rs:242-247; rustls provider xmpp.rs:563-565 |
| RFC 6120 SRV resolution | **implemented + tested** | `dns_config_for_server` xmpp.rs:510-527; test xmpp.rs:696-702 |
| XEP-0030 service discovery | **implemented + tested** | disco#info on Online xmpp.rs:313-321, result xmpp.rs:363-374, feature map xmpp.rs:475-500; test xmpp.rs:596-611 |
| RFC 6121 roster | **implemented + tested** (subscribe/unsubscribe commands missing; `ver` always None) | request xmpp.rs:303-312, result/push/ack xmpp.rs:356-381, projection xmpp.rs:438-462 + lib.rs:505-544; tests xmpp.rs:613-642, lib.rs:1394-1440 |
| MUC (XEP-0045) | **partial** (capability + groupchat send; no join/leave/occupancy; incoming groupchat **dropped**, chat-only receive) | xmpp.rs:392-395, 479; drop at xmpp.rs:401-415; gating lib.rs:650-654 |
| MAM (XEP-0313) | **missing** (capability only) | xmpp.rs:480 |
| Stream Management (XEP-0198) | **partial** (detect-only; capability from stream features + disco; `DeliveryUpdated` projection; no ack/sequence/resume) | xmpp.rs:464-473, 481; lib.rs:829-841 |
| OMEMO (XEP-0384) | **missing** (capability only) | xmpp.rs:490-492 |
| HTTP upload (XEP-0363) | **partial** (capability + `attach()` projection with gate; no upload client) | xmpp.rs:482; lib.rs:807-828 |
| Receipts (XEP-0184) | **partial** (capability + `DeliveryUpdated` projection; no receipt stanza send/parse) | xmpp.rs:483; lib.rs:829-841 |
| Chat markers (XEP-0333) | **missing** (capability only) | xmpp.rs:484 |
| Chat states / typing (XEP-0085) | **missing** (capability only) | xmpp.rs:485 |
| Reactions (XEP-0444) | **partial** (capability + core `add_reaction` + UI render; no wire stanza) | xmpp.rs:486-487; lib.rs:883-904; MindChatApp.kt:640-646 |
| Corrections (XEP-0308) | **missing** (capability only) | xmpp.rs:488 |
| Replies (urn:xmpp:reply:0) | **partial** (capability + `in_reply_to` model/validation; transport never emits a reply element) | xmpp.rs:489; lib.rs:213, 746-751 |
| Shared pins (capability-gated) | **missing** (enum variant only; undiscoverable, no disco mapping) | lib.rs:135; ffi.rs:211 |
| UnifiedPush | **missing** (capability only, no UP dependency) | xmpp.rs:483 |

---

## 5. Top 8 risks (ranked by impact)

1. **No persistence layer at all.** Accounts/messages vanish on restart; password must be re-entered; M2 "encrypted database migrations" is from scratch (only `CoreStore` trait + `InMemoryStore` exist). *Mitigation:* SQLCipher-backed `CoreStore` implementation keyed on `CoreSnapshot` round-trip; Rust core already guarantees stable ID restoration (lib.rs:1584-1602).
2. **MUC is effectively unimplemented at the wire level.** Every incoming groupchat message is silently dropped (`translate_incoming_message` is chat-only), so v1's MUC contract cannot be met. *Mitigation:* accept `MessageType::Groupchat`, add join/leave/occupancy stanzas before the MUC switch is enabled.
3. **Stream Management is detect-only and outbox flush is not idempotent.** No ack/sequence tracking; `flush_outbox` re-sends every Pending/Failed message on each Online transition without dedup → duplicate deliveries after reconnect. *Mitigation:* add outbound-stanza tracking and correlate server acks before trusting the flush.
4. **No live-server or end-to-end verification.** All transport tests are XML-mapping or `FakeTransport`; the PLAN.md:104-105 disposable-XMPP-server infrastructure has no code. StartTLS, SASL failure paths, roster `ver=`, and reconnect are unverified against a real server; CI runs no Android tests at all. *Mitigation:* build the disposable-server harness and add `testDebugUnitTest`/emulator jobs to CI.
5. **FFI on the main thread.** Commands and `refresh()` (snapshot + drain) hit the native boundary synchronously from Compose event handlers; only the poll loop is IO-dispatched. Slow native calls jank or ANR the UI. *Mitigation:* route every gateway call through one `Dispatchers.IO` coroutine and return results to the main dispatcher (pattern already used by `pollTransport`).
6. **Declared, unused dangerous permissions.** `POST_NOTIFICATIONS` and `RECORD_AUDIO` have no consuming code or runtime-request flow; a release invites Play/F-Droid policy rejection. *Mitigation:* remove them until notifications/voice land, or implement channels + runtime requests in the same change.
7. **UniFFI CLI unpinned locally.** `generate-uniffi-kotlin.sh` checks only for CLI presence, not version; a dev with a different `uniffi-bindgen` produces bindings mismatched against the 0.32.0 scaffolding (runtime ABI break). *Mitigation:* version-check in the script (`uniffi-bindgen --version`) or resolve the CLI from cargo metadata.
8. **No release signing or reproducibility.** No `signingConfigs` (release APK unsigned), minify off with empty proguard rules, no `SOURCE_DATE_EPOCH`, no `--locked` in build scripts, no F-Droid metadata. *Mitigation:* add a keystore-backed `signingConfig`, keep-rules for JNA/UniFFI before enabling minify, and pin the toolchain in release recipes.

---

## 6. DASHBOARD DATA

```json
{
  "meta": [["Rust core","4041 LOC"],["Kotlin shell","1947 LOC"],["Tests","43"],["Milestone 1","100%"],["Strings ru/en","51/51"],["ABIs","3"]],
  "stats": [{"v":"4041","l":"Rust LOC","d":"core: lib/xmpp/transport/extension/ffi"},{"v":"1947","l":"Kotlin LOC","d":"main only; +150 test = 2097"},{"v":"43","l":"Tests","d":"33 Rust + 6 unit + 4 instrumented"},{"v":"33/33","l":"Rust tests pass","d":"29 default, 33 all-features"},{"v":"51/51","l":"Strings en/ru","d":"no missing keys"},{"v":"3","l":"ABIs","d":"arm64-v8a · armeabi-v7a · x86_64"},{"v":"17","l":"FFI functions","d":"MindChatCoreHandle + version"},{"v":"26","l":"minSdk","d":"Android 8+"}],
  "flow": [["Compose UI","23 composables, 5 screens"],["MindChatGateway","native adapter + snapshot remap"],["UniFFI","17 fn contract"],["MindChatCore","state machine, 16 types"],["TransportCoordinator","connect/flush/poll"],["TokioXmppTransport","StartTLS + SRV, per-account thread"]],
  "flow2": [["XMPP server","starttls + disco#info"],["TransportEvent","8 variants"],["MindChatCore","8 event projections"],["CoreEvent","5 ID-only events"],["FfiCoreEvent","5 events"],["UI snapshot","750ms poll → Compose"]],
  "mstones": [["M1 Foundation","100",".done w"],["M2 Protocol+persistence","55",".w"],["M3 Messaging","33",".w"],["M4 Security+background","20",".w"],["M5 Extension readiness","100",".w"]],
  "modules": {"h":[["","Module"],["loc","LOC"],["files","Files"],["note","Role"]],"r":[["lib.rs","1857","1","state machine + domain models"],["xmpp.rs","722","1","Tokio/XMPP transport"],["ffi.rs","849","1","UniFFI surface"],["extension.rs","463","1","extension policy"],["transport.rs","150","1","adapter boundary"],["Kotlin app","1947","9","Compose UI + gateway + prefs"]]},
  "xeps": {"h":["Feature","Status","Evidence"],"r":[["RFC 6120 StartTLS","implemented","xmpp.rs"],["XEP-0030 disco","implemented","xmpp.rs"],["RFC 6121 roster","implemented","xmpp.rs + lib.rs"],["MAM (XEP-0313)","missing","—"],["MUC (XEP-0045)","partial","capability + send, chat-only receive"],["SM (XEP-0198)","partial","detect-only"],["OMEMO (XEP-0384)","missing","—"],["HTTP upload","partial","capability + attach() only"],["Reactions","partial","core only"],["UnifiedPush","missing","—"]]},
  "tests": {"bars":[{"l":"lib.rs","v":17,"c":"#6c8cff"},{"l":"xmpp.rs","v":6,"c":"#6c8cff"},{"l":"ffi.rs","v":4,"c":"#6c8cff"},{"l":"extension.rs","v":5,"c":"#6c8cff"},{"l":"transport.rs","v":1,"c":"#6c8cff"},{"l":"Kotlin unit","v":6,"c":"#4ade80"},{"l":"Kotlin instr","v":4,"c":"#4ade80"}],"donut":[{"l":"Rust unit","v":33,"c":"#6c8cff"},{"l":"Kotlin unit","v":6,"c":"#4ade80"},{"l":"Instrumented","v":4,"c":"#fbbf24"}]},
  "versions": {"h":["Tool","Version","Note"],"r":[["Rust","1.97.1","edition 2024"],["UniFFI","0.32.0","unpinned locally"],["Gradle","8.14.3","—"],["AGP","8.11.0","—"],["Kotlin","2.1.0","—"],["JDK","17","pinned"],["NDK","29.0.14206865","cargo-ndk platform 26"],["minSdk/target/compile","26/36/36","Android 8+ → 16"]]},
  "risks": [["Persistence","no SQLCipher, re-enter password after restart"],["MUC","groupchat stanzas dropped, Chat-only"],["SM","detect-only, flush not idempotent"],["E2E infra","no live-server tests despite PLAN claim"],["FFI on main thread","commands + snapshot refresh hit FFI on UI thread"],["Permissions","POST_NOTIFICATIONS/RECORD_AUDIO unused"],["UniFFI unpinned","local CLI version drift"],["Signing","no release signing/reproducibility"]]
}
```

### Dashboard data notes (report-conflict resolutions)

- **Kotlin LOC 1947**: the presentation report's `kotlin_loc` is 1947 *main*
  LOC (+150 test = 2097 total). The stats description was corrected from
  "main + 150 test" to "main only; +150 test = 2097" so the value cannot be
  misread as including tests.
- **MUC (XEP-0045) = partial, not missing**: the verification report's feature
  matrix lists "Partial (capability + groupchat send only)" with the incoming
  groupchat drop as the gap; the milestone table counts MUC as partial. The
  placeholder's "missing" was corrected.
- **HTTP upload = partial**: the verification report's §4 matrix says
  "Missing (capability + attach projection)" but its own M3 milestone table
  counts HTTP upload among the 4 partials (capability + `attach()` projection,
  no upload client). The internally consistent milestone breakdown wins.
- **Roster evidence** widened from "lib.rs" to "xmpp.rs + lib.rs" (wire
  handling in xmpp.rs, projection in lib.rs).
- All other values (LOC split 1857/722/849/463/150, test counts 17/6/4/5/1,
  Rust 29 default / 33 all-features both executed and passing, 51/51 strings
  verified by `comm`, 3 ABIs, 17 FFI functions, minSdk 26, milestone
  percentages 100/55/33/20/100) matched the reports without adjustment.
