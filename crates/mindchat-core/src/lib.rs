//! MindChat's platform-neutral domain core.
//!
//! The crate deliberately models client behavior without exposing protocol
//! parser, database, cryptographic, or Android types. It is therefore the
//! stable boundary consumed by future UniFFI bindings.

#![allow(missing_docs)]
#![allow(clippy::doc_markdown, clippy::missing_errors_doc, clippy::needless_pass_by_value)]

use std::collections::{BTreeSet, HashMap};
use std::fmt;

pub mod extension;
pub mod transport;

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

/// Stable identifier for an XMPP account configured in the client.
pub type AccountId = u64;
/// Stable identifier for a direct or group conversation.
pub type ConversationId = u64;
/// Stable identifier for a message.
pub type MessageId = u64;
/// Stable identifier for a message reaction.
pub type ReactionId = u64;

const MAX_MESSAGE_CHARS: usize = 16_384;

/// Connection status projected to the UI.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum ConnectionState {
    #[default]
    Offline,
    Connecting,
    Online,
    Failed,
}

/// Conversation transport topology.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConversationKind {
    Direct,
    MultiUserChat,
}

/// Direction of a message relative to the local account.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MessageDirection {
    Incoming,
    Outgoing,
}

/// Delivery state shown for an outgoing message.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DeliveryState {
    Pending,
    Sent,
    Delivered,
    Read,
    Failed,
}

/// High-level payload category. Binary data is kept outside this core model.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum MessageKind {
    Text,
    Attachment,
    Voice,
}

/// XMPP capability that must be discovered before its UI action is available.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
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
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Account {
    pub id: AccountId,
    pub jid: String,
    pub server: String,
    pub display_name: String,
    pub connection_state: ConnectionState,
    pub capabilities: BTreeSet<ProtocolCapability>,
}

/// A local conversation projection.
#[derive(Clone, Debug, Eq, PartialEq)]
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
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Attachment {
    pub id: String,
    pub filename: String,
    pub mime_type: String,
    pub byte_count: u64,
    pub remote_url: Option<String>,
}

/// An immutable message projection.
#[derive(Clone, Debug, Eq, PartialEq)]
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
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Reaction {
    pub id: ReactionId,
    pub message_id: MessageId,
    pub emoji: String,
    pub actor: String,
}

/// Read-only state suitable for a persistence adapter or test fixture.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CoreSnapshot {
    pub accounts: Vec<Account>,
    pub conversations: Vec<Conversation>,
    pub messages: Vec<Message>,
    pub reactions: Vec<Reaction>,
}

/// Events emitted after state changes. Native UI layers subscribe and render
/// snapshots rather than reaching into the core's internal maps.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum CoreEvent {
    AccountChanged(AccountId),
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
        let mut conversations = self.conversations.values().cloned().collect::<Vec<_>>();
        let mut messages = self.messages.values().cloned().collect::<Vec<_>>();
        let mut reactions = self.reactions.values().cloned().collect::<Vec<_>>();
        accounts.sort_by_key(|item| item.id);
        conversations.sort_by_key(|item| item.id);
        messages.sort_by_key(|item| item.id);
        reactions.sort_by_key(|item| item.id);
        CoreSnapshot { accounts, conversations, messages, reactions }
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

    /// Applies one normalized event emitted by an XMPP transport adapter.
    ///
    /// The adapter owns parsing and network recovery. This state machine owns
    /// the durable UI projection, so no transport-specific type needs to
    /// reach storage or a generated binding.
    pub fn apply_transport_event(
        &mut self,
        event: TransportEvent,
    ) -> Result<Option<MessageId>, CoreError> {
        match event {
            TransportEvent::Connected { account_id, capabilities } => {
                let account = self
                    .accounts
                    .get_mut(&account_id)
                    .ok_or(CoreError::UnknownAccount(account_id))?;
                account.connection_state = ConnectionState::Online;
                account.capabilities = capabilities;
                self.events.push(CoreEvent::AccountChanged(account_id));
                Ok(None)
            }
            TransportEvent::Disconnected { account_id, recoverable } => {
                self.set_connection_state(
                    account_id,
                    if recoverable { ConnectionState::Offline } else { ConnectionState::Failed },
                )?;
                Ok(None)
            }
            TransportEvent::IncomingText {
                conversation_id,
                sender,
                body,
                received_at_epoch_ms,
            } => self.receive_text(conversation_id, sender, body, received_at_epoch_ms).map(Some),
            TransportEvent::DeliveryUpdated { message_id, state } => {
                self.set_delivery_state(message_id, state)?;
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
        })?;
        Ok(())
    }

    /// Sends all pending or retryable text for an account in stable message-id order.
    pub fn flush_outbox(
        &mut self,
        account_id: AccountId,
    ) -> Result<usize, TransportCoordinatorError> {
        let pending = self.core.pending_outgoing_messages(account_id)?;
        let mut sent_count = 0;
        for message in pending {
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
    pub fn poll_next_event(&mut self) -> Result<bool, TransportCoordinatorError> {
        let Some(event) = self.transport.next_event()? else {
            return Ok(false);
        };
        self.core.apply_transport_event(event)?;
        Ok(true)
    }
}

fn validate_jid(jid: &str) -> Result<(), CoreError> {
    let mut split = jid.split('@');
    let local = split.next().unwrap_or_default();
    let domain = split.next().unwrap_or_default();
    if local.trim().is_empty() || domain.trim().is_empty() || split.next().is_some() {
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
    }

    #[test]
    fn accounts_start_without_undiscovered_optional_capabilities() {
        let mut core = MindChatCore::default();
        let account_id = account(&mut core);
        assert_eq!(core.accounts()[0].id, account_id);
        assert!(core.accounts()[0].capabilities.is_empty());
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
                message_id: outgoing_id,
                state: DeliveryState::Sent,
            }),
            Ok(None)
        );
        let incoming_id = core
            .apply_transport_event(TransportEvent::IncomingText {
                conversation_id,
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
            }),
            Ok(None)
        );
        assert_eq!(core.accounts()[0].connection_state, ConnectionState::Failed);
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
}
