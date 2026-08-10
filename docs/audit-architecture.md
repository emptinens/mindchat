# MindChat 0.1.2 / 0.1.3 Architecture Audit

Audit date: 2026-08-10
Scope: combined diff `286f3a1..HEAD` (commits `f16d80d` docs, `75ba93b` bounded
connect, `2e98a3f` persistence, plus theming/specs). Focus areas: threading
model, persistence races, restore semantics, state-machine invariants,
SQLCipher evolution, and the vendored tokio-xmpp Stream Management patch.

Method: full source read of `crates/mindchat-core/src/{ffi,lib,transport,xmpp,
persistence}.rs`, `app/src/main/java/com/mindchat/app/{MindChatGateway,
MindChatApp}.kt`, the vendored tokio-xmpp diff and the two release specs.
Line anchors below were verified against the current tree. No files were
modified; no commits were made.

---

## Executive summary

The 0.1.2 bounded-connect rewrite is structurally sound: the select-based
connect phase cannot deadlock, worker retirement frees slots for retry, and
the vendored patch preserves Stream Management state. The 0.1.3 persistence
layer is correctly scoped (no secrets, versioned, atomic, sanitized restore)
and has a clean path to SQLCipher.

Three **major** findings remain, all in robustness/concurrency rather than the
happy path:

1. `core.saveState` can be called concurrently from `pollTransport` and
   `persistNow` on two `Dispatchers.IO` threads, writing the **same `<path>.tmp`**
   with truncate/rename. This can corrupt the state file (losing the entire
   0.1.3 feature) or silently persist a stale snapshot. The spec claims Kotlin
   serializes saves; it does not.
2. `flush_outbox` performs blocking network I/O (up to 15 s per message) while
   holding the core `Mutex`, stalling every other core operation.
3. Post-`Online` worker liveness is unbounded: three `send_stanza` awaits with
   no timeout plus no idle watchdog can freeze the worker (and the account in
   a stale "Online") for minutes on a dead-but-undetected socket, leak the
   worker thread on disconnect, and wedge the core lock via finding 2.

Finding 1 is the most serious for the 0.1.3 deliverable itself. Fixes are
concrete and small (Section 9). No blockers found.

---

## 1. Threading model (Q1)

Verified facts:

- One `std::sync::Mutex` guards `TransportCoordinator<TokioXmppTransport>`
  (`ffi.rs:440-453`); every FFI mutation/read takes it.
- One dedicated std thread + `current_thread` Tokio runtime per account
  (`xmpp.rs:117-132`). The worker never takes the core mutex: it only writes
  to a std `mpsc::channel` (`xmpp.rs:84-87`) and reads its command
  `UnboundedReceiver`. There is therefore **no lock inversion and no
  deadlock** possible between the coordinator and the worker.
- The `connect()` handshake (`ready_sender`/`ready_receiver`, `xmpp.rs:114,
  123, 134-149`) is one-shot and completed by the spawned thread before any
  event can be produced; `recv()` cannot miss a wakeup because it is a
  blocking std channel receive.
- Kotlin polls on `Dispatchers.IO` (`MindChatGateway.kt:255`) driven by a
  750 ms `LaunchedEffect` loop (`MindChatApp.kt:105-110`).

Connect-phase select (`xmpp.rs:272-288`), verdicts:

- **Cannot deadlock.** Both select arms are always pollable: the command
  receiver is owned by the worker and the phase future is under a 30 s
  `tokio::time::timeout` (`xmpp.rs:287`). The disconnect response is sent
  before `return` (`xmpp.rs:275-277`), so `transport.disconnect`'s
  `recv_timeout(5s)` (`xmpp.rs:160`) resolves promptly, and `join()` at
  `xmpp.rs:169-171` returns quickly because the thread is already exiting.
- **Cannot leak workers on the normal paths.** Every unexpected exit emits a
  terminal `Disconnected` before `return`: DNS error (`xmpp.rs:290-300`),
  connect exhaustion (`xmpp.rs:291-299`), phase timeout (`xmpp.rs:302-311`),
  main-loop stream end (`xmpp.rs:356-362`), and `handle_client_event`
  `Disconnected` (`xmpp.rs:501-511`). The slot is freed when the event is
  polled: `TokioXmppTransport::next_event` calls `retire_worker` on
  `Disconnected` (`xmpp.rs:194-204`; test
  `disconnected_events_release_the_account_worker_slot`, `xmpp.rs:1052-1072`).
  Because the UI only offers Reconnect after the terminal event has been
  applied (which requires the poll that retires the worker), a retry cannot
  hit "account is already connecting" in the normal UI flow.
- **Command channel during connecting:** `Disconnect` is honored (responds
  `Ok` and exits, `xmpp.rs:275-277`); `Send` is rejected with a typed
  `ConnectionFailed("account is still connecting")` (`xmpp.rs:278-282`).
  In practice `Send` cannot even reach the worker during connecting because
  `ffi::flush_outbox` gates on `ConnectionState::Online` and returns 0
  otherwise (`ffi.rs:623-639`, `lib.rs:632`).
- **Drop behavior** (`xmpp.rs:207-214`): sends `Disconnect` to every worker
  without joining; workers exit after `send_end` or when the command channel
  closes (`xmpp.rs:340-343`). Correct for process teardown. Caveats in
  findings M-3 and I-1 (wedged `send_end` leaks the thread; that is
  acceptable on Android process death but not in long-lived processes or
  tests that drop/recreate a gateway).

One residual transport-side note: a terminal `Disconnected` queued in the
event channel retires the worker **only when polled** (`xmpp.rs:194-204`), so
a retry racing the poll would see the stale entry; the UI ordering above makes
this unreachable in the current app. See M-4 for the sibling race.

## 2. Persistence race conditions (Q2) — M-1, M-2, m-3

### M-1 (major): concurrent `save_state` can corrupt or lose the state file

Two independent coroutines write the same file:

- `pollTransport`: `runCatching { core.saveState(stateFile.absolutePath) }`
  at `MindChatGateway.kt:287`, inside `withContext(Dispatchers.IO)`.
- `persistNow`: `runCatching { core.saveState(...) }` at
  `MindChatGateway.kt:306`, also on `Dispatchers.IO`, launched by the ON_STOP
  observer (`MindChatApp.kt:96-104`).

Both run on the **shared IO thread pool**, so they can overlap: ON_STOP fires
while the 750 ms poll loop is still composed and mid-save. There is no
gateway-side synchronization.

Rust side takes the snapshot under the lock but writes outside it
(`ffi.rs:657-660`), and the write path uses one shared staging name
`<path>.tmp` (`persistence.rs:89-98`, `tmp_path_for` at
`persistence.rs:164-168`). Two concurrent writers therefore:

- truncate each other's in-progress tmp file (`File::create` truncates), so
  the interleaved content that gets renamed over the real path can be mixed /
  invalid JSON (`persistence.rs:91-97`), or
- rename in the wrong order, leaving an **older snapshot on disk** after a
  newer one was saved (lost update), and/or
- fail one rename with `ENOENT` (`persistence.rs:96`).

A corrupted file is silently accepted on the next start, then renamed aside
(`MindChatGateway.kt:144-149`), which **discards all accounts, chats, and
history** — the entire 0.1.3 feature. The release spec claims "Kotlin
serializes saves ... so writes never interleave" (release-0.1.3-spec.md
§4 "Concurrent saves"), which is incorrect: the two call sites are
independent coroutines with no shared serialization point.

`ffi.rs:656` documents "Callers must serialize concurrent `save_state`
calls" — the caller does not.

Recommendation: serialize writes in the gateway with a dedicated
single-threaded dispatcher (e.g. `Dispatchers.IO.limitedParallelism(1)` for
saves only) or a `kotlinx.coroutines.sync.Mutex` shared by both call sites;
alternatively serialize inside the handle with a second `Mutex` around the
file write, or make the tmp name unique per call plus `fs::rename` with
last-writer-wins semantics (unique tmp names fix corruption but not stale
overwrite; a lock fixes both).

### m-3 (minor): `dirty` is cleared before/without save success

- `pollTransport` does `val userDirty = dirty; dirty = false` **before** the
  save (`MindChatGateway.kt:283-288`); if `saveState` then fails,
  `runCatching` swallows it and the mutation is never persisted or retried.
- `persistNow` unconditionally sets `dirty = false` after a `runCatching`
  (`MindChatGateway.kt:306-307`). If a user mutation lands between the
  snapshot capture inside `saveState` and `dirty = false`, the flag is
  cleared for a mutation that is **not** in the written file, and the next
  poll computes `stateChanged == false` and skips the save
  (`MindChatGateway.kt:283-288`). The mutation is lost on a fast kill.

Recommendation: clear `dirty` only after a successful save, and guard with a
monotonic mutation counter (clear only if the saved snapshot is at least as
new as the flag) so a stale clear cannot lose a newer mutation.

### M-2 (major): `flush_outbox` holds the core mutex across blocking network I/O

`ffi::flush_outbox` holds the session lock for the whole flush
(`ffi.rs:623-639`), and `TransportCoordinator::flush_outbox` calls
`transport.send(...)` per pending message (`lib.rs:1193-1209`). Each
`transport.send` can block the caller for up to `SEND_TIMEOUT` = 15 s
(`xmpp.rs:175-192`), and the worker may be wedged in an unbounded
`send_stanza` (M-3 below), so the command may not be serviced until after the
timeout. With N pending messages the core mutex is held for up to 15×N
seconds per poll, blocking `poll_transport_events`, `snapshot`,
`drain_events`, and `save_state` — the app's entire state pipeline — for the
same period. Delivery retry then re-enters the same stall every 750 ms.

Recommendation: bound or move the send out of the lock. Options: (a) a
short per-message budget and a `try_send`-style non-blocking handoff with
async completion; (b) do the flush under a dedicated worker/queue instead of
the core mutex; (c) at minimum, keep the lock only for the state checks and
queue the `OutgoingMessage`s, applying `Sent`/`Failed` from the worker's
completion events.

## 3. Restore semantics (Q3) — verified correct

- **Exactly-once, empty-handle call:** `NativeMindChatGateway.init` calls
  `core.loadState(...)` once before any mutation (`MindChatGateway.kt:140-151`);
  the handle is a fresh `MindChatCoreHandle()` (`MindChatGateway.kt:125`), so
  the guard (`ffi.rs:672-678`: refuse non-empty core) and the
  connected-accounts guard (`ffi.rs:679-684`) are trivially satisfied. The
  property initializer `state by mutableStateOf(snapshotToUiState())`
  (`MindChatGateway.kt:137`) runs before `init` but only reads the empty
  core; no ordering hazard.
- **Sanitize:** `sanitize_snapshot` resets `connection_state = Offline`,
  `connection_error = None`, `capabilities = BTreeSet::new()` per account and
  `presence = Offline`, `status = None` per contact (`persistence.rs:150-161`),
  and is applied on every successful load (`persistence.rs:140`). Restore
  never projects a stale Online/Connecting or capability set.
- **Pending outbox survives restore:** `pending_outgoing_messages` retains
  `Pending | Failed` messages (`lib.rs:861-895`) and sanitize deliberately
  leaves delivery states alone, so `flush_outbox` retries restored messages
  after the user reconnects. Covered by
  `queued_outgoing_messages_survive_snapshot_restore...` (`lib.rs:1673-1704`).
- **Transport guard:** `load_state` refuses while
  `transport.connected_accounts()` is non-empty (`ffi.rs:679-684`); on the
  startup path this cannot trigger. The guard closes the "restore over a live
  session" hole for future callers.
- Minor gap (I-2): `sanitize_snapshot` does not validate referential
  integrity (orphan messages/reactions referencing absent conversations, or
  contacts referencing absent accounts). A semantically inconsistent but
  syntactically valid file is accepted; orphans are silently invisible or
  produce `UnknownConversation` on `DeliveryUpdated`. Low risk because files
  are app-written atomically, but a hostile/crafted file is not fully
  rejected by the "corrupt" path.

## 4. State-machine invariants (Q4)

- Connecting → Online/Failed/Offline: `Connected` maps to Online with
  capabilities and cleared error (`lib.rs:588-598`);
  `Disconnected{recoverable}` maps recoverable→Offline, non-recoverable→Failed
  (`lib.rs:599-609`). Consistent.
- **Auth failure yields Failed, not Offline:** the first client event on a
  rejected SASL exchange is `Disconnected(Auth(_))`; `terminal_failure_for_event`
  sets `recoverable = !is_auth_error(error)` (`xmpp.rs:456-463`), so
  `recoverable == false` → Failed with a precise detail. Covered by
  `terminal_failure_for_event_marks_auth_non_recoverable` (`xmpp.rs:1028-1037`)
  and the live test `live_wrong_password_fails_fast_without_preflight`.
- **Intentional disconnect yields Offline without a terminal event:** the
  coordinator projects its own `Disconnected{recoverable:true}` and the
  worker exits on the command without emitting (`xmpp.rs:275-277`,
  `xmpp.rs:335-339`). Two caveats (m-4, m-5): the `client.next() == None`
  branch emits `recoverable: false` (`xmpp.rs:356-362`), which classifies a
  plain stream end as non-recoverable Failed, inconsistent with
  `handle_client_event`'s error-based mapping (`xmpp.rs:501-510`); and a
  `Disconnected` that races the disconnect command is queued in the event
  channel and applied on the next poll after the coordinator's Offline
  projection, flipping the account to Failed (`xmpp.rs:194-204` +
  `lib.rs:1182-1190`). Neither is UI-reachable today — Kotlin never calls
  `disconnectAccount` (verified: no call sites in `app/src`) — but both
  violate the stated invariant at the API level.
- **Coordinator connect error path:** sets Connecting before spawning, and on
  `transport.connect` error projects Failed and returns the error
  (`lib.rs:1165-1179`); on success it stays Connecting until events resolve
  it. Correct.

## 5. Evolution to SQLCipher (Q5) — clean, no blockers

- The `CoreStore` trait (`lib.rs:346-349`) is the intended storage seam, and
  the snapshot model (`CoreSnapshot`, `lib.rs:232-239`) is storage-agnostic.
  The JSON store currently **bypasses** `CoreStore` (`ffi.rs:657-691` calls
  `persistence::save_state/load_state` directly); routing the two FFI methods
  through a `CoreStore` impl now would make the SQLCipher swap a drop-in
  replacement. Nothing structural blocks it.
- The versioned envelope (`schema_version: u32`, `persistence.rs:19`,
  `persistence.rs:28-33`), the 64 MiB bound (`persistence.rs:24`), and
  `sanitize_snapshot` are all store-agnostic policies that carry over.
- Serde derive scope is correct: only the snapshot model derives
  `Serialize/Deserialize` (`lib.rs:52-239`); `SecretString`,
  `ConnectionRequest`, `OutgoingMessage`, and `TransportEvent` do not
  (matching release-0.1.3-spec.md §3.1.1). No secret-bearing type is
  persistable.
- Design decisions to carry into milestone 2, not blockers: (a) the
  load guard and sanitize must live on the load path of whatever store is
  used; (b) full-snapshot rewrites map to a "snapshot table" or per-entity
  upserts inside the SQLCipher transaction; (c) passwords remain
  out-of-band (Keystore), which is compatible.

## 6. Vendored patch and Stream Management resume (Q6) — preserved

The patch changed only the connector slot payload type
(`oneshot::Sender<Connection>` → `oneshot::Sender<Result<Connection,
crate::Error>>`) in `stanzastream/mod.rs` (`mod.rs:131-142, 152-163, 240-246`),
`stanzastream/worker.rs` (`worker.rs:87-124, 170-205`), and added
`WorkerEvent::Fatal` (`worker.rs:96-104`) with a terminate-then-emit path
(`worker.rs:193-199, 578-588`).

- `sm_state` is **untouched** by the patch: it still flows through
  `WorkerStream::Connecting { sm_state }` (`worker.rs:112-124, 139-147`) and
  into `NegotiationState::new(&features, sm_state.take())` on reconnect
  (`worker.rs:172-186`). Stream Management resume through the transparent
  reconnect path is preserved; `StreamEvent::Fatal` (auth) terminates without
  resume, which is the correct semantic.
- The previously unreachable-hang auth path (reconnector returns without
  sending → `ReconnectAborted` panic → frontend channel closes → `client.next()`
  parks forever, documented in transport-hang-analysis.md Finding 2) is now a
  clean `Event::Disconnected(error)` (`client/worker.rs:101-105`).
- The remaining `ReconnectAborted` panic (`worker.rs:589-591`) is only
  reachable if the worker's slot sender drops without a value, which after
  the patch effectively requires genuine teardown; acceptable.
- MindChat's own worker never survives a `Disconnected` (it tears down on the
  terminal event), so SM resume in MindChat only ever happens inside the
  vendored layer before an `Online` event; the `resumed: true` flag is
  ignored by `connect_attempt`'s `terminal_failure_for_event` and the
  `Online` handler (`xmpp.rs:456-463, 474-499`), which is harmless.

---

## 7. Severity-ranked findings

| # | Severity | Finding | Evidence |
|---|---|---|---|
| M-1 | **Major** | Concurrent `saveState` from `pollTransport` + `persistNow` writes the same `<path>.tmp` → corrupt file (whole state discarded on next start) or stale-snapshot lost update; spec's "Kotlin serializes" claim is false | `MindChatGateway.kt:287`, `MindChatGateway.kt:306`, `MindChatApp.kt:96-110`, `ffi.rs:657-660`, `persistence.rs:89-98,164-168` |
| M-2 | **Major** | `flush_outbox` holds the core `Mutex` across up to 15 s/message blocking sends; stalls all core ops, repeated every 750 ms poll | `ffi.rs:623-639`, `lib.rs:1193-1209`, `xmpp.rs:175-192` |
| M-3 | **Major** | Post-Online worker liveness unbounded: no-timeout `send_stanza` triple + no idle watchdog + `Suspended` dropped → stale "Online" for minutes, wedged disconnect leaks thread | `xmpp.rs:474-498`, `xmpp.rs:329-364`, `vendor/client/worker.rs:101`, `vendor/client/mod.rs:94-108`, `xmlstream/common.rs:66` |
| m-3 | Minor | `dirty` cleared before/without save success; stale-clear can lose a mutation | `MindChatGateway.kt:283-288,304-309` |
| m-4 | Minor | `client.next() == None` → `recoverable:false` → Failed for a plain stream end (inconsistent classification); races the intentional-disconnect Offline projection | `xmpp.rs:356-362`, `xmpp.rs:501-510`, `lib.rs:1182-1190` |
| m-5 | Minor | Recoverable connect failures (DNS, timeout, candidate exhaustion, all `recoverable:true`) land in Offline, but UI shows a Reconnect button only for Failed → no direct retry affordance | `lib.rs:599-609`, `xmpp.rs:290-311,432`, `MindChatApp.kt:381-385` |
| m-6 | Minor | Unbounded std-mpsc event backlog + 128-event poll clamp (~170 ev/s drain) can grow memory without bound under presence/roster floods | `xmpp.rs:84-87`, `ffi.rs:19,606-617`, `MindChatGateway.kt:257` |
| I-1 | Info | `Drop`/`disconnect` never join a worker wedged in `send_end` (unbounded close); thread leaks until socket error (300 s read timeout); acceptable on process death | `xmpp.rs:169-172,207-214`, `vendor/client/mod.rs:146-154` |
| I-2 | Info | `sanitize_snapshot` accepts referentially inconsistent files (orphan messages/contacts) | `persistence.rs:150-161` |
| I-3 | Info | `load_state` holds the core lock across the file read on the main thread at startup; fine once, watch file growth | `ffi.rs:671-691`, `MindChatGateway.kt:140-151` |
| I-4 | Info | SQLCipher path clean; route FFI persistence through `CoreStore` to make milestone 2 a drop-in swap | `lib.rs:346-349`, `ffi.rs:657-691` |

## 8. Verified-correct list (for the record)

- No deadlock in the select-based connect phase; disconnect during connecting
  is honored promptly; Send during connecting is rejected with a typed error
  and is unreachable in practice due to the Online gate.
- Worker retirement on terminal `Disconnected` frees the account slot; retry
  after a rendered terminal state cannot hit "already connecting".
- Intentional-disconnect path (coordinator-projected Offline) is correct when
  no terminal event races it.
- Auth failures are terminal, non-recoverable, precise, and fast (no
  preflight).
- Restore is exactly-once on an empty handle; sanitize resets
  connection/capabilities/error; pending outbox survives for retry; transport
  guard present.
- Serde derive scope is correct; no secrets persist; schema versioning,
  atomic rename, and the size bound are sound.
- SM state preserved through reconnect; the vendored patch converts the
  auth-failure hang into a clean terminal event.
- `cargo test --package mindchat-core` passes (see verification).

## 9. Concrete fixes

1. **M-1:** serialize saves. In `NativeMindChatGateway`, add
   `private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)` and
   route both `saveState` call sites through it (or a `kotlinx.coroutines.sync.Mutex`).
   Alternatively, in Rust, add a `Mutex<()>` around the file write inside
   `save_state` (after snapshot capture) so concurrent saves serialize
   regardless of caller.
2. **m-3:** clear `dirty` only on successful save; replace the boolean with a
   mutation counter (`dirtySinceSave: Long`) incremented on each mutation and
   cleared only when the saved snapshot includes it.
3. **M-2:** don't do blocking sends under the core mutex. Move
   `flush_outbox` to a per-account queue drained by a dedicated task, or give
   the transport a non-blocking `send` handoff with delivery updates flowing
   back through `TransportEvent::DeliveryUpdated` (already modeled). At
   minimum, reduce `SEND_TIMEOUT` and bound the per-poll flush count.
4. **M-3:** bound the three `send_stanza` calls in `handle_client_event`
   (`xmpp.rs:480,483,496`) with `tokio::time::timeout` (~10 s each, log and
   continue); add an idle watchdog to the main loop select (`xmpp.rs:329-364`,
   e.g. 60 s with no event → emit `Disconnected{recoverable:true}`); or
   surface `StreamEvent::Suspended` to Kotlin with a grace period
   (analysis-doc fix 3).
5. **m-4:** classify `client.next() == None` as `recoverable: true`
   (`xmpp.rs:359`), matching `handle_client_event`; on the intentional-
   disconnect path, drain/ignore queued terminal events for the account
   (generation counter on `WorkerHandle`, stamped into events) and make the
   coordinator project Offline even when `transport.disconnect` errors
   (`lib.rs:1182-1190`).
6. **m-5:** show the Reconnect action for Offline accounts that carry a
   `connection_error` (`MindChatApp.kt:381-385`), since 0.1.2 deliberately
   made DNS/timeout failures recoverable-Offline.
7. **m-6:** bound the std event channel (or drop-oldest on full) and raise /
   make configurable the per-poll clamp.
8. **I-4:** route `save_state`/`load_state` through a `CoreStore` impl now so
   SQLCipher is a one-file swap carrying over the guard and sanitize.

## 10. Verification performed

- `cargo test --package mindchat-core` (unit + FFI + persistence tests) — ran
  during this audit; result in background task output.
- No live tests (`MINDCHAT_LIVE_TESTS` unset by default).
- No files modified, no commits made.
