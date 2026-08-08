# Rust Domain Core Report (mindchat-core)

Source of truth: `crates/mindchat-core`, workspace root `Cargo.toml`. Generated 2026-08-08. All line references are to files in `crates/mindchat-core/src/` unless prefixed otherwise. This report covers only the Rust core; the Android app and Gradle pipeline are out of scope.

## 1. Module map

| File | Responsibility | LOC (source lines) |
|---|---|---|
| `lib.rs` | Platform-neutral domain model, `MindChatCore` state machine, `TransportCoordinator`, validation, core tests | 1857 |
| `xmpp.rs` | Concrete `TokioXmppTransport` (tokio + tokio-xmpp worker per account), stanza translation, XEP-0030/roster/SM detection, transport tests | 722 |
| `transport.rs` | `XmppTransport` trait, `TransportEvent`/`TransportError`/`ConnectionRequest`/`OutgoingMessage`/`SecretString` adapter boundary | 150 |
| `extension.rs` | Extension manifest/permission/policy boundary, ID-only event filtering | 463 |
| `ffi.rs` | UniFFI DTOs, error mapping, `MindChatCoreHandle` exported surface, FFI tests | 849 |
| **Total** | | **4041** |

Crate wiring: `lib.rs:13-20` conditionally exposes modules behind features (`xmpp-transport` gates `xmpp`, `uniffi` gates `ffi`), and `lib.rs:22-23` calls `uniffi::setup_scaffolding!()`. The crate produces `rlib` + `cdylib` (`crates/mindchat-core/Cargo.toml:10-11`). Workspace: single member, edition 2024, rust-version 1.88, `unsafe_code = "forbid"`, clippy all+pedantic warn (`Cargo.toml:2-17`). Toolchain pinned to 1.97.1 with clippy+rustfmt (`rust-toolchain.toml:2-4`). rustfmt: max_width 100, `use_small_heuristics = "Max"` (`rustfmt.toml:1-3`). UniFFI Kotlin binding: package `com.mindchat.core`, `android = true`, `generate_immutable_records = true` (`crates/mindchat-core/uniffi.toml:1-4`).

## 2. Domain model

Identifier aliases: `AccountId`, `ConversationId`, `MessageId`, `ReactionId` are all `u64` (`lib.rs:38-44`). Message bodies are capped at `MAX_MESSAGE_CHARS = 16_384` (`lib.rs:46`).

### Enums (8, all in `lib.rs`)

| Type | Lines | Variants | State encoded |
|---|---|---|---|
| `ConnectionState` | `lib.rs:50-56` | `Offline` (default), `Connecting`, `Online`, `Failed` | UI-projected connection status of one account |
| `ContactPresence` | `lib.rs:60-66` | `Online`, `Away`, `DoNotDisturb`, `Offline` (default) | Presence projected for one roster contact |
| `RosterSubscription` | `lib.rs:74-86` | `None` (default), `Inbound`, `Outbound`, `Mutual`, `PendingOutbound` | Account-local projection of RFC 6121 roster subscription state |
| `ConversationKind` | `lib.rs:90-93` | `Direct`, `MultiUserChat` | Conversation transport topology |
| `MessageDirection` | `lib.rs:97-100` | `Incoming`, `Outgoing` | Direction relative to local account |
| `DeliveryState` | `lib.rs:104-110` | `Pending`, `Sent`, `Delivered`, `Read`, `Failed` | Delivery projection for outgoing messages |
| `MessageKind` | `lib.rs:114-118` | `Text`, `Attachment`, `Voice` | High-level payload category; binary data kept out of the core (`lib.rs:112`) |
| `ProtocolCapability` | `lib.rs:122-136` | `MultiUserChat`, `MessageArchiveManagement`, `StreamManagement`, `HttpFileUpload`, `Omemo`, `PushNotifications`, `Receipts`, `ChatMarkers`, `ChatStates`, `MessageReactions`, `MessageCorrections`, `MessageReplies`, `SharedPins` (13) | XMPP feature that must be discovered before its UI action is available |

### Structs (8, all in `lib.rs`)

| Type | Lines | Key fields | State encoded |
|---|---|---|---|
| `AccountSetup` | `lib.rs:140-156` | `jid`, `server`, `display_name` | Input for configuring an account; validated in core |
| `Account` | `lib.rs:160-167` | `id`, `jid`, `server`, `display_name`, `connection_state`, `capabilities: BTreeSet<ProtocolCapability>` | One configured account plus live connection state and discovered features |
| `Contact` | `lib.rs:171-178` | `account_id`, `jid`, `display_name`, `presence`, `status: Option<String>`, `subscription` | Account-scoped roster contact projection |
| `Conversation` | `lib.rs:182-190` | `id`, `account_id`, `kind`, `address`, `title`, `unread_count: u32`, `last_activity_epoch_ms: u64` | Local conversation projection incl. unread badge and last activity |
| `Attachment` | `lib.rs:194-200` | `id`, `filename`, `mime_type`, `byte_count`, `remote_url: Option<String>` | UI-safe attachment metadata |
| `Message` | `lib.rs:204-215` | `id`, `conversation_id`, `sender`, `body`, `direction`, `kind`, `sent_at_epoch_ms`, `delivery_state`, `in_reply_to: Option<MessageId>`, `attachment: Option<Attachment>` | Immutable message projection |
| `Reaction` | `lib.rs:219-224` | `id`, `message_id`, `emoji`, `actor` | Emoji reaction attached to a message |
| `CoreSnapshot` | `lib.rs:228-234` | `accounts`, `contacts`, `conversations`, `messages`, `reactions` (all `Vec`) | Read-only full state for a persistence adapter or test fixture |

### Infrastructure types

`CoreEvent` (5 variants, `lib.rs:239-245`), `CoreCommand` (3 variants, `lib.rs:252-256`), `CoreError` (11 variants, `lib.rs:260-272`), `TransportCoordinatorError` (`lib.rs:304-307`), `CoreStore` trait (`lib.rs:341-344`), `InMemoryStore` (`lib.rs:348-361`), `MindChatCore` (`lib.rs:365-376`), `TransportCoordinator<T>` (`lib.rs:1103-1142`). Transport boundary types live in `transport.rs`: `SecretString` (`transport.rs:16-36`), `ConnectionRequest` (`transport.rs:40-45`), `OutgoingMessage` (`transport.rs:49-57`), `TransportEvent` (`transport.rs:61-106`), `TransportError` (`transport.rs:110-115`), `XmppTransport` trait (`transport.rs:134-139`).

## 3. State machine / coordinator

### Session lifecycle states
`ConnectionState` is the only session lifecycle vocabulary: accounts are created `Offline` (`lib.rs:437`), move to `Connecting` on connect (`lib.rs:1159`), to `Online` on `TransportEvent::Connected` (`lib.rs:579-587`), and to `Offline` or `Failed` on `TransportEvent::Disconnected` depending on the `recoverable` flag (`lib.rs:589-595`). Transport-level classification of recoverability: `recoverable = !matches!(error, tokio_xmpp::Error::Auth(_))` (`xmpp.rs:325`), so authentication failures become `Failed`, everything else becomes `Offline`.

### Reconnect-safe outgoing projection
There is no transport-side retry queue. Outgoing messages are inserted with `DeliveryState::Pending` (`lib.rs:765`) and kept in the snapshot. `pending_outgoing_messages(account_id)` (`lib.rs:847-881`) returns all messages with direction `Outgoing` and state `Pending | Failed`, in message-id order, joined with their conversation's address and kind. The coordinator's `flush_outbox` (`lib.rs:1178-1194`) sends each one via the transport, marking `Sent` on success and `Failed` on error (leaving it retryable). The FFI layer only flushes when the account is `Online` (`ffi.rs:600-616`), so an app can call it after every poll pass.

### Snapshot model and restoration
`snapshot()` (`lib.rs:408-422`) produces a deterministic `CoreSnapshot`: all five collections sorted (accounts/conversations/messages/reactions by id; contacts by `(account_id, jid)`, `lib.rs:414-421`). `from_snapshot` (`lib.rs:381-404`) rebuilds the `HashMap`s and reseeds the ID allocators to `max(id) + 1` per collection (`lib.rs:382-386`), so restored cores continue allocating stable, collision-free IDs. Events are not persisted: `events: Vec::new()` on restore (`lib.rs:402`). `CoreStore` (`load`/`save`, `lib.rs:341-344`) is the persistence boundary; `InMemoryStore` (`lib.rs:348-361`) is the deterministic in-memory implementation. The snapshot round-trip keeps stable IDs and pending messages survive restore (tests at `lib.rs:1584-1602`, `lib.rs:1656-1687`).

## 4. Transport

### `TransportCoordinator<T>` (`lib.rs:1103-1204`)
Generic over any `T: XmppTransport`. `connect` (`lib.rs:1146-1165`) builds a `ConnectionRequest` from core account data, sets `Connecting`, and on transport error sets `Failed`. Credentials exist only inside the request; the core never stores the password (`lib.rs:1099-1102`, doc at `lib.rs:1145`). `disconnect` (`lib.rs:1168-1175`) calls the transport then projects a recoverable `Disconnected`. `poll_next_event` (`lib.rs:1197-1203`) pulls one normalized `TransportEvent` and applies it via `MindChatCore::apply_transport_event` (`lib.rs:574-634`), which is the single projection path: Connected→Online+capabilities, Disconnected→Offline/Failed, CapabilitiesDiscovered→`set_capabilities`, roster upsert/remove, presence update, `IncomingText`→`receive_transport_text` (auto-creates the conversation once, `lib.rs:1001-1042`), `DeliveryUpdated`→`set_delivery_state`.

### `TokioXmppTransport` (`xmpp.rs:45-191`)
Each account gets one dedicated current-thread Tokio runtime in a named thread `mindchat-xmpp-{account_id}` (`xmpp.rs:102-118`). The sync trait communicates with the worker through an unbounded command channel and `mpsc` hand-off response channels (`xmpp.rs:99-100`); events flow back over a single `mpsc::channel` (`xmpp.rs:71`). `connect` refuses duplicate workers (`xmpp.rs:94-96`), `disconnect` uses a 5s timeout (`DISCONNECT_TIMEOUT`, `xmpp.rs:37`, `xmpp.rs:146-154`), `send` uses a 15s timeout (`SEND_TIMEOUT`, `xmpp.rs:36`, `xmpp.rs:169-177`). `next_event` retires the worker slot on `Disconnected` events (`xmpp.rs:183-186`, `retire_worker` at `xmpp.rs:83-88`). `Drop` sends a disconnect to every worker (`xmpp.rs:193-200`). TLS: rustls ring provider is installed per worker (`install_rustls_provider`, `xmpp.rs:563-565`).

### Connection flow (`run_worker`, `xmpp.rs:235-286`)
The worker builds `Client::new_starttls(jid, password, dns_config, Timeouts::default())` (`xmpp.rs:242-247`): StartTLS is the only stream mode used. A `tokio::select!` loop interleaves commands and client events; when the client stream ends, a `Disconnected { recoverable: false }` event is emitted and the worker exits (`xmpp.rs:277-283`).

### SRV vs explicit host (`xmpp.rs:510-561`)
`dns_config_for_server` (`xmpp.rs:510-527`): empty server → `ProtocolViolation`; explicit `host:port` → `DnsConfig::no_srv(host, port)`; IP literal → `DnsConfig::addr` (IPv6 re-bracketed, `xmpp.rs:516-523`); bare host → `DnsConfig::srv_default_client(server)` (SRV lookup). `split_host_and_port` (`xmpp.rs:529-550`) handles bracketed IPv6 and single-colon host:port; `parse_endpoint` rejects empty hosts and port 0 (`xmpp.rs:552-561`).

### Service discovery (XEP-0030)
On `Online` (`xmpp.rs:297-323`) the worker: (1) derives stream capabilities from stream features (`stream_capabilities_from_features`, `xmpp.rs:464-473`, currently only `stream_management.is_some()` → `StreamManagement`), (2) emits `Connected` with those capabilities, (3) sends initial available presence (`xmpp.rs:303`), (4) sends a roster IQ get with id `mindchat-roster-{account_id}` (`xmpp.rs:304-312`, id at `xmpp.rs:502-504`), and (5) sends a disco#info IQ get with id `mindchat-disco-{account_id}` to the bound domain (`xmpp.rs:313-321`, id at `xmpp.rs:506-508`). `handle_iq` (`xmpp.rs:348-384`) matches the roster result (`xmpp.rs:356-362`), the disco result (`xmpp.rs:363-374`), and roster pushes (`Iq::Set`, `xmpp.rs:375-381`), acking pushes with an empty IQ result (`xmpp.rs:379-380`). `capabilities_from_disco` (`xmpp.rs:475-500`) maps feature namespaces: `ns::MUC`, `ns::MAM`, `ns::SM`, `ns::HTTP_UPLOAD`, `ns::PUSH`, `ns::RECEIPTS`, `ns::DISPLAYED_MARKERS`, `ns::CHATSTATES`, `ns::REACTIONS`, `ns::MESSAGE_CORRECT`, `"urn:xmpp:reply:0"`, and OMEMO 1/2/legacy. Unknown features are ignored (`xmpp.rs:492-494`).

### Roster request/push projection
`emit_roster` (`xmpp.rs:438-452`) iterates roster items: `subscription == Remove` → `RosterContactRemoved`; otherwise `RosterContactUpsert` with `roster_subscription` mapping (`xmpp.rs:454-462`): `To`→`Inbound`, `From`→`Outbound`, `Both`→`Mutual`, `None + Ask::Subscribe`→`PendingOutbound`, else `None`. The core's `sync_roster_contact` preserves the latest presence/status of a known contact (`lib.rs:505-525`), and `remove_contact` is idempotent (`lib.rs:531-544`).

### Presence normalization
`translate_presence` (`xmpp.rs:417-436`) strips the resource (`from.to_bare()`, `xmpp.rs:418`), maps `show`: Away/Xa→Away, Dnd→DoNotDisturb, Chat/None→Online (`xmpp.rs:420-424`), `type=unavailable`→Offline, and any other presence type (subscribe/unsubscribed/subscribe errors) yields no event (`xmpp.rs:425-426`). Status text prefers the default language key then any value (`xmpp.rs:428-429`). The core further drops presence for non-roster JIDs (`lib.rs:548-549`, `lib.rs:560-561`).

### Incoming messages
`translate_incoming_message` (`xmpp.rs:401-415`) accepts only `MessageType::Chat`, requires a `from`, extracts the best body preferring `["ru", "en"]` (`xmpp.rs:406`), drops empty bodies, and emits `IncomingText` with bare-JID address/sender.

### Stream management detection
Detection only: `stream_management.is_some()` produces the `StreamManagement` capability (`xmpp.rs:464-473`). There is no `<enabled/>`/`<resumed/>`/`<failed/>` stanza handling and no SM sequence correlation in this crate; `tokio-xmpp`'s client handles the protocol details internally (`xmpp.rs:242-247`).

### Error handling
`map_xmpp_error` (`xmpp.rs:584-589`): `Auth`→`AuthenticationFailed`, else `ConnectionFailed(String)`. `map_io_error` (`xmpp.rs:580-582`): io errors → `ConnectionFailed`. JID parse failures → `ProtocolViolation` (`xmpp.rs:221-225`, `xmpp.rs:387-391`). `send_event` is best-effort (`xmpp.rs:567-569`).

### Reconnection behavior
No automatic reconnection exists in the transport. On disconnect the worker exits and its slot is freed (`xmpp.rs:83-88`, `xmpp.rs:183-186`). Reconnect is app-driven: `connect` again, poll events, then `flush_outbox` resends surviving Pending/Failed messages (see section 3). The `recoverable` flag distinguishes auth failures (`Failed`, requires user action) from transient failures (`Offline`, retryable).

## 5. Commands vs events

### Commands (Kotlin→Rust)
Domain vocabulary: `CoreCommand` (`lib.rs:252-256`), 3 variants:
- `SendText { conversation_id: ConversationId, body: String, in_reply_to: Option<MessageId> }`
- `MarkConversationRead { conversation_id: ConversationId }`
- `AddReaction { message_id: MessageId, emoji: String }`

Executed via `MindChatCore::execute` (`lib.rs:679-698`, first-party) or `execute_extension_command` (`lib.rs:706-734`, permissioned, attributed to the owning account's JID).

FFI-exported methods on `MindChatCoreHandle` (`#[uniffi::export] impl`, `ffi.rs:432-627`), 16 total incl. the constructor: `new` (`ffi.rs:438-447`), `add_account` (`ffi.rs:450-460`), `set_connection_state` (`ffi.rs:463-469`), `set_capabilities` (`ffi.rs:472-481`), `upsert_contact` (`ffi.rs:484-496`), `open_conversation` (`ffi.rs:499-511`), `send_text` (`ffi.rs:514-526`), `receive_text` (`ffi.rs:529-540`), `add_reaction` (`ffi.rs:543-550`), `mark_conversation_read` (`ffi.rs:553-555`), `connect_account` (empty password rejected, `ffi.rs:561-572`), `disconnect_account` (`ffi.rs:575-577`), `poll_transport_events` (bounded at 128/poll, `ffi.rs:583-594`), `flush_outbox` (`ffi.rs:600-616`), `snapshot` (`ffi.rs:619-621`), `drain_events` (`ffi.rs:624-626`). Plus free function `mindchat_binding_version()` (`ffi.rs:630-634`).

### Events (Rust→Kotlin)
Domain `CoreEvent` (`lib.rs:239-245`), 5 variants: `AccountChanged(AccountId)`, `RosterChanged(AccountId)`, `ConversationChanged(ConversationId)`, `MessageAdded(MessageId)`, `MessageChanged(MessageId)`. Drained via `drain_events` (`lib.rs:921-923`), mirrored 1:1 to `FfiCoreEvent` (`ffi.rs:336-342`) with named fields.

Transport→core: `TransportEvent` (`transport.rs:61-106`), 8 variants: `Connected { account_id, capabilities: BTreeSet<ProtocolCapability> }`, `Disconnected { account_id, recoverable: bool }`, `CapabilitiesDiscovered { account_id, capabilities }`, `RosterContactUpsert { account_id, jid, display_name, subscription }`, `RosterContactRemoved { account_id, jid }`, `ContactPresenceUpdated { account_id, jid, presence, status: Option<String> }`, `IncomingText { account_id, kind, address, sender, body, received_at_epoch_ms }`, `DeliveryUpdated { message_id, state }`.

Extension-facing: `ExtensionEvent` (`extension.rs:155-161`), 5 ID-only variants (see section 6).

## 6. Extension boundary (`extension.rs`)

The base app does not execute third-party commands; the module defines the vocabulary a future sandboxed extension runtime must enforce (`extension.rs:3-5`). `EXTENSION_API_VERSION = 1` (`extension.rs:12`); manifest limits: id ≤128, name ≤80, version ≤64 chars (`extension.rs:14-16`).

- **Permissions** (`ExtensionPermission`, `extension.rs:23-31`), 7 variants: `ObserveAccountChanges`, `ObserveRosterChanges`, `ObserveConversationChanges`, `ObserveMessageChanges`, `SendMessages`, `MarkConversationsRead`, `AddReactions`. Stable manifest spellings round-trip via `manifest_name`/`from_manifest_name` (`extension.rs:36-61`).
- **Manifest** (`ExtensionManifest`, `extension.rs:66-72`): `id`, `name`, `version`, `api_version`, `permissions: BTreeSet`. `validate` (`extension.rs:94-116`) enforces reverse-domain IDs (lowercase, dot-separated, ≥2 segments, `is_valid_extension_id`, `extension.rs:341-366`), name/version lengths, and API version equality.
- **Policy mediation** (`ExtensionPolicy`, `extension.rs:165-261`): `new` requires every grant to be declared in the manifest, else `UndeclaredGrant` (`extension.rs:182-189`). `authorize_command` (`extension.rs:206-214`) maps each `CoreCommand` to exactly one permission via `required_permission` (`extension.rs:333-339`: SendText→SendMessages, MarkConversationRead→MarkConversationsRead, AddReaction→AddReactions) and rejects un-granted commands before any mutation. `execute_extension_command` (`lib.rs:706-734`) calls authorization first, then routes to the same validated core mutations with the sender attributed to the conversation-owning account's JID.
- **ID-only event filtering** (`visible_events`, `extension.rs:218-260`): filters `CoreEvent`s down to `ExtensionEvent`s allowed by observation grants; events never carry message text, credentials, database handles, or crypto material (`extension.rs:151-153`). One observation permission gates both `MessageAdded` and `MessageChanged` (`ObserveMessageChanges`, `extension.rs:243-256`).

Errors: `ExtensionManifestError` (5 variants, `extension.rs:121-127`), `ExtensionPolicyError` (2, `extension.rs:265-268`), `ExtensionCommandError` (2: `PermissionDenied`, `Core(CoreError)`, `extension.rs:296-299`).

## 7. Errors and FFI mapping

| Error enum | Variants | Location |
|---|---|---|
| `CoreError` | 11: `InvalidJid`, `InvalidServer`, `InvalidConversationAddress`, `EmptyMessage`, `MessageTooLong`, `EmptyReaction`, `InvalidReplyTarget`, `UnknownAccount`, `UnknownConversation`, `UnknownMessage`, `CapabilityUnavailable` | `lib.rs:260-272` |
| `TransportError` | 4: `AuthenticationFailed`, `ConnectionFailed(String)`, `ProtocolViolation(String)`, `Unsupported(String)` | `transport.rs:110-115` |
| `TransportCoordinatorError` | 2: `Core(CoreError)`, `Transport(TransportError)` | `lib.rs:304-307` |
| `ExtensionManifestError` | 5: `EmptyId`, `InvalidId`, `InvalidName`, `InvalidVersion`, `UnsupportedApiVersion{requested,supported}` | `extension.rs:121-127` |
| `ExtensionPolicyError` | 2: `InvalidManifest`, `UndeclaredGrant{extension_id,permission}` | `extension.rs:265-268` |
| `ExtensionCommandError` | 2: `PermissionDenied{extension_id,permission}`, `Core(CoreError)` | `extension.rs:296-299` |
| `MindChatBindingError` (FFI) | 6: `InvalidInput{detail}`, `NotFound{detail}`, `CapabilityUnavailable{capability}`, `AuthenticationFailed`, `ConnectionFailed{detail}`, `Internal{detail}` | `ffi.rs:346-353` |

All types implement `Display` and `std::error::Error`; the coordinator and extension errors expose `source()`.

FFI mapping: `From<CoreError>` (`ffi.rs:372-390`) collapses validation failures (`InvalidJid`, `InvalidServer`, `InvalidConversationAddress`, `EmptyMessage`, `MessageTooLong`, `EmptyReaction`, `InvalidReplyTarget`) into `InvalidInput`; unknown-ID errors into `NotFound`; `CapabilityUnavailable` passes through as a typed variant. `From<TransportError>` (`ffi.rs:392-401`): `AuthenticationFailed`→`AuthenticationFailed`, `ConnectionFailed`→`ConnectionFailed`, `ProtocolViolation`→`InvalidInput`, `Unsupported`→`Internal`. `From<TransportCoordinatorError>` (`ffi.rs:403-410`) delegates. `MindChatCoreHandle::lock` converts a poisoned mutex into `Internal` (`ffi.rs:422-430`). Error strings at the FFI boundary come from the `Display` impls, so internal enum names never leak to Kotlin (verified by `bridge_maps_domain_errors_without_exposing_internal_types`, `ffi.rs:819-825`).

## 8. Tests

`cargo test --workspace` (default features): 29 passed, 0 failed. `cargo test --workspace --features uniffi`: 33 passed, 0 failed, 0 doc tests. The 4 `ffi.rs` tests are gated behind the `uniffi` feature (`lib.rs:19-20`), which is not in `default` (`crates/mindchat-core/Cargo.toml:14-16`). 5 test modules, 33 test functions:

**`lib.rs` `mod tests` (17)** — fake `XmppTransport` with scripted events and a fail-once send flag (`lib.rs:1262-1294`):
- `rejects_invalid_account_identifiers` (1302): bad JID, whitespace server, resource-suffixed JID.
- `accounts_start_without_undiscovered_optional_capabilities` (1319): empty capability set.
- `roster_contacts_are_account_scoped_normalized_and_snapshot_safe` (1327): name/status trimming, JID-local fallback name, account scoping, event ordering, snapshot restore.
- `roster_contact_rejects_unknown_accounts_and_invalid_jids` (1381).
- `server_roster_and_presence_events_preserve_subscription_state` (1395): roster upsert + presence merge, directed presence ignored, roster removal.
- `rejects_invalid_conversation_address_and_cross_conversation_reply` (1443): address validation and `InvalidReplyTarget`.
- `capability_gates_reactions` (1474): reactions require `MessageReactions`.
- `preserves_message_order_and_transitions_delivery` (1492): id order, delivery transition, `in_reply_to`.
- `capability_gates_group_chats` (1512): MUC conversation requires `MultiUserChat`.
- `incoming_messages_increment_and_read_clears_unread_count` (1530).
- `addressed_incoming_text_creates_the_direct_conversation_once` (1545): auto-create, title from localpart, unread accumulation.
- `snapshot_round_trip_keeps_stable_ids` (1584): ID stability across restore.
- `transport_events_project_connection_incoming_messages_and_receipts` (1605): Connected/Disconnected/DeliveryUpdated/IncomingText projection.
- `queued_outgoing_messages_survive_snapshot_restore_and_are_account_scoped` (1656): outbox projection across restore, account scoping, unknown account error.
- `extension_commands_are_permissioned_and_attributed_to_the_owning_account` (1690): denied and allowed extension commands, sender attribution.
- `extension_commands_still_obey_server_capability_gates` (1752): capability check still applies after policy.
- `coordinator_connects_flushes_retries_and_projects_transport_events` (1785): end-to-end coordinator; redacted password debug, flush order, fail→Failed→retryable, disconnect→Offline.

**`transport.rs` `mod tests` (1)**:
- `secret_debug_output_is_redacted` (146): `Debug` prints `SecretString([redacted])`.

**`xmpp.rs` `mod tests` (6)** — parse real XML via `xmpp_parsers`:
- `maps_discovered_features_without_claiming_unknown_capabilities` (597): disco feature mapping incl. unknown-feature rejection.
- `maps_roster_subscription_and_removal_events` (614): `to`/`remove`/`none+ask=subscribe` roster items → upsert/removed/pending-outbound.
- `maps_available_and_unavailable_presence_to_bare_jid` (645): DND + status, unavailable→Offline.
- `maps_direct_message_stanzas_without_leaking_resource_addresses` (674): chat message → `IncomingText` with bare JID.
- `accepts_explicit_host_ports_but_preserves_srv_default_hosts` (697): SRV vs `NoSrv` vs `Addr` vs invalid port.
- `disconnected_events_release_the_account_worker_slot` (705): worker slot retirement.

**`extension.rs` `mod tests` (5)**:
- `manifest_requires_a_stable_reverse_domain_id` (373): ID validation incl. trailing dash.
- `permission_manifest_names_round_trip_and_reject_unknown_values` (385).
- `policy_filters_events_by_specific_observation_permissions` (396): ID-only event filtering.
- `policy_denies_requested_commands_that_the_host_did_not_grant` (429).
- `policy_rejects_a_grant_that_the_manifest_did_not_request` (451): `UndeclaredGrant`.

**`ffi.rs` `mod tests` (4)**:
- `bridge_exposes_safe_snapshot_and_events` (742): snapshot + event drain through the handle.
- `bridge_exposes_roster_contacts_without_transport_or_secret_types` (786).
- `bridge_maps_domain_errors_without_exposing_internal_types` (819): `InvalidJid` → `InvalidInput{detail}`.
- `bridge_rejects_empty_passwords_before_starting_a_transport_worker` (828).

## 9. Gaps / TODOs

- **Zero TODO/FIXME/`unimplemented!`/`todo!` markers** in `crates/mindchat-core/src` (grep verified).
- **`SharedPins` is undiscoverable**: the `ProtocolCapability::SharedPins` variant (`lib.rs:135`) has no mapping in `capabilities_from_disco` (`xmpp.rs:475-500`) or stream features; it can never be set by the transport.
- **Receipts/markers/reactions are detect-only**: `DeliveryUpdated` is defined (`transport.rs:102-105`) and applied (`lib.rs:629-631`) but never emitted by `TokioXmppTransport`; there is no XEP-0184 receipt, XEP-0333 marker, or XEP-0444 reaction stanza handling. `DeliveryState::Read` is never produced by the transport.
- **Attachments have no wire path**: `MindChatCore::attach` (`lib.rs:807-828`) mutates a message with attachment/voice metadata, but no FFI export calls it and there is no HTTP upload or attachment send path.
- **MUC receive path absent**: `translate_incoming_message` accepts only `MessageType::Chat` (`xmpp.rs:402-404`); groupchat messages are dropped, though `ConversationKind::MultiUserChat` is sent as `groupchat` (`xmpp.rs:392-395`) and the MUC capability is discovered.
- **Subscription negotiation absent**: presence `subscribe`/`subscribed`/`unsubscribe` stanzas yield no events (`xmpp.rs:425-426`); roster subscription requests are handled only as server-confirmed state (`RosterSubscription`).
- **No auto-reconnect**: workers exit on disconnect; retry is app-driven via `connect` + `flush_outbox` (see section 4).
- **Extensions are future-only**: `ExtensionEvent`/`visible_events` (`extension.rs:218-260`) have no FFI export; no extension can actually register today. The doc comments say so explicitly (`extension.rs:3-5`, `lib.rs:247-250`).
- **`CoreStore`/`InMemoryStore` unused in tests and unreferenced elsewhere** in the crate (grep verified); they exist as the persistence boundary for a future SQLCipher-backed Android store (`lib.rs:339-344`).
- **UnifFFI surface is read/write asymmetric**: e.g. no account removal, no `set_delivery_state`/`attach`/`sync_roster_contact`/`remove_contact` exports; Kotlin reaches only the 16 exported methods listed in section 5.

## Metrics block (exact numbers)

```
rust_loc_total             4041
per-file LOC:
  lib.rs                    1857
  xmpp.rs                    722
  transport.rs               150
  extension.rs               463
  ffi.rs                     849
model_type_count              16  (8 enums + 8 structs in lib.rs domain model)
command_count                  3  CoreCommand variants; 16 FFI-exported handle methods (+1 free fn)
event_count                    5  CoreEvent variants; 5 FfiCoreEvent; 8 TransportEvent; 5 ExtensionEvent
error_variant_count           32  CoreError 11 + TransportError 4 + TransportCoordinatorError 2
                                 + ExtensionManifestError 5 + ExtensionPolicyError 2
                                 + ExtensionCommandError 2 + MindChatBindingError 6
test_count_total              33  (29 under default features, 33 with --features uniffi; all pass)
test_module_count              5
todo_marker_count              0
dependencies (crates/mindchat-core/Cargo.toml, all optional):
  futures         0.3.31
  tokio           1.48.0   (features: net, rt, sync, time)
  tokio-xmpp      6.0.0    (default-features=false; features: ring, starttls, webpki-roots)
  uniffi          0.32.0   (default-features=false)
  xmpp-parsers    0.23.0
```
