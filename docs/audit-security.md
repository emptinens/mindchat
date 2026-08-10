# MindChat Security Audit — 0.1.2 / 0.1.3 (diff `286f3a1..HEAD`)

Auditor scope: combined diff of the bounded-connect work (0.1.2) and JSON
persistence work (0.1.3), plus the vendored `tokio-xmpp` patch. Review date:
2026-08-10. No code was modified; this report is advisory only.

Method: manual trace of the password path, TLS path, persistence path, and
retry/bound logic; targeted greps for secrets in logs/errors; `cargo test
--lib` (41/41 pass, including `secret_debug_output_is_redacted`,
`load_state_rejects_oversized_file`, `save_state_is_atomic_temp_renamed`).

---

## Invariants that hold

1. **Passwords never cross the FFI boundary into `Ffi*` records, events,
   errors, or the persisted JSON.**
   Trace: Kotlin `connectAccount` -> `MindChatCoreHandle.connect_account`
   (`crates/mindchat-core/src/ffi.rs:584-596`) -> `SecretString::new(password)`
   (`crates/mindchat-core/src/transport.rs:25-31`) ->
   `TransportCoordinator::connect` (`crates/mindchat-core/src/lib.rs:1158-1179`)
   -> `ConnectionRequest.password: SecretString`
   (`crates/mindchat-core/src/transport.rs:41-47`) ->
   `WorkerConnection::from_request` consumes it via `into_inner()`
   (`crates/mindchat-core/src/xmpp.rs:229,248`) and it lives only in the
   worker thread's `Client` and the reconnector closure
   (`crates/mindchat-core/src/xmpp.rs:401-410`; `vendor/tokio-xmpp/src/stanzastream/mod.rs:128-199`).
   It is never retained by `MindChatCore` (no field,
   `crates/mindchat-core/src/lib.rs:349-360`), never in `CoreSnapshot`
   (`crates/mindchat-core/src/lib.rs:228-236`), and `Account` has no password
   field (`crates/mindchat-core/src/lib.rs:163-171`).
2. **`SecretString` Debug redaction is intact and tested.**
   `transport.rs:48-51` renders `SecretString([redacted])`; unit test
   `transport.rs:82-87` and coordinator test `lib.rs:1818-1822` both assert it.
   `ConnectionRequest` derives `Debug` but only ever prints the redacted
   wrapper.
3. **Serde derives exist only on snapshot model types.**
   `Serialize`/`Deserialize` appear on the enums and model structs in
   `lib.rs:52-229` plus `CoreSnapshot` (`lib.rs:228-236`) and
   `PersistedState` (`persistence.rs:27-33`). `ConnectionRequest`,
   `SecretString`, `OutgoingMessage`, and `TransportEvent` have **no** serde
   derives (`transport.rs:23-116`). The persisted JSON therefore cannot
   contain a password field by construction.
4. **No credential material in error/log strings.**
   Grep for `password`+`log|println|dbg|debug` across `.rs`/`.kt` returns
   nothing; the app has no `Log.*`/`println` calls at all. The only
   error strings that cross to the UI are `tokio_xmpp::Error` `Display`
   output (`vendor/tokio-xmpp/src/error.rs:26-100`): `AuthError::Fail`
   prints only the SASL condition (`error.rs:96-113`), `AuthError::Sasl`
   wraps `sasl` 0.5.2 `MechanismError` whose variants contain no credential
   data (verified in the locked crate source), and `I/O error: {0}` has no
   password. `FfiAccount.connection_error` (persisted in the snapshot, cleared
   on restore by `sanitize_snapshot`) is fed only by those strings plus fixed
   literals like `"connection attempt exceeded 30 seconds"`.
5. **TLS is intact on every path; no cleartext fallback.**
   StartTLS on 5222 (`vendor/tokio-xmpp/src/connect/starttls.rs:66-96`),
   which **refuses to proceed** when the server does not advertise STARTTLS
   (`ProtocolError::NoTls`, `starttls.rs:88-92`); direct TLS on 5223
   (`vendor/tokio-xmpp/src/connect/direct_tls.rs:44-71`). Both go through
   `establish_tls_connection` (`vendor/tokio-xmpp/src/connect/tls_common.rs:125-147`):
   rustls with webpki-roots (`tls_common.rs:135`) or native certs
   (`tls_common.rs:140`), standard `ClientConfig::builder()` with full chain
   **and hostname** verification via `ServerName::try_from` (`tls_common.rs:130`)
   and `with_root_certificates`/`with_no_client_auth` (`tls_common.rs:145-146`).
   There is no `dangerous()`/`set_certificate_verifier` bypass anywhere.
   Features are `direct-tls, ring, starttls, webpki-roots`
   (`crates/mindchat-core/Cargo.toml:28`); `insecure-tcp` is not enabled and
   `TcpServerConnector` is not referenced in `mindchat-core`. The provider
   install is correct: `install_rustls_provider()` installs the ring default
   provider once per worker (`xmpp.rs:897-899`). The vendored patch touches
   only `Cargo.toml`, `client/worker.rs`, and `stanzastream/*` — no TLS file.
6. **Size bound is enforced before reading into memory.**
   `load_state` checks `metadata.len() > MAX_STATE_FILE_BYTES` (64 MiB)
   **before** `std::fs::read` (`persistence.rs:120-127,129`), then re-checks
   the post-read byte count before parsing (`persistence.rs:130-133`) — this
   closes the metadata/read TOCTOU.
7. **Corrupt/oversized/unknown-version files fail gracefully, never crash.**
   `serde_json::from_slice` errors map to `PersistenceError::Corrupt`
   (`persistence.rs:135-136`); version mismatch to `UnsupportedVersion`
   (`persistence.rs:137-139`); oversized to `TooLarge`. Kotlin catches the
   resulting `MindChatBindingException`, renames the file aside, and starts
   with an empty core (`MindChatGateway.kt:140-150`). Unknown JSON fields are
   ignored by serde (no `deny_unknown_fields`), which is safe because
   `schema_version` guards cross-version files.
8. **Atomicity.** Save is serialize -> write `<path>.tmp` -> `sync_all` ->
   `rename` over the target (`persistence.rs:84-104`); a crash leaves only a
   stale `.tmp` that the next save overwrites. Tested
   (`persistence.rs:334-345`).
9. **Path safety (same-app scenario).** The state path is the fixed constant
   `File(context.filesDir, "mindchat_state.json")` (`MindChatGateway.kt:113`);
   no user input reaches the path. `tmp_path_for` only appends `.tmp`
   (`persistence.rs:164-168`). `rename` over a symlink at `path` replaces the
   link rather than writing through it. The corrupt-file rename target is a
   constant name plus a millisecond timestamp in app-private `filesDir`
   (`MindChatGateway.kt:147`) — no injection.
10. **No world-readable export.** `filesDir` is app-private (parent 0700);
    `allowBackup="false"` and both backup rules exclude everything
    (`AndroidManifest.xml`; `backup_rules.xml`; `data_extraction_rules.xml`),
    so chat history is neither backed up to the cloud nor transferable. There
    are no exported components. Default file mode (0666 & ~umask, typically
    0644) is acceptable because the parent directory is not traversable by
    other apps.
11. **No unbounded retry in the connect hot path.**
    DNS capped at 8 s (`xmpp.rs:747`), SRV at 3 s inner (`xmpp.rs:773`), one
    attempt capped at 10 s (`xmpp.rs:411`), whole phase at 30 s via the
    `tokio::select!` deadline (`xmpp.rs:282-302`), and the phase is selectable
    against the command channel so a user disconnect is honored immediately.
    Authentication failure is terminal: the vendored `Fatal` path surfaces
    `Error::Auth` through the slot (`stanzastream/mod.rs:183-192`), the worker
    terminates (`stanzastream/worker.rs:193-198`), the client worker emits
    `Event::Disconnected(error)` (`client/worker.rs:105`), and
    `terminal_failure_for_event` marks it non-recoverable
    (`xmpp.rs:447-465`). Kotlin never auto-reconnects: reconnect is
    user-initiated (`MindChatApp.kt:196-208`).

---

## Findings (severity-ranked)

### MAJOR

#### M1 — Post-Online silent reconnect loop never emits a terminal event
- **Where:** `vendor/tokio-xmpp/src/stanzastream/mod.rs:140-199` (reconnector
  `loop` with `delay *= 2`, capped at 30 s, no attempt cap, no give-up);
  `client/worker.rs:101` discards `Suspended` without an event;
  `crates/mindchat-core/src/xmpp.rs:330-353` (the post-connect main loop parks
  on `client.next()` with no bound).
- **Issue:** after a session drops post-Online, tokio-xmpp reconnects forever
  (backoff 1 s -> 30 s) and never surfaces anything to the UI. The account
  stays pinned at "Online" indefinitely; the reconnect loop re-runs a full
  TLS + SASL login (with the password held in the closure) once per 30 s for
  as long as the server is unreachable. Auth failures are fixed by the
  vendored `Fatal` patch, but any non-auth failure (server down, network
  loss, wrong DNS) is not. The `else` branch that would emit
  `Disconnected { recoverable: false, .. }` (`xmpp.rs:349-353`) is effectively
  unreachable on this path. This is documented as a known gap in
  `docs/transport-hang-analysis.md:74-91` ("Cause 2").
- **Security impact:** availability/observability DoS and battery drain, not
  a confidentiality break. It is rate-limited (max one attempt/30 s) and
  TLS-protected, and the account worker still services `Disconnect` commands
  (`xmpp.rs:331-340`), so a user can recover manually.
- **Fix (suggested):** bound the *established* session too: e.g., cap
  reconnect attempts (give up after N consecutive failures and emit
  `StreamEvent::Fatal`/`Disconnected`), or surface `Suspended` to the app
  after an idle/reconnect threshold so the UI can show "Offline (reconnecting)"
  and the worker can honor a timeout. If silent resume (XEP-0198) is desired,
  at minimum emit a `Disconnected { recoverable: true }` when the reconnect
  budget is exhausted.

#### M2 — Concurrent saves can corrupt the persisted file (integrity)
- **Where:** `crates/mindchat-core/src/ffi.rs:657-668` (doc: "Callers must
  serialize concurrent `save_state` calls"); Kotlin does **not**:
  `MindChatGateway.kt:287` (pollTransport, every 750 ms) and
  `MindChatGateway.kt:306` (`persistNow` on `ON_STOP`,
  `MindChatApp.kt:95-105`) both run on `Dispatchers.IO` and can overlap.
  Both write the **same** `<path>.tmp` (`persistence.rs:91-96`).
- **Issue:** two interleaved `write_all` calls on two file descriptors of the
  same `.tmp` path produce mixed content; whichever `rename` lands last wins
  with the corrupted bytes. The next launch fails to parse it, renames the
  file aside (`MindChatGateway.kt:144-149`), and the user's full history is
  lost. Low probability, total-loss impact.
- **Fix (suggested):** serialize saves in the gateway (a `Mutex`/single
  `Channel` writer on IO), or make `save_state` internally atomic against
  itself with a per-path mutex / `O_EXCL` staging + unique tmp name
  (`<path>.<pid>.<counter>.tmp`).

### MINOR

#### m1 — `loadState` runs on the main thread at first composition
- **Where:** `MindChatGateway.kt:140-150`; constructed from
  `remember(context) { MindChatGatewayFactory.create(context) }`
  (`MindChatApp.kt:88`).
- **Issue:** the blocking FFI load (read + JSON parse, up to 64 MiB by the
  bound) runs on the UI thread during first composition; a large state file
  risks an ANR on cold start.
- **Fix:** construct/load on `Dispatchers.IO` and publish the restored
  snapshot back to the UI state.

#### m2 — Save failures are swallowed and `dirty` is cleared regardless
- **Where:** `MindChatGateway.kt:284-287` (`val userDirty = dirty; dirty =
  false` *before* `runCatching { core.saveState(...) }`) and
  `MindChatGateway.kt:306-307` (`persistNow` clears `dirty` even if the save
  threw).
- **Issue:** a transient I/O failure silently drops the pending mutation; the
  app then reports the account/chat as persisted while the file is stale.
- **Fix:** clear `dirty` only on a successful save; retry on the next poll and
  surface a non-fatal error.

#### m3 — 64 MiB bound is generous; load has unbounded record-count memory cost
- **Where:** `persistence.rs:24` (`MAX_STATE_FILE_BYTES`), load path
  `persistence.rs:119-141`, rebuild `lib.rs:375-400`.
- **Issue:** a same-app corrupt/hostile file within the bound can hold on the
  order of 10^5-10^6 records, amplifying to hundreds of MB during
  deserialization + `from_snapshot`, on the main thread (see m1). Same-app
  only (file is app-private), so risk is low, but the bound alone does not
  bound memory.
- **Fix:** lower the bound (e.g., 8-16 MiB) and/or cap record counts per
  collection before materializing the core; refuse (rename-aside) beyond the
  cap.

#### m4 — Corrupt-file rename failure is unchecked
- **Where:** `MindChatGateway.kt:147` — `stateFile.renameTo(...)` return value
  ignored.
- **Issue:** if the rename fails (rare), the corrupt file remains; every
  subsequent launch repeats load-fail-rename (plus a new `.corrupt-*` file).
  Not a loop within a session, just repeated startup churn and file
  accumulation.
- **Fix:** check the return value; if rename fails, log once and continue
  (or overwrite the corrupt file with a fresh save).

### INFO

#### i1 — No explicit file permission hardening
- **Where:** `persistence.rs:91` (`std::fs::File::create`, mode 0666 & ~umask).
- **Note:** acceptable because the parent `filesDir` is app-private (0700);
  defense-in-depth: `std::os::unix::fs::PermissionsExt::set_mode(0o600)` on
  both `path` and the `.tmp` staging file.

#### i2 — No parent-directory fsync after `rename`
- **Where:** `persistence.rs:96`.
- **Note:** the rename itself is atomic, but the directory entry may not be
  durable across power loss. Fine for app state; a `File::open(parent)?.sync_all()`
  closes the gap if crash-durability ever matters.

#### i3 — FFI path parameter is an unconstrained `String`
- **Where:** `ffi.rs:657` (`save_state`) and `ffi.rs:671` (`load_state`).
- **Note:** only the app calls these today, with a fixed `filesDir` path, so
  no traversal is reachable. A future caller could persist chat history to a
  world-readable location or through a symlink. Consider constraining to a
  directory + fixed filename, or validating that the path is under
  `filesDir`.

#### i4 — Chat history is plaintext at rest
- **Where:** persisted JSON (`persistence.rs:84-104`), message bodies
  included.
- **Note:** mitigated by app-private storage, `allowBackup=false`, and no
  exported components. The `CoreStore` docs (`lib.rs:301-306`) already
  envision SQLCipher-backed storage; the JSON snapshot is a reasonable
  non-secret interim, but message content is not encrypted at rest.

#### i5 — Stray reconnector tasks after a cancelled connect attempt
- **Where:** `vendor/tokio-xmpp/src/stanzastream/mod.rs:128-199` spawns a
  detached `tokio::spawn` per attempt; the 10 s/30 s timeouts cancel
  `client.next()` but the spawned task exits only when its next
  `slot.send` fails (receiver dropped) or after a failed login + up to 30 s
  sleep.
- **Note:** bounded, transient (one task per candidate, exits on its own);
  no leaked live sessions because a successful auth after cancellation hits a
  dead slot and closes the stream gracefully.

#### i6 — `.corrupt-*` files accumulate
- **Where:** `MindChatGateway.kt:147`.
- **Note:** app-private, small; clean up stale `mindchat_state.json.corrupt-*`
  after the first successful save/load.

---

## Severity summary

| Severity | Count | IDs |
|---|---|---|
| Blocker | 0 | — |
| Major | 2 | M1 (silent forever-reconnect post-Online), M2 (concurrent-save corruption) |
| Minor | 4 | m1-m4 |
| Info | 6 | i1-i6 |

## Bottom line

The two headline invariants hold: **passwords are never persisted and never
leak into FFI records, events, errors, or the JSON snapshot**, and **TLS is
intact on every path (StartTLS 5222 / direct TLS 5223) with full rustls
certificate and hostname verification and no cleartext fallback**. The
vendored patch is minimal, well-scoped, and does not weaken certificate
verification; it correctly turns auth rejection into a terminal,
non-recoverable event. The remaining actionable items are M1 (bound the
established-session reconnect loop so the UI always gets a terminal event)
and M2 (serialize saves), both availability/integrity rather than
confidentiality issues.
