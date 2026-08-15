//! MindChat's platform-neutral domain core.
//!
//! The crate deliberately models client behavior without exposing protocol
//! parser, database, cryptographic, or Android types. It is therefore the
//! stable boundary consumed by future UniFFI bindings.

#![allow(missing_docs)]
#![allow(clippy::doc_markdown, clippy::missing_errors_doc, clippy::needless_pass_by_value)]

use std::collections::{BTreeSet, HashMap};
use std::fmt;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

pub mod extension;
pub mod persistence;
pub mod transport;

#[cfg(feature = "xmpp-transport")]
pub mod xmpp;

#[cfg(feature = "uniffi")]
pub mod ffi;

#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!();

pub use extension::{
    EXTENSION_API_VERSION, ExtensionCommandError, ExtensionEvent, ExtensionManifest,
    ExtensionManifestError, ExtensionPermission, ExtensionPolicy, ExtensionPolicyError,
    required_permission,
};
pub use transport::{
    ConnectionRequest, OutgoingMessage, SecretString, TransportError, TransportEvent, XmppTransport,
};

#[cfg(feature = "xmpp-transport")]
pub use xmpp::{RegisterRequest, TokioXmppTransport, resolve_endpoint};

/// Stable identifier for an XMPP account configured in the client.
pub type AccountId = u64;
/// Stable identifier for a direct or group conversation.
pub type ConversationId = u64;
/// Stable identifier for a message.
pub type MessageId = u64;
/// Stable identifier for a message reaction.
pub type ReactionId = u64;

const MAX_MESSAGE_CHARS: usize = 16_384;

/// Per-flush-call send budget for [`TransportCoordinator::flush_outbox`].
///
/// The transport worker owns its own per-send timeout (currently 15 s in
/// `xmpp.rs`), so a single blocking `send` can still take that long. This
/// constant is the flush-level budget checked between sends: once it has
/// elapsed, the flush stops and remaining messages stay pending for the next
/// call. It deliberately undercuts the transport's 15 s per-send timeout so
/// the session lock is never held for a whole multi-message queue.
const FLUSH_SEND_TIMEOUT: Duration = Duration::from_secs(10);

/// Maximum queued messages sent per [`TransportCoordinator::flush_outbox`] call.
///
/// Together with [`FLUSH_SEND_TIMEOUT`] this bounds the session lock hold of
/// one flush. Worst case is `FLUSH_SEND_TIMEOUT` plus one transport per-send
/// timeout (15 s), because a send already in flight cannot be interrupted.
const FLUSH_OUTBOX_MAX_BATCH: usize = 32;

/// Connection status projected to the UI.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub enum ConnectionState {
    #[default]
    Offline,
    Connecting,
    Online,
    Failed,
}

/// Presence projected for one roster contact.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub enum ContactPresence {
    Online,
    Away,
    DoNotDisturb,
    #[default]
    Offline,
}

/// Direction of a roster presence subscription as confirmed by the server.
///
/// This is an account-local projection of RFC 6121 roster state. Subscription
/// requests themselves remain transport commands and are not represented as
/// credentials or raw stanzas in the core.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub enum RosterSubscription {
    /// Neither side currently receives the other side's presence.
    #[default]
    None,
    /// The local account receives the contact's presence.
    Inbound,
    /// The contact receives the local account's presence.
    Outbound,
    /// Both sides receive presence updates.
    Mutual,
    /// The server has a pending outgoing subscription request.
    PendingOutbound,
}

/// Conversation transport topology.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum ConversationKind {
    Direct,
    MultiUserChat,
}

/// Direction of a message relative to the local account.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum MessageDirection {
    Incoming,
    Outgoing,
}

/// Delivery state shown for an outgoing message.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum DeliveryState {
    Pending,
    Sent,
    Delivered,
    Read,
    Failed,
}

/// High-level payload category. Binary data is kept outside this core model.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum MessageKind {
    Text,
    Attachment,
    Voice,
}

/// XMPP capability that must be discovered before its UI action is available.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd, Serialize, Deserialize)]
pub enum ProtocolCapability {
    MultiUserChat,
    MessageArchiveManagement,
    StreamManagement,
    HttpFileUpload,
    Omemo,
    PushNotifications,
    Receipts,
    ChatMarkers,
    ChatStates,
    MessageReactions,
    MessageCorrections,
    MessageReplies,
    SharedPins,
}

/// Details supplied when configuring an account.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct AccountSetup {
    pub jid: String,
    pub server: String,
    pub display_name: String,
}

impl AccountSetup {
    /// Constructs a setup request; server/JID validation occurs in the core.
    #[must_use]
    pub fn new(
        jid: impl Into<String>,
        server: impl Into<String>,
        display_name: impl Into<String>,
    ) -> Self {
        Self { jid: jid.into(), server: server.into(), display_name: display_name.into() }
    }
}

/// One configured XMPP account.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Account {
    pub id: AccountId,
    pub jid: String,
    pub server: String,
    pub display_name: String,
    pub connection_state: ConnectionState,
    pub capabilities: BTreeSet<ProtocolCapability>,
    /// Last connection failure reason, cleared on the next successful connect.
    pub connection_error: Option<String>,
    /// Whether a mid-session network loss is retried automatically (XEP-0198
    /// resume). Non-secret and persisted, so it survives a restore. Defaults
    /// to `true`: a user-requested disconnect is immediate either way.
    #[serde(default = "default_auto_reconnect")]
    pub auto_reconnect: bool,
}

/// Accounts default to automatic reconnection; 0.1.8 made mid-session network
/// loss recoverable instead of terminal.
fn default_auto_reconnect() -> bool {
    true
}

/// A roster contact projection owned by one account.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Contact {
    pub account_id: AccountId,
    pub jid: String,
    pub display_name: String,
    pub presence: ContactPresence,
    pub status: Option<String>,
    pub subscription: RosterSubscription,
}

/// A local conversation projection.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Conversation {
    pub id: ConversationId,
    pub account_id: AccountId,
    pub kind: ConversationKind,
    pub address: String,
    pub title: String,
    pub unread_count: u32,
    pub last_activity_epoch_ms: u64,
}

/// Attachment metadata that is safe to expose to the UI.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Attachment {
    pub id: String,
    pub filename: String,
    pub mime_type: String,
    pub byte_count: u64,
    pub remote_url: Option<String>,
}

/// An immutable message projection.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Message {
    pub id: MessageId,
    pub conversation_id: ConversationId,
    pub sender: String,
    pub body: String,
    pub direction: MessageDirection,
    pub kind: MessageKind,
    pub sent_at_epoch_ms: u64,
    pub delivery_state: DeliveryState,
    pub in_reply_to: Option<MessageId>,
    pub attachment: Option<Attachment>,
}

/// An emoji reaction attached to a message.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Reaction {
    pub id: ReactionId,
    pub message_id: MessageId,
    pub emoji: String,
    pub actor: String,
}

/// Read-only state suitable for a persistence adapter or test fixture.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub struct CoreSnapshot {
    pub accounts: Vec<Account>,
    pub contacts: Vec<Contact>,
    pub conversations: Vec<Conversation>,
    pub messages: Vec<Message>,
    pub reactions: Vec<Reaction>,
}

/// Events emitted after state changes. Native UI layers subscribe and render
/// snapshots rather than reaching into the core's internal maps.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CoreEvent {
    AccountChanged(AccountId),
    RosterChanged(AccountId),
    ConversationChanged(ConversationId),
    MessageAdded(MessageId),
    MessageChanged(MessageId),
}

/// Operations available to first-party UI and the controlled extension/automation boundary.
///
/// The base app does not execute third-party commands. Keeping the vocabulary
/// internal now makes its later permission model explicit and auditable.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CoreCommand {
    SendText { conversation_id: ConversationId, body: String, in_reply_to: Option<MessageId> },
    MarkConversationRead { conversation_id: ConversationId },
    AddReaction { message_id: MessageId, emoji: String },
}

/// Typed failures returned to Kotlin and protocol adapters.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CoreError {
    InvalidJid,
    InvalidServer,
    InvalidConversationAddress,
    EmptyMessage,
    MessageTooLong,
    EmptyReaction,
    EmptyDisplayName,
    InvalidReplyTarget,
    UnknownAccount(AccountId),
    UnknownConversation(ConversationId),
    UnknownMessage(MessageId),
    CapabilityUnavailable(ProtocolCapability),
}

impl fmt::Display for CoreError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidJid => formatter.write_str("a full JID is required"),
            Self::InvalidServer => formatter.write_str("a server hostname is required"),
            Self::InvalidConversationAddress => {
                formatter.write_str("a full conversation address is required")
            }
            Self::EmptyMessage => formatter.write_str("a message cannot be empty"),
            Self::MessageTooLong => {
                formatter.write_str("message exceeds the configured length limit")
            }
            Self::EmptyReaction => formatter.write_str("a reaction cannot be empty"),
            Self::EmptyDisplayName => formatter.write_str("a display name is required"),
            Self::InvalidReplyTarget => {
                formatter.write_str("a reply must target a message in the same conversation")
            }
            Self::UnknownAccount(id) => write!(formatter, "unknown account {id}"),
            Self::UnknownConversation(id) => write!(formatter, "unknown conversation {id}"),
            Self::UnknownMessage(id) => write!(formatter, "unknown message {id}"),
            Self::CapabilityUnavailable(capability) => {
                write!(formatter, "server does not advertise {capability:?}")
            }
        }
    }
}

impl std::error::Error for CoreError {}

/// Failure while coordinating a domain core with an XMPP transport adapter.
#[derive(Debug)]
pub enum TransportCoordinatorError {
    Core(CoreError),
    Transport(TransportError),
}

impl fmt::Display for TransportCoordinatorError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Core(error) => write!(formatter, "core error: {error}"),
            Self::Transport(error) => write!(formatter, "transport error: {error}"),
        }
    }
}

impl std::error::Error for TransportCoordinatorError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Core(error) => Some(error),
            Self::Transport(error) => Some(error),
        }
    }
}

impl From<CoreError> for TransportCoordinatorError {
    fn from(value: CoreError) -> Self {
        Self::Core(value)
    }
}

impl From<TransportError> for TransportCoordinatorError {
    fn from(value: TransportError) -> Self {
        Self::Transport(value)
    }
}

/// Persistence boundary. SQLCipher-backed Android storage will implement this
/// trait; test and preview clients use `InMemoryStore`.
pub trait CoreStore {
    fn load(&self) -> Result<CoreSnapshot, CoreError>;
    fn save(&mut self, snapshot: CoreSnapshot) -> Result<(), CoreError>;
}

/// Deterministic in-memory persistence adapter used by unit tests and previews.
#[derive(Clone, Debug, Default)]
pub struct InMemoryStore {
    snapshot: CoreSnapshot,
}

impl CoreStore for InMemoryStore {
    fn load(&self) -> Result<CoreSnapshot, CoreError> {
        Ok(self.snapshot.clone())
    }

    fn save(&mut self, snapshot: CoreSnapshot) -> Result<(), CoreError> {
        self.snapshot = snapshot;
        Ok(())
    }
}

/// In-memory state machine that the XMPP transport and generated bindings use.
#[derive(Clone, Debug, Default)]
pub struct MindChatCore {
    next_account_id: AccountId,
    next_conversation_id: ConversationId,
    next_message_id: MessageId,
    next_reaction_id: ReactionId,
    accounts: HashMap<AccountId, Account>,
    contacts: HashMap<(AccountId, String), Contact>,
    conversations: HashMap<ConversationId, Conversation>,
    messages: HashMap<MessageId, Message>,
    reactions: HashMap<ReactionId, Reaction>,
    events: Vec<CoreEvent>,
}

impl MindChatCore {
    /// Restores the visible state from a storage snapshot.
    #[must_use]
    pub fn from_snapshot(snapshot: CoreSnapshot) -> Self {
        let next_account_id = snapshot.accounts.iter().map(|item| item.id).max().unwrap_or(0) + 1;
        let next_conversation_id =
            snapshot.conversations.iter().map(|item| item.id).max().unwrap_or(0) + 1;
        let next_message_id = snapshot.messages.iter().map(|item| item.id).max().unwrap_or(0) + 1;
        let next_reaction_id = snapshot.reactions.iter().map(|item| item.id).max().unwrap_or(0) + 1;

        Self {
            next_account_id,
            next_conversation_id,
            next_message_id,
            next_reaction_id,
            accounts: snapshot.accounts.into_iter().map(|item| (item.id, item)).collect(),
            contacts: snapshot
                .contacts
                .into_iter()
                .map(|item| ((item.account_id, item.jid.clone()), item))
                .collect(),
            conversations: snapshot.conversations.into_iter().map(|item| (item.id, item)).collect(),
            messages: snapshot.messages.into_iter().map(|item| (item.id, item)).collect(),
            reactions: snapshot.reactions.into_iter().map(|item| (item.id, item)).collect(),
            events: Vec::new(),
        }
    }

    /// Returns a deterministic snapshot for a persistence adapter.
    #[must_use]
    pub fn snapshot(&self) -> CoreSnapshot {
        let mut accounts = self.accounts.values().cloned().collect::<Vec<_>>();
        let mut contacts = self.contacts.values().cloned().collect::<Vec<_>>();
        let mut conversations = self.conversations.values().cloned().collect::<Vec<_>>();
        let mut messages = self.messages.values().cloned().collect::<Vec<_>>();
        let mut reactions = self.reactions.values().cloned().collect::<Vec<_>>();
        accounts.sort_by_key(|item| item.id);
        contacts.sort_by(|left, right| {
            left.account_id.cmp(&right.account_id).then_with(|| left.jid.cmp(&right.jid))
        });
        conversations.sort_by_key(|item| item.id);
        messages.sort_by_key(|item| item.id);
        reactions.sort_by_key(|item| item.id);
        CoreSnapshot { accounts, contacts, conversations, messages, reactions }
    }

    /// Configures an account in the local core.
    pub fn add_account(&mut self, setup: AccountSetup) -> Result<AccountId, CoreError> {
        validate_jid(&setup.jid)?;
        validate_server(&setup.server)?;

        let id = self.allocate_account_id();
        self.accounts.insert(
            id,
            Account {
                id,
                jid: setup.jid,
                server: setup.server,
                display_name: setup.display_name,
                connection_state: ConnectionState::Offline,
                capabilities: BTreeSet::new(),
                connection_error: None,
                auto_reconnect: true,
            },
        );
        self.events.push(CoreEvent::AccountChanged(id));
        Ok(id)
    }

    /// Moves an account into the connection lifecycle.
    pub fn set_connection_state(
        &mut self,
        account_id: AccountId,
        state: ConnectionState,
    ) -> Result<(), CoreError> {
        let account =
            self.accounts.get_mut(&account_id).ok_or(CoreError::UnknownAccount(account_id))?;
        account.connection_state = state;
        if matches!(state, ConnectionState::Connecting | ConnectionState::Online) {
            account.connection_error = None;
        }
        self.events.push(CoreEvent::AccountChanged(account_id));
        Ok(())
    }

    /// Replaces capabilities after XMPP service discovery completes.
    pub fn set_capabilities(
        &mut self,
        account_id: AccountId,
        capabilities: impl IntoIterator<Item = ProtocolCapability>,
    ) -> Result<(), CoreError> {
        let account =
            self.accounts.get_mut(&account_id).ok_or(CoreError::UnknownAccount(account_id))?;
        account.capabilities = capabilities.into_iter().collect();
        self.events.push(CoreEvent::AccountChanged(account_id));
        Ok(())
    }

    /// Creates or updates one local roster projection for an existing account.
    ///
    /// A future XMPP roster adapter owns subscription negotiation and server
    /// synchronization. This core stores only the normalized, UI-safe state.
    pub fn upsert_contact(
        &mut self,
        account_id: AccountId,
        jid: impl Into<String>,
        display_name: impl Into<String>,
        presence: ContactPresence,
        status: Option<String>,
    ) -> Result<(), CoreError> {
        self.ensure_account(account_id)?;
        let jid = jid.into();
        validate_jid(&jid)?;
        let display_name = normalize_contact_name(display_name.into(), &jid);
        let status = normalize_status(status);
        self.contacts.insert(
            (account_id, jid.clone()),
            Contact {
                account_id,
                jid,
                display_name,
                presence,
                status,
                subscription: RosterSubscription::None,
            },
        );
        self.events.push(CoreEvent::RosterChanged(account_id));
        Ok(())
    }

    /// Applies server-confirmed roster metadata while retaining the most
    /// recent presence projection for an already-known contact.
    ///
    /// Malformed roster data (an unknown account or an invalid JID) is
    /// ignored rather than surfaced as an error, so one bad roster stanza
    /// from a server cannot abort the transport polling loop.
    pub fn sync_roster_contact(
        &mut self,
        account_id: AccountId,
        jid: impl Into<String>,
        display_name: impl Into<String>,
        subscription: RosterSubscription,
    ) -> Result<bool, CoreError> {
        if !self.accounts.contains_key(&account_id) {
            return Ok(false);
        }
        let jid = jid.into();
        if validate_jid(&jid).is_err() {
            return Ok(false);
        }
        let display_name = normalize_contact_name(display_name.into(), &jid);
        let existing = self.contacts.get(&(account_id, jid.clone()));
        let presence = existing.map_or(ContactPresence::Offline, |contact| contact.presence);
        let status = existing.and_then(|contact| contact.status.clone());
        self.contacts.insert(
            (account_id, jid.clone()),
            Contact { account_id, jid, display_name, presence, status, subscription },
        );
        self.events.push(CoreEvent::RosterChanged(account_id));
        Ok(true)
    }

    /// Removes a contact after a server roster removal event.
    ///
    /// A removal for an already absent contact is harmless and does not emit a
    /// redundant UI event. Malformed roster data (an unknown account or an
    /// invalid JID) is ignored so a bad roster stanza cannot abort polling.
    pub fn remove_contact(
        &mut self,
        account_id: AccountId,
        jid: impl Into<String>,
    ) -> Result<bool, CoreError> {
        if !self.accounts.contains_key(&account_id) {
            return Ok(false);
        }
        let jid = jid.into();
        if validate_jid(&jid).is_err() {
            return Ok(false);
        }
        let removed = self.contacts.remove(&(account_id, jid)).is_some();
        if removed {
            self.events.push(CoreEvent::RosterChanged(account_id));
        }
        Ok(removed)
    }

    /// Updates presence received for an existing roster contact.
    ///
    /// Directed presence from a non-roster JID is intentionally ignored so it
    /// cannot create an unrequested contact projection. Malformed presence
    /// (an unknown account or an invalid JID) is also ignored, keeping one bad
    /// presence stanza from aborting the transport polling loop.
    pub fn update_contact_presence(
        &mut self,
        account_id: AccountId,
        jid: impl Into<String>,
        presence: ContactPresence,
        status: Option<String>,
    ) -> Result<bool, CoreError> {
        if !self.accounts.contains_key(&account_id) {
            return Ok(false);
        }
        let jid = jid.into();
        if validate_jid(&jid).is_err() {
            return Ok(false);
        }
        let Some(contact) = self.contacts.get_mut(&(account_id, jid)) else {
            return Ok(false);
        };
        contact.presence = presence;
        contact.status = normalize_status(status);
        self.events.push(CoreEvent::RosterChanged(account_id));
        Ok(true)
    }

    /// Applies one normalized event emitted by an XMPP transport adapter.
    ///
    /// The adapter owns parsing and network recovery. This state machine owns
    /// the durable UI projection, so no transport-specific type needs to
    /// reach storage or a generated binding.
    ///
    /// Event application is resilient by contract: malformed or unknown
    /// transport data (an invalid JID, an invalid conversation address, or an
    /// unknown account) is skipped rather than surfaced as an error, so a
    /// single bad stanza can never abort the FFI polling loop.
    pub fn apply_transport_event(
        &mut self,
        event: TransportEvent,
    ) -> Result<Option<MessageId>, CoreError> {
        match event {
            TransportEvent::Connected { account_id, capabilities } => {
                let Some(account) = self.accounts.get_mut(&account_id) else {
                    return Ok(None);
                };
                account.connection_state = ConnectionState::Online;
                account.capabilities = capabilities;
                account.connection_error = None;
                self.events.push(CoreEvent::AccountChanged(account_id));
                Ok(None)
            }
            TransportEvent::Disconnected { account_id, recoverable, detail } => {
                let Some(account) = self.accounts.get_mut(&account_id) else {
                    return Ok(None);
                };
                account.connection_state =
                    if recoverable { ConnectionState::Offline } else { ConnectionState::Failed };
                account.connection_error = detail;
                self.events.push(CoreEvent::AccountChanged(account_id));
                Ok(None)
            }
            TransportEvent::CapabilitiesDiscovered { account_id, capabilities } => {
                // Capabilities for an account that is not configured locally
                // are ignored; there is nothing to project them onto.
                if self.accounts.contains_key(&account_id) {
                    self.set_capabilities(account_id, capabilities)?;
                }
                Ok(None)
            }
            TransportEvent::RosterContactUpsert { account_id, jid, display_name, subscription } => {
                self.sync_roster_contact(account_id, jid, display_name, subscription)?;
                Ok(None)
            }
            TransportEvent::RosterContactRemoved { account_id, jid } => {
                self.remove_contact(account_id, jid)?;
                Ok(None)
            }
            TransportEvent::ContactPresenceUpdated { account_id, jid, presence, status } => {
                self.update_contact_presence(account_id, jid, presence, status)?;
                Ok(None)
            }
            TransportEvent::IncomingText {
                account_id,
                kind,
                address,
                sender,
                body,
                received_at_epoch_ms,
            } => self.receive_transport_text(
                account_id,
                kind,
                address,
                sender,
                body,
                received_at_epoch_ms,
            ),
            TransportEvent::DeliveryUpdated { account_id, sender, message_id, state } => {
                // Receipt events are account- and sender-scoped. A stale,
                // unknown, malformed, or foreign receipt is harmless and
                // must not abort the polling loop.
                self.apply_delivery_update(account_id, &sender, message_id, state)?;
                Ok(None)
            }
        }
    }

    /// Opens or creates a conversation projection.
    pub fn open_conversation(
        &mut self,
        account_id: AccountId,
        kind: ConversationKind,
        address: impl Into<String>,
        title: impl Into<String>,
        now_epoch_ms: u64,
    ) -> Result<ConversationId, CoreError> {
        let address = address.into();
        let title = title.into();
        validate_conversation_address(&address)?;
        let account =
            self.accounts.get(&account_id).ok_or(CoreError::UnknownAccount(account_id))?;
        if kind == ConversationKind::MultiUserChat
            && !account.capabilities.contains(&ProtocolCapability::MultiUserChat)
        {
            return Err(CoreError::CapabilityUnavailable(ProtocolCapability::MultiUserChat));
        }
        if let Some(existing) = self.conversations.values().find(|item| {
            item.account_id == account_id && item.kind == kind && item.address == address
        }) {
            return Ok(existing.id);
        }

        let id = self.allocate_conversation_id();
        self.conversations.insert(
            id,
            Conversation {
                id,
                account_id,
                kind,
                address,
                title,
                unread_count: 0,
                last_activity_epoch_ms: now_epoch_ms,
            },
        );
        self.events.push(CoreEvent::ConversationChanged(id));
        Ok(id)
    }

    /// Applies a first-party command under the same validation as automation.
    pub fn execute(
        &mut self,
        command: CoreCommand,
        actor: impl Into<String>,
        now_epoch_ms: u64,
    ) -> Result<Option<MessageId>, CoreError> {
        match command {
            CoreCommand::SendText { conversation_id, body, in_reply_to } => {
                self.send_text(conversation_id, actor, body, in_reply_to, now_epoch_ms).map(Some)
            }
            CoreCommand::MarkConversationRead { conversation_id } => {
                self.mark_conversation_read(conversation_id)?;
                Ok(None)
            }
            CoreCommand::AddReaction { message_id, emoji } => {
                self.add_reaction(message_id, actor, emoji)?;
                Ok(None)
            }
        }
    }

    /// Executes a permissioned extension command as the owning local account.
    ///
    /// The extension supplies neither a sender identity nor raw transport data.
    /// Its manifest policy is checked before mutation, and message/reaction
    /// attribution always uses the JID of the account that owns the target
    /// conversation.
    pub fn execute_extension_command(
        &mut self,
        policy: &ExtensionPolicy,
        command: CoreCommand,
        now_epoch_ms: u64,
    ) -> Result<Option<MessageId>, ExtensionCommandError> {
        policy.authorize_command(&command)?;
        match command {
            CoreCommand::SendText { conversation_id, body, in_reply_to } => {
                let sender = self.local_sender_for_conversation(conversation_id)?;
                self.send_text(conversation_id, sender, body, in_reply_to, now_epoch_ms)
                    .map(Some)
                    .map_err(Into::into)
            }
            CoreCommand::MarkConversationRead { conversation_id } => {
                self.mark_conversation_read(conversation_id)?;
                Ok(None)
            }
            CoreCommand::AddReaction { message_id, emoji } => {
                let conversation_id = self
                    .messages
                    .get(&message_id)
                    .ok_or(CoreError::UnknownMessage(message_id))?
                    .conversation_id;
                let actor = self.local_sender_for_conversation(conversation_id)?;
                self.add_reaction(message_id, actor, emoji).map(|_| None).map_err(Into::into)
            }
        }
    }

    /// Adds an outgoing text message to the pending queue projection.
    pub fn send_text(
        &mut self,
        conversation_id: ConversationId,
        sender: impl Into<String>,
        body: impl Into<String>,
        in_reply_to: Option<MessageId>,
        now_epoch_ms: u64,
    ) -> Result<MessageId, CoreError> {
        self.ensure_conversation(conversation_id)?;
        if let Some(reply_id) = in_reply_to {
            let reply = self.messages.get(&reply_id).ok_or(CoreError::UnknownMessage(reply_id))?;
            if reply.conversation_id != conversation_id {
                return Err(CoreError::InvalidReplyTarget);
            }
        }
        let body = validate_body(body.into())?;

        let id = self.allocate_message_id();
        self.messages.insert(
            id,
            Message {
                id,
                conversation_id,
                sender: sender.into(),
                body,
                direction: MessageDirection::Outgoing,
                kind: MessageKind::Text,
                sent_at_epoch_ms: now_epoch_ms,
                delivery_state: DeliveryState::Pending,
                in_reply_to,
                attachment: None,
            },
        );
        self.touch_conversation(conversation_id, now_epoch_ms, false)?;
        self.events.push(CoreEvent::MessageAdded(id));
        Ok(id)
    }

    /// Inserts an incoming text message after the transport has validated it.
    pub fn receive_text(
        &mut self,
        conversation_id: ConversationId,
        sender: impl Into<String>,
        body: impl Into<String>,
        now_epoch_ms: u64,
    ) -> Result<MessageId, CoreError> {
        self.ensure_conversation(conversation_id)?;
        let body = validate_body(body.into())?;
        let id = self.allocate_message_id();
        self.messages.insert(
            id,
            Message {
                id,
                conversation_id,
                sender: sender.into(),
                body,
                direction: MessageDirection::Incoming,
                kind: MessageKind::Text,
                sent_at_epoch_ms: now_epoch_ms,
                delivery_state: DeliveryState::Delivered,
                in_reply_to: None,
                attachment: None,
            },
        );
        self.touch_conversation(conversation_id, now_epoch_ms, true)?;
        self.events.push(CoreEvent::MessageAdded(id));
        Ok(id)
    }

    /// Adds attachment metadata after a successful or queued upload.
    pub fn attach(
        &mut self,
        message_id: MessageId,
        kind: MessageKind,
        attachment: Attachment,
    ) -> Result<(), CoreError> {
        if !matches!(kind, MessageKind::Attachment | MessageKind::Voice) {
            return Err(CoreError::CapabilityUnavailable(ProtocolCapability::HttpFileUpload));
        }
        let conversation_id = self
            .messages
            .get(&message_id)
            .ok_or(CoreError::UnknownMessage(message_id))?
            .conversation_id;
        self.ensure_conversation_capability(conversation_id, ProtocolCapability::HttpFileUpload)?;
        let message =
            self.messages.get_mut(&message_id).ok_or(CoreError::UnknownMessage(message_id))?;
        message.kind = kind;
        message.attachment = Some(attachment);
        self.events.push(CoreEvent::MessageChanged(message_id));
        Ok(())
    }

    /// Updates the delivery projection received from Stream Management or a receipt.
    pub fn set_delivery_state(
        &mut self,
        message_id: MessageId,
        delivery_state: DeliveryState,
    ) -> Result<(), CoreError> {
        let message =
            self.messages.get_mut(&message_id).ok_or(CoreError::UnknownMessage(message_id))?;
        message.delivery_state = delivery_state;
        self.events.push(CoreEvent::MessageChanged(message_id));
        Ok(())
    }

    /// Applies a scoped delivery update from the transport.
    ///
    /// The transport does not own the domain outbox, so it may receive a
    /// receipt for an unknown, already-restored, or unrelated message. Such
    /// receipts are ignored rather than surfaced as core errors; this keeps a
    /// malformed peer stanza from stopping subsequent polling.
    fn apply_delivery_update(
        &mut self,
        account_id: AccountId,
        sender: &str,
        message_id: MessageId,
        delivery_state: DeliveryState,
    ) -> Result<bool, CoreError> {
        if !self.accounts.contains_key(&account_id) || validate_jid(sender).is_err() {
            return Ok(false);
        }
        let Some(message) = self.messages.get(&message_id) else {
            return Ok(false);
        };
        if message.direction != MessageDirection::Outgoing {
            return Ok(false);
        }
        let Some(conversation) = self.conversations.get(&message.conversation_id) else {
            return Ok(false);
        };
        // A receipt sender is compared as a bare JID, matching transport
        // normalization. The conversation address is required to be direct so
        // a groupchat or another account cannot claim this message ID.
        if conversation.account_id != account_id
            || conversation.kind != ConversationKind::Direct
            || conversation.address != sender
        {
            return Ok(false);
        }
        // Receipt delivery must never regress a stronger local state (for
        // example Read -> Delivered) and duplicate receipts are idempotent.
        let current_state = message.delivery_state;
        if delivery_rank(delivery_state) <= delivery_rank(current_state) {
            return Ok(false);
        }
        self.set_delivery_state(message_id, delivery_state)?;
        Ok(true)
    }

    /// Returns locally queued outgoing text in message-id order for one
    /// account. Pending and failed messages are intentionally retained in the
    /// snapshot, allowing a reconnecting transport to retry them after core
    /// restoration without exposing its queue implementation to the UI.
    pub fn pending_outgoing_messages(
        &self,
        account_id: AccountId,
    ) -> Result<Vec<OutgoingMessage>, CoreError> {
        self.accounts
            .contains_key(&account_id)
            .then_some(())
            .ok_or(CoreError::UnknownAccount(account_id))?;

        let mut outgoing = self
            .messages
            .values()
            .filter(|message| {
                message.direction == MessageDirection::Outgoing
                    && matches!(
                        message.delivery_state,
                        DeliveryState::Pending | DeliveryState::Failed
                    )
            })
            .filter_map(|message| {
                let conversation = self.conversations.get(&message.conversation_id)?;
                (conversation.account_id == account_id).then(|| OutgoingMessage {
                    account_id,
                    conversation_id: conversation.id,
                    message_id: message.id,
                    kind: conversation.kind,
                    recipient: conversation.address.clone(),
                    body: message.body.clone(),
                    in_reply_to: message.in_reply_to,
                })
            })
            .collect::<Vec<_>>();
        outgoing.sort_by_key(|message| message.message_id);
        Ok(outgoing)
    }

    /// Adds a reaction while preserving all existing participants' reactions.
    pub fn add_reaction(
        &mut self,
        message_id: MessageId,
        actor: impl Into<String>,
        emoji: impl Into<String>,
    ) -> Result<ReactionId, CoreError> {
        let conversation_id = self
            .messages
            .get(&message_id)
            .ok_or(CoreError::UnknownMessage(message_id))?
            .conversation_id;
        self.ensure_conversation_capability(conversation_id, ProtocolCapability::MessageReactions)?;
        let emoji = emoji.into();
        if emoji.trim().is_empty() {
            return Err(CoreError::EmptyReaction);
        }
        let id = self.allocate_reaction_id();
        self.reactions.insert(id, Reaction { id, message_id, emoji, actor: actor.into() });
        self.events.push(CoreEvent::MessageChanged(message_id));
        Ok(id)
    }

    /// Clears the unread badge after the active chat becomes visible.
    pub fn mark_conversation_read(
        &mut self,
        conversation_id: ConversationId,
    ) -> Result<(), CoreError> {
        let conversation = self
            .conversations
            .get_mut(&conversation_id)
            .ok_or(CoreError::UnknownConversation(conversation_id))?;
        conversation.unread_count = 0;
        self.events.push(CoreEvent::ConversationChanged(conversation_id));
        Ok(())
    }

    /// Removes an account and every projection owned by it.
    ///
    /// The account's contacts, conversations, and the messages and reactions
    /// of those conversations are removed from the core. No removal-specific
    /// event exists yet, so the UI is notified with the established
    /// [`CoreEvent::AccountChanged`] event; the next snapshot simply no longer
    /// contains the account or its data.
    pub fn delete_account(&mut self, account_id: AccountId) -> Result<(), CoreError> {
        if !self.accounts.contains_key(&account_id) {
            return Err(CoreError::UnknownAccount(account_id));
        }
        let conversation_ids = self
            .conversations
            .values()
            .filter(|conversation| conversation.account_id == account_id)
            .map(|conversation| conversation.id)
            .collect::<Vec<_>>();
        for conversation_id in conversation_ids {
            self.remove_conversation_state(conversation_id);
        }
        self.contacts.retain(|(owner, _), _| *owner != account_id);
        self.accounts.remove(&account_id);
        self.events.push(CoreEvent::AccountChanged(account_id));
        Ok(())
    }

    /// Removes a conversation and every message and reaction inside it.
    ///
    /// Notifies the UI with the established [`CoreEvent::ConversationChanged`]
    /// event; the next snapshot no longer contains the conversation.
    pub fn delete_conversation(
        &mut self,
        conversation_id: ConversationId,
    ) -> Result<(), CoreError> {
        if !self.conversations.contains_key(&conversation_id) {
            return Err(CoreError::UnknownConversation(conversation_id));
        }
        self.remove_conversation_state(conversation_id);
        self.events.push(CoreEvent::ConversationChanged(conversation_id));
        Ok(())
    }

    /// Replaces the display name shown for an account.
    pub fn update_account_display_name(
        &mut self,
        account_id: AccountId,
        display_name: impl Into<String>,
    ) -> Result<(), CoreError> {
        let display_name = display_name.into();
        if display_name.trim().is_empty() {
            return Err(CoreError::EmptyDisplayName);
        }
        let account =
            self.accounts.get_mut(&account_id).ok_or(CoreError::UnknownAccount(account_id))?;
        account.display_name = display_name;
        self.events.push(CoreEvent::AccountChanged(account_id));
        Ok(())
    }

    /// Drains events in mutation order for a UI event loop.
    pub fn drain_events(&mut self) -> Vec<CoreEvent> {
        std::mem::take(&mut self.events)
    }

    /// Returns all accounts ordered by their stable ID.
    #[must_use]
    pub fn accounts(&self) -> Vec<Account> {
        self.snapshot().accounts
    }

    /// Returns all conversations for an account ordered by stable ID.
    #[must_use]
    pub fn conversations(&self, account_id: AccountId) -> Vec<Conversation> {
        self.snapshot()
            .conversations
            .into_iter()
            .filter(|item| item.account_id == account_id)
            .collect()
    }

    /// Returns roster contacts for an account sorted by JID.
    #[must_use]
    pub fn contacts(&self, account_id: AccountId) -> Vec<Contact> {
        self.snapshot().contacts.into_iter().filter(|item| item.account_id == account_id).collect()
    }

    /// Returns all messages in a conversation ordered by stable ID.
    #[must_use]
    pub fn messages(&self, conversation_id: ConversationId) -> Vec<Message> {
        self.snapshot()
            .messages
            .into_iter()
            .filter(|item| item.conversation_id == conversation_id)
            .collect()
    }

    /// Returns all reactions for a message ordered by stable ID.
    #[must_use]
    pub fn reactions(&self, message_id: MessageId) -> Vec<Reaction> {
        self.snapshot().reactions.into_iter().filter(|item| item.message_id == message_id).collect()
    }

    fn allocate_account_id(&mut self) -> AccountId {
        let id = self.next_account_id.max(1);
        self.next_account_id = id + 1;
        id
    }

    fn allocate_conversation_id(&mut self) -> ConversationId {
        let id = self.next_conversation_id.max(1);
        self.next_conversation_id = id + 1;
        id
    }

    fn allocate_message_id(&mut self) -> MessageId {
        let id = self.next_message_id.max(1);
        self.next_message_id = id + 1;
        id
    }

    fn allocate_reaction_id(&mut self) -> ReactionId {
        let id = self.next_reaction_id.max(1);
        self.next_reaction_id = id + 1;
        id
    }

    fn ensure_conversation(&self, conversation_id: ConversationId) -> Result<(), CoreError> {
        self.conversations
            .contains_key(&conversation_id)
            .then_some(())
            .ok_or(CoreError::UnknownConversation(conversation_id))
    }

    fn ensure_account(&self, account_id: AccountId) -> Result<(), CoreError> {
        self.accounts
            .contains_key(&account_id)
            .then_some(())
            .ok_or(CoreError::UnknownAccount(account_id))
    }

    /// Removes a conversation, its messages, and their reactions without
    /// emitting an event. Callers emit the appropriate change event so account
    /// deletion cascades silently while direct conversation deletion notifies.
    fn remove_conversation_state(&mut self, conversation_id: ConversationId) {
        let message_ids = self
            .messages
            .values()
            .filter(|message| message.conversation_id == conversation_id)
            .map(|message| message.id)
            .collect::<Vec<_>>();
        for message_id in message_ids {
            self.reactions.retain(|_, reaction| reaction.message_id != message_id);
            self.messages.remove(&message_id);
        }
        self.conversations.remove(&conversation_id);
    }

    /// Applies an incoming message addressed to a conversation.
    ///
    /// Incoming data for an unknown account or with an invalid conversation
    /// address is skipped (`Ok(None)`) rather than surfaced as an error, so a
    /// malformed message stanza cannot abort the transport polling loop.
    fn receive_transport_text(
        &mut self,
        account_id: AccountId,
        kind: ConversationKind,
        address: String,
        sender: String,
        body: String,
        received_at_epoch_ms: u64,
    ) -> Result<Option<MessageId>, CoreError> {
        if !self.accounts.contains_key(&account_id) {
            return Ok(None);
        }
        if validate_conversation_address(&address).is_err() {
            return Ok(None);
        }
        // Validate the payload before creating a conversation projection, so
        // a rejected stanza (for example an empty body) cannot leave a
        // half-applied conversation behind.
        validate_body(body.clone())?;
        let existing_conversation_id = self
            .conversations
            .values()
            .find(|conversation| {
                conversation.account_id == account_id
                    && conversation.kind == kind
                    && conversation.address == address
            })
            .map(|conversation| conversation.id);
        let conversation_id = if let Some(id) = existing_conversation_id {
            id
        } else {
            let id = self.allocate_conversation_id();
            let title = address.split('@').next().unwrap_or(&address).to_owned();
            self.conversations.insert(
                id,
                Conversation {
                    id,
                    account_id,
                    kind,
                    address,
                    title,
                    unread_count: 0,
                    last_activity_epoch_ms: received_at_epoch_ms,
                },
            );
            self.events.push(CoreEvent::ConversationChanged(id));
            id
        };
        self.receive_text(conversation_id, sender, body, received_at_epoch_ms).map(Some)
    }

    fn local_sender_for_conversation(
        &self,
        conversation_id: ConversationId,
    ) -> Result<String, CoreError> {
        let conversation = self
            .conversations
            .get(&conversation_id)
            .ok_or(CoreError::UnknownConversation(conversation_id))?;
        self.accounts
            .get(&conversation.account_id)
            .map(|account| account.jid.clone())
            .ok_or(CoreError::UnknownAccount(conversation.account_id))
    }

    fn ensure_conversation_capability(
        &self,
        conversation_id: ConversationId,
        capability: ProtocolCapability,
    ) -> Result<(), CoreError> {
        let conversation = self
            .conversations
            .get(&conversation_id)
            .ok_or(CoreError::UnknownConversation(conversation_id))?;
        let account = self
            .accounts
            .get(&conversation.account_id)
            .ok_or(CoreError::UnknownAccount(conversation.account_id))?;
        account
            .capabilities
            .contains(&capability)
            .then_some(())
            .ok_or(CoreError::CapabilityUnavailable(capability))
    }

    fn touch_conversation(
        &mut self,
        conversation_id: ConversationId,
        now_epoch_ms: u64,
        increment_unread: bool,
    ) -> Result<(), CoreError> {
        let conversation = self
            .conversations
            .get_mut(&conversation_id)
            .ok_or(CoreError::UnknownConversation(conversation_id))?;
        conversation.last_activity_epoch_ms = now_epoch_ms;
        if increment_unread {
            conversation.unread_count = conversation.unread_count.saturating_add(1);
        }
        self.events.push(CoreEvent::ConversationChanged(conversation_id));
        Ok(())
    }
}

/// Coordinates a [`MindChatCore`] with a concrete XMPP transport.
///
/// The coordinator retains credentials only for the duration of a connect
/// call. It sends queued text in stable message-id order, projects transport
/// events through the core, and leaves persistence and UI access on their
/// respective sides of the boundary.
pub struct TransportCoordinator<T> {
    core: MindChatCore,
    transport: T,
}

impl<T> TransportCoordinator<T> {
    /// Creates a coordinator from independently configured core and transport values.
    #[must_use]
    pub fn new(core: MindChatCore, transport: T) -> Self {
        Self { core, transport }
    }

    /// Returns the domain state projection.
    #[must_use]
    pub fn core(&self) -> &MindChatCore {
        &self.core
    }

    /// Returns mutable domain state for controlled setup and persistence restoration.
    pub fn core_mut(&mut self) -> &mut MindChatCore {
        &mut self.core
    }

    /// Returns the owned XMPP transport adapter.
    #[must_use]
    pub fn transport(&self) -> &T {
        &self.transport
    }

    /// Returns mutable transport access for adapter configuration and tests.
    pub fn transport_mut(&mut self) -> &mut T {
        &mut self.transport
    }

    /// Splits the coordinator back into its state and transport values.
    #[must_use]
    pub fn into_parts(self) -> (MindChatCore, T) {
        (self.core, self.transport)
    }
}

impl<T: XmppTransport> TransportCoordinator<T> {
    /// Starts an account connection without storing the supplied password in the core.
    pub fn connect(
        &mut self,
        account_id: AccountId,
        password: SecretString,
    ) -> Result<(), TransportCoordinatorError> {
        let account =
            self.core.accounts.get(&account_id).ok_or(CoreError::UnknownAccount(account_id))?;
        let request = ConnectionRequest {
            account_id,
            jid: account.jid.clone(),
            server: account.server.clone(),
            password,
            auto_reconnect: account.auto_reconnect,
        };
        self.core.set_connection_state(account_id, ConnectionState::Connecting)?;
        if let Err(error) = self.transport.connect(request) {
            self.core.set_connection_state(account_id, ConnectionState::Failed)?;
            return Err(error.into());
        }
        Ok(())
    }

    /// Disconnects an account and projects a local offline state.
    pub fn disconnect(&mut self, account_id: AccountId) -> Result<(), TransportCoordinatorError> {
        self.transport.disconnect(account_id)?;
        self.core.apply_transport_event(TransportEvent::Disconnected {
            account_id,
            recoverable: true,
            detail: None,
        })?;
        Ok(())
    }

    /// Sends pending or retryable text for an account in stable message-id order.
    ///
    /// The flush is bounded so a stalled server cannot freeze the whole app:
    /// at most [`FLUSH_OUTBOX_MAX_BATCH`] messages are sent per call and the
    /// call stops once [`FLUSH_SEND_TIMEOUT`] has elapsed between sends. Each
    /// `send` can itself block up to the transport's own per-send timeout
    /// (currently 15 s inside the XMPP worker), so the worst-case session lock
    /// hold is `FLUSH_SEND_TIMEOUT` plus one per-send timeout, roughly 25 s.
    /// Messages left behind stay pending and are retried by the next flush
    /// call. A send failure still aborts the batch immediately and marks the
    /// failing message `Failed`, preserving the abort-on-first-error contract.
    pub fn flush_outbox(
        &mut self,
        account_id: AccountId,
    ) -> Result<usize, TransportCoordinatorError> {
        let pending = self.core.pending_outgoing_messages(account_id)?;
        let started = Instant::now();
        let mut sent_count = 0;
        for message in pending.into_iter().take(FLUSH_OUTBOX_MAX_BATCH) {
            if started.elapsed() >= FLUSH_SEND_TIMEOUT {
                break;
            }
            let message_id = message.message_id;
            if let Err(error) = self.transport.send(message) {
                self.core.set_delivery_state(message_id, DeliveryState::Failed)?;
                return Err(error.into());
            }
            self.core.set_delivery_state(message_id, DeliveryState::Sent)?;
            sent_count += 1;
        }
        Ok(sent_count)
    }

    /// Polls one normalized transport event and applies it to the domain state.
    ///
    /// Returns `Ok(true)` when an event was applied, `Ok(false)` when the
    /// queue is empty, and `Err` for a transport channel failure or a
    /// per-event application failure. Application failures are non-fatal:
    /// [`Self::poll_transport_events`] skips the failing event and keeps
    /// draining, so a single malformed stanza can never abort the poll loop.
    pub fn poll_next_event(&mut self) -> Result<bool, TransportCoordinatorError> {
        let Some(event) = self.transport.next_event()? else {
            return Ok(false);
        };
        self.core.apply_transport_event(event)?;
        Ok(true)
    }

    /// Applies up to `max_events` queued transport events to the domain state.
    ///
    /// A failing event is consumed and skipped rather than aborting the
    /// batch. This guarantees `Connected`/`Disconnected` events queued behind
    /// a malformed stanza are still applied in the same poll cycle. Only a
    /// transport channel failure (for example a dead worker) aborts the loop
    /// and propagates as `Err`. Returns the number of events consumed.
    pub fn poll_transport_events(
        &mut self,
        max_events: usize,
    ) -> Result<usize, TransportCoordinatorError> {
        let mut processed = 0;
        while processed < max_events {
            match self.poll_next_event() {
                // Applied events and skipped-but-consumed failing events both
                // advance the batch; only the queue running empty stops it.
                Ok(true) | Err(TransportCoordinatorError::Core(_)) => processed += 1,
                Ok(false) => break,
                Err(error) => return Err(error),
            }
        }
        Ok(processed)
    }
}

fn delivery_rank(state: DeliveryState) -> u8 {
    match state {
        DeliveryState::Pending => 0,
        DeliveryState::Failed => 1,
        DeliveryState::Sent => 2,
        DeliveryState::Delivered => 3,
        DeliveryState::Read => 4,
    }
}

fn validate_jid(jid: &str) -> Result<(), CoreError> {
    let mut split = jid.split('@');
    let local = split.next().unwrap_or_default();
    let domain = split.next().unwrap_or_default();
    if local.trim().is_empty()
        || domain.trim().is_empty()
        || local.contains(char::is_whitespace)
        || domain.contains(char::is_whitespace)
        || domain.contains('/')
        || split.next().is_some()
    {
        return Err(CoreError::InvalidJid);
    }
    Ok(())
}

fn validate_server(server: &str) -> Result<(), CoreError> {
    (!server.trim().is_empty() && !server.contains(char::is_whitespace))
        .then_some(())
        .ok_or(CoreError::InvalidServer)
}

fn validate_conversation_address(address: &str) -> Result<(), CoreError> {
    validate_jid(address).map_err(|_| CoreError::InvalidConversationAddress)
}

fn normalize_contact_name(display_name: String, jid: &str) -> String {
    match display_name.trim() {
        "" => jid.split('@').next().unwrap_or(jid).to_owned(),
        value => value.to_owned(),
    }
}

fn normalize_status(status: Option<String>) -> Option<String> {
    status.and_then(|value| match value.trim() {
        "" => None,
        value => Some(value.to_owned()),
    })
}

fn validate_body(body: String) -> Result<String, CoreError> {
    let trimmed = body.trim();
    if trimmed.is_empty() {
        return Err(CoreError::EmptyMessage);
    }
    if trimmed.chars().count() > MAX_MESSAGE_CHARS {
        return Err(CoreError::MessageTooLong);
    }
    Ok(trimmed.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    #[derive(Default)]
    struct FakeTransport {
        connection_requests: Vec<ConnectionRequest>,
        disconnected_accounts: Vec<AccountId>,
        sent_messages: Vec<OutgoingMessage>,
        events: VecDeque<TransportEvent>,
        fail_next_send: bool,
    }

    impl XmppTransport for FakeTransport {
        fn connect(&mut self, request: ConnectionRequest) -> Result<(), TransportError> {
            self.connection_requests.push(request);
            Ok(())
        }

        fn disconnect(&mut self, account_id: AccountId) -> Result<(), TransportError> {
            self.disconnected_accounts.push(account_id);
            Ok(())
        }

        fn send(&mut self, message: OutgoingMessage) -> Result<(), TransportError> {
            if self.fail_next_send {
                self.fail_next_send = false;
                return Err(TransportError::ConnectionFailed("temporary failure".to_owned()));
            }
            self.sent_messages.push(message);
            Ok(())
        }

        fn next_event(&mut self) -> Result<Option<TransportEvent>, TransportError> {
            Ok(self.events.pop_front())
        }
    }

    fn account(core: &mut MindChatCore) -> AccountId {
        core.add_account(AccountSetup::new("alice@example.org", "example.org", "Alice"))
            .expect("test account is valid")
    }

    #[test]
    fn duplicate_connected_events_are_idempotent() {
        // 0.1.8: after a mid-session network loss the worker keeps polling the
        // same client, so a reconnect (XEP-0198 resume or a fresh bind) emits
        // a second Connected for the same account. Applying it must leave the
        // account Online with the newest capabilities and no error.
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        core.apply_transport_event(TransportEvent::Connected {
            account_id,
            capabilities: BTreeSet::from([ProtocolCapability::Receipts]),
        })
        .expect("first connected applies");
        assert_eq!(core.accounts()[0].connection_state, ConnectionState::Online);

        core.apply_transport_event(TransportEvent::Connected {
            account_id,
            capabilities: BTreeSet::from([
                ProtocolCapability::Receipts,
                ProtocolCapability::StreamManagement,
            ]),
        })
        .expect("duplicate connected applies");
        assert_eq!(core.accounts()[0].connection_state, ConnectionState::Online);
        assert_eq!(
            core.accounts()[0].capabilities,
            BTreeSet::from([ProtocolCapability::Receipts, ProtocolCapability::StreamManagement,])
        );
        assert_eq!(core.accounts()[0].connection_error, None);
    }

    #[test]
    fn rejects_invalid_account_identifiers() {
        let mut core = MindChatCore::default();
        assert_eq!(
            core.add_account(AccountSetup::new("alice", "example.org", "Alice")),
            Err(CoreError::InvalidJid)
        );
        assert_eq!(
            core.add_account(AccountSetup::new("alice@example.org", "bad host", "Alice")),
            Err(CoreError::InvalidServer)
        );
        assert_eq!(
            core.add_account(AccountSetup::new("alice@example.org/mobile", "example.org", "Alice")),
            Err(CoreError::InvalidJid)
        );
    }

    #[test]
    fn accounts_start_without_undiscovered_optional_capabilities() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        assert_eq!(core.accounts()[0].id, account_id);
        assert!(core.accounts()[0].capabilities.is_empty());
    }

    #[test]
    fn roster_contacts_are_account_scoped_normalized_and_snapshot_safe() {
        let mut core = MindChatCore::default();
        let alice = account(&mut core);
        let mila = core
            .add_account(AccountSetup::new("mila@example.net", "example.net", "Mila"))
            .expect("second account");
        core.drain_events();

        core.upsert_contact(
            alice,
            "bob@example.org",
            " Bob ",
            ContactPresence::Online,
            Some(" Ready ".to_owned()),
        )
        .expect("contact");
        core.upsert_contact(
            alice,
            "bob@example.org",
            "",
            ContactPresence::Away,
            Some("   ".to_owned()),
        )
        .expect("updated contact");
        core.upsert_contact(mila, "bob@example.org", "Bobby", ContactPresence::Offline, None)
            .expect("account-scoped contact");

        assert_eq!(
            core.contacts(alice),
            vec![Contact {
                account_id: alice,
                jid: "bob@example.org".to_owned(),
                display_name: "bob".to_owned(),
                presence: ContactPresence::Away,
                status: None,
                subscription: RosterSubscription::None,
            }]
        );
        assert_eq!(core.contacts(mila)[0].display_name, "Bobby");
        assert_eq!(
            core.drain_events(),
            vec![
                CoreEvent::RosterChanged(alice),
                CoreEvent::RosterChanged(alice),
                CoreEvent::RosterChanged(mila),
            ]
        );

        let restored = MindChatCore::from_snapshot(core.snapshot());
        assert_eq!(restored.contacts(alice)[0].presence, ContactPresence::Away);
        assert_eq!(restored.contacts(mila)[0].jid, "bob@example.org");
    }

    #[test]
    fn roster_contact_rejects_unknown_accounts_and_invalid_jids() {
        let mut core = MindChatCore::default();
        assert_eq!(
            core.upsert_contact(99, "bob@example.org", "Bob", ContactPresence::Offline, None),
            Err(CoreError::UnknownAccount(99))
        );
        let account_id = account(&mut core);
        assert_eq!(
            core.upsert_contact(account_id, "not-a-jid", "Bob", ContactPresence::Offline, None),
            Err(CoreError::InvalidJid)
        );
    }

    #[test]
    fn server_roster_and_presence_events_preserve_subscription_state() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        core.drain_events();

        core.apply_transport_event(TransportEvent::RosterContactUpsert {
            account_id,
            jid: "bob@example.org".to_owned(),
            display_name: " Bob ".to_owned(),
            subscription: RosterSubscription::Mutual,
        })
        .expect("roster item");
        core.apply_transport_event(TransportEvent::ContactPresenceUpdated {
            account_id,
            jid: "bob@example.org".to_owned(),
            presence: ContactPresence::DoNotDisturb,
            status: Some(" Busy ".to_owned()),
        })
        .expect("presence item");
        core.apply_transport_event(TransportEvent::ContactPresenceUpdated {
            account_id,
            jid: "directed@example.org".to_owned(),
            presence: ContactPresence::Online,
            status: None,
        })
        .expect("directed presence is ignored");

        assert_eq!(
            core.contacts(account_id),
            vec![Contact {
                account_id,
                jid: "bob@example.org".to_owned(),
                display_name: "Bob".to_owned(),
                presence: ContactPresence::DoNotDisturb,
                status: Some("Busy".to_owned()),
                subscription: RosterSubscription::Mutual,
            }]
        );

        core.apply_transport_event(TransportEvent::RosterContactRemoved {
            account_id,
            jid: "bob@example.org".to_owned(),
        })
        .expect("roster removal");
        assert!(core.contacts(account_id).is_empty());
    }

    #[test]
    fn rejects_invalid_conversation_address_and_cross_conversation_reply() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        assert_eq!(
            core.open_conversation(account_id, ConversationKind::Direct, "not-a-jid", "Bob", 1),
            Err(CoreError::InvalidConversationAddress)
        );

        let first_conversation = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("first conversation");
        let second_conversation = core
            .open_conversation(account_id, ConversationKind::Direct, "mila@example.org", "Mila", 2)
            .expect("second conversation");
        let message_id = core
            .send_text(first_conversation, "alice@example.org", "hello", None, 3)
            .expect("message");

        assert_eq!(
            core.send_text(
                second_conversation,
                "alice@example.org",
                "wrong reply",
                Some(message_id),
                4
            ),
            Err(CoreError::InvalidReplyTarget)
        );
    }

    #[test]
    fn capability_gates_reactions() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let message_id = core
            .send_text(conversation_id, "alice@example.org", "hello", None, 2)
            .expect("message");
        core.set_capabilities(account_id, []).expect("account");

        assert_eq!(
            core.add_reaction(message_id, "alice@example.org", "👍"),
            Err(CoreError::CapabilityUnavailable(ProtocolCapability::MessageReactions))
        );
    }

    #[test]
    fn preserves_message_order_and_transitions_delivery() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let first =
            core.send_text(conversation_id, "alice@example.org", "hello", None, 2).expect("send");
        let second = core
            .send_text(conversation_id, "alice@example.org", "again", Some(first), 3)
            .expect("send");

        core.set_delivery_state(first, DeliveryState::Delivered).expect("state");
        let messages = core.messages(conversation_id);
        assert_eq!(messages.iter().map(|message| message.id).collect::<Vec<_>>(), [first, second]);
        assert_eq!(messages[0].delivery_state, DeliveryState::Delivered);
        assert_eq!(messages[1].in_reply_to, Some(first));
    }

    #[test]
    fn capability_gates_group_chats() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        core.set_capabilities(account_id, []).expect("account exists");

        assert_eq!(
            core.open_conversation(
                account_id,
                ConversationKind::MultiUserChat,
                "room@example.org",
                "Room",
                1
            ),
            Err(CoreError::CapabilityUnavailable(ProtocolCapability::MultiUserChat))
        );
    }

    #[test]
    fn incoming_messages_increment_and_read_clears_unread_count() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");

        core.receive_text(conversation_id, "bob@example.org", "ping", 2).expect("receive");
        assert_eq!(core.conversations(account_id)[0].unread_count, 1);

        core.mark_conversation_read(conversation_id).expect("read");
        assert_eq!(core.conversations(account_id)[0].unread_count, 0);
    }

    #[test]
    fn addressed_incoming_text_creates_the_direct_conversation_once() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        core.drain_events();

        let first = core
            .apply_transport_event(TransportEvent::IncomingText {
                account_id,
                kind: ConversationKind::Direct,
                address: "bob@example.org".to_owned(),
                sender: "bob@example.org".to_owned(),
                body: "first".to_owned(),
                received_at_epoch_ms: 10,
            })
            .expect("incoming text")
            .expect("message ID");
        let second = core
            .apply_transport_event(TransportEvent::IncomingText {
                account_id,
                kind: ConversationKind::Direct,
                address: "bob@example.org".to_owned(),
                sender: "bob@example.org".to_owned(),
                body: "second".to_owned(),
                received_at_epoch_ms: 20,
            })
            .expect("incoming text")
            .expect("message ID");

        let conversations = core.conversations(account_id);
        assert_eq!(conversations.len(), 1);
        assert_eq!(conversations[0].title, "bob");
        assert_eq!(conversations[0].unread_count, 2);
        assert_eq!(
            core.messages(conversations[0].id).iter().map(|message| message.id).collect::<Vec<_>>(),
            [first, second]
        );
    }

    #[test]
    fn snapshot_round_trip_keeps_stable_ids() {
        let mut original = MindChatCore::default();
        let account_id = account(&mut original);
        original
            .set_capabilities(account_id, [ProtocolCapability::MessageReactions])
            .expect("capabilities");
        let conversation_id = original
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let message_id = original
            .send_text(conversation_id, "alice@example.org", "hello", None, 2)
            .expect("message");
        original.add_reaction(message_id, "bob@example.org", "👍").expect("reaction");

        let restored = MindChatCore::from_snapshot(original.snapshot());
        assert_eq!(restored.accounts()[0].id, account_id);
        assert_eq!(restored.messages(conversation_id)[0].id, message_id);
        assert_eq!(restored.reactions(message_id)[0].emoji, "👍");
    }

    #[test]
    fn scoped_receipts_ignore_unknown_foreign_and_non_direct_messages() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let other_account = core
            .add_account(AccountSetup::new("mila@example.net", "example.net", "Mila"))
            .expect("second account");
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let message_id = core
            .send_text(conversation_id, "alice@example.org", "queued", None, 2)
            .expect("message");
        core.drain_events();

        for event in [
            TransportEvent::DeliveryUpdated {
                account_id,
                sender: "mallory@example.org".to_owned(),
                message_id,
                state: DeliveryState::Delivered,
            },
            TransportEvent::DeliveryUpdated {
                account_id: other_account,
                sender: "bob@example.org".to_owned(),
                message_id,
                state: DeliveryState::Delivered,
            },
            TransportEvent::DeliveryUpdated {
                account_id,
                sender: "bob@example.org".to_owned(),
                message_id: 999_999,
                state: DeliveryState::Delivered,
            },
            TransportEvent::DeliveryUpdated {
                account_id,
                sender: "bob@example.org".to_owned(),
                message_id,
                state: DeliveryState::Delivered,
            },
        ] {
            assert_eq!(core.apply_transport_event(event), Ok(None));
        }
        assert_eq!(core.messages(conversation_id)[0].delivery_state, DeliveryState::Delivered);

        let room = core
            .set_capabilities(account_id, [ProtocolCapability::MultiUserChat])
            .and_then(|()| {
                core.open_conversation(
                    account_id,
                    ConversationKind::MultiUserChat,
                    "room@example.org",
                    "Room",
                    3,
                )
            })
            .expect("groupchat");
        let group_message =
            core.send_text(room, "alice@example.org", "group", None, 4).expect("group message");
        assert_eq!(
            core.apply_transport_event(TransportEvent::DeliveryUpdated {
                account_id,
                sender: "room@example.org".to_owned(),
                message_id: group_message,
                state: DeliveryState::Delivered,
            }),
            Ok(None)
        );
        assert_eq!(core.messages(room)[0].delivery_state, DeliveryState::Pending);
    }

    #[test]
    fn transport_events_project_connection_incoming_messages_and_receipts() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let outgoing_id = core
            .send_text(conversation_id, "alice@example.org", "queued", None, 2)
            .expect("queued message");
        core.drain_events();

        let capabilities = BTreeSet::from([ProtocolCapability::Receipts]);
        assert_eq!(
            core.apply_transport_event(TransportEvent::Connected { account_id, capabilities }),
            Ok(None)
        );
        assert_eq!(core.accounts()[0].connection_state, ConnectionState::Online);
        assert_eq!(core.accounts()[0].capabilities, BTreeSet::from([ProtocolCapability::Receipts]));

        assert_eq!(
            core.apply_transport_event(TransportEvent::DeliveryUpdated {
                account_id,
                sender: "bob@example.org".to_owned(),
                message_id: outgoing_id,
                state: DeliveryState::Sent,
            }),
            Ok(None)
        );
        let incoming_id = core
            .apply_transport_event(TransportEvent::IncomingText {
                account_id,
                kind: ConversationKind::Direct,
                address: "bob@example.org".to_owned(),
                sender: "bob@example.org".to_owned(),
                body: "received".to_owned(),
                received_at_epoch_ms: 3,
            })
            .expect("incoming event")
            .expect("incoming message id");
        assert_eq!(core.messages(conversation_id)[1].id, incoming_id);
        assert_eq!(core.conversations(account_id)[0].unread_count, 1);

        assert_eq!(
            core.apply_transport_event(TransportEvent::Disconnected {
                account_id,
                recoverable: false,
                detail: Some("not authorized".to_owned()),
            }),
            Ok(None)
        );
        assert_eq!(core.accounts()[0].connection_state, ConnectionState::Failed);
        assert_eq!(core.accounts()[0].connection_error.as_deref(), Some("not authorized"));
    }

    #[test]
    fn queued_outgoing_messages_survive_snapshot_restore_and_are_account_scoped() {
        let mut original = MindChatCore::default();
        let alice = account(&mut original);
        let bob = original
            .add_account(AccountSetup::new("mila@example.net", "example.net", "Mila"))
            .expect("second account");
        let alice_conversation = original
            .open_conversation(alice, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("alice conversation");
        let bob_conversation = original
            .open_conversation(bob, ConversationKind::Direct, "nina@example.net", "Nina", 2)
            .expect("mila conversation");
        let first = original
            .send_text(alice_conversation, "alice@example.org", "first", None, 3)
            .expect("first message");
        let second = original
            .send_text(alice_conversation, "alice@example.org", "second", Some(first), 4)
            .expect("second message");
        original
            .send_text(bob_conversation, "mila@example.net", "other", None, 5)
            .expect("other account message");
        original.set_delivery_state(first, DeliveryState::Sent).expect("receipt");

        let restored = MindChatCore::from_snapshot(original.snapshot());
        let queued = restored.pending_outgoing_messages(alice).expect("alice outbox");
        assert_eq!(queued.len(), 1);
        assert_eq!(queued[0].message_id, second);
        assert_eq!(queued[0].recipient, "bob@example.org");
        assert_eq!(queued[0].in_reply_to, Some(first));
        assert_eq!(restored.pending_outgoing_messages(bob).expect("mila outbox").len(), 1);
        assert_eq!(restored.pending_outgoing_messages(99), Err(CoreError::UnknownAccount(99)));
    }

    #[test]
    fn extension_commands_are_permissioned_and_attributed_to_the_owning_account() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let denied_policy = ExtensionPolicy::new(
            ExtensionManifest::new(
                "org.mindchat.read-marker",
                "Read marker",
                "1.0.0",
                [ExtensionPermission::SendMessages],
            ),
            [],
        )
        .expect("manifest");

        assert_eq!(
            core.execute_extension_command(
                &denied_policy,
                CoreCommand::SendText {
                    conversation_id,
                    body: "blocked".to_owned(),
                    in_reply_to: None,
                },
                2,
            ),
            Err(ExtensionCommandError::PermissionDenied {
                extension_id: "org.mindchat.read-marker".to_owned(),
                permission: ExtensionPermission::SendMessages,
            })
        );
        assert!(core.messages(conversation_id).is_empty());

        let allowed_policy = ExtensionPolicy::new(
            ExtensionManifest::new(
                "org.mindchat.quick-replies",
                "Quick replies",
                "1.0.0",
                [ExtensionPermission::SendMessages],
            ),
            [ExtensionPermission::SendMessages],
        )
        .expect("manifest");
        let message_id = core
            .execute_extension_command(
                &allowed_policy,
                CoreCommand::SendText {
                    conversation_id,
                    body: "hello from an extension".to_owned(),
                    in_reply_to: None,
                },
                3,
            )
            .expect("command")
            .expect("message id");

        assert_eq!(core.messages(conversation_id)[0].id, message_id);
        assert_eq!(core.messages(conversation_id)[0].sender, "alice@example.org");
    }

    #[test]
    fn extension_commands_still_obey_server_capability_gates() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let message_id = core
            .send_text(conversation_id, "alice@example.org", "hello", None, 2)
            .expect("message");
        let policy = ExtensionPolicy::new(
            ExtensionManifest::new(
                "org.mindchat.reactor",
                "Reactor",
                "1.0.0",
                [ExtensionPermission::AddReactions],
            ),
            [ExtensionPermission::AddReactions],
        )
        .expect("manifest");

        assert_eq!(
            core.execute_extension_command(
                &policy,
                CoreCommand::AddReaction { message_id, emoji: "👍".to_owned() },
                3,
            ),
            Err(ExtensionCommandError::Core(CoreError::CapabilityUnavailable(
                ProtocolCapability::MessageReactions,
            )))
        );
    }

    #[test]
    fn coordinator_connects_flushes_retries_and_projects_transport_events() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let first = core
            .send_text(conversation_id, "alice@example.org", "first", None, 2)
            .expect("first queued message");
        let second = core
            .send_text(conversation_id, "alice@example.org", "second", Some(first), 3)
            .expect("second queued message");
        let mut coordinator = TransportCoordinator::new(core, FakeTransport::default());

        coordinator
            .connect(account_id, SecretString::new("not-persisted"))
            .expect("connect request");
        assert_eq!(coordinator.core().accounts()[0].connection_state, ConnectionState::Connecting);
        assert_eq!(coordinator.transport().connection_requests.len(), 1);
        assert_eq!(
            format!("{:?}", coordinator.transport().connection_requests[0].password),
            "SecretString([redacted])"
        );

        coordinator.transport_mut().events.push_back(TransportEvent::Connected {
            account_id,
            capabilities: BTreeSet::from([ProtocolCapability::Receipts]),
        });
        assert!(coordinator.poll_next_event().expect("connected event"));
        assert_eq!(coordinator.core().accounts()[0].connection_state, ConnectionState::Online);

        assert_eq!(coordinator.flush_outbox(account_id).expect("outbox flush"), 2);
        assert_eq!(
            coordinator
                .transport()
                .sent_messages
                .iter()
                .map(|message| message.message_id)
                .collect::<Vec<_>>(),
            [first, second]
        );
        assert!(
            coordinator
                .core()
                .pending_outgoing_messages(account_id)
                .expect("empty outbox")
                .is_empty()
        );

        let retry = coordinator
            .core_mut()
            .send_text(conversation_id, "alice@example.org", "retry", None, 4)
            .expect("retry message");
        coordinator.transport_mut().fail_next_send = true;
        assert!(matches!(
            coordinator.flush_outbox(account_id),
            Err(TransportCoordinatorError::Transport(TransportError::ConnectionFailed(_)))
        ));
        assert_eq!(
            coordinator.core().messages(conversation_id)[2].delivery_state,
            DeliveryState::Failed
        );
        assert_eq!(
            coordinator.core().pending_outgoing_messages(account_id).expect("retry outbox")[0]
                .message_id,
            retry
        );

        coordinator.disconnect(account_id).expect("disconnect");
        assert_eq!(coordinator.transport().disconnected_accounts, [account_id]);
        assert_eq!(coordinator.core().accounts()[0].connection_state, ConnectionState::Offline);
    }

    #[test]
    fn malformed_roster_event_is_skipped_without_creating_a_contact() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        assert_eq!(
            core.apply_transport_event(TransportEvent::RosterContactUpsert {
                account_id,
                jid: "not-a-jid".to_owned(),
                display_name: "Bob".to_owned(),
                subscription: RosterSubscription::Mutual,
            }),
            Ok(None)
        );
        assert!(core.contacts(account_id).is_empty());
    }

    #[test]
    fn invalid_presence_jid_event_is_skipped() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        assert_eq!(
            core.apply_transport_event(TransportEvent::ContactPresenceUpdated {
                account_id,
                jid: "bad jid".to_owned(),
                presence: ContactPresence::Online,
                status: None,
            }),
            Ok(None)
        );
        assert!(core.contacts(account_id).is_empty());
    }

    #[test]
    fn incoming_text_with_invalid_address_is_skipped() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        assert_eq!(
            core.apply_transport_event(TransportEvent::IncomingText {
                account_id,
                kind: ConversationKind::Direct,
                address: "not-a-jid".to_owned(),
                sender: "not-a-jid".to_owned(),
                body: "hello".to_owned(),
                received_at_epoch_ms: 1,
            }),
            Ok(None)
        );
        assert!(core.conversations(account_id).is_empty());
    }

    #[test]
    fn unknown_account_transport_events_are_skipped() {
        let mut core = MindChatCore::default();
        assert_eq!(
            core.apply_transport_event(TransportEvent::Connected {
                account_id: 999,
                capabilities: BTreeSet::from([ProtocolCapability::Receipts]),
            }),
            Ok(None)
        );
        assert_eq!(
            core.apply_transport_event(TransportEvent::Disconnected {
                account_id: 999,
                recoverable: true,
                detail: None,
            }),
            Ok(None)
        );
        assert_eq!(
            core.apply_transport_event(TransportEvent::CapabilitiesDiscovered {
                account_id: 999,
                capabilities: BTreeSet::from([ProtocolCapability::Receipts]),
            }),
            Ok(None)
        );
        assert_eq!(
            core.apply_transport_event(TransportEvent::RosterContactUpsert {
                account_id: 999,
                jid: "bob@example.org".to_owned(),
                display_name: "Bob".to_owned(),
                subscription: RosterSubscription::Mutual,
            }),
            Ok(None)
        );
        assert!(core.accounts().is_empty());
        assert!(core.contacts(999).is_empty());
    }

    #[test]
    fn poll_batch_continues_past_a_failing_event() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let mut coordinator = TransportCoordinator::new(core, FakeTransport::default());

        // A message with an empty body fails core validation (EmptyMessage);
        // the batch must skip it and still apply the Connected event behind it.
        coordinator.transport_mut().events.push_back(TransportEvent::IncomingText {
            account_id,
            kind: ConversationKind::Direct,
            address: "bob@example.org".to_owned(),
            sender: "bob@example.org".to_owned(),
            body: "   ".to_owned(),
            received_at_epoch_ms: 1,
        });
        coordinator.transport_mut().events.push_back(TransportEvent::Connected {
            account_id,
            capabilities: BTreeSet::from([ProtocolCapability::Receipts]),
        });

        assert_eq!(coordinator.poll_transport_events(4).expect("batch poll"), 2);
        assert_eq!(coordinator.core().accounts()[0].connection_state, ConnectionState::Online);
        assert_eq!(coordinator.core().conversations(account_id).len(), 0);
    }

    #[test]
    fn flush_outbox_is_bounded_to_a_batch_per_call() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let total = FLUSH_OUTBOX_MAX_BATCH + 10;
        for index in 0..total {
            core.send_text(
                conversation_id,
                "alice@example.org",
                format!("message {index}"),
                None,
                index as u64 + 2,
            )
            .expect("queued message");
        }

        let mut coordinator = TransportCoordinator::new(core, FakeTransport::default());
        assert_eq!(
            coordinator.flush_outbox(account_id).expect("first flush"),
            FLUSH_OUTBOX_MAX_BATCH
        );
        assert_eq!(coordinator.transport().sent_messages.len(), FLUSH_OUTBOX_MAX_BATCH);

        assert_eq!(coordinator.flush_outbox(account_id).expect("second flush"), 10);
        assert_eq!(coordinator.transport().sent_messages.len(), total);
        assert!(
            coordinator
                .core()
                .pending_outgoing_messages(account_id)
                .expect("empty outbox")
                .is_empty()
        );
    }

    #[test]
    fn delete_account_removes_all_account_owned_state() {
        let mut core = MindChatCore::default();
        let alice = account(&mut core);
        let mila = core
            .add_account(AccountSetup::new("mila@example.net", "example.net", "Mila"))
            .expect("second account");
        core.upsert_contact(alice, "bob@example.org", "Bob", ContactPresence::Online, None)
            .expect("contact");
        let alice_conversation = core
            .open_conversation(alice, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("alice conversation");
        let alice_message = core
            .send_text(alice_conversation, "alice@example.org", "hello", None, 2)
            .expect("alice message");
        core.set_capabilities(alice, [ProtocolCapability::MessageReactions])
            .expect("reactions capability");
        core.add_reaction(alice_message, "bob@example.org", "👍").expect("reaction");
        let mila_conversation = core
            .open_conversation(mila, ConversationKind::Direct, "nina@example.net", "Nina", 3)
            .expect("mila conversation");
        core.drain_events();

        core.delete_account(alice).expect("account deleted");
        assert!(core.accounts().iter().all(|item| item.id != alice));
        assert!(core.contacts(alice).is_empty());
        assert!(core.conversations(alice).is_empty());
        assert!(core.messages(alice_conversation).is_empty());
        assert!(core.reactions(alice_message).is_empty());
        assert_eq!(core.accounts()[0].id, mila);
        assert_eq!(core.conversations(mila), vec![core.conversations(mila)[0].clone()]);
        assert_eq!(core.conversations(mila)[0].id, mila_conversation);
        assert_eq!(core.drain_events(), vec![CoreEvent::AccountChanged(alice)]);
    }

    #[test]
    fn delete_account_and_conversation_reject_unknown_ids() {
        let mut core = MindChatCore::default();
        assert_eq!(core.delete_account(99), Err(CoreError::UnknownAccount(99)));
        assert_eq!(core.delete_conversation(99), Err(CoreError::UnknownConversation(99)));
    }

    #[test]
    fn delete_conversation_removes_messages_and_reactions() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        let conversation_id = core
            .open_conversation(account_id, ConversationKind::Direct, "bob@example.org", "Bob", 1)
            .expect("conversation");
        let message_id = core
            .send_text(conversation_id, "alice@example.org", "hello", None, 2)
            .expect("message");
        core.set_capabilities(account_id, [ProtocolCapability::MessageReactions])
            .expect("reactions capability");
        core.add_reaction(message_id, "bob@example.org", "👍").expect("reaction");
        core.drain_events();

        core.delete_conversation(conversation_id).expect("conversation deleted");
        assert!(core.conversations(account_id).is_empty());
        assert!(core.messages(conversation_id).is_empty());
        assert!(core.reactions(message_id).is_empty());
        assert_eq!(core.drain_events(), vec![CoreEvent::ConversationChanged(conversation_id)]);
    }

    #[test]
    fn update_account_display_name_propagates_and_validates() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        core.drain_events();

        assert_eq!(
            core.update_account_display_name(account_id, "   "),
            Err(CoreError::EmptyDisplayName)
        );
        assert_eq!(
            core.update_account_display_name(99, "Alicia"),
            Err(CoreError::UnknownAccount(99))
        );

        core.update_account_display_name(account_id, "Alicia").expect("display name updated");
        assert_eq!(core.accounts()[0].display_name, "Alicia");
        assert_eq!(core.drain_events(), vec![CoreEvent::AccountChanged(account_id)]);
    }
}
