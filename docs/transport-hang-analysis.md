# Transport hang analysis: account stuck on "Connecting" with no terminal event

Audit date: 2026-08-10
Scope: `crates/mindchat-core/src/xmpp.rs`, `crates/mindchat-core/src/lib.rs`,
`crates/mindchat-core/src/ffi.rs`, `app/src/main/java/com/mindchat/app/*.kt`,
`app/src/main/AndroidManifest.xml`, vendored `tokio-xmpp` 6.0.0 (`vendor/`).
This document is an independent analysis; it does not modify source code.

## 1. How the connect path is supposed to terminate

`TokioXmppTransport::connect` (xmpp.rs:97-141) spawns one dedicated
`current_thread` Tokio runtime in a std thread. The worker
(`run_worker`, xmpp.rs:245-332) runs:

```
resolve_endpoints(server)          // DNS: hickory SRV + OS getaddrinfo  [UNBOUNDED]
  -> connect_attempt(attempts)     // per candidate:
        preflight_auth(...)        //   20s bound   (xmpp.rs:442)
        Client::new_* + client.next() // 20s bound (xmpp.rs:412)
  -> main select! loop (client.next() / commands)  [no idle bound]
```

A terminal event is only ever emitted as `TransportEvent::Disconnected`
(xmpp.rs:255-264, 270-279, 323-328) or `Connected` (xmpp.rs:469-472).
`tokio-xmpp`'s `Client` never emits `Event::Disconnected`: the `Event` enum
defines it (vendor/tokio-xmpp/src/event.rs:57), but `ClientWorker` drops the
stream's `Suspended` event (vendor/tokio-xmpp/src/client/worker.rs:101) and
stream closure is signalled only by the event channel ending
(`client.next()` returning `None`, which requires the worker task to exit).
So every "no terminal event" path below is a genuine hang or stale-state path.

The app polls events every 750 ms (`MindChatApp.kt:89-94`) and applies them
through `poll_transport_events` (ffi.rs:585-596) -> `poll_next_event`
(lib.rs:1209-1215) -> `apply_transport_event` (lib.rs:580-645, maps
`Connected -> Online`, `Disconnected{recoverable} -> Offline/Failed`).
The FFI/coordinator/app layers are correct: if the worker emits a terminal
event, the UI leaves "Connecting". Therefore the bug lives in the worker
never emitting one, or emitting it only after an unbounded delay.

---

## 2. Ranked list of concrete hang causes

Ranking = likelihood on a real Android device x impact (time stuck in
"Connecting" / absence of a terminal event).

### Cause 1 - Unbounded DNS resolution before any timeout (device-specific, HIGH likelihood, indefinite impact)

`run_worker` awaits `resolve_endpoints` with no timeout (xmpp.rs:252).
Every resolver inside is unbounded from the app's perspective:

- `srv_endpoint` (xmpp.rs:749-770): `hickory_resolver::system_conf::read_system_conf`
  then `TokioResolver::builder_tokio().srv_lookup(...)`. Hickory defaults are
  `timeout: 5s, attempts: 2` (hickory-resolver 0.26.1 config.rs) with per
  nameserver retries; with several unreachable nameservers in a readable
  resolv.conf this alone is tens of seconds, and on Android the file is
  frequently unreadable/absent so the whole SRV step fails fast (which the
  fix commit relied on).
- `resolve_host` (xmpp.rs:773-783): `tokio::net::lookup_host` is implemented
  as `spawn_blocking(getaddrinfo)` (tokio 1.53.1 `net/addr.rs:182`). The
  blocking pool is separate, so there is **no deadlock** with the
  current-thread runtime, but the wall time is bounded only by the OS
  resolver. Android netd/getaddrinfo stalls for tens of seconds to minutes on
  flaky cellular, captive-portal, private-DNS, or VPN-wedged networks.
- `resolve_endpoints` (xmpp.rs:726-746) can issue up to **three** unbounded
  lookups: SRV, then `resolve_host(server, 5222)`, then `resolve_host(server, 5223)`.

During all of this, no event exists, so the account sits in "Connecting"
indefinitely. This is the only section of the current code that is genuinely
unbounded before the first event, and it is exactly the kind of failure that
only reproduces on a device, not on the macOS dev machine where
`MINDCHAT_LIVE_TESTS=1` validated jabber.ru/xmpp.jp.

### Cause 2 - tokio-xmpp reconnects silently forever; no terminal event on connection loss (device-specific, HIGH likelihood post-Online, indefinite)

The vendored `StanzaStream::new_c2s` reconnector (vendor/stanzastream/mod.rs:127-192)
retries `client_auth` forever with exponential backoff 1s -> 30s
(`sleep(delay)`, line 182-186), only aborting on `Error::Auth`
(lines 174-180, the MindChat patch). On every disconnect the
`StanzaStreamWorker` emits `Suspended`, which `ClientWorker` discards
(client/worker.rs:101), then silently restarts the reconnector
(stanzastream/worker.rs:501-516). The frontend `Client` therefore never
learns that the stream died, and `client.next()` keeps returning `Pending`
(never `None`) as long as the reconnector keeps retrying:

- If the reconnect never succeeds (server unreachable), the main loop at
  xmpp.rs:296-331 parks forever and **no `Disconnected` is ever emitted**:
  the account shows a stale "Online" after an initial successful connect, or
  remains stuck if this state is entered during connect.
- Before the first event, this is masked by the 20s `client.next()` bound in
  `connect_attempt`, but after `Online` there is **no bound at all**.

Pre-fix (commit 286f3a1^) this same silent-reconnect loop existed with
**no 20s bounds and no preflight**, so on any unreachable server or wrong
password the account stayed in "Connecting" forever with no terminal event -
exactly the reported symptom. If the device is running versionCode 1
(0.1.0, pre-286f3a1) this is the direct root cause; the current tree is
0.1.1/versionCode 2 (app/build.gradle.kts:55-56), so verify the installed APK.

### Cause 3 - Stacked 20s per-candidate bounds: worst-case ~80s + DNS before a terminal event (environment-independent, MEDIUM likelihood of "feels forever", bounded)

`connect_attempt` (xmpp.rs:366-428) per candidate spends up to
`preflight_auth` 20s (xmpp.rs:442) plus first-event 20s (xmpp.rs:412).
A bare domain yields two candidates (SRV/5222 StartTLS + 5223 direct TLS,
xmpp.rs:738-744), an IP yields two, so worst case is ~80s before the
`Disconnected` that finally leaves "Connecting". Combined with Cause 1 the
user-visible wait can be minutes. This is bounded, but on a device where
5222/5223 are blocked by a carrier or captive portal it is a long, silent
"Connecting" that only ends as `Offline` (recoverable=true, xmpp.rs:427),
never with a precise reason.

### Cause 4 - Lost-wakeup races: `try_lock() -> Poll::Pending` without registering a waker (environment-independent, LOW likelihood, transient/pipeline-stalling)

`StanzaReceiver::poll_next` (vendor/stanzastream/mod.rs:330-336) and
`ClientReceiver::poll_next` (vendor/client/receiver.rs:42-44) return
`Poll::Pending` when `try_lock()` fails **without registering a waker on the
inner channel**. A `tokio::select!` that parks with no wake source can miss
events permanently until something else wakes it. In this code:

- `Client::poll_next` itself (vendor/client/stream.rs:30) polls the mpsc
  receiver directly (proper waker), so the connect path is safe from this.
- The internal `ClientWorker` polls `StanzaReceiver`; lock contention arises
  when a `send_stanza` (xmpp.rs:473/475/490 or FFI send) blocks on a full
  transmit queue (depth 16) while the stanzastream is reconnecting and not
  draining writes. In the worst case the pipeline stalls until the reconnect
  succeeds (transient) - never a hang that survives a successful reconnect.
- It can permanently block the worker only when the connection stays dead and
  `send_stanza` holds the lock forever; that is the same failure class as
  Cause 2 and manifests as a stale state, not a bounded recovery.

### Cause 5 - Unbounded `send_stanza` awaits inside `handle_client_event` (device-specific, MEDIUM likelihood, blocks the whole worker loop)

After `Online`, `handle_client_event` (xmpp.rs:459-493) awaits
`send_stanza(Presence)`, `send_stanza(roster IQ)` and `send_stanza(disco IQ)`
with **no timeout** (xmpp.rs:473, 475, 490). `send_stanza` waits until the
stanza reaches `StanzaStage::Sent` (vendor/client/mod.rs:94-109); if the
socket is dead but undetected (no FIN/RST; read timeout default 300s/300s ->
hard error only after ~600s, vendor/xmlstream/common.rs:63-70) or the
transmit queue is full during a reconnect, these awaits block the single
worker task: no further events are processed, and even
`WorkerCommand::Disconnect` cannot be serviced (the FFI `disconnect()` then
times out at 5s, xmpp.rs:151-155). This is post-Online (the account already
showed Online), but it is a second "worker is dead, no terminal event" state.

### Cause 6 - Leaked reconnector tasks from dropped Clients during candidate stacking (environment-independent, LOW impact, transient)

When the 20s first-event bound expires, `client` is dropped
(xmpp.rs:412-424). Dropping it tears down `ClientWorker`/`StanzaStreamWorker`
(oneshot `shutdown_tx` drop), but the **reconnector task survives**: it keeps
calling `client_auth` forever (backoff 1s->30s) against the failed endpoint,
only exiting on an `Auth` error or a successful connect whose `slot.send`
fails. Because `run_worker` keeps the runtime alive until it returns, these
tasks run concurrently on the single thread during subsequent candidates,
adding TLS dials/sockets and scheduling pressure. They die when the runtime
drops after the terminal event, so this is a resource leak during the connect
window, not a hang by itself.

### Cause 7 - Ruled out: current-thread runtime deadlocks, preflight leak, permissions, cleartext, rustls

- **`block_on` + `tokio::spawn` from resolver internals:** no deadlock.
  `tokio::net::lookup_host` uses the shared blocking pool
  (tokio net/addr.rs:182); spawned tasks (`ClientWorker`,
  `StanzaStreamWorker`, reconnector, hickory internals) are co-driven by
  `block_on`'s park loop. All connection code is on the runtime thread; there
  is no synchronous block in the worker except `read_system_conf` (fast).
- **Preflight stream drop:** `client_auth` (vendor/client/login.rs:113-138)
  spawns nothing; the returned `XmppStream` has no background tasks; dropping
  it closes the TCP socket. No leaked tasks from the preflight itself, and no
  way for it to hang the second `Client` (which is a fresh connection).
- **INTERNET permission:** present (AndroidManifest.xml:3).
- **Cleartext policy:** irrelevant here. Android's cleartext policy is
  enforced by HTTP stacks, not raw BSD sockets; the native `TcpStream` to
  port 5222 (StartTLS) is not blocked.
- **rustls provider:** `install_rustls_provider` runs first in `run_worker`
  (xmpp.rs:250, 819-821); `install_default()` failure is ignored and
  harmless. `ring` provider is enabled (Cargo.toml feature "ring").
- **Negotiation (bind) deadline:** none exists in the library
  ("TODO: define a deadline for negotiation", vendor/stanzastream/negotiation.rs:150),
  but this is masked by the 20s first-event bound during connect; it only
  contributes to Cause 2/5 post-Online.

---

## 3. Device-specific vs environment-independent

| Cause | Device-specific? | Notes |
|---|---|---|
| 1 Unbounded DNS | **Yes** - mobile/cellular, captive portal, private DNS, VPN | Dev machine has healthy DNS; device often does not. Only truly unbounded pre-first-event path. |
| 2 Silent forever-reconnect | **Yes** (network loss on mobile) | Pre-fix APK makes this deterministic for any unreachable server/wrong password. Post-fix it produces stale "Online", not "Connecting". |
| 3 Stacked 80s worst case | No | Environment-independent arithmetic of xmpp.rs:366-428. |
| 4 try_lock lost wakeup | No | Race; transient under lock contention. |
| 5 Unbounded send_stanza | Yes (dead-but-undetected socket) | Post-Online; blocks worker loop. |
| 6 Leaked reconnectors | No | Transient resource leak during connect. |
| 7 (ruled out) | - | Runtime deadlock, preflight leak, permissions, cleartext, rustls. |

---

## 4. Minimal fix set that GUARANTEES leaving "Connecting" within a bounded time

1. **Wrap the entire connect phase in one hard budget (guarantees a terminal
   event).** In `run_worker` (xmpp.rs:245), run
   `resolve_endpoints + connect_attempt` inside
   `tokio::time::timeout(CONNECT_BUDGET, ...)` (e.g. 45s) and emit
   `Disconnected{recoverable:true, detail}` on expiry, before returning.
   This makes every pre-first-event path - DNS, TCP, TLS, SASL, bind,
   stacked candidates - terminate with a terminal event within the budget,
   regardless of OS resolver stalls or library retries.

2. **Bound each DNS step.** Wrap `srv_endpoint` (5-8s) and `resolve_host`
   (5-8s) in `tokio::time::timeout`, and/or skip hickory SRV on Android
   entirely (the `read_system_conf` path is documented-unreliable there),
   going straight to the OS resolver; cap the total DNS budget inside the
   Cause-1 fix.

3. **Make post-Online connection loss observable and bounded.**
   (a) Patch `ClientWorker` (vendor/client/worker.rs:101) to forward
   `StreamEvent::Suspended` to the frontend instead of dropping it, and map it
   in `handle_client_event` to `Disconnected{recoverable:true}` after a short
   grace period; and/or
   (b) add an idle watchdog to the main loop: `tokio::time::timeout(IDLE, ...)`
   around the select at xmpp.rs:296-331 (reset on every event), emitting
   `Disconnected` on expiry; and/or
   (c) wrap the presence/roster/disco `send_stanza` calls in
   `handle_client_event` with a timeout (e.g. 10s) so the loop cannot be
   permanently blocked (Cause 5).

4. **Fix the lost-wakeup race.** In `StanzaReceiver::poll_next` and
   `ClientReceiver::poll_next` (vendor/stanzastream/mod.rs:330-336,
   vendor/client/receiver.rs:42-44), on `try_lock()` failure call
   `cx.waker().wake_by_ref()` before returning `Poll::Pending`, so a
   contended stream is re-polled instead of parking without a wake source.

5. **(Belt and suspenders, app side.)** If an account remains in
   `Connecting` longer than ~90s, force `disconnect_account` and project
   `Failed` with the transport's last detail. This guarantees a UI exit from
   "Connecting" even if a future transport bug reappears.

After fixes 1+2, the hard guarantee is: **every connect emits either
`Connected` or a terminal `Disconnected` within DNS-budget + CONNECT_BUDGET
(<= ~60s), always, on any device and any network state.** Fix 3 extends the
guarantee to the connected state.

---

## 5. Tests that would prove the fix

### Unit tests (crates/mindchat-core, no network)

- `connect_attempt` with a stub `ServerConnector` that never completes:
  assert `Preflight::ConnectionFailed` after exactly ~CONNECT_TIMEOUT, and
  total runtime <= 2 x CONNECT_TIMEOUT + slack for two candidates.
- `run_worker` connect-phase budget: a fake worker whose resolver never
  returns (inject a hanging `resolve_endpoints` replacement) must still
  produce a `Disconnected` within CONNECT_BUDGET. This is the test that
  proves Cause 1/3 are closed.
- DNS timeout: `resolve_host`/`srv_endpoint` wrappers against a
  never-responding stub resolver return a `ConnectionFailed` within the per-
  step bound.
- Lost-wakeup regression: construct a `Client`, `split()` it, hold the
  `Arc<Mutex<Client>>` lock, poll `ClientReceiver` with a waker that counts
  wakes; assert the receiver is re-polled (wake_by_ref fired) instead of
  parking with zero wake registrations.
- Main-loop watchdog: a client stream that yields no events must trigger
  `Disconnected` within the idle bound.
- FFI/coordinator: `connect_account` + injected terminal event -> account
  state leaves `Connecting` within one `poll_transport_events` call
  (extends existing `coordinator_connects_flushes_retries...` test).

### Live tests (MINDCHAT_LIVE_TESTS=1)

- `live_connect_unreachable_endpoint_is_bounded`: connect with an explicit
  TEST-NET address (203.0.113.1) for both ports; assert a terminal
  `Disconnected` arrives within <= CONNECT_BUDGET + slack (today: <= 80s;
  with the budget: <= ~45s). Run on Wi-Fi and on cellular.
- `live_connect_with_broken_dns_is_bounded`: server = a name that resolves
  slowly or not at all; assert `Disconnected` within CONNECT_BUDGET (proves
  the DNS budget on the device).
- `live_wrong_password_fails_fast` (existing `live_login_chain_reaches_sasl_...`)
  already proves the auth-preflight chain; extend it to assert elapsed time
  <= bound.
- `live_reconnect_emits_terminal_event`: connect to a real server, then block
  egress (airplane mode / firewall) and assert `Disconnected` (or a
  reconnect then `Disconnected`) within the idle bound, proving Cause 2/5.
- Instrumented Android test: add account on a device with the app's poll
  loop; assert the account leaves `Connecting` (becomes `Online` or
  `Failed`) within CONNECT_BUDGET + poll interval on Wi-Fi, and that the
  top-bar label transitions.

---

## 6. Bottom line

On the current tree the account can only stay on "Connecting" indefinitely
through the unbounded DNS section (Cause 1, device-specific) or through the
never-terminating silent-reconnect state (Cause 2, which post-Online shows
as a stale state and pre-fix 0.1.0 showed as the exact reported bug).
Everything else is bounded (Cause 3) or a secondary stall (Causes 4-6).
The minimal, guaranteed fix is a single overall connect budget around
DNS + candidate attempts (fixes 1-2), plus making connection loss visible
and time-bounded after Online (fix 3).
