# MindChat verification and gap-analysis report

Date: 2026-08-08. Author: verification/gap-analysis researcher.
Scope: PLAN.md, README.md, docs/EXTENSIONS.md, docs/NATIVE_BINDING.md, all Rust
`#[test]` functions in `crates/mindchat-core/src/**`, all Kotlin tests in
`app/src/test/**` and `app/src/androidTest/**`, and all CI/lint config in the
repo. All counts below were confirmed by source inspection; the Rust suite was
executed twice on this machine.

Verification runs performed:

- `cargo test --workspace` (default features) → **29 passed, 0 failed**.
- `cargo test --workspace --all-features` → **33 passed, 0 failed** (the 4
  extra tests are the `uniffi`-gated FFI bridge tests, `ffi.rs:19-20`).
- `./gradlew :app:testDebugUnitTest` → **could not run**: the only JDK on this
  machine is 1.8 (`java version "1.8.0_491"`), and AGP 8.11.0 requires JVM 11+
  ("Dependency requires at least JVM runtime version 11"). Android unit and
  instrumented counts below are exact source counts, not run results.
- Android instrumented tests additionally require an emulator/device, which
  this environment does not provide; CI does not execute them either (see §2).

---

## 1. Test inventory

### 1.1 Rust unit tests — 33 total (29 under default features)

#### `crates/mindchat-core/src/lib.rs` — `mod tests`, 17 tests (lib.rs:1257-1856; in-process `FakeTransport`, no network)

| Test (line) | Behavior asserted |
| --- | --- |
| `rejects_invalid_account_identifiers` (1302) | JID without domain, server with whitespace, JID with resource all rejected (`InvalidJid`/`InvalidServer`) |
| `accounts_start_without_undiscovered_optional_capabilities` (1319) | Fresh account has an empty capability set |
| `roster_contacts_are_account_scoped_normalized_and_snapshot_safe` (1327) | Contacts scoped per account; display-name/status trimming; `RosterChanged` events in order; snapshot restore |
| `roster_contact_rejects_unknown_accounts_and_invalid_jids` (1381) | `UnknownAccount` on upsert for missing account; `InvalidJid` for malformed contact |
| `server_roster_and_presence_events_preserve_subscription_state` (1395) | Roster upsert preserves presence; directed (non-roster) presence ignored; removal emits event and clears contact |
| `rejects_invalid_conversation_address_and_cross_conversation_reply` (1443) | `InvalidConversationAddress`; reply to a message in another conversation rejected (`InvalidReplyTarget`) |
| `capability_gates_reactions` (1474) | `add_reaction` fails `CapabilityUnavailable(MessageReactions)` without the capability |
| `preserves_message_order_and_transitions_delivery` (1492) | Message IDs stay ordered; `in_reply_to` linkage; delivery-state transition |
| `capability_gates_group_chats` (1512) | MUC conversation creation gated by `MultiUserChat` capability |
| `incoming_messages_increment_and_read_clears_unread_count` (1530) | Unread increments on receive; `mark_conversation_read` clears it |
| `addressed_incoming_text_creates_the_direct_conversation_once` (1545) | One conversation per (account, kind, address); messages append in order; unread=2 |
| `snapshot_round_trip_keeps_stable_ids` (1584) | Account/conversation/message IDs and reaction emoji survive restore |
| `transport_events_project_connection_incoming_messages_and_receipts` (1605) | `Connected` sets Online + capabilities; `DeliveryUpdated`; `IncomingText` unread; `Disconnected` non-recoverable → Failed |
| `queued_outgoing_messages_survive_snapshot_restore_and_are_account_scoped` (1656) | Outbox per account, message-id order, reply linkage after restore, `UnknownAccount` error |
| `extension_commands_are_permissioned_and_attributed_to_the_owning_account` (1690) | Denied extension command rejected pre-mutation; granted command sends with owning account's JID |
| `extension_commands_still_obey_server_capability_gates` (1752) | Extension `AddReaction` blocked by `CapabilityUnavailable` even after authorization |
| `coordinator_connects_flushes_retries_and_projects_transport_events` (1785) | Connect (password Debug-redacted), event polling, outbox flush order, failed send → `Failed` + retained retry, disconnect → Offline |

#### `crates/mindchat-core/src/transport.rs` — `mod tests`, 1 test (transport.rs:141-150)

| Test (line) | Behavior asserted |
| --- | --- |
| `secret_debug_output_is_redacted` (145) | `SecretString` Debug output is `SecretString([redacted])` |

#### `crates/mindchat-core/src/xmpp.rs` — `mod tests`, 6 tests (xmpp.rs:591-722; XML parsed via `xmpp_parsers`, no live server)

| Test (line) | Behavior asserted |
| --- | --- |
| `maps_discovered_features_without_claiming_unknown_capabilities` (596) | Disco#info XML → known capabilities only; unknown feature dropped |
| `maps_roster_subscription_and_removal_events` (613) | Roster XML → upsert/removal events with subscription direction (`to`→Inbound, `remove`→Removed, `ask=subscribe`→PendingOutbound) |
| `maps_available_and_unavailable_presence_to_bare_jid` (644) | Presence show/status and unavailable → bare-JID `ContactPresenceUpdated` |
| `maps_direct_message_stanzas_without_leaking_resource_addresses` (673) | Chat stanza → `IncomingText`; resource stripped from address/sender |
| `accepts_explicit_host_ports_but_preserves_srv_default_hosts` (696) | DNS config: SRV default vs `host:port` (NoSrv) vs literal address vs bad port error |
| `disconnected_events_release_the_account_worker_slot` (704) | Polling a `Disconnected` event removes the worker handle |

#### `crates/mindchat-core/src/ffi.rs` — `mod tests`, 4 tests (ffi.rs:737-849; feature `uniffi` only)

| Test (line) | Behavior asserted |
| --- | --- |
| `bridge_exposes_safe_snapshot_and_events` (741) | `MindChatCoreHandle` add account/conversation/message; snapshot DTOs and `FfiCoreEvent` stream in order |
| `bridge_exposes_roster_contacts_without_transport_or_secret_types` (785) | Contact projection through FFI (jid, presence, status, subscription); only `RosterChanged` event |
| `bridge_maps_domain_errors_without_exposing_internal_types` (818) | Domain error → `MindChatBindingError::InvalidInput` |
| `bridge_rejects_empty_passwords_before_starting_a_transport_worker` (827) | Empty password → `InvalidInput`; zero-event poll; account stays Offline |

#### `crates/mindchat-core/src/extension.rs` — `mod tests`, 5 tests (extension.rs:368-463)

| Test (line) | Behavior asserted |
| --- | --- |
| `manifest_requires_a_stable_reverse_domain_id` (372) | Non-reverse-domain ID and trailing hyphen rejected; valid ID accepted |
| `permission_manifest_names_round_trip_and_reject_unknown_values` (384) | `manifest_name`/`from_manifest_name` round trip; unknown spelling → `None` |
| `policy_filters_events_by_specific_observation_permissions` (395) | `visible_events` emits only granted ID-only events |
| `policy_denies_requested_commands_that_the_host_did_not_grant` (428) | `authorize_command` denial with extension ID + permission |
| `policy_rejects_a_grant_that_the_manifest_did_not_request` (450) | `UndeclaredGrant` at policy construction |

### 1.2 Android unit tests — 6 (app/src/test)

#### `app/src/test/java/com/mindchat/app/AppLockStateMachineTest.kt` — 6 tests (pure JVM, no Android framework)

| Test (line) | Behavior asserted |
| --- | --- |
| `enablingLockBlocksContentAndRequestsOneAutomaticPrompt` (9) | Enable → locked, content blocked, one automatic prompt (`automaticPromptNonce=1`) |
| `cancellationKeepsTheGateLockedWithoutReopeningTheSystemPrompt` (21) | Cancel → stays LOCKED, no new automatic prompt |
| `backgroundingAfterSuccessLocksAgainAndSchedulesAnotherPrompt` (34) | Background after unlock → relock + nonce 2 |
| `backgroundingWhileAlreadyLockedSchedulesAuthenticationOnReturn` (47) | Background while locked → nonce 2 on return |
| `disablingLockMakesLateAuthenticationCallbacksHarmless` (60) | Disable → late success callback is a no-op; state UNLOCKED, no block |
| `successWithoutAnActivePromptCannotUnlockTheGate` (74) | Success with no active authentication does not unlock |

### 1.3 Android instrumented tests — 4 (app/src/androidTest)

#### `app/src/androidTest/java/com/mindchat/app/MindChatAppTest.kt` — 4 tests (Compose UI rule on `MainActivity`, uses `PreviewMindChatGateway`/`InMemoryMindChatPreferences`, no native library)

| Test (line) | Behavior asserted |
| --- | --- |
| `addAccountFlowIsAvailableBeforeAnyAccountExists` (18) | Home renders "MindChat"; "Add account" opens the flow; "JID" field displayed |
| `customizationChoicesSurviveGatewayRecreation` (25) | Dynamic-color/comfortable-layout/app-lock toggles persist across gateway recreation |
| `localContactsAreScopedToTheActiveAccountAndUseTheProvidedDisplayName` (40) | Contact scoped to active account; display name used; duplicate conversation open returns same ID |
| `accountSetupRequiresAPasswordBeforeThePreviewSessionIsCreated` (55) | Empty password rejects account; non-empty password adds it |

**Exact counts:** Rust unit 33 (29 default features, 33 `--all-features`, both
verified by execution), Android unit 6 (source count; not executed), Android
instrumented 4 (source count; not executed). There are no Rust integration
tests (`crates/mindchat-core/tests/` does not exist) and no other Kotlin test
files in the repo.

---

## 2. CI and lint configuration

### CI: yes, one workflow

`.github/workflows/verify.yml` (the only CI file in the repo; no
`.gitlab-ci.yml`, Makefile, or Justfile anywhere).

- **Triggers** (verify.yml:3-6): `pull_request`; `push` to `main`. Permissions: read-only contents (verify.yml:8-9).
- **Job `rust`** (verify.yml:12-22), Ubuntu, Rust 1.97.1 pinned via `dtolnay/rust-toolchain` (matches `rust-toolchain.toml`):
  1. `cargo fmt --all -- --check` (verify.yml:20)
  2. `cargo clippy --workspace --all-targets --all-features -- -D warnings` (verify.yml:21)
  3. `cargo test --workspace --all-features` (verify.yml:22)
- **Job `android`** (verify.yml:24-41), Ubuntu: JDK 17 (Temurin), `android-actions/setup-android`, Rust Android targets, `sdkmanager` installs `platforms;android-36`, `build-tools;36.0.0`, `ndk;29.0.14206865` (verify.yml:37-38), `cargo-ndk 4.1.2` and `uniffi 0.32.0` (verify.yml:39), then:
  `./gradlew :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --max-workers=2` (verify.yml:41)

**CI gaps:** no `testDebugUnitTest` (Android unit tests never run in CI), no
`connectedAndroidTest` (instrumented tests never run, even on an emulator), and
no Kotlin static-analysis tool (no ktlint/detekt anywhere). CI "test" coverage
is Rust-only; the Android job only compiles the test sources.

### Lint configuration

- Rust: `rustfmt.toml` (edition 2024, max_width 100); workspace lints in
  `Cargo.toml:9-17` (`unsafe_code = "forbid"`, `missing_docs = "warn"`, clippy
  `all` + `pedantic` at warn with `module_name_repetitions` allowed);
  `rust-toolchain.toml` pins 1.97.1 with `clippy`/`rustfmt` components.
- Android: AGP `lintDebug` runs in CI; `app/build.gradle.kts` enables Compose
  and buildConfig. No Kotlin lint config file exists.

### .gitignore

`.gitignore` covers Gradle/Android Studio outputs (`.gradle/`, `build/`,
`**/build/`, `local.properties`, `.idea/`, `*.iml`), Android artifacts
(`*.apk`, `*.aab`, `captures/`), Rust `target/`, native-library staging
(`app/src/main/jniLibs/`), and secrets/diagnostics (`*.keystore`, `*.jks`,
`*.log`, `mindchat-diagnostics-*.zip`). No committed keystores or logs exist.

---

## 3. Milestone mapping (PLAN.md:32-53)

Legend: **Done** = in code, **Partial** = skeleton/projection/capability only,
**Missing** = no code. Evidence is `file:line`.

### M1 — Foundation (PLAN.md:34-36)

| Deliverable | Status | Evidence |
| --- | --- | --- |
| Workspace | Done | `Cargo.toml:1-3` (`[workspace] members = ["crates/mindchat-core"]`); `settings.gradle.kts:15` (`include(":app")`) |
| Android shell | Done | `app/src/main/java/com/mindchat/app/MindChatApp.kt` (Compose shell, chat/contacts/settings); `MainActivity.kt:32-35` setContent |
| Rust state core | Done | `MindChatCore` `lib.rs:365-377`; snapshot/restore `lib.rs:381-422` |
| Typed interface | Done | UniFFI DTOs/commands `ffi.rs:20-353`; `MindChatCoreHandle` `ffi.rs:417-627`; `uniffi.toml` (`com.mindchat.core`, android=true) |
| CI | Done | `.github/workflows/verify.yml` (see §2) |
| Linting | Done | `rustfmt.toml`; `Cargo.toml:9-17`; `rust-toolchain.toml`; `:app:lintDebug` in CI |
| Test fixtures | Done | `FakeTransport` `lib.rs:1262-1294`; `InMemoryStore` `lib.rs:346-361`; `PreviewMindChatGateway` `MindChatGateway.kt:400-531`; `InMemoryMindChatPreferences` `MindChatPreferences.kt:50-61` |
| Localization | Done | `values/strings.xml` and `values-ru/strings.xml`, 51 keys each, key sets identical (diffed); `supportsRtl="true"` `AndroidManifest.xml:14` |
| Accessibility semantics | Done (untested) | `contentDescription` on add-account `MindChatApp.kt:345-350`, add-contact `:266`, new-chat `:263-266`, back `:531-535`; all UI text is `Text`. No test asserts semantics (PLAN.md:101-102 acceptance claim not covered) |
| No-telemetry diagnostics policy | Done | No analytics SDK in `gradle/libs.versions.toml`; manifest permissions only INTERNET/POST_NOTIFICATIONS/RECORD_AUDIO `AndroidManifest.xml:3-5`; backups fully excluded `res/xml/backup_rules.xml` and `data_extraction_rules.xml`; policy strings `strings.xml` (`diagnostics_summary` "Export only when you choose to share it", `privacy_summary` "No analytics or MindChat cloud account") |

M1 estimate: **10/10 Done → 100%** (caveat: accessibility and diagnostics are
code/policy-only, not test-verified).

### M2 — Protocol and persistence (PLAN.md:37-41)

| Deliverable | Status | Evidence |
| --- | --- | --- |
| Rust XMPP transport behind internal adapter | Done | `XmppTransport` trait `transport.rs:134-139`; `TransportCoordinator<T>` `lib.rs:1103-1204`; `TokioXmppTransport` `xmpp.rs:45-191`; transport types never exported (module gated `lib.rs:16-17`) |
| StartTLS | Done (untested live) | `Client::new_starttls` `xmpp.rs:242-247`; rustls provider `xmpp.rs:563-565`. No live-server test |
| SRV | Done | `dns_config_for_server` `xmpp.rs:510-527` (SRV default / `no_srv` / literal `Addr`); test `xmpp.rs:696-702` |
| Service discovery (XEP-0030) | Done | Disco query sent on Online `xmpp.rs:313-321`; result handled `xmpp.rs:363-374`; feature mapping `xmpp.rs:475-500`; test `xmpp.rs:596-611` |
| Account login | Done | `connect_account` `ffi.rs:561-572`; SASL auth error mapping `xmpp.rs:584-589`; coordinator hand-off `lib.rs:1146-1165` |
| Account registration (XEP-0077) | Missing | No registration code anywhere; README.md:79-80 confirms subscribe/unsubscribe and registration remain protocol work |
| Server-synchronized roster | Done (pull + push) | Roster request on Online `xmpp.rs:303-312`; result `xmpp.rs:356-362`; push + ack `xmpp.rs:375-381`; `emit_roster`/`roster_subscription` `xmpp.rs:438-462`; tests `xmpp.rs:613-642`, `lib.rs:1394-1440`. Server-side subscribe/unsubscribe commands absent. Note: roster request always sends `ver: None` (`xmpp.rs:308-310`), so no incremental sync |
| MUC | Partial | Capability `ns::MUC` `xmpp.rs:479`; groupchat send `xmpp.rs:392-395`; gating `lib.rs:650-654`. No join/leave/occupancy; incoming groupchat messages dropped (`translate_incoming_message` is chat-only `xmpp.rs:401-415`) |
| MAM | Missing | Capability mapping only `xmpp.rs:480`; no archive query/result handling |
| Stream Management | Partial | Capability from stream features `xmpp.rs:464-473` and disco `xmpp.rs:481`; `DeliveryUpdated` projection `lib.rs:829-841` with test `lib.rs:1604-1653`. No stanza-level ack/sequence handling or resume logic in this repo |
| Encrypted database migrations | Missing | `CoreStore` trait `lib.rs:341-344` is the only persistence boundary; only `InMemoryStore` `lib.rs:346-361`; SQLCipher mentioned only in a doc comment `lib.rs:339`; no migration code, no DB dependency |
| Media storage | Missing | `Attachment` DTO `lib.rs:193-200` and `attach()` projection `lib.rs:807-828` exist, but no file/media storage layer |
| No transport crate types through FFI | Done | `ffi.rs:8-13` imports only domain types; `xmpp` gated `lib.rs:16-17`; enforced by design and test `ffi.rs:785-816` |

M2 estimate: **5 Done / 2 Partial / 4 Missing → ~55%**.

### M3 — Messaging (PLAN.md:42-43)

| Deliverable | Status | Evidence |
| --- | --- | --- |
| HTTP upload | Partial | Capability `xmpp.rs:482`; `attach()` projection + capability gate `lib.rs:807-828`. No upload client |
| Receipts (XEP-0184) | Partial | Capability `xmpp.rs:483`; `DeliveryUpdated`/states `lib.rs:104-110, 829-841`; test `lib.rs:1604-1653`. No receipt stanza send/parse |
| Markers (XEP-0333) | Missing | Capability `ns::DISPLAYED_MARKERS` `xmpp.rs:484` only; no marker handling |
| Typing (XEP-0085) | Missing | Capability `ns::CHATSTATES` `xmpp.rs:485` only; no chatstate handling |
| Reactions (XEP-0444) | Partial | Capability `xmpp.rs:486-487`; core `add_reaction` `lib.rs:883-904`; UI renders `MindChatApp.kt:640-646`; tests `lib.rs:1473-1489, 1751-1782`. No wire stanza send/parse |
| Corrections/retractions (XEP-0308) | Missing | Capability `ns::MESSAGE_CORRECT` `xmpp.rs:488` only; no correction/retraction code |
| Replies (XEP-0333 fallback/urn:xmpp:reply:0) | Partial | Capability `xmpp.rs:489`; core `in_reply_to` model `lib.rs:213` + validation `lib.rs:746-751`. Transport `send_message` never emits a reply element (`in_reply_to` unused in `xmpp.rs:386-399`) |
| Capability-gated shared pins | Missing | `SharedPins` enum `lib.rs:135` and `ffi.rs:211` only; no disco mapping, no code |
| Reconnection-safe outgoing queues | Done | `pending_outgoing_messages` `lib.rs:847-881`; `flush_outbox` `lib.rs:1178-1194`; snapshot-restore test `lib.rs:1655-1687`; retry test `lib.rs:1784-1856` |

M3 estimate: **1 Done / 4 Partial / 4 Missing → ~33%**.

### M4 — Security and background delivery (PLAN.md:45-47)

| Deliverable | Status | Evidence |
| --- | --- | --- |
| OMEMO device trust UX | Missing | `Omemo` capability mapping `xmpp.rs:490-492` only; no OMEMO sessions/device list/trust UI |
| Android Keystore-wrapped storage keys | Missing | No Keystore usage anywhere (repo-wide grep: no matches); no storage layer |
| Optional biometric lock | Done | `AppLockStateMachine` `AppLock.kt:31-80`; `BiometricPrompt` adapter `AndroidAppLockAuthenticator.kt:9-48` (BIOMETRIC_WEAK or DEVICE_CREDENTIAL); `FLAG_SECURE` `MainActivity.kt:45-53`; preference `MindChatPreferences.kt:22-47`; 6 unit tests (see §1.2) |
| UnifiedPush endpoint registration | Missing | `PushNotifications` capability `xmpp.rs:483` only; no UnifiedPush dependency or code |
| Background-sync degradation without distributor | Missing | No background sync, no push fallback code |

M4 estimate: **1 Done / 0 Partial / 4 Missing → 20%**.

### M5 — Extension readiness, in-scope boundary only (PLAN.md:48-53)

| Deliverable | Status | Evidence |
| --- | --- | --- |
| Stabilized internal command/event/capability boundaries | Done | `CoreCommand` `lib.rs:251-256`, `CoreEvent` `lib.rs:239-245`, `ProtocolCapability` `lib.rs:121-136` |
| Data-only manifest | Done | `ExtensionManifest` `extension.rs:66-117`; `EXTENSION_API_VERSION=1` `extension.rs:12`; validation test `extension.rs:372-382` |
| Narrowly scoped permissions | Done | 7-permission enum + stable spellings `extension.rs:23-62`; test `extension.rs:384-393` |
| ID-only event filtering | Done | `visible_events` `extension.rs:218-260`; test `extension.rs:395-426` |
| Policy-mediated commands with account-owned attribution | Done | `authorize_command` `extension.rs:206-214`; `execute_extension_command` `lib.rs:706-734`; tests `lib.rs:1690-1749, 1752-1782` |

Explicitly out of scope for the base release (per EXTENSIONS.md:67-79): package
parsing, sandboxing, consent UI, SDK, signing, revocation, catalog. No third-
party code loader exists or is claimed.

M5 estimate: **5 Done / 0 Partial / 0 Missing → 100%** of in-scope items.

---

## 4. Feature matrix (protocol features → code status)

| Feature | Status | Evidence |
| --- | --- | --- |
| StartTLS | Implemented (code; no live test) | `Client::new_starttls` `xmpp.rs:242-247` |
| SRV resolution | Implemented + tested | `xmpp.rs:510-527`; test `xmpp.rs:696-702` |
| XEP-0030 service discovery | Implemented + tested | `xmpp.rs:313-321, 363-374, 475-500`; test `xmpp.rs:596-611` |
| Roster (result + push + presence) | Implemented + tested | `xmpp.rs:303-312, 356-381, 438-462`; tests `xmpp.rs:613-642`, `lib.rs:1394-1440`. Subscribe/unsubscribe commands missing; `ver` always None (full re-fetch per connect, `xmpp.rs:308-310`) |
| MUC | Partial (capability + groupchat send only) | `xmpp.rs:392-395, 479`; incoming groupchat dropped `xmpp.rs:401-415` |
| MAM | Missing (capability only) | `xmpp.rs:480` |
| Stream Management | Partial (capability + delivery projection) | `xmpp.rs:464-473, 481`; `lib.rs:829-841` |
| OMEMO | Missing (capability only) | `xmpp.rs:490-492` |
| HTTP upload | Missing (capability + attach projection) | `xmpp.rs:482`; `lib.rs:807-828` |
| Receipts | Partial (capability + delivery projection) | `xmpp.rs:483`; `lib.rs:829-841` |
| Reactions | Partial (capability + core, no wire) | `xmpp.rs:486-487`; `lib.rs:883-904` |
| Typing | Missing (capability only) | `xmpp.rs:485` |
| UnifiedPush | Missing (capability only) | `xmpp.rs:483`; no UP dependency |

---

## 5. Top 5 risks for the next milestone (protocol and persistence)

1. **No persistence layer exists at all.** Only the `CoreStore` trait
   (`lib.rs:341-344`) and `InMemoryStore` (`lib.rs:346-361`) exist; there is no
   SQLCipher dependency, no migration framework, and no account/message
   persistence (README.md:92-95 requires password re-entry after restart).
   M2's "encrypted database migrations" is a from-scratch build, and the
   snapshot format (`CoreSnapshot`) is the only migration input available.
2. **Stream Management is detection-only and reconnection is not idempotent.**
   No ack/sequence tracking or resume logic exists; `flush_outbox`
   (`lib.rs:1178-1194`) re-sends every Pending/Failed message on each Online
   transition (`ffi.rs:600-616` gates on connection state) with no stanza
   dedup, so a reconnect can duplicate deliveries. Any SM work must add
   outbound-stanza tracking before the outbox flush trusts server acks.
3. **MUC is effectively unimplemented at the wire level.** The transport
   cannot join rooms, and `translate_incoming_message` drops all non-chat
   messages (`xmpp.rs:401-415`), so every groupchat reply from other
   participants is silently lost today; MUC join/leave/occupancy must be built
   before the v1 MUC contract (PLAN.md:8) can be met.
4. **No live-server or e2e verification exists.** All transport tests are
   XML-mapping or fake-transport (xmpp.rs tests, `FakeTransport` lib.rs:1262);
   PLAN.md:104-105's "disposable XMPP server" e2e infrastructure has no code
   anywhere. StartTLS negotiation, SASL failure paths, roster `ver=` handling,
   and reconnect behavior are unverified against a real server, and CI runs no
   Android tests at all (§2).
5. **Registration and roster subscription commands are absent.** Only
   pre-existing accounts can log in (`connect_account` ffi.rs:561-572); there
   is no XEP-0077 registration and no server-side subscribe/unsubscribe
   command path (README.md:79-80), which blocks the v1 requirement of
   "registration where a server supports it" (PLAN.md:9).

---

## Metrics block

- test_count_rust: 33 (`--all-features`, verified by run 2026-08-08: 33 passed, 0 failed; default features: 29 passed, 0 failed)
- test_count_android_unit: 6 (source count; not executed locally: machine JDK is 1.8, AGP needs 11+; CI does not run `testDebugUnitTest` either)
- test_count_android_instrumented: 4 (source count; not executed: needs emulator; CI does not run `connectedAndroidTest`)
- test_function_list:
  - lib.rs (17): rejects_invalid_account_identifiers, accounts_start_without_undiscovered_optional_capabilities, roster_contacts_are_account_scoped_normalized_and_snapshot_safe, roster_contact_rejects_unknown_accounts_and_invalid_jids, server_roster_and_presence_events_preserve_subscription_state, rejects_invalid_conversation_address_and_cross_conversation_reply, capability_gates_reactions, preserves_message_order_and_transitions_delivery, capability_gates_group_chats, incoming_messages_increment_and_read_clears_unread_count, addressed_incoming_text_creates_the_direct_conversation_once, snapshot_round_trip_keeps_stable_ids, transport_events_project_connection_incoming_messages_and_receipts, queued_outgoing_messages_survive_snapshot_restore_and_are_account_scoped, extension_commands_are_permissioned_and_attributed_to_the_owning_account, extension_commands_still_obey_server_capability_gates, coordinator_connects_flushes_retries_and_projects_transport_events
  - transport.rs (1): secret_debug_output_is_redacted
  - xmpp.rs (6): maps_discovered_features_without_claiming_unknown_capabilities, maps_roster_subscription_and_removal_events, maps_available_and_unavailable_presence_to_bare_jid, maps_direct_message_stanzas_without_leaking_resource_addresses, accepts_explicit_host_ports_but_preserves_srv_default_hosts, disconnected_events_release_the_account_worker_slot
  - ffi.rs (4): bridge_exposes_safe_snapshot_and_events, bridge_exposes_roster_contacts_without_transport_or_secret_types, bridge_maps_domain_errors_without_exposing_internal_types, bridge_rejects_empty_passwords_before_starting_a_transport_worker
  - extension.rs (5): manifest_requires_a_stable_reverse_domain_id, permission_manifest_names_round_trip_and_reject_unknown_values, policy_filters_events_by_specific_observation_permissions, policy_denies_requested_commands_that_the_host_did_not_grant, policy_rejects_a_grant_that_the_manifest_did_not_request
  - AppLockStateMachineTest.kt (6): enablingLockBlocksContentAndRequestsOneAutomaticPrompt, cancellationKeepsTheGateLockedWithoutReopeningTheSystemPrompt, backgroundingAfterSuccessLocksAgainAndSchedulesAnotherPrompt, backgroundingWhileAlreadyLockedSchedulesAuthenticationOnReturn, disablingLockMakesLateAuthenticationCallbacksHarmless, successWithoutAnActivePromptCannotUnlockTheGate
  - MindChatAppTest.kt (4): addAccountFlowIsAvailableBeforeAnyAccountExists, customizationChoicesSurviveGatewayRecreation, localContactsAreScopedToTheActiveAccountAndUseTheProvidedDisplayName, accountSetupRequiresAPasswordBeforeThePreviewSessionIsCreated
- ci_configured: yes; `.github/workflows/verify.yml` — rust job: `cargo fmt --all -- --check`, `cargo clippy --workspace --all-targets --all-features -- -D warnings`, `cargo test --workspace --all-features`; android job: `:app:lintDebug`, `:app:compileDebugAndroidTestKotlin`, `:app:assembleDebug`; triggers: push to main, pull_request. CI never executes Android unit or instrumented tests
- ci_file_list: [".github/workflows/verify.yml"]
- milestone_status:
  - M1: 100% — 10 done / 0 partial / 0 missing (accessibility + diagnostics policy in code but untested)
  - M2: 55% — 5 done / 2 partial / 4 missing (transport, StartTLS, SRV, XEP-0030, login, roster, no-FFI-leak done; MUC, SM partial; registration, MAM, encrypted DB migrations, media storage missing)
  - M3: 33% — 1 done / 4 partial / 4 missing (outgoing queues done; upload, receipts, reactions, replies partial; markers, typing, corrections/retractions, shared pins missing)
  - M4: 20% — 1 done / 0 partial / 4 missing (biometric lock done; OMEMO trust UX, Keystore keys, UnifiedPush, background-sync degradation missing)
  - M5: 100% (in-scope) — 5 done / 0 partial / 0 missing (manifest, permissions, ID-only events, policy commands, boundaries; runtime/package work explicitly out of scope)
