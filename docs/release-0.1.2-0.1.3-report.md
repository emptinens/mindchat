# MindChat 0.1.2 + 0.1.3 — Release Report

Date: 2026-08-10. Coordinator: Jcode. Process: agent company (architect,
investigator, implementers, auditors, verifier) with local git commits.

## Problem reported

Login did not work on the Android device: an account stayed on **"Connecting"**
forever, with no transition to Online or Failed and no error shown.

## Root cause (confirmed by independent analysis)

`docs/transport-hang-analysis.md` (independent investigator) and
`docs/release-0.1.2-spec.md` (architect) converged on:

1. **Unbounded DNS phase** — `run_worker` awaited `resolve_endpoints` with no
   deadline. `hickory` SRV lookup and `getaddrinfo` can hang for a long time on
   a device with wedged DNS (captive portal, VPN, no network). No terminal
   event was emitted, so the UI stayed on "Connecting" indefinitely.
2. **Double handshake per candidate** — a bounded auth preflight ran a full
   `TLS + SASL` login, then a **second** `Client` connected again. Up to 40 s
   per candidate before any event, with the preflight's value questionable.
3. **Stacked timeouts** — up to 3 candidates × 20 s each ≈ 120 s plus DNS.
4. **Silent fatal errors in vendored tokio-xmpp** — on rejected credentials the
   reconnector gave up **without** sending on the oneshot slot; the worker
   parked forever and `client.next()` never yielded. `Event::Disconnected`
   could not reach the `Client` on this path at all.

The macOS live tests passed because the failure modes are device/network
specific (reproduced only on-device).

## Process (agent company)

| Wave | Role | Output |
| --- | --- | --- |
| 1 | Architect (deep read, spec) | `docs/release-0.1.2-spec.md`, `docs/release-0.1.3-spec.md` |
| 1 | Independent investigator (root cause) | `docs/transport-hang-analysis.md` |
| 2 | Implementer (Rust transport) | 0.1.2 transport rewrite + vendored patch |
| 3 | Implementer (Rust persistence) | 0.1.3 `persistence.rs` + FFI |
| 3 | Implementer (Kotlin gateway) | restore/persist flow + lifecycle hook |
| 4 | Auditor (security) | `docs/audit-security.md` (0 blockers) |
| 4 | Auditor (architecture) | `docs/audit-architecture.md` |
| 4 | Auditor (code quality) | `docs/audit-code-quality.md` |
| 5 | Verifier (me) | fmt/clippy/tests/live tests/Android build |

All agents ran the same model as the coordinator (`deepseek-v4-flash`) at
maximum effort. Work was partitioned by file ownership to avoid collisions and
committed locally between waves.

## Release 0.1.2 — bounded connect (versionCode 3)

Commit `75ba93b` + audit follow-ups (`710466f`, `4b3e89e`).

- **Guaranteed terminal event:** the whole connect phase (DNS + all candidate
  handshakes) runs under a 30 s `CONNECT_PHASE_TIMEOUT` and is selectable
  against the command channel; a `Disconnected` is always emitted within
  budget, and a user disconnect during "connecting" is honored immediately.
- **Single handshake:** removed the auth preflight; one bounded 10 s wait per
  candidate; auth rejection arrives as the first event with the server's
  precise SASL reason and maps to a non-recoverable `Disconnected`.
- **Android-first DNS:** `getaddrinfo` candidates (5222 StartTLS, 5223 direct
  TLS) come first under an 8 s DNS budget; hickory SRV is a 3 s best-effort
  fallback appended after them; duplicates deduped.
- **Vendored patch:** fatal connector failures surface as
  `StreamEvent::Fatal` → `Event::Disconnected(Error::Auth(_))` instead of
  hanging `client.next()`; the reconnect loop probes `slot.is_closed()` to stop
  stray dialing tasks; `TemporaryAuthFailure` is classified recoverable.
- **UI:** Reconnect action now also appears for recoverable connect errors
  (Offline with a reason), so a wedged DNS always has a visible retry button.

## Release 0.1.3 — local persistence (versionCode 4)

Commits `2e98a3f` + audit follow-ups.

- Accounts, contacts, conversations, messages, and reactions persist to a
  versioned JSON file (`mindchat_state.json` in app-private `filesDir`).
- Atomic writes (unique staging file + `sync_all` + `rename`), 16 MiB size
  bound enforced on both save and load, schema version 1, corrupt/unknown
  files refused and renamed aside, never crash.
- **Passwords are never stored.** Restored accounts come back `Offline` with
  cleared errors/capabilities; reconnect re-asks for the password.
- Kotlin restores on startup, flushes via the poll loop (dirty flag cleared
  only on success, restored on failure), and has an `ON_STOP` lifecycle hook.

## Verification (evidence)

- `cargo fmt --all -- --check` — clean
- `cargo clippy --workspace --all-targets -- -D warnings` — clean
- `cargo test --workspace` — **42 unit tests** pass
- `cargo test --manifest-path vendor/tokio-xmpp/Cargo.toml` — **17 tests** pass
- Live tests (`MINDCHAT_LIVE_TESTS=1`), all 4 pass:
  - blackhole `10.255.255.1` → terminal `Disconnected` in **20.1 s** (≤ 30 s bound)
  - wrong password on jabber.ru → terminal failure in **312 ms** with precise
    `NotAuthorized` detail, no preflight
  - jabber.ru SASL rejection → non-recoverable with detail
  - SRV/OS resolution works
- Android build (`:app:assembleDebug`, JDK 17, NDK 29, 3 ABIs) — **BUILD
  SUCCESSFUL**, APK packaged

## Audit outcome

- **Security** (`docs/audit-security.md`): 0 blockers. Invariants confirmed:
  passwords never cross FFI or reach persisted JSON; TLS intact on every path
  (rustls, full cert + hostname verification, no cleartext fallback); no
  unbounded retry in the connect hot path.
- **Architecture** (`docs/audit-architecture.md`): restore semantics, state
  machine transitions, SM resume preserved; SQLCipher migration path is clean.
- **Code quality** (`docs/audit-code-quality.md`): the final review found and
  we fixed the dirty-flag lifecycle bug, save-time size bound, stray-dialing
  task, and auth classification; no open blockers/majors remain.

## Follow-ups (out of scope for 0.1.2/0.1.3)

- Bound the **established-session** reconnect loop (post-Online silent
  reconnect never emits a terminal event today; documented gap M-1/M-3).
- `flush_outbox` holds the core mutex across blocking sends; move off the lock.
- Async restore on `Dispatchers.IO` at startup (currently synchronous init).
- Instrumented Kotlin tests for the persistence flow on a device/emulator.
- SQLCipher-backed storage and account registration (plan milestones 2+).
