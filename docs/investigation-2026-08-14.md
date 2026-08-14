# MindChat full investigation — 2026-08-14

Coordinated four same-model subagents (core, transport, Android layer, docs/build)
plus direct verification. No source files were modified. HEAD `be77346` with an
uncommitted XEP-0184 receipts WIP in `lib.rs`, `transport.rs`, `xmpp.rs`,
`MindChatGateway.kt` (316 insertions) and untracked `STATUS.md`.

## 1. Verification executed on this host

Rust 1.97.1 with clippy/rustfmt is available; there is no JDK 17+ and no Android
SDK (`local.properties` points at a removed `/tmp/mindchat-sdk`), so the Android
build/lint/instrumented path could not be re-run.

| Check | Result |
| --- | --- |
| `cargo check --offline --workspace --all-features` | PASS |
| `cargo test --offline --workspace --all-features` | 54 unit + 4 live tests PASS (live tests self-skip without `MINDCHAT_LIVE_TESTS=1`) |
| `cargo clippy --offline --workspace --all-targets --all-features -- -D warnings` | PASS |
| `cargo test --offline --workspace --no-default-features` | 31 PASS |
| `cargo fmt --all -- --check` / `git diff --check` | PASS |
| Vendored `tokio-xmpp` tests | NOT runnable offline (`ktls` absent from cache), not in CI |
| Android build/lint/APK | NOT re-runnable here; stale Aug 12 artifacts confirm a past successful build |

The WIP receipt code compiles and its 5 new tests pass; the recorded 49/30 test
counts in STATUS.md are correct at HEAD (49 = 5+7+17+7+1+12; 30 = 5+17+7+1).

## 2. STATUS.md / release-record accuracy

- **Stale:** the recorded APK hash `c44660aa…`/33,183,686 (STATUS.md:221) does
  not match the on-disk `app-debug.apk` (`1c99dd2c…`/33,338,565, Aug 12);
  STATUS.md:7 "worktree clean" is false (4 WIP files); `release-0.1.4-report.md`
  "Java 8 only / Android unverified" predates the JDK 21 build documented in
  STATUS.md and reads as contradictory without an annotation.
- **Accurate:** version lockstep (crate 0.1.4, versionName 0.1.4, versionCode 5),
  CI workflow matches the documented pipeline (Temurin 17, android-36, NDK
  29.0.14206865, Rust 1.97.1, cargo-ndk 4.1.2, uniffi 0.32.0), 16 MiB bound,
  no-password, bounded-connect, atomic-persistence, EOF-recoverable claims all
  verified in code.
- **Tags:** `v0.1.2`, `v0.1.3` exist; no `v0.1.4` tag.

## 3. WIP review — XEP-0184 receipts (uncommitted)

Verdict: sound core design, two robustness gaps.

Verified correct: `apply_delivery_update` scoping (account + bare-JID sender +
direct conversation + outgoing direction + rank non-regression), idempotent
duplicate handling, MUC rejection, `parse_receipt_message_id` strictness
(`mindchat-<u64>`, rejects 0/whitespace/foreign), direct-only `<request/>`,
ack-with-stanza-id requirement. Forged `mindchat-<n>` receipts are inherent to
XEP-0184 (advisory, no crypto binding) and are contained by the domain check.

Gaps:

1. Ack send (`xmpp.rs:537-541`) awaits `client.send_stanza` unbounded in the
   worker loop; a dead socket stalls the worker (reintroduces the audit M-3
   stall class). Wrap in the existing send timeout.
2. `MindChatGateway.kt:316-324` poll catch returns the fallback snapshot but
   skips `markDirty()`/save, so events applied before the failing event are
   visible yet may never persist (data-loss window).
3. `<request/>` is attached unconditionally although `ProtocolCapability::Receipts`
   is discovered but never consulted; inconsistent with the capability-gating
   design (MUC, reactions).
4. Ack `to` is the bare JID (XEP-0184 examples use the sender's full JID);
   multi-resource peers may see the receipt on the wrong device.
5. `delivery_rank` places `Failed`(1) below `Sent`(2) — untested latent ordering;
   add a monotonicity test.

## 4. Findings by severity

### Critical

- **C1 — Post-Online network loss never surfaces a terminal event.** The
  vendored reconnector silently retries forever (`vendor/stanzastream/mod.rs`
  139-211) and `client/worker.rs:101` discards `Suspended`, so `client.next()`
  stays `Pending` on real EOF; the 0.1.4 EOF→recoverable-`Disconnected` mapping
  (`xmpp.rs:357-365`) is only reachable via local-close/terminated paths. The UI
  shows stale "Online" indefinitely. Fix: forward `Suspended` or add an idle
  watchdog that emits `Disconnected{recoverable:true}`, and bound reconnect
  attempts per session.
- **C2 — Handoff doc cannot be used as-is.** STATUS.md APK hash and "clean
  worktree" are stale; update after committing the WIP (or delete the hash
  block), annotate the 0.1.4 report's Java-8 paragraph as superseded.

### Major

- **M1 — `flush_outbox` holds the core `Mutex` up to 15 s per send × N**
  (`ffi.rs:623-639`, `xmpp.rs:184-192`); `pollTransportEvents`, `snapshot`,
  `sendText`, and `disconnect` all block, freezing UI and persistence. A send
  that times out is marked `Failed` and retried on reconnect while the original
  may still complete → at-least-once with no dedup (duplicate delivery).
- **M2 — Poll loop can die or crash:** `refresh()`/`drainEvents`
  (`MindChatGateway.kt:326-329`) sit outside the guarded region; an escaping
  exception kills the infinite `LaunchedEffect` or crashes the app.
- **M3 — Every swallowed `MindChatBindingException` is silent:** no log, no UI,
  no counter; a poisoned-lock `Internal` failure still yields the stale-Connecting
  symptom the WIP tries to fix.
- **M4 — Android UX gaps:** no system-back handling in chat detail (back exits
  the app), MUC-create failure gives no feedback, no retry affordance while
  stuck Connecting without an error.
- **M5 — Main-thread JNA + O(history) re-projection every 750 ms**
  (`MindChatGateway.kt:379-382, 438-461`): UI jank grows with history.
- **M6 — CI gaps:** never runs `:app:testDebugUnitTest`, instrumented tests,
  `--no-default-features`, or vendored tokio-xmpp tests; no caching (cargo-ndk +
  uniffi compile every run). Vendored tests cannot run offline (`ktls` missing).
- **M7 — PLAN.md acceptance items unimplemented and untracked:** disposable
  XMPP-server e2e, Google-only dependency rejection, reproducible F-Droid APK
  (unsigned release, no F-Droid metadata, no `SOURCE_DATE_EPOCH`).

### Minor

- Vendored lost-wakeup race in `try_lock` poll paths (`stanzastream/mod.rs:357-363`,
  `client/receiver.rs:41-47`).
- `TemporaryAuthFailure` recoverable at connect but non-recoverable mid-session
  (`xmpp.rs:453-463` vs `517-528`).
- Doc drift: 64 MiB bound cited in `audit-security.md`, `release-0.1.3-spec.md`
  (code enforces 16 MiB); `verification-report.md` "no Rust integration tests"
  now false; `build-pipeline-report.md` carries 0.1.0-dev versions;
  `NATIVE_BINDING.md:49-53` implies Kotlin-owned Keystore/notifications/push and
  Rust-owned OMEMO/encrypted storage that do not exist; EN/RU strings verified
  53/53 in sync (presentation report's 51 is stale).
- Unused dangerous permissions `POST_NOTIFICATIONS`, `RECORD_AUDIO` declared
  (store-policy risk); `ConversationUi.encrypted` never set by the native
  projection yet a `🔒`/`Encrypted` UI exists (implies OMEMO that does not);
  inert Diagnostics item in settings; `persistNow` writes unconditionally on
  every `ON_STOP`; app-lock prompt nonce not bumped while `AUTHENTICATING`.
- `.gitignore`: `.kotlin/` unignored, `local.properties` duplicated,
  `vendor/tokio-xmpp/.cargo_vcs_info.json` tracked (noise only). No committed
  secrets/APKs.
- No FFI test for `poll_transport_events` clamping or `flush_outbox` offline
  gating; no rank-regression or incoming-receipt-rejection tests; ack-id can
  collide within the same millisecond (cosmetic).

## 5. Invariants — all verified intact

1. Passwords never persist (UI `remember` only, `SecretString` handoff, absent
   from snapshots/events/prefs/JSON).
2. `SecretString` redacts Debug, with a test.
3. Connect phase bounded (8 s DNS, 3 s SRV, 10 s attempt, 30 s total) with
   guaranteed terminal event during connect; TLS full verification (rustls +
   webpki-roots), no cleartext fallback.
4. Persistence atomic (temp file + `sync_all` + rename), bounded 16 MiB,
   versioned, sanitized on load, epoch-tracked from Kotlin.

## 6. Recommended priority order

1. Commit/rebase the WIP, then refresh STATUS.md (counts 54/31, receipts
   partial-caveat, correct or remove the APK hash); tag `v0.1.4`.
2. Fix the two WIP robustness points: bound the ack send; mark dirty + save in
   the poll catch path (or return the partial applied count from the FFI).
3. Make post-Online loss observable (C1): surface `Suspended` or add a watchdog.
4. Restructure `flush_outbox` (per-message timeout, sent-once marker, release
   lock between sends).
5. Extend CI: `:app:testDebugUnitTest`, `--no-default-features`, vendored tests
   (cache `ktls`), caching; add explicit tracking for the three PLAN.md
   acceptance items.
6. Repair stale docs (64 MiB refs, build-pipeline versions, NATIVE_BINDING
   aspirations, 0.1.4 report annotation).

Full per-area reports with file:line evidence were produced by the four
subagents (core, transport, Android, docs/build) and summarized here.
