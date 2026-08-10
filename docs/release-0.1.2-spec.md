# MindChat 0.1.2 — Connect-Path Reliability Spec

**Goal:** the connect path must NEVER leave an account stuck in `Connecting`. Every
`connect_account` call must produce a terminal `TransportEvent` (`Connected` or
`Disconnected { recoverable, detail }`) within a bounded wall-clock deadline
(~30 s), and `disconnect` during the connecting phase must be honored promptly.

This document is a file-level implementation spec. It makes no source changes;
it refines the stated hypotheses against the code as it exists at 0.1.1
(versionCode 2) and prescribes exactly what to change.

---

## 1. Root-cause findings (verified against the code)

All line references are to the 0.1.1 tree and will shift after edits; they are
given as anchors, not contracts.

### Finding 1 — DNS resolution is unbounded (HYPOTHESIS 1: CONFIRMED, and it is the primary "stuck" cause)

`run_worker` (crates/mindchat-core/src/xmpp.rs:245) awaits `resolve_endpoints`
(xmpp.rs:726) with **no deadline** before any per-attempt timeout exists.

- `resolve_endpoints` calls `srv_endpoint` (xmpp.rs:749) FIRST for bare domains
  (xmpp.rs:738). `srv_endpoint` runs `hickory_resolver::system_conf::read_system_conf()`
  and `resolver.srv_lookup(...)` with no timeout. On Android, `/etc/resolv.conf`
  is typically absent (fails fast) or a stub/VPN config whose `srv_lookup` can
  hang for a long time.
- The SRV target is then resolved with `resolve_host` (xmpp.rs:773) via
  `tokio::net::lookup_host` (getaddrinfo on a blocking-pool thread), also
  unbounded. On Android getaddrinfo can block for a long time (no network,
  captive portal, IPv6 timeouts).
- Even when SRV fails, `resolve_host(server, 5222)` (xmpp.rs:740) and
  `resolve_host(server, 5223)` (xmpp.rs:742) are unbounded.

Consequence: `run_worker` never reaches the per-attempt timeout, no terminal
event is emitted, and the UI stays in `Connecting` forever. This alone explains
the reported symptom.

### Finding 2 — double full handshake per candidate (HYPOTHESIS 2: CONFIRMED, with an important refinement)

`connect_attempt` (xmpp.rs:366) runs a bounded **auth preflight** for each
candidate (`preflight_auth`, xmpp.rs:431) — a complete `TLS + SASL` login via
`tokio_xmpp::client::login::client_auth` bounded by `CONNECT_TIMEOUT` (20 s) —
then creates a **second** `Client` and waits up to another `CONNECT_TIMEOUT`
for its first event (xmpp.rs:412). That is two stacked handshakes: up to 40 s
per candidate before a terminal event can occur.

Refinement on the claim "the vendored tokio-xmpp already makes auth failures
terminal": this is true **only inside the reconnect loop**, and it is not
surfaced as an event:

- In `vendor/tokio-xmpp/src/stanzastream/mod.rs:new_c2s` (line 170-180), a
  `crate::Error::Auth(_)` from `client_auth` causes the reconnector task to log
  "Authentication errors are fatal; giving up" and **return without sending on
  the oneshot slot**.
- The worker then observes a dropped slot: `stanzastream/worker.rs:180-184`
  transitions to `Terminated` and emits `WorkerEvent::ReconnectAborted`, which
  `run()` (worker.rs:548-550) handles with `panic!("Backend was unable to handle
  reconnect request.")`. The spawned `StanzaStreamWorker` task dies, the
  frontend channel closes, and `ClientWorker` (client/worker.rs:58-69) parks on
  a `next()` that never yields again.
- Net effect: `client.next()` **hangs forever** on auth failure. No
  `Event::Disconnected` is ever produced for the Client path anywhere in the
  vendored crate (verified: `Event::Disconnected` appears only in the enum
  definition; `client/worker.rs` forwards only `Online`/`Resumed`/`Stanza`).

Therefore "remove the preflight and map auth errors from the first event" is
**not possible without a small vendored patch** that carries a fatal connector
error through the slot and out as `Event::Disconnected(Error::Auth(_))`. With
that patch, the preflight becomes redundant and can be removed entirely (this
spec does both).

### Finding 3 — candidate count and timeout stacking (HYPOTHESIS 3: CONFIRMED, worse than stated)

For a bare domain, `resolve_endpoints` can produce **three** candidates, not
two: the SRV StartTLS endpoint, the plain `host:5222` StartTLS endpoint, and
the plain `host:5223` direct-TLS endpoint (xmpp.rs:738-745). With preflight
(20 s) plus connect (20 s) per candidate, worst case is 3 × 40 s = 120 s of
handshake time **after** an unbounded DNS phase. Well over a minute, and in the
DNS case, infinite.

### Finding 4 — terminal-event invariant (HYPOTHESIS 4: correct, currently violated)

`run_worker` emits a terminal `Disconnected` only on (a) DNS error,
(b) `connect_attempt` exhaustion, or (c) `client.next()` returning `None`.
Cases (a) and (b) are unbounded/very long today; case (c) cannot happen during
the connecting phase. `handle_client_event` maps a `Disconnected` event
(recoverable = `!matches!(error, Error::Auth(_))`, xmpp.rs:494-505) correctly,
and `TokioXmppTransport::next_event` retires the worker on the terminal event
(xmpp.rs:185-195), freeing the account slot for retry. The only missing piece is
making a terminal event *guaranteed* in bounded time.

### Finding 5 — DNS ordering recommendation

`tokio::net::lookup_host` (getaddrinfo) works on Android and is the reliable
path. hickory SRV requires `system_conf` parsing that is unreliable on Android.
**Recommendation: getaddrinfo first, hickory SRV as a bounded (3 s), best-effort
fallback appended AFTER the plain candidates**, under an 8 s total DNS budget.
Rationale: (a) plain candidates usually succeed or fail fast (TCP refused),
(b) SRV-correct endpoints (e.g. `gmail.com` → `talk.google.com`) are still
reached when the plain candidates fail, (c) hickory is never on the hot path on
stock Android, (d) everything is bounded regardless.

### Finding 6 — coordinator/FFI/UI projection (HYPOTHESIS 6: CONFIRMED, no change needed beyond the transport)

- `TransportCoordinator::connect` (lib.rs:1157) sets `Connecting`, spawns the
  worker via `TokioXmppTransport::connect` (returns immediately), and projects
  `Failed` only if `transport.connect` itself errors (lib.rs:1171-1173).
  After that, state resolves only through `TransportEvent`s polled by
  `ffi.rs::poll_transport_events` (ffi.rs:585).
- `ffi.rs::connect_account` (ffi.rs:563) rejects empty passwords before any
  worker starts (good; keep).
- UI: `MindChatApp.kt:89-94` polls `gateway.pollTransport()` every 750 ms →
  `core.pollTransportEvents(32U)` (MindChatGateway.kt:217-255). So any terminal
  event is reflected on screen within ~1.5 s of being emitted. The UI loop is
  healthy; the transport must simply always terminate.

---

## 2. Scope

### 2.1 `crates/mindchat-core/src/xmpp.rs` (transport; the main change)

1. **Replace the timeout constants.**
   - Remove `CONNECT_TIMEOUT` (20 s) at xmpp.rs:42.
   - Add:
     - `const DNS_TOTAL_TIMEOUT: Duration = Duration::from_secs(8);`
     - `const SRV_TIMEOUT: Duration = Duration::from_secs(3);`
     - `const CONNECT_ATTEMPT_TIMEOUT: Duration = Duration::from_secs(10);`
     - `const CONNECT_PHASE_TIMEOUT: Duration = Duration::from_secs(30);`
   - Keep `SEND_TIMEOUT` (15 s) and `DISCONNECT_TIMEOUT` (5 s) unchanged.

2. **`resolve_endpoints` (xmpp.rs:726): bounded, getaddrinfo-first.**
   - Wrap the whole body in `tokio::time::timeout(DNS_TOTAL_TIMEOUT, ...)`;
     on timeout return `Err("DNS resolution timed out")` (UI-safe detail).
   - Explicit `host:port` and explicit-IP paths stay as-is (they are already
     immediate).
   - Bare-domain path becomes, in order:
     1. `resolve_host(server, 5222)` → push `(addr, false)` (StartTLS).
     2. `resolve_host(server, 5223)` → push `(addr, true)` (direct TLS).
        A failed single lookup is skipped (log-free; fall through), not fatal.
     3. Bounded best-effort SRV: `tokio::time::timeout(SRV_TIMEOUT, srv_endpoint(server))`
        → on `Ok(Some(endpoint))` push `(endpoint, false)`; on timeout or
        `None` push nothing. SRV is thus tried **only after** the plain
        candidates, which keeps Android on the getaddrinfo path and preserves
        SRV correctness as a fallback.
     4. If the list is empty → `Err("cannot resolve XMPP server {server}")`.
     5. `dedupe_candidates(attempts)` (new pure helper below) before returning.
   - `srv_endpoint` itself (xmpp.rs:749) keeps its current logic but must not
     be awaited anywhere unbounded; it is only ever called through the 3 s
     inner timeout above.

3. **New pure helpers (unit-testable without network).**
   - `fn dedupe_candidates(attempts: Vec<(SocketAddr, bool)>) -> Vec<(SocketAddr, bool)>`
     — preserves order, removes duplicate `(SocketAddr, bool)` pairs (e.g. when
     the SRV target equals the plain host on 5222).
   - `fn is_auth_error(error: &tokio_xmpp::Error) -> bool` —
     `matches!(error, tokio_xmpp::Error::Auth(_))`.
   - `fn terminal_failure_for_event(event: &TokioXmppEvent) -> Option<ConnectFailure>`
     — if the first client event is `Event::Disconnected(error)`, return
     `Some(ConnectFailure { detail: error.to_string(), recoverable: !is_auth_error(error) })`;
     otherwise `None`. This is the single place that maps the *first* client
     event to a terminal failure, replacing the preflight's auth detection.

4. **`connect_attempt` (xmpp.rs:366): remove preflight, single bounded wait per
   candidate.**
   - Delete `preflight_auth` (xmpp.rs:431) and the `Preflight` enum
     (xmpp.rs:349).
   - For each `(endpoint, direct_tls)` in `attempts`:
     1. Build one `Client` with `DnsConfig::addr(&endpoint.to_string())`
        (`new_starttls` or `new_direct_tls_with_config`, `Timeouts::default()`)
        exactly as today (xmpp.rs:397-411).
     2. `match tokio::time::timeout(CONNECT_ATTEMPT_TIMEOUT, client.next()).await`:
        - `Ok(Some(event))` → if `terminal_failure_for_event(&event)` returns
          `Some(failure)` → `Err(failure)`; else
          `Ok(ReadyClient { client, pending_event: Some(event) })`.
        - `Ok(None)` → `last_error = "...closed during handshake"`, continue.
        - `Err(_)` → `last_error = "...timed out after 10 seconds"`, continue.
   - `Err(ConnectFailure { detail: last_error, recoverable: true })` on
     exhaustion, exactly as today.
   - Auth failures now arrive as the first event (via the vendored patch) and
     become `ConnectFailure { recoverable: false }` with the precise SASL
     detail. No double handshake, no extra TCP/TLS round trip.

5. **`run_worker` (xmpp.rs:245): bounded connect phase + cancel support.**
   - Replace the sequential `resolve_endpoints().await` then
     `connect_attempt().await` (xmpp.rs:252-280) with a selectable, bounded
     phase:
     ```
     let phase = async {
         let attempts = resolve_endpoints(&connection.server).await?;
         connect_attempt(&connection, attempts).await
     };
     let outcome = tokio::select! {
         command = command_receiver.recv() => {
             match command {
                 Some(WorkerCommand::Disconnect { response_sender }) =>
                     let _ = response_sender.send(Ok(()));      // prompt cancel, no terminal event (intentional)
                 Some(WorkerCommand::Send { response_sender, .. }) =>
                     let _ = response_sender.send(Err(TransportError::ConnectionFailed(
                         "account is still connecting".to_owned())));
                 None => {}
             }
             return;                                            // worker exits; coordinator already projects Offline
         }
         outcome = tokio::time::timeout(CONNECT_PHASE_TIMEOUT, phase) => outcome,
     };
     ```
   - `match outcome`:
     - `Ok(Ok(ReadyClient { client, pending_event }))` → proceed to the
       existing `pending_event` handling and main loop.
     - `Ok(Err(failure))` → emit
       `Disconnected { recoverable: failure.recoverable, detail: Some(failure.detail) }`,
       return (same as today, xmpp.rs:269-278).
     - `Err(_)` (phase deadline) → emit
       `Disconnected { recoverable: true, detail: Some("connection attempt exceeded 30 seconds") }`,
       return.
   - This makes the invariant hold: a terminal event is emitted within
     `CONNECT_PHASE_TIMEOUT` (30 s) of worker start in every path, and a user
     cancel is answered within milliseconds instead of being queued behind a
     multi-minute connect.
   - `ReadyClient` (xmpp.rs:335) is unchanged. `ConnectFailure` (xmpp.rs:341)
     is unchanged.

6. **`resolve_endpoint` (singular, xmpp.rs:706, exported, used by
   `tests/live_login.rs`).**
   - Reimplement as "first candidate of `resolve_endpoints`" (or share the same
     bounded helpers) so its behavior stays consistent with the connect path.
   - Document that for SRV-dependent domains it may return the plain
     host:5222 candidate rather than the SRV target; it is a diagnostic helper.

7. **Doc-comment updates** on `connect_attempt` (xmpp.rs:358-365), which
   currently documents the preflight; rewrite to describe single-handshake,
   bounded, auth-via-first-event semantics.

### 2.2 `vendor/tokio-xmpp` (vendored patch: surface fatal connector errors)

The vendored crate is already patched (see the `MindChat patch` markers); this
extends that patch so a terminal `client_auth` failure reaches the `Client` as
`Event::Disconnected(Error::Auth(_))` instead of hanging the stream.

1. **`vendor/tokio-xmpp/src/stanzastream/mod.rs`**
   - `StreamEvent`: add variant
     `Fatal(crate::Error)` ("the stream failed terminally, e.g. authentication
     was rejected; no reconnect will be attempted").
   - `StanzaStream::new` (mod.rs:225): change the connector slot type to
     `oneshot::Sender<Result<Connection, crate::Error>>`.
   - `new_c2s` (mod.rs:120): same slot type. In the success arm, wrap the send:
     `let Err(mut conn) = slot.send(Ok(Connection { stream, features, identity: jid })) else { return; };`
     then keep the existing graceful-close block, unwrapping `conn` to the
     `Connection` before closing. In the auth-failure arm (currently
     mod.rs:170-180), before `return`, send the error through the slot:
     `let _ = slot.send(Err(e));` so the worker can propagate it.

2. **`vendor/tokio-xmpp/src/stanzastream/worker.rs`**
   - `WorkerEvent::Disconnected` (worker.rs:88): slot becomes
     `oneshot::Sender<Result<Connection, Error>>` (both in the enum and in
     `WorkerStream::Connecting.notify`).
   - `WorkerStream::Connecting` (worker.rs:102): slot receiver becomes
     `oneshot::Receiver<Result<Connection, Error>>`.
   - `poll_duplex`'s `Connecting` arm (worker.rs:159-185):
     - `Ok(Ok(Connection { stream, features, identity }))` → existing path.
     - `Ok(Err(error))` → `*this = Self::Terminated;`
       `return Poll::Ready(Some(WorkerEvent::Fatal { error }));`
     - `Err(_)` (dropped slot) → existing `ReconnectAborted` path (frontend
       gone; keep the panic or convert to `Terminated` + end-of-stream; do not
       emit `Fatal`, because no reason is known).
   - Add `WorkerEvent::Fatal { error: crate::Error }`.
   - `run()`: handle `WorkerEvent::Fatal { error }` by sending
     `Event::Stream(StreamEvent::Fatal(error))` to the frontend via the
     existing `send_or_break!` machinery and then `break` out of the loop.

3. **`vendor/tokio-xmpp/src/client/worker.rs`**
   - In `handle_event` (client/worker.rs:71), add:
     `StanzaStreamEvent::Stream(StreamEvent::Fatal(error)) => Event::Disconnected(error)`
     so the `Client` stream yields the terminal event.

4. **`vendor/tokio-xmpp/src/stanzastream/tests.rs`**
   - The test connector (tests.rs:111) wraps its send:
     `sink.send(Ok(Connection { ... }))`.
   - Add a regression test: a connector that sends
     `Err(Error::Auth(AuthError::Fail(DefinedCondition::NotAuthorized)))`
     produces `Event::Stream(StreamEvent::Fatal(_))` from the `StanzaStream`.

No other callers of `StanzaStream::new` exist in the vendored tree (the
component module has its own login path), so the signature change is contained
to these four files.

### 2.3 No changes to `lib.rs` / `ffi.rs` / Kotlin for 0.1.2

The coordinator, FFI DTOs, gateway, and UI already resolve `Connecting`
correctly once the transport guarantees a terminal event. Explicitly out of
scope for 0.1.2: persistence, UI text, and transport auto-reconnect tuning.

### 2.4 Version bumps

- `app/build.gradle.kts`: `versionCode = 3`, `versionName = "0.1.2"`.
- `crates/mindchat-core/Cargo.toml`: `version = "0.1.2"` (feeds
  `mindchat_binding_version()`; keep in lockstep with the app).

---

## 3. Test plan

### 3.1 Rust unit tests (no network)

In `crates/mindchat-core/src/xmpp.rs` tests module (or a new `#[cfg(test)]`
module):

- `dedupe_candidates_removes_duplicate_pairs_preserving_order`.
- `candidate_ordering_places_plain_endpoints_before_srv` (test the pure
  ordering helper if extracted; otherwise cover via `resolve_endpoints` with an
  explicit-IP server, which never touches DNS: `127.0.0.1` → exactly two
  candidates, StartTLS 5222 first, direct TLS 5223 second).
- `is_auth_error_maps_only_auth_variants` (construct
  `tokio_xmpp::Error::Auth(tokio_xmpp::Error::Auth(...))` via `AuthError` from
  `xmpp_parsers` if constructible in dev; otherwise assert on a small local
  mirror of the classification logic).
- `terminal_failure_for_event_marks_auth_non_recoverable` — build
  `TokioXmppEvent::Disconnected(tokio_xmpp::Error::Auth(...))` and assert
  `recoverable == false` and the detail string is non-empty.
- Existing tests (roster mapping, disco mapping, worker-slot release) must keep
  passing; `validate_endpoint_syntax` is unchanged.

In `vendor/tokio-xmpp/src/stanzastream/tests.rs`:

- `fatal_connector_error_is_surfaced_as_stream_event` (described in 2.2.4).
- Existing stanzastream tests updated for the `Ok(...)` slot payload must pass.

### 3.2 Live tests (gated by `MINDCHAT_LIVE_TESTS=1`)

`crates/mindchat-core/tests/live_login.rs`:

- `live_login_chain_reaches_sasl_on_jabber_ru` — tighten the deadline from
  45 s to 30 s and keep the assertions: terminal `Disconnected` with
  `recoverable == false` and `detail.is_some()` (now satisfied without the
  preflight, proving the vendored patch surfaces the SASL failure as the first
  event). Keep `live_resolve_endpoint_finds_jabber_ru` as-is (asserts
  resolution success only).
- Add `live_blackhole_connect_terminates_within_30_seconds`: connect to
  `10.255.255.1` (explicit IP → immediate resolution, connect hangs at the OS
  level). Assert a terminal `Disconnected { recoverable: true, .. }` arrives
  within 32 s (bounded by the 30 s phase deadline; the two per-attempt 10 s
  timeouts fire first, so expect ~20-25 s).
- Add `live_wrong_password_fails_fast_without_preflight`: connect with a bogus
  JID + bogus password against `MINDCHAT_LIVE_SERVER` (default `jabber.ru`);
  assert terminal `Disconnected { recoverable: false, .. }` with a
  non-empty `detail` arrives within 20 s (auth failure is fast; this guards the
  no-preflight path against regressions).

### 3.3 Manual Android verification

1. Build with `scripts/build-rust-android.sh` + `./gradlew assembleDebug`.
2. Add an account pointing at an unroutable server (e.g. `10.255.255.1` or a
   non-existent domain); confirm the account leaves `Connecting` and shows
   `Failed`/`Offline` with a reason within ~35 s.
3. Add an account with a wrong password against a real server; confirm a fast
   (seconds) `Failed` with "authentication" detail.
4. During `Connecting`, tap Reconnect/Disconnect (or rotate away) and confirm
   the UI responds within ~1 s and the account lands on `Offline`.
5. Airplane-mode test: account leaves `Connecting` within ~35 s.
6. Repeated connect/retry cycles: no "already connecting" deadlock, worker
   slots always freed (`next_event` retirement, xmpp.rs:185-195).

### 3.4 Verification commands

```
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings   # pedantic is warn; must stay clean
cargo test --workspace
MINDCHAT_LIVE_TESTS=1 cargo test --package mindchat-core --test live_login -- --nocapture
cargo test --manifest-path vendor/tokio-xmpp/Cargo.toml   # vendored patch tests
```

---

## 4. Invariants that must not be violated

- **Bounded terminal event:** every `connect_account` emits `Connected` or
  `Disconnected` within 30 s wall-clock; the UI never stays in `Connecting`
  longer than ~32 s (750 ms poll granularity).
- **No passwords across FFI:** passwords enter only via
  `connect_account(password: String)` → `SecretString` → the worker; they are
  never placed in any `Ffi*` record, event, error, or snapshot field, and never
  in log output (`SecretString` `Debug` is redacted; keep it).
- **No transport types across FFI:** `ffi.rs` exposes only `Ffi*` DTOs; no
  `tokio_xmpp`, `TcpStream`, `Client`, or `TransportEvent` type crosses the
  UniFFI boundary (re-verified after the `connect_attempt` rewrite).
- **Auth failures are terminal and precise:** `Error::Auth` maps to
  `Disconnected { recoverable: false }` with a UI-safe detail string; never a
  generic timeout with `recoverable: true`.
- **Intentional disconnect emits no terminal event** (the coordinator projects
  `Offline` itself); only unexpected outcomes emit `Disconnected`.
- **Workspace lints stay green:** `unsafe_code = "forbid"`, `missing_docs =
  "warn"` (document all new public items, including the vendored
  `StreamEvent::Fatal`), clippy pedantic clean, `cargo fmt` clean.
- **No new dependencies for 0.1.2.** Only constants, functions, and the
  vendored patch.

---

## 5. Non-goals for 0.1.2

- Local persistence (0.1.3).
- Retry/backoff policy changes after a terminal event (UI-driven retry stays).
- Changing the 750 ms poll cadence, the `32U` event bound, or the UI.
- Removing hickory from the dependency tree (kept, bounded; dropping it is an
  acceptable alternative only if the bounded fallback proves flaky on device —
  that decision is deferred to 0.1.3).
