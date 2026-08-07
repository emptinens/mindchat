//! UniFFI-facing DTOs and commands.
//!
//! This module deliberately translates the domain model instead of exporting it
//! directly. The domain remains free to evolve its storage and transport
//! details, while Kotlin receives only immutable, display-safe records and
//! commands.

use crate::{
    AccountSetup, ConnectionState, ConversationKind, CoreError, CoreEvent, DeliveryState,
    MessageDirection, MessageKind, MindChatCore, ProtocolCapability,
};
use std::fmt;
use std::sync::{Arc, Mutex, MutexGuard};

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
    pub conversations: Vec<FfiConversation>,
    pub messages: Vec<FfiMessage>,
    pub reactions: Vec<FfiReaction>,
}

/// Notification emitted after a state change. The Kotlin layer refetches a
/// snapshot to render the resulting state.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiCoreEvent {
    AccountChanged { account_id: u64 },
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
    Internal { detail: String },
}

impl fmt::Display for MindChatBindingError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidInput { detail }
            | Self::NotFound { detail }
            | Self::Internal { detail } => formatter.write_str(detail),
            Self::CapabilityUnavailable { capability } => {
                write!(formatter, "capability unavailable: {capability:?}")
            }
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

/// Thread-safe owner for a Rust core instance.
///
/// Kotlin never receives the inner state machine. Every mutation is validated
/// in Rust and the result is rendered from immutable snapshots.
#[derive(uniffi::Object)]
pub struct MindChatCoreHandle {
    core: Mutex<MindChatCore>,
}

impl MindChatCoreHandle {
    fn lock(&self) -> Result<MutexGuard<'_, MindChatCore>, MindChatBindingError> {
        self.core.lock().map_err(|_| MindChatBindingError::Internal {
            detail: "MindChat core lock was poisoned".to_owned(),
        })
    }
}

#[uniffi::export]
impl MindChatCoreHandle {
    /// Creates an empty local core. Account credentials are intentionally not
    /// accepted here; a future Android credential flow hands them only to the
    /// XMPP transport boundary.
    #[uniffi::constructor]
    #[must_use]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { core: Mutex::new(MindChatCore::default()) })
    }

    /// Adds an account configuration after basic JID and server validation.
    pub fn add_account(
        &self,
        jid: String,
        server: String,
        display_name: String,
    ) -> Result<u64, MindChatBindingError> {
        self.lock()?.add_account(AccountSetup::new(jid, server, display_name)).map_err(Into::into)
    }

    /// Updates the visible connection state for an account.
    pub fn set_connection_state(
        &self,
        account_id: u64,
        state: FfiConnectionState,
    ) -> Result<(), MindChatBindingError> {
        self.lock()?.set_connection_state(account_id, state.into()).map_err(Into::into)
    }

    /// Replaces the server features discovered for an account.
    pub fn set_capabilities(
        &self,
        account_id: u64,
        capabilities: Vec<FfiProtocolCapability>,
    ) -> Result<(), MindChatBindingError> {
        self.lock()?
            .set_capabilities(account_id, capabilities.into_iter().map(Into::into))
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
        self.lock()?.receive_text(conversation_id, sender, body, now_epoch_ms).map_err(Into::into)
    }

    /// Adds a reaction to a message.
    pub fn add_reaction(
        &self,
        message_id: u64,
        actor: String,
        emoji: String,
    ) -> Result<u64, MindChatBindingError> {
        self.lock()?.add_reaction(message_id, actor, emoji).map_err(Into::into)
    }

    /// Clears a conversation's unread count.
    pub fn mark_conversation_read(&self, conversation_id: u64) -> Result<(), MindChatBindingError> {
        self.lock()?.mark_conversation_read(conversation_id).map_err(Into::into)
    }

    /// Returns all UI-safe state in stable identifier order.
    pub fn snapshot(&self) -> Result<FfiCoreSnapshot, MindChatBindingError> {
        Ok(self.lock()?.snapshot().into())
    }

    /// Drains state-change notifications in mutation order.
    pub fn drain_events(&self) -> Result<Vec<FfiCoreEvent>, MindChatBindingError> {
        Ok(self.lock()?.drain_events().into_iter().map(Into::into).collect())
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
    fn bridge_maps_domain_errors_without_exposing_internal_types() {
        let core = MindChatCoreHandle::new();
        assert_eq!(
            core.add_account("invalid".to_owned(), "example.org".to_owned(), "Alice".to_owned()),
            Err(MindChatBindingError::InvalidInput { detail: "a full JID is required".to_owned() })
        );
    }
}
