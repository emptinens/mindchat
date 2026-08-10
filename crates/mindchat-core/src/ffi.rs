//! UniFFI-facing DTOs and commands.
//!
//! This module deliberately translates the domain model instead of exporting it
//! directly. The domain remains free to evolve its storage and transport
//! details, while Kotlin receives only immutable, display-safe records and
//! commands.

use crate::persistence::{PersistenceError, load_state, save_state};
use crate::{
    AccountSetup, ConnectionState, ContactPresence, ConversationKind, CoreError, CoreEvent,
    DeliveryState, MessageDirection, MessageKind, MindChatCore, ProtocolCapability,
    RosterSubscription, SecretString, TokioXmppTransport, TransportCoordinator,
    TransportCoordinatorError, TransportError,
};
use std::fmt;
use std::path::Path;
use std::sync::{Arc, Mutex, MutexGuard};

const MAX_TRANSPORT_EVENTS_PER_POLL: u32 = 128;

/// State of an XMPP account as rendered by the platform UI.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiConnectionState {
    Offline,
    Connecting,
    Online,
    Failed,
}

impl From<ConnectionState> for FfiConnectionState {
    fn from(value: ConnectionState) -> Self {
        match value {
            ConnectionState::Offline => Self::Offline,
            ConnectionState::Connecting => Self::Connecting,
            ConnectionState::Online => Self::Online,
            ConnectionState::Failed => Self::Failed,
        }
    }
}

impl From<FfiConnectionState> for ConnectionState {
    fn from(value: FfiConnectionState) -> Self {
        match value {
            FfiConnectionState::Offline => Self::Offline,
            FfiConnectionState::Connecting => Self::Connecting,
            FfiConnectionState::Online => Self::Online,
            FfiConnectionState::Failed => Self::Failed,
        }
    }
}

/// Presence state displayed for one roster contact.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiContactPresence {
    Online,
    Away,
    DoNotDisturb,
    Offline,
}

impl From<ContactPresence> for FfiContactPresence {
    fn from(value: ContactPresence) -> Self {
        match value {
            ContactPresence::Online => Self::Online,
            ContactPresence::Away => Self::Away,
            ContactPresence::DoNotDisturb => Self::DoNotDisturb,
            ContactPresence::Offline => Self::Offline,
        }
    }
}

impl From<FfiContactPresence> for ContactPresence {
    fn from(value: FfiContactPresence) -> Self {
        match value {
            FfiContactPresence::Online => Self::Online,
            FfiContactPresence::Away => Self::Away,
            FfiContactPresence::DoNotDisturb => Self::DoNotDisturb,
            FfiContactPresence::Offline => Self::Offline,
        }
    }
}

/// Server-confirmed roster subscription state for a contact.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiRosterSubscription {
    None,
    Inbound,
    Outbound,
    Mutual,
    PendingOutbound,
}

impl From<RosterSubscription> for FfiRosterSubscription {
    fn from(value: RosterSubscription) -> Self {
        match value {
            RosterSubscription::None => Self::None,
            RosterSubscription::Inbound => Self::Inbound,
            RosterSubscription::Outbound => Self::Outbound,
            RosterSubscription::Mutual => Self::Mutual,
            RosterSubscription::PendingOutbound => Self::PendingOutbound,
        }
    }
}

impl From<FfiRosterSubscription> for RosterSubscription {
    fn from(value: FfiRosterSubscription) -> Self {
        match value {
            FfiRosterSubscription::None => Self::None,
            FfiRosterSubscription::Inbound => Self::Inbound,
            FfiRosterSubscription::Outbound => Self::Outbound,
            FfiRosterSubscription::Mutual => Self::Mutual,
            FfiRosterSubscription::PendingOutbound => Self::PendingOutbound,
        }
    }
}

/// Conversation topology exposed to Kotlin.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiConversationKind {
    Direct,
    MultiUserChat,
}

impl From<FfiConversationKind> for ConversationKind {
    fn from(value: FfiConversationKind) -> Self {
        match value {
            FfiConversationKind::Direct => Self::Direct,
            FfiConversationKind::MultiUserChat => Self::MultiUserChat,
        }
    }
}

impl From<ConversationKind> for FfiConversationKind {
    fn from(value: ConversationKind) -> Self {
        match value {
            ConversationKind::Direct => Self::Direct,
            ConversationKind::MultiUserChat => Self::MultiUserChat,
        }
    }
}

/// Direction of a message relative to the local account.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiMessageDirection {
    Incoming,
    Outgoing,
}

impl From<MessageDirection> for FfiMessageDirection {
    fn from(value: MessageDirection) -> Self {
        match value {
            MessageDirection::Incoming => Self::Incoming,
            MessageDirection::Outgoing => Self::Outgoing,
        }
    }
}

/// Delivery state projected from Stream Management and receipts.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiDeliveryState {
    Pending,
    Sent,
    Delivered,
    Read,
    Failed,
}

impl From<DeliveryState> for FfiDeliveryState {
    fn from(value: DeliveryState) -> Self {
        match value {
            DeliveryState::Pending => Self::Pending,
            DeliveryState::Sent => Self::Sent,
            DeliveryState::Delivered => Self::Delivered,
            DeliveryState::Read => Self::Read,
            DeliveryState::Failed => Self::Failed,
        }
    }
}

/// High-level message payload category.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiMessageKind {
    Text,
    Attachment,
    Voice,
}

impl From<MessageKind> for FfiMessageKind {
    fn from(value: MessageKind) -> Self {
        match value {
            MessageKind::Text => Self::Text,
            MessageKind::Attachment => Self::Attachment,
            MessageKind::Voice => Self::Voice,
        }
    }
}

/// XMPP feature discovered for an account.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiProtocolCapability {
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

impl From<ProtocolCapability> for FfiProtocolCapability {
    fn from(value: ProtocolCapability) -> Self {
        match value {
            ProtocolCapability::MultiUserChat => Self::MultiUserChat,
            ProtocolCapability::MessageArchiveManagement => Self::MessageArchiveManagement,
            ProtocolCapability::StreamManagement => Self::StreamManagement,
            ProtocolCapability::HttpFileUpload => Self::HttpFileUpload,
            ProtocolCapability::Omemo => Self::Omemo,
            ProtocolCapability::PushNotifications => Self::PushNotifications,
            ProtocolCapability::Receipts => Self::Receipts,
            ProtocolCapability::ChatMarkers => Self::ChatMarkers,
            ProtocolCapability::ChatStates => Self::ChatStates,
            ProtocolCapability::MessageReactions => Self::MessageReactions,
            ProtocolCapability::MessageCorrections => Self::MessageCorrections,
            ProtocolCapability::MessageReplies => Self::MessageReplies,
            ProtocolCapability::SharedPins => Self::SharedPins,
        }
    }
}

impl From<FfiProtocolCapability> for ProtocolCapability {
    fn from(value: FfiProtocolCapability) -> Self {
        match value {
            FfiProtocolCapability::MultiUserChat => Self::MultiUserChat,
            FfiProtocolCapability::MessageArchiveManagement => Self::MessageArchiveManagement,
            FfiProtocolCapability::StreamManagement => Self::StreamManagement,
            FfiProtocolCapability::HttpFileUpload => Self::HttpFileUpload,
            FfiProtocolCapability::Omemo => Self::Omemo,
            FfiProtocolCapability::PushNotifications => Self::PushNotifications,
            FfiProtocolCapability::Receipts => Self::Receipts,
            FfiProtocolCapability::ChatMarkers => Self::ChatMarkers,
            FfiProtocolCapability::ChatStates => Self::ChatStates,
            FfiProtocolCapability::MessageReactions => Self::MessageReactions,
            FfiProtocolCapability::MessageCorrections => Self::MessageCorrections,
            FfiProtocolCapability::MessageReplies => Self::MessageReplies,
            FfiProtocolCapability::SharedPins => Self::SharedPins,
        }
    }
}

/// An account record safe for display in the Android UI.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiAccount {
    pub id: u64,
    pub jid: String,
    pub server: String,
    pub display_name: String,
    pub connection_state: FfiConnectionState,
    pub capabilities: Vec<FfiProtocolCapability>,
    /// Last connection failure reason, safe to show in the UI.
    pub connection_error: Option<String>,
}

/// A roster contact record safe for display in the Android UI.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiContact {
    pub account_id: u64,
    pub jid: String,
    pub display_name: String,
    pub presence: FfiContactPresence,
    pub status: Option<String>,
    pub subscription: FfiRosterSubscription,
}

/// A direct chat or MUC projection safe for display in the Android UI.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiConversation {
    pub id: u64,
    pub account_id: u64,
    pub kind: FfiConversationKind,
    pub address: String,
    pub title: String,
    pub unread_count: u32,
    pub last_activity_epoch_ms: u64,
}

/// File metadata displayed by a future attachment renderer.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiAttachment {
    pub id: String,
    pub filename: String,
    pub mime_type: String,
    pub byte_count: u64,
    pub remote_url: Option<String>,
}

/// Immutable message projection safe for platform rendering.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiMessage {
    pub id: u64,
    pub conversation_id: u64,
    pub sender: String,
    pub body: String,
    pub direction: FfiMessageDirection,
    pub kind: FfiMessageKind,
    pub sent_at_epoch_ms: u64,
    pub delivery_state: FfiDeliveryState,
    pub in_reply_to: Option<u64>,
    pub attachment: Option<FfiAttachment>,
}

/// A reaction attached to a message.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiReaction {
    pub id: u64,
    pub message_id: u64,
    pub emoji: String,
    pub actor: String,
}

/// Complete immutable UI snapshot. No XML, database handles, passwords, or
/// cryptographic key material cross this boundary.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiCoreSnapshot {
    pub accounts: Vec<FfiAccount>,
    pub contacts: Vec<FfiContact>,
    pub conversations: Vec<FfiConversation>,
    pub messages: Vec<FfiMessage>,
    pub reactions: Vec<FfiReaction>,
}

/// Notification emitted after a state change. The Kotlin layer refetches a
/// snapshot to render the resulting state.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiCoreEvent {
    AccountChanged { account_id: u64 },
    RosterChanged { account_id: u64 },
    ConversationChanged { conversation_id: u64 },
    MessageAdded { message_id: u64 },
    MessageChanged { message_id: u64 },
}

/// Errors that can be handled by the Android presentation layer.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Error)]
pub enum MindChatBindingError {
    InvalidInput { detail: String },
    NotFound { detail: String },
    CapabilityUnavailable { capability: FfiProtocolCapability },
    AuthenticationFailed,
    ConnectionFailed { detail: String },
    Internal { detail: String },
}

impl fmt::Display for MindChatBindingError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidInput { detail }
            | Self::NotFound { detail }
            | Self::ConnectionFailed { detail }
            | Self::Internal { detail } => formatter.write_str(detail),
            Self::CapabilityUnavailable { capability } => {
                write!(formatter, "capability unavailable: {capability:?}")
            }
            Self::AuthenticationFailed => formatter.write_str("authentication failed"),
        }
    }
}

impl std::error::Error for MindChatBindingError {}

impl From<CoreError> for MindChatBindingError {
    fn from(value: CoreError) -> Self {
        match value {
            CoreError::InvalidJid
            | CoreError::InvalidServer
            | CoreError::InvalidConversationAddress
            | CoreError::EmptyMessage
            | CoreError::MessageTooLong
            | CoreError::EmptyReaction
            | CoreError::InvalidReplyTarget => Self::InvalidInput { detail: value.to_string() },
            CoreError::UnknownAccount(_)
            | CoreError::UnknownConversation(_)
            | CoreError::UnknownMessage(_) => Self::NotFound { detail: value.to_string() },
            CoreError::CapabilityUnavailable(capability) => {
                Self::CapabilityUnavailable { capability: capability.into() }
            }
        }
    }
}

impl From<TransportError> for MindChatBindingError {
    fn from(value: TransportError) -> Self {
        match value {
            TransportError::AuthenticationFailed => Self::AuthenticationFailed,
            TransportError::ConnectionFailed(detail) => Self::ConnectionFailed { detail },
            TransportError::ProtocolViolation(detail) => Self::InvalidInput { detail },
            TransportError::Unsupported(detail) => Self::Internal { detail },
        }
    }
}

impl From<TransportCoordinatorError> for MindChatBindingError {
    fn from(value: TransportCoordinatorError) -> Self {
        match value {
            TransportCoordinatorError::Core(error) => error.into(),
            TransportCoordinatorError::Transport(error) => error.into(),
        }
    }
}

impl From<PersistenceError> for MindChatBindingError {
    fn from(value: PersistenceError) -> Self {
        match value {
            PersistenceError::Io(error) => {
                Self::Internal { detail: format!("state persistence I/O error: {error}") }
            }
            PersistenceError::Corrupt(detail) => {
                Self::Internal { detail: format!("corrupt state file: {detail}") }
            }
            PersistenceError::UnsupportedVersion(version) => {
                Self::Internal { detail: format!("unsupported state schema version {version}") }
            }
            PersistenceError::TooLarge(bytes) => {
                Self::Internal { detail: format!("state file too large: {bytes} bytes") }
            }
        }
    }
}

/// Thread-safe owner for a Rust core instance.
///
/// Kotlin never receives the inner state machine. Every mutation is validated
/// in Rust and the result is rendered from immutable snapshots.
#[derive(uniffi::Object)]
pub struct MindChatCoreHandle {
    session: Mutex<TransportCoordinator<TokioXmppTransport>>,
}

impl MindChatCoreHandle {
    fn lock(
        &self,
    ) -> Result<MutexGuard<'_, TransportCoordinator<TokioXmppTransport>>, MindChatBindingError>
    {
        self.session.lock().map_err(|_| MindChatBindingError::Internal {
            detail: "MindChat core lock was poisoned".to_owned(),
        })
    }
}

#[uniffi::export]
impl MindChatCoreHandle {
    /// Creates an empty local core and a concrete internal XMPP session.
    ///
    /// Account credentials are accepted only by [`Self::connect_account`],
    /// never stored in the core snapshot, and never returned through this API.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            session: Mutex::new(TransportCoordinator::new(
                MindChatCore::default(),
                TokioXmppTransport::new(),
            )),
        })
    }

    /// Adds an account configuration after basic JID and server validation.
    pub fn add_account(
        &self,
        jid: String,
        server: String,
        display_name: String,
    ) -> Result<u64, MindChatBindingError> {
        self.lock()?
            .core_mut()
            .add_account(AccountSetup::new(jid, server, display_name))
            .map_err(Into::into)
    }

    /// Updates the visible connection state for an account.
    pub fn set_connection_state(
        &self,
        account_id: u64,
        state: FfiConnectionState,
    ) -> Result<(), MindChatBindingError> {
        self.lock()?.core_mut().set_connection_state(account_id, state.into()).map_err(Into::into)
    }

    /// Replaces the server features discovered for an account.
    pub fn set_capabilities(
        &self,
        account_id: u64,
        capabilities: Vec<FfiProtocolCapability>,
    ) -> Result<(), MindChatBindingError> {
        self.lock()?
            .core_mut()
            .set_capabilities(account_id, capabilities.into_iter().map(Into::into))
            .map_err(Into::into)
    }

    /// Creates or updates a local roster contact projection.
    pub fn upsert_contact(
        &self,
        account_id: u64,
        jid: String,
        display_name: String,
        presence: FfiContactPresence,
        status: Option<String>,
    ) -> Result<(), MindChatBindingError> {
        self.lock()?
            .core_mut()
            .upsert_contact(account_id, jid, display_name, presence.into(), status)
            .map_err(Into::into)
    }

    /// Opens a direct chat or MUC projection.
    pub fn open_conversation(
        &self,
        account_id: u64,
        kind: FfiConversationKind,
        address: String,
        title: String,
        now_epoch_ms: u64,
    ) -> Result<u64, MindChatBindingError> {
        self.lock()?
            .core_mut()
            .open_conversation(account_id, kind.into(), address, title, now_epoch_ms)
            .map_err(Into::into)
    }

    /// Queues a text message in the domain core.
    pub fn send_text(
        &self,
        conversation_id: u64,
        sender: String,
        body: String,
        in_reply_to: Option<u64>,
        now_epoch_ms: u64,
    ) -> Result<u64, MindChatBindingError> {
        self.lock()?
            .core_mut()
            .send_text(conversation_id, sender, body, in_reply_to, now_epoch_ms)
            .map_err(Into::into)
    }

    /// Inserts a text message received from the protocol adapter.
    pub fn receive_text(
        &self,
        conversation_id: u64,
        sender: String,
        body: String,
        now_epoch_ms: u64,
    ) -> Result<u64, MindChatBindingError> {
        self.lock()?
            .core_mut()
            .receive_text(conversation_id, sender, body, now_epoch_ms)
            .map_err(Into::into)
    }

    /// Adds a reaction to a message.
    pub fn add_reaction(
        &self,
        message_id: u64,
        actor: String,
        emoji: String,
    ) -> Result<u64, MindChatBindingError> {
        self.lock()?.core_mut().add_reaction(message_id, actor, emoji).map_err(Into::into)
    }

    /// Clears a conversation's unread count.
    pub fn mark_conversation_read(&self, conversation_id: u64) -> Result<(), MindChatBindingError> {
        self.lock()?.core_mut().mark_conversation_read(conversation_id).map_err(Into::into)
    }

    /// Starts a concrete XMPP session for an existing account.
    ///
    /// The password is handed directly to the active Rust worker and is not
    /// retained by the domain core, snapshots, or event stream.
    pub fn connect_account(
        &self,
        account_id: u64,
        password: String,
    ) -> Result<(), MindChatBindingError> {
        if password.is_empty() {
            return Err(MindChatBindingError::InvalidInput {
                detail: "a password is required".to_owned(),
            });
        }
        self.lock()?.connect(account_id, SecretString::new(password)).map_err(Into::into)
    }

    /// Stops an active XMPP session and projects the account as offline.
    pub fn disconnect_account(&self, account_id: u64) -> Result<(), MindChatBindingError> {
        self.lock()?.disconnect(account_id).map_err(Into::into)
    }

    /// Applies up to `max_events` normalized transport events to the core.
    ///
    /// The bound prevents a busy server from monopolizing the Kotlin caller;
    /// pass zero to perform no work, and values above 128 are clamped.
    pub fn poll_transport_events(&self, max_events: u32) -> Result<u32, MindChatBindingError> {
        let mut session = self.lock()?;
        let limit = max_events.min(MAX_TRANSPORT_EVENTS_PER_POLL);
        let mut applied = 0;
        while applied < limit {
            if !session.poll_next_event().map_err(MindChatBindingError::from)? {
                break;
            }
            applied += 1;
        }
        Ok(applied)
    }

    /// Sends queued or retryable text for an online account in stable message order.
    ///
    /// Offline and connecting accounts keep their queue untouched and return
    /// zero, so Android can safely invoke this after each polling pass.
    pub fn flush_outbox(&self, account_id: u64) -> Result<u32, MindChatBindingError> {
        let mut session = self.lock()?;
        let connection_state = session
            .core()
            .accounts()
            .into_iter()
            .find(|account| account.id == account_id)
            .map(|account| account.connection_state)
            .ok_or(CoreError::UnknownAccount(account_id))?;
        if connection_state != ConnectionState::Online {
            return Ok(0);
        }
        session
            .flush_outbox(account_id)
            .map(|count| u32::try_from(count).unwrap_or(u32::MAX))
            .map_err(Into::into)
    }

    /// Returns all UI-safe state in stable identifier order.
    pub fn snapshot(&self) -> Result<FfiCoreSnapshot, MindChatBindingError> {
        Ok(self.lock()?.core().snapshot().into())
    }

    /// Drains state-change notifications in mutation order.
    pub fn drain_events(&self) -> Result<Vec<FfiCoreEvent>, MindChatBindingError> {
        Ok(self.lock()?.core_mut().drain_events().into_iter().map(Into::into).collect())
    }

    /// Writes the current snapshot to `path` as versioned JSON.
    ///
    /// The snapshot is captured under the session lock and written outside it,
    /// so file I/O never blocks other core mutations. Concurrent saves are
    /// safe: each write stages a unique temporary file and renames it over
    /// `path` atomically, so the last completed save wins with a complete
    /// snapshot. No secrets are written: account passwords never enter the
    /// core or the snapshot.
    pub fn save_state(&self, path: String) -> Result<(), MindChatBindingError> {
        let snapshot = self.lock()?.core().snapshot();
        save_state(&snapshot, Path::new(&path)).map_err(Into::into)
    }

    /// Restores a saved snapshot from `path`, returning whether one was loaded.
    ///
    /// `Ok(false)` means no state file exists and the handle stays empty.
    /// `Ok(true)` replaces the core with the sanitized snapshot; restored
    /// accounts are always `Offline` with cleared errors and capabilities
    /// until a real connect happens. The load is refused when the core
    /// already contains accounts or the transport owns connections, because
    /// restore must run exactly once at startup on an empty handle rather
    /// than merge or clobber live state. The file contains no secrets.
    pub fn load_state(&self, path: String) -> Result<bool, MindChatBindingError> {
        let mut session = self.lock()?;
        if !session.core().accounts().is_empty() {
            return Err(MindChatBindingError::Internal {
                detail: "core already contains accounts; load_state must run before any mutation"
                    .to_owned(),
            });
        }
        if !session.transport().connected_accounts().is_empty() {
            return Err(MindChatBindingError::Internal {
                detail: "transport already has connected accounts; load_state must run before any connection"
                    .to_owned(),
            });
        }
        let Some(snapshot) = load_state(Path::new(&path)).map_err(MindChatBindingError::from)?
        else {
            return Ok(false);
        };
        *session.core_mut() = MindChatCore::from_snapshot(snapshot);
        Ok(true)
    }
}

/// Identifies the binding contract that the packaged native library exports.
#[uniffi::export]
#[must_use]
pub fn mindchat_binding_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

impl From<crate::CoreSnapshot> for FfiCoreSnapshot {
    fn from(value: crate::CoreSnapshot) -> Self {
        Self {
            accounts: value.accounts.into_iter().map(Into::into).collect(),
            contacts: value.contacts.into_iter().map(Into::into).collect(),
            conversations: value.conversations.into_iter().map(Into::into).collect(),
            messages: value.messages.into_iter().map(Into::into).collect(),
            reactions: value.reactions.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<crate::Account> for FfiAccount {
    fn from(value: crate::Account) -> Self {
        Self {
            id: value.id,
            jid: value.jid,
            server: value.server,
            display_name: value.display_name,
            connection_state: value.connection_state.into(),
            capabilities: value.capabilities.into_iter().map(Into::into).collect(),
            connection_error: value.connection_error,
        }
    }
}

impl From<crate::Contact> for FfiContact {
    fn from(value: crate::Contact) -> Self {
        Self {
            account_id: value.account_id,
            jid: value.jid,
            display_name: value.display_name,
            presence: value.presence.into(),
            status: value.status,
            subscription: value.subscription.into(),
        }
    }
}

impl From<crate::Conversation> for FfiConversation {
    fn from(value: crate::Conversation) -> Self {
        Self {
            id: value.id,
            account_id: value.account_id,
            kind: value.kind.into(),
            address: value.address,
            title: value.title,
            unread_count: value.unread_count,
            last_activity_epoch_ms: value.last_activity_epoch_ms,
        }
    }
}

impl From<crate::Attachment> for FfiAttachment {
    fn from(value: crate::Attachment) -> Self {
        Self {
            id: value.id,
            filename: value.filename,
            mime_type: value.mime_type,
            byte_count: value.byte_count,
            remote_url: value.remote_url,
        }
    }
}

impl From<crate::Message> for FfiMessage {
    fn from(value: crate::Message) -> Self {
        Self {
            id: value.id,
            conversation_id: value.conversation_id,
            sender: value.sender,
            body: value.body,
            direction: value.direction.into(),
            kind: value.kind.into(),
            sent_at_epoch_ms: value.sent_at_epoch_ms,
            delivery_state: value.delivery_state.into(),
            in_reply_to: value.in_reply_to,
            attachment: value.attachment.map(Into::into),
        }
    }
}

impl From<crate::Reaction> for FfiReaction {
    fn from(value: crate::Reaction) -> Self {
        Self { id: value.id, message_id: value.message_id, emoji: value.emoji, actor: value.actor }
    }
}

impl From<CoreEvent> for FfiCoreEvent {
    fn from(value: CoreEvent) -> Self {
        match value {
            CoreEvent::AccountChanged(account_id) => Self::AccountChanged { account_id },
            CoreEvent::RosterChanged(account_id) => Self::RosterChanged { account_id },
            CoreEvent::ConversationChanged(conversation_id) => {
                Self::ConversationChanged { conversation_id }
            }
            CoreEvent::MessageAdded(message_id) => Self::MessageAdded { message_id },
            CoreEvent::MessageChanged(message_id) => Self::MessageChanged { message_id },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bridge_exposes_safe_snapshot_and_events() {
        let core = MindChatCoreHandle::new();
        let account_id = core
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        let conversation_id = core
            .open_conversation(
                account_id,
                FfiConversationKind::Direct,
                "bob@example.org".to_owned(),
                "Bob".to_owned(),
                100,
            )
            .expect("conversation");
        let message_id = core
            .send_text(
                conversation_id,
                "alice@example.org".to_owned(),
                "hello".to_owned(),
                None,
                200,
            )
            .expect("message");

        let snapshot = core.snapshot().expect("snapshot");
        assert_eq!(snapshot.accounts[0].id, account_id);
        assert_eq!(snapshot.messages[0].id, message_id);
        assert_eq!(snapshot.messages[0].body, "hello");
        assert_eq!(
            core.drain_events().expect("events"),
            vec![
                FfiCoreEvent::AccountChanged { account_id },
                FfiCoreEvent::ConversationChanged { conversation_id },
                FfiCoreEvent::ConversationChanged { conversation_id },
                FfiCoreEvent::MessageAdded { message_id },
            ]
        );
    }

    #[test]
    fn bridge_exposes_roster_contacts_without_transport_or_secret_types() {
        let core = MindChatCoreHandle::new();
        let account_id = core
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        core.drain_events().expect("initial events");

        core.upsert_contact(
            account_id,
            "bob@example.org".to_owned(),
            "Bob".to_owned(),
            FfiContactPresence::Online,
            Some("Available".to_owned()),
        )
        .expect("contact");

        let snapshot = core.snapshot().expect("snapshot");
        assert_eq!(snapshot.contacts.len(), 1);
        assert_eq!(snapshot.contacts[0].jid, "bob@example.org");
        assert_eq!(snapshot.contacts[0].presence, FfiContactPresence::Online);
        assert_eq!(snapshot.contacts[0].status.as_deref(), Some("Available"));
        assert_eq!(snapshot.contacts[0].subscription, FfiRosterSubscription::None);
        assert_eq!(
            core.drain_events().expect("events"),
            vec![FfiCoreEvent::RosterChanged { account_id }]
        );
    }

    #[test]
    fn bridge_maps_domain_errors_without_exposing_internal_types() {
        let core = MindChatCoreHandle::new();
        assert_eq!(
            core.add_account("invalid".to_owned(), "example.org".to_owned(), "Alice".to_owned()),
            Err(MindChatBindingError::InvalidInput { detail: "a full JID is required".to_owned() })
        );
    }

    #[test]
    fn bridge_rejects_empty_passwords_before_starting_a_transport_worker() {
        let core = MindChatCoreHandle::new();
        let account_id = core
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        core.drain_events().expect("initial events");

        assert_eq!(
            core.connect_account(account_id, String::new()),
            Err(MindChatBindingError::InvalidInput { detail: "a password is required".to_owned() })
        );
        assert_eq!(core.poll_transport_events(0).expect("zero poll"), 0);
        assert_eq!(
            core.snapshot().expect("snapshot").accounts[0].connection_state,
            FfiConnectionState::Offline
        );
    }

    /// Unique state-file path under the system temp dir for one test.
    ///
    /// The parent directory is removed when the guard drops so tests never
    /// leave files behind.
    struct TempState {
        path: std::path::PathBuf,
    }

    impl TempState {
        fn new(label: &str) -> Self {
            static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
            let dir = std::env::temp_dir().join(format!(
                "mindchat-ffi-{label}-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&dir).expect("create test temp directory");
            Self { path: dir.join("mindchat_state.json") }
        }

        fn path(&self) -> std::path::PathBuf {
            self.path.clone()
        }
    }

    impl Drop for TempState {
        fn drop(&mut self) {
            if let Some(parent) = self.path.parent() {
                let _result = std::fs::remove_dir_all(parent);
            }
        }
    }

    fn populate_handle(core: &MindChatCoreHandle) -> (u64, u64, u64) {
        let account_id = core
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        core.drain_events().expect("initial events");
        let conversation_id = core
            .open_conversation(
                account_id,
                FfiConversationKind::Direct,
                "bob@example.org".to_owned(),
                "Bob".to_owned(),
                100,
            )
            .expect("conversation");
        let message_id = core
            .send_text(
                conversation_id,
                "alice@example.org".to_owned(),
                "hello".to_owned(),
                None,
                200,
            )
            .expect("message");
        (account_id, conversation_id, message_id)
    }

    #[test]
    fn bridge_save_and_load_state_round_trip() {
        let first = MindChatCoreHandle::new();
        let (account_id, conversation_id, message_id) = populate_handle(&first);
        let temp = TempState::new("round-trip");

        first.save_state(temp.path().to_string_lossy().into_owned()).expect("save succeeds");

        let second = MindChatCoreHandle::new();
        assert!(
            second.load_state(temp.path().to_string_lossy().into_owned()).expect("load succeeds")
        );

        let restored = second.snapshot().expect("restored snapshot");
        assert_eq!(restored.accounts.len(), 1);
        assert_eq!(restored.accounts[0].id, account_id);
        assert_eq!(restored.accounts[0].connection_state, FfiConnectionState::Offline);
        assert!(restored.accounts[0].capabilities.is_empty());
        assert_eq!(restored.conversations[0].id, conversation_id);
        assert_eq!(restored.messages[0].id, message_id);
        assert_eq!(restored.messages[0].body, "hello");
    }

    #[test]
    fn bridge_load_state_refuses_non_empty_core() {
        let core = MindChatCoreHandle::new();
        populate_handle(&core);
        let temp = TempState::new("refuse");

        assert_eq!(
            core.load_state(temp.path().to_string_lossy().into_owned()),
            Err(MindChatBindingError::Internal {
                detail: "core already contains accounts; load_state must run before any mutation"
                    .to_owned(),
            })
        );
    }

    #[test]
    fn bridge_load_state_missing_file_returns_false() {
        let core = MindChatCoreHandle::new();
        let temp = TempState::new("missing");
        let missing = temp.path().with_extension("never-written.json");

        assert!(
            !core
                .load_state(missing.to_string_lossy().into_owned())
                .expect("missing file loads as false")
        );
    }
}
