# Code Quality Audit: MindChat 0.1.2 / 0.1.3

Audit date: 2026-08-10
Scope: `git diff 286f3a1..HEAD` (commits `d535c28..710466f`), covering the bounded-connect rewrite in `xmpp.rs`, the `tokio-xmpp` vendor patch, the persistence feature (`persistence.rs`, `ffi.rs`, serde derives), Kotlin dirty-flag/restore work, and tests/version bumps.
Method: full diff read of all changed files, plus in-file verification of state machines (worker.rs Connecting/Fatal paths), the poll loop, and save/load paths. No build was run (budget).

## Summary

| Severity | Count |
|---|---|
| Blocker | 1 |
| Major | 2 |
| Minor | 4 |
| Nit | 5 |

The bounded-connect rewrite and the vendored tokio-xmpp patch are, on close reading, correct: the connect phase guarantees a terminal event within 30s on every path (phase timeout at `xmpp.rs:287`, per-attempt bound at `xmpp.rs:414`, DNS total bound at `xmpp.rs:731`), the `select!` honors an immediate disconnect during "connecting", and the `Fatal` result-slot typing is consistent end to end with no SM/reconnect regression. The blocker is in the new Kotlin persistence plumbing, not in the Rust transport.

---

## Blocker

### B1. `dirty` is never cleared on successful save in `pollTransport`, so the app rewrites the state file every 750 ms forever

`app/src/main/java/com/mindchat/app/MindChatGateway.kt:287-295`

```kotlin
val userDirty = dirty
val stateChanged = userDirty || processedEvents > 0U || flushedAccounts.isNotEmpty()
if (stateChanged) {
    val saved = runCatching { core.saveState(stateFile.absolutePath) }.isSuccess
    // Clear the dirty flag only on success so a transient I/O
    // failure is retried on the next poll instead of silently
    // dropping the pending mutation. ...
    if (!saved) dirty = userDirty || dirty
}
```

The comment states the intent ("clear the dirty flag only on success"), but there is no `else { dirty = false }`. The only place `dirty` is ever reset to `false` is `persistNow()` (`MindChatGateway.kt:315`), which runs on `ON_STOP`. Consequences:

- After any user mutation (add account, send message, etc.), every 750 ms poll performs a full `core.snapshot()` + `save_state()`: serialize the whole snapshot, create a unique tmp file, `sync_all()`, rename. That is unbounded repeated disk I/O (battery, flash wear, jank on the IO dispatcher) until the activity happens to stop.
- The failure path `dirty = userDirty || dirty` is effectively a no-op (the OR is with a value that was never cleared), so the intended "retry on transient I/O failure" is not what the code does.
- `persistNow()` existing is the only thing masking this: it clears the flag at ON_STOP, at which point the "save every poll" stops.

Fix (also closes a related race): `dirty` is a plain `@Volatile` boolean with a read-modify-write gap between "snapshot taken under save" and "flag cleared", so `if (saved) dirty = false` can drop a mutation that lands mid-save (the mutation's `dirty = true` gets overwritten). Use a monotonically increasing mutation counter (`@Volatile private var mutationEpoch = 0L`, incremented in every mutator) and capture `val userEpoch = mutationEpoch` before saving; after a successful save, only clear when no mutation happened during the save:

```kotlin
val saved = runCatching { core.saveState(stateFile.absolutePath) }.isSuccess
if (saved && mutationEpoch == userEpoch) dirty = false
```

(with `dirty` still set on every mutation, and `stateChanged` derived from `dirty`). Or use an `AtomicBoolean`/CAS. Either way the "save succeeded" path must clear the flag exactly once.

## Major

### M1. Save/load size bound is asymmetric: the app can persist a file it will refuse to load

`crates/mindchat-core/src/persistence.rs:27` (`MAX_STATE_FILE_BYTES = 16 MiB`), `:131-138` (load enforces the bound) vs `:87-110` (`save_state` never bounds the serialized size).

A snapshot with enough history serializes over 16 MiB, `save_state` writes it happily, and the next startup `load_state` returns `PersistenceError::TooLarge`, the init handler renames it `.corrupt-…` aside (`MindChatGateway.kt:136-142`), and the user's data silently disappears (it is only renamed, but nothing ever reads it back). Fix: enforce the same cap in `save_state` (serialize first, then `if bytes.len() > MAX_STATE_FILE_BYTES { return Err(PersistenceError::TooLarge(..)) }` before touching the filesystem). The rename-aside behavior in Kotlin is then only reachable for genuinely corrupt/external files.

### M2. Vendor reconnector retry loop never watches for slot cancellation; the patch's exit path is only one-way

`vendor/tokio-xmpp/src/stanzastream/mod.rs:131-200`. The reconnector closure spawns a task that loops `login_with(...)` forever on non-auth errors (1 s sleep) and only exits via (a) a successful connect whose `slot.send` fails, or (b) an auth error, which the patch now routes through `slot.send(Err(e))`. If the `StanzaStream` worker is terminated (e.g. the 10 s attempt timeout in `xmpp.rs` drops the client) while the reconnector is inside the retry-sleep, the spawned task keeps dialing until it either connects or hits an auth error. This is pre-existing upstream behavior, and the patch does not regress it, but since the patch is already touching this closure it should add a cheap cancellation probe: `if slot.is_closed() { return; }` in the retry loop. Note the doc on `save_state` (persistence.rs:78-80) claims a stale staging file is "eventually reclaimed [by the OS]" — that is not true for app-private dirs; the same "cleanup" framing should not be relied on for spawned tasks. Severity: major only because a wedged TCP blackhole + user cancel leaves a stray dialing task for the process lifetime.

## Minor

### m1. Kotlin `load_state` failure handling renames on *every* refusal, including `UnsupportedVersion` and transient `Io`

`app/src/main/java/com/mindchat/app/MindChatGateway.kt:130-143`. The `runCatching { core.loadState(...) }` catches all failures and renames the file aside, then the app continues empty. For `Io` failures (e.g. a transient EAGAIN-class error) and `UnsupportedVersion`, destroying the only copy of the data is aggressive; the FFI layer already collapses all `PersistenceError` variants into one `Internal` error (`ffi.rs:416-431`), so Kotlin cannot distinguish. Fix: surface a typed error (or at least keep `TooLarge`/`Corrupt` as the only rename-worthy cases) by adding a discriminant to `MindChatBindingError` or exposing a boolean from the FFI.

### m2. `is_auth_error` marks *all* `Auth` errors non-recoverable, including `TemporaryAuthFailure`

`crates/mindchat-core/src/xmpp.rs:451-454`. `sasl::DefinedCondition::TemporaryAuthFailure` is a server-side transient condition that a later retry could clear, but it is routed to `recoverable: false` via `terminal_failure_for_event` (`xmpp.rs:456-463`). The old preflight had the same behavior, so this is not a regression, but the "recoverable" contract is now the UI's retry signal; `TemporaryAuthFailure` should be recoverable. Fix: special-case `DefinedCondition::TemporaryAuthFailure` in `is_auth_error` (or return a tri-state).

### m3. `CONNECT_ATTEMPT_TIMEOUT` budget can exceed the phase budget on 3-candidate domains

`crates/mindchat-core/src/xmpp.rs:41-51`, `:731-765`. Worst case for a bare domain: DNS 8 s + 3 candidates x 10 s = 38 s > 30 s. The phase timeout still fires at 30 s, so the bounded-connect invariant holds and the detail string is accurate, but a candidate handshake is cancelled mid-flight by the outer timeout, making the per-attempt bound partially moot for the 3rd candidate. Consider shrinking `CONNECT_ATTEMPT_TIMEOUT` to 8 s (8 + 3 x 8 = 32 > 30, so 7 s) or computing the per-attempt budget from the remaining phase time. Not a correctness bug.

### m4. No test covers the Kotlin dirty-flag / persist flow, which is where B1 lives

All persistence tests are Rust-side and are good (`persistence.rs` tests, `ffi.rs` bridge tests, `tests/live_login.rs`). The Kotlin gateway logic (dirty flag, save-on-poll, `ON_STOP` persist, rename-aside) has zero unit tests; the `PreviewMindChatGateway` (`MindChatGateway.kt:574+`) is a stub with no persistence behavior, so the dirty-flag bug shipped untested. Fix: add a JVM unit test with a temp-file `NativeMindChatGateway` (the core is a plain JNI-less handle only in the native build, so this needs the same seam used for `PreviewMindChatGateway`; at minimum test `persistNow`/poll interactions through a small interface).

## Nits

- `crates/mindchat-core/src/persistence.rs:340` — comment says "64 MiB fits usize" but the constant is 16 MiB.
- `crates/mindchat-core/src/persistence.rs:92` — `snapshot.clone()` after the FFI layer already produced an owned snapshot (`ffi.rs:653`); a double copy of a potentially large state. Take the snapshot by value in `save_state` or document the cost.
- `crates/mindchat-core/src/ffi.rs:657` vs `persistence.rs:73-80` — contradicting concurrency contracts: `save_state` doc claims concurrent saves are safe (unique tmp, last rename wins), the FFI doc says "callers must serialize concurrent save_state calls". Pick one; the unique-tmp design already makes concurrent saves safe.
- `crates/mindchat-core/src/ffi.rs:423-427` — refusal detail strings are duplicated verbatim in tests (`ffi.rs:941-946`); a wording change breaks the test for the wrong reason. Assert on the variant or a stable prefix.
- Root `Cargo.toml:11-14` — patch comment still says "the login module is public for auth preflight"; the 0.1.2 rewrite deleted the preflight (`xmpp.rs` removed `preflight_auth`). Stale comment.
- `vendor/tokio-xmpp/src/stanzastream/worker.rs:114` — `notify` field (type now `Option<(Sender<Result<Connection, _>>, _)>`) is vestigial: every construction site sets `None`, matching upstream. The type change is consistent, but the field could be removed while we own the fork.
- `crates/mindchat-core/src/persistence.rs:101-104` — no directory `fsync` after `rename`; atomicity is guaranteed, durability across a power cut is not. Fine for a chat app; worth a comment.

## Tests and version consistency

- Unit tests added are meaningful: `dedupe_candidates`, `terminal_failure_for_event` (auth vs non-auth), stanzastream `fatal_connector_error_is_surfaced_as_stream_event` (the key vendor regression test), and the persistence suite (round-trip, missing, corrupt, version, oversized, atomic-tmp, sanitize) all assert behavior, not just "did not crash". Live tests are env-gated with real deadlines (30 s/32 s blackhole, 20 s wrong-password) and assert `recoverable` and detail presence.
- Versions are consistent: `mindchat-core` 0.1.3 (Cargo.toml + Cargo.lock), app `versionName 0.1.3` / `versionCode 4` (0.1.1 was 2; 0.1.2 -> 3, 0.1.3 -> 4), composeBom bump is in lockstep. The vendor `[workspace]` table does not conflict with the root workspace (vendor is a `[patch]` source, not a member).
- The `[workspace]` addition to the vendor Cargo.toml is minimal and justified by the comment; no other vendored changes beyond the `Fatal`/`Result` slot typing, which is applied uniformly across `mod.rs`, `worker.rs`, `client/worker.rs`, and `tests.rs`.

## Recommended order of fixes

1. B1 dirty-flag clear (blocker; one line plus the epoch guard).
2. M1 save-time size bound (self-inflicted data loss on next launch).
3. M2 slot-closed probe in the vendor reconnector.
4. m1 typed persistence errors to Kotlin, m2 `TemporaryAuthFailure`.
