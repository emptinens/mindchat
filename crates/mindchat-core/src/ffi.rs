//! UniFFI-facing DTOs and commands.
//!
//! This module deliberately translates the domain model instead of exporting it
//! directly. The domain remains free to evolve its storage and transport
//! details, while Kotlin receives only immutable, display-safe records and
//! commands.
//!
//! # Current public session methods on [`MindChatCoreHandle`]
//!
//! ```text
//! new
//! add_account
//! set_connection_state
//! set_capabilities
//! upsert_contact
//! open_conversation
//! send_text
//! receive_text
//! add_reaction
//! mark_conversation_read
//! connect_account
//! connect_account_with_proxy
//! set_account_proxies
//! account_proxies
//! test_proxy
//! register_account
//! disconnect_account
//! delete_account
//! update_account_display_name
//! delete_conversation
//! poll_transport_events
//! flush_outbox
//! snapshot
//! drain_events
//! save_state
//! load_state
//! ```
//!
//! [`mindchat_binding_version`] is the free binding contract probe. All session
//! methods take UI-safe values and return typed errors; passwords are accepted
//! only by [`MindChatCoreHandle::connect_account`],
//! [`MindChatCoreHandle::connect_account_with_proxy`], and
//! [`MindChatCoreHandle::register_account`], never stored or returned. Proxy
//! credentials follow the same hand-off pattern and are never persisted.

use crate::persistence::{
    CURRENT_SCHEMA_VERSION, PersistenceError, StateFileMetadata, load_state_with_metadata,
    save_state,
};
use crate::proxy::{ProxyConfig, ProxyKind, ProxyProbe};
use crate::{
    AccountSetup, ConnectionState, ContactPresence, ConversationKind, CoreError, CoreEvent,
    DeliveryState, DisconnectKind, MessageDirection, MessageKind, MindChatCore, ProtocolCapability,
    RegisterRequest, RosterSubscription, SecretString, TokioXmppTransport, TransportCoordinator,
    TransportCoordinatorError, TransportError, XmppTransport,
};
use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

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

/// Typed reason an account left the connected state (ROADMAP 6.5), rendered
/// as the bucket label above the detail prose. Derived by the core from typed
/// transport failures; prose is never parsed for this.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiDisconnectKind {
    /// The server rejected the credentials (SASL failure).
    AuthenticationFailed,
    /// TLS certificate validation failed (invalid, expired, unknown CA, hostname mismatch).
    TlsVerificationFailed,
    /// The server refused the stream or session (for example a stream error).
    ServerRefused,
    /// The link was lost: timeout, EOF, suspended stream, or reconnect budget
    /// exhausted.
    NetworkLost,
    /// The user explicitly disconnected the account.
    Cancelled,
    /// Anything without a dedicated bucket.
    Unknown,
}

impl From<DisconnectKind> for FfiDisconnectKind {
    fn from(value: DisconnectKind) -> Self {
        match value {
            DisconnectKind::AuthenticationFailed => Self::AuthenticationFailed,
            DisconnectKind::TlsVerificationFailed => Self::TlsVerificationFailed,
            DisconnectKind::ServerRefused => Self::ServerRefused,
            DisconnectKind::NetworkLost => Self::NetworkLost,
            DisconnectKind::Cancelled => Self::Cancelled,
            DisconnectKind::Unknown => Self::Unknown,
        }
    }
}

impl From<FfiDisconnectKind> for DisconnectKind {
    fn from(value: FfiDisconnectKind) -> Self {
        match value {
            FfiDisconnectKind::AuthenticationFailed => Self::AuthenticationFailed,
            FfiDisconnectKind::TlsVerificationFailed => Self::TlsVerificationFailed,
            FfiDisconnectKind::ServerRefused => Self::ServerRefused,
            FfiDisconnectKind::NetworkLost => Self::NetworkLost,
            FfiDisconnectKind::Cancelled => Self::Cancelled,
            FfiDisconnectKind::Unknown => Self::Unknown,
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

/// How a proxy tunnel is established. The inverse of the domain
/// [`ConnectStrategy`] proxy variants, exposed without the `Direct` case.
#[derive(Clone, Copy, Debug, Eq, PartialEq, uniffi::Enum)]
pub enum FfiProxyKind {
    Socks5,
    HttpConnect,
}

impl From<FfiProxyKind> for ProxyKind {
    fn from(value: FfiProxyKind) -> Self {
        match value {
            FfiProxyKind::Socks5 => ProxyKind::Socks5,
            FfiProxyKind::HttpConnect => ProxyKind::HttpConnect,
        }
    }
}

impl From<ProxyKind> for FfiProxyKind {
    fn from(value: ProxyKind) -> Self {
        match value {
            ProxyKind::Socks5 => Self::Socks5,
            ProxyKind::HttpConnect => Self::HttpConnect,
        }
    }
}

/// Non-secret proxy configuration safe for display and persistence.
///
/// Deliberately has no password field: proxy credentials are runtime-only
/// and are handed to [`MindChatCoreHandle::test_proxy`] or
/// [`MindChatCoreHandle::connect_account_with_proxy`] at the call site.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiProxyConfig {
    pub host: String,
    pub port: u16,
    pub kind: FfiProxyKind,
}

/// Outcome of a proxy probe, safe to show in the UI.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiProxyProbe {
    pub ok: bool,
    pub latency_ms: u64,
    pub error: Option<String>,
}

impl From<ProxyProbe> for FfiProxyProbe {
    fn from(value: ProxyProbe) -> Self {
        Self { ok: value.ok, latency_ms: value.latency_ms, error: value.error }
    }
}

impl From<ProxyConfig> for FfiProxyConfig {
    fn from(value: ProxyConfig) -> Self {
        Self { host: value.host, port: value.port, kind: value.kind.into() }
    }
}

/// Validates an FFI proxy configuration into the domain value.
fn to_proxy_config(config: &FfiProxyConfig) -> Result<ProxyConfig, MindChatBindingError> {
    ProxyConfig::new(config.host.clone(), config.port, config.kind.into())
        .map_err(|error| MindChatBindingError::InvalidInput { detail: error.to_string() })
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
    /// Typed disconnect classification (ROADMAP 6.5), rendered as the bucket
    /// label above [`Self::connection_error`]. `None` while the account is
    /// connected or has not disconnected yet.
    pub disconnect_kind: Option<FfiDisconnectKind>,
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

/// Opt-in diagnostics export (ROADMAP 6.5), assembled by
/// [`MindChatCoreHandle::diagnostics_report`].
///
/// Structurally excludes every secret and every piece of snapshot content:
/// there are no password, message-body, avatar, or JID fields, only record
/// counts and persistence metadata. The state path is the app's own
/// non-secret state file location.
#[derive(Clone, Debug, Eq, PartialEq, uniffi::Record)]
pub struct FfiDiagnosticsReport {
    /// Number of configured accounts.
    pub account_count: u64,
    /// Number of roster contacts.
    pub contact_count: u64,
    /// Number of conversations.
    pub conversation_count: u64,
    /// Number of messages.
    pub message_count: u64,
    /// Number of reactions.
    pub reaction_count: u64,
    /// Absolute path of the last state file passed to save/load, if any.
    pub state_path: Option<String>,
    /// Size of the state file in bytes, as last observed by save/load.
    pub state_size_bytes: Option<u64>,
    /// Schema version of the state file, as last observed by save/load.
    pub state_schema_version: Option<u32>,
    /// Whether the last load attempt failed and the file was quarantined.
    pub state_quarantined: bool,
    /// Epoch milliseconds of the last successful save.
    pub state_last_saved_at_epoch_ms: Option<u64>,
    /// Epoch milliseconds of the last successful load.
    pub state_last_loaded_at_epoch_ms: Option<u64>,
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
            | CoreError::EmptyDisplayName
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
            TransportError::TlsVerification(detail) => Self::ConnectionFailed { detail },
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
    /// Non-secret persistence observations for the diagnostics report.
    persistence: Mutex<PersistenceTrack>,
}

/// Persistence metadata tracked by save/load for [`FfiDiagnosticsReport`]
/// (ROADMAP 6.5). Contains no snapshot content.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
struct PersistenceTrack {
    path: Option<PathBuf>,
    size_bytes: Option<u64>,
    schema_version: Option<u32>,
    quarantined: bool,
    last_saved_at_epoch_ms: Option<u64>,
    last_loaded_at_epoch_ms: Option<u64>,
}

/// Current wall clock in epoch milliseconds.
fn now_epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| u64::try_from(duration.as_millis()).unwrap_or(u64::MAX))
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

    /// Records a successful save for the diagnostics report.
    fn record_save(&self, path: &Path) {
        if let Ok(mut track) = self.persistence.lock() {
            track.path = Some(path.to_path_buf());
            track.size_bytes = std::fs::metadata(path).ok().map(|metadata| metadata.len());
            track.schema_version = Some(CURRENT_SCHEMA_VERSION);
            track.last_saved_at_epoch_ms = Some(now_epoch_ms());
        }
    }

    /// Records a successful load for the diagnostics report.
    fn record_load_success(&self, path: &Path, metadata: &StateFileMetadata) {
        if let Ok(mut track) = self.persistence.lock() {
            track.path = Some(path.to_path_buf());
            track.size_bytes = Some(metadata.size_bytes);
            track.schema_version = Some(metadata.schema_version);
            track.last_loaded_at_epoch_ms = Some(now_epoch_ms());
        }
    }

    /// Records a missing-file load for the diagnostics report.
    fn record_load_missing(&self, path: &Path) {
        if let Ok(mut track) = self.persistence.lock() {
            track.path = Some(path.to_path_buf());
            track.last_loaded_at_epoch_ms = Some(now_epoch_ms());
        }
    }

    /// Records a failed load for the diagnostics report. The state file could
    /// not be restored, so it is flagged as quarantined (the app renames it
    /// aside and starts clean).
    fn record_load_failure(&self, path: &Path, error: &PersistenceError) {
        if let Ok(mut track) = self.persistence.lock() {
            track.path = Some(path.to_path_buf());
            track.size_bytes = std::fs::metadata(path).ok().map(|metadata| metadata.len());
            if let PersistenceError::UnsupportedVersion(version) = error {
                track.schema_version = Some(*version);
            }
            track.quarantined = true;
            track.last_loaded_at_epoch_ms = Some(now_epoch_ms());
        }
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
            persistence: Mutex::new(PersistenceTrack::default()),
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

    /// Starts a concrete XMPP session for an existing account, optionally
    /// through a proxy tunnel (ROADMAP 6.3).
    ///
    /// With `proxy` set to `None` this behaves exactly like
    /// [`Self::connect_account`]. With a tunnel, the SOCKS5 or HTTP CONNECT
    /// handshake runs first and DNS for the XMPP server happens only at the
    /// proxy (SRV is skipped, preventing DNS leaks). The account password and
    /// `proxy_password` are handed to the worker and are never stored by the
    /// core, snapshots, or event stream.
    pub fn connect_account_with_proxy(
        &self,
        account_id: u64,
        password: String,
        proxy: Option<FfiProxyConfig>,
        proxy_password: Option<String>,
    ) -> Result<(), MindChatBindingError> {
        if password.is_empty() {
            return Err(MindChatBindingError::InvalidInput {
                detail: "a password is required".to_owned(),
            });
        }
        let proxy = proxy.map(|config| to_proxy_config(&config)).transpose()?;
        self.lock()?
            .connect_with_proxy(
                account_id,
                SecretString::new(password),
                proxy,
                proxy_password.map(SecretString::new),
            )
            .map_err(Into::into)
    }

    /// Replaces the proxy library assigned to an account.
    ///
    /// The library is non-secret (host/port/kind) and survives a restore;
    /// proxy passwords are never stored by the core and are supplied per
    /// connect. `None` clears the library.
    pub fn set_account_proxies(
        &self,
        account_id: u64,
        proxies: Option<Vec<FfiProxyConfig>>,
    ) -> Result<(), MindChatBindingError> {
        let proxies = proxies
            .unwrap_or_default()
            .iter()
            .map(to_proxy_config)
            .collect::<Result<Vec<_>, _>>()?;
        self.lock()?.core_mut().set_account_proxies(account_id, proxies).map_err(Into::into)
    }

    /// Returns the proxy library assigned to an account.
    ///
    /// Configs always come back password-free: the core never stores proxy
    /// credentials, so there is nothing to return.
    pub fn account_proxies(
        &self,
        account_id: u64,
    ) -> Result<Vec<FfiProxyConfig>, MindChatBindingError> {
        self.lock()?
            .core()
            .account_proxies(account_id)
            .map(|proxies| proxies.into_iter().map(Into::into).collect())
            .map_err(Into::into)
    }

    /// Pings a proxy configuration through a bounded tunnel handshake.
    ///
    /// The probe opens the tunnel to the proxy's own address, so it needs no
    /// external target and resolves no XMPP hostname. `proxy_password` is
    /// runtime-only. `latency_ms` is the wall-clock time from TCP open through
    /// handshake completion; the whole probe is capped at 15 seconds, so this
    /// method must be invoked off the UI thread.
    pub fn test_proxy(
        &self,
        config: FfiProxyConfig,
        proxy_password: Option<String>,
    ) -> FfiProxyProbe {
        let proxy = match to_proxy_config(&config) {
            Ok(proxy) => proxy,
            Err(error) => {
                return FfiProxyProbe { ok: false, latency_ms: 0, error: Some(error.to_string()) };
            }
        };
        crate::proxy::proxy_probe(&proxy, proxy_password.as_deref()).into()
    }

    /// Registers a new account with XEP-0077 in-band registration and starts
    /// its authenticated session.
    ///
    /// The whole registration exchange (DNS, stream setup, fields query, and
    /// submission) runs synchronously under a hard time bound, so this call
    /// always returns a terminal result. Registration is offered only when the
    /// server advertises `jabber:iq:register`; servers that require a data form
    /// or captcha are refused with a UI-safe detail, because MindChat
    /// implements only username/password registration. On success the account
    /// is created in the core and a normal authenticated session starts, so
    /// the account ends up `Online` once transport events are polled.
    ///
    /// The password is handed to the registration worker and to the session
    /// connect; it is never stored in the core, snapshots, or event stream.
    pub fn register_account(
        &self,
        username: String,
        server: String,
        display_name: String,
        password: String,
    ) -> Result<u64, MindChatBindingError> {
        if password.is_empty() {
            return Err(MindChatBindingError::InvalidInput {
                detail: "a password is required".to_owned(),
            });
        }
        if username.trim().is_empty() {
            return Err(MindChatBindingError::InvalidInput {
                detail: "a username is required".to_owned(),
            });
        }
        if server.trim().is_empty() {
            return Err(MindChatBindingError::InvalidInput {
                detail: "a server hostname is required".to_owned(),
            });
        }
        let mut session = self.lock()?;
        let jid = format!("{username}@{server}");
        session
            .transport_mut()
            .register(RegisterRequest {
                username: username.clone(),
                server: server.clone(),
                password: SecretString::new(password.clone()),
            })
            .map_err(MindChatBindingError::from)?;
        let account_id =
            session.core_mut().add_account(AccountSetup::new(jid, server, display_name))?;
        session.connect(account_id, SecretString::new(password))?;
        Ok(account_id)
    }

    /// Stops an active XMPP session and projects the account as offline.
    pub fn disconnect_account(&self, account_id: u64) -> Result<(), MindChatBindingError> {
        self.lock()?.disconnect(account_id).map_err(Into::into)
    }

    /// Removes an account, disconnecting it first if a session is active.
    ///
    /// The account's contacts, conversations, messages, and reactions are
    /// removed from the core, and the UI is notified with `AccountChanged`;
    /// the next snapshot simply no longer contains the account or its data.
    pub fn delete_account(&self, account_id: u64) -> Result<(), MindChatBindingError> {
        let mut session = self.lock()?;
        if !session.core().accounts().iter().any(|account| account.id == account_id) {
            return Err(MindChatBindingError::NotFound {
                detail: format!("unknown account {account_id}"),
            });
        }
        // Best effort: a tracked worker (including a dead one whose terminal
        // event was not polled yet) is released; the account is being deleted,
        // so a stale worker cannot keep the account slot or the connection.
        if session.transport().connected_accounts().contains(&account_id) {
            let _ = session.transport_mut().disconnect(account_id);
        }
        session.core_mut().delete_account(account_id).map_err(Into::into)
    }

    /// Replaces the display name shown for an account.
    ///
    /// An empty or whitespace-only display name is rejected as invalid input.
    pub fn update_account_display_name(
        &self,
        account_id: u64,
        display_name: String,
    ) -> Result<(), MindChatBindingError> {
        if display_name.trim().is_empty() {
            return Err(MindChatBindingError::InvalidInput {
                detail: "a display name is required".to_owned(),
            });
        }
        self.lock()?
            .core_mut()
            .update_account_display_name(account_id, display_name)
            .map_err(Into::into)
    }

    /// Removes a conversation and every message and reaction inside it.
    ///
    /// The UI is notified with `ConversationChanged`; the next snapshot no
    /// longer contains the conversation or its data.
    pub fn delete_conversation(&self, conversation_id: u64) -> Result<(), MindChatBindingError> {
        self.lock()?.core_mut().delete_conversation(conversation_id).map_err(Into::into)
    }

    /// Applies up to `max_events` normalized transport events to the core.
    ///
    /// The bound prevents a busy server from monopolizing the Kotlin caller;
    /// pass zero to perform no work, and values above 128 are clamped.
    ///
    /// Polling is resilient: a malformed or unknown event is consumed and
    /// skipped instead of aborting the batch, so `Connected`/`Disconnected`
    /// events queued behind a bad stanza are still applied in the same poll
    /// cycle. Only a transport channel failure or a poisoned session lock
    /// surfaces as an error. Returns the number of events consumed.
    pub fn poll_transport_events(&self, max_events: u32) -> Result<u32, MindChatBindingError> {
        let mut session = self.lock()?;
        let limit = max_events.min(MAX_TRANSPORT_EVENTS_PER_POLL);
        session
            .poll_transport_events(limit as usize)
            .map(|count| u32::try_from(count).unwrap_or(u32::MAX))
            .map_err(Into::into)
    }

    /// Sends queued or retryable text for an online account in stable message order.
    ///
    /// Offline and connecting accounts keep their queue untouched and return
    /// zero, so Android can safely invoke this after each polling pass. The
    /// send batch is bounded in the coordinator (16 messages, ~4 s budget,
    /// 0.1.8 P1-1), so a stalled server cannot freeze the session lock for
    /// the whole queue.
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
    /// core or the snapshot. The outcome feeds the diagnostics report.
    pub fn save_state(&self, path: String) -> Result<(), MindChatBindingError> {
        let snapshot = self.lock()?.core().snapshot();
        save_state(&snapshot, Path::new(&path)).map_err(MindChatBindingError::from)?;
        self.record_save(Path::new(&path));
        Ok(())
    }

    /// Restores a saved snapshot from `path`, returning whether one was loaded.
    ///
    /// `Ok(false)` means no state file exists and the handle stays empty.
    /// `Ok(true)` replaces the core with the sanitized snapshot; restored
    /// accounts are always `Offline` with cleared errors and capabilities
    /// until a real connect happens. The load is refused when the core
    /// already contains accounts or the transport owns connections, because
    /// restore must run exactly once at startup on an empty handle rather
    /// than merge or clobber live state. The file contains no secrets, and
    /// load outcomes (including a quarantined file) feed the diagnostics
    /// report.
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
        let path = Path::new(&path);
        match load_state_with_metadata(path) {
            Ok(Some((snapshot, metadata))) => {
                *session.core_mut() = MindChatCore::from_snapshot(snapshot);
                self.record_load_success(path, &metadata);
                Ok(true)
            }
            Ok(None) => {
                self.record_load_missing(path);
                Ok(false)
            }
            Err(error) => {
                self.record_load_failure(path, &error);
                Err(MindChatBindingError::from(error))
            }
        }
    }

    /// Assembles the opt-in diagnostics export (ROADMAP 6.5).
    ///
    /// Snapshot counters come from the current in-memory state; persistence
    /// metadata comes from the last save/load outcome. The report never reads
    /// the state file itself, so it cannot fail on I/O, and it structurally
    /// contains no passwords, message bodies, avatar paths, or JIDs. If the
    /// session lock is poisoned (the core panicked), counters read zero but
    /// the persistence metadata is still reported.
    pub fn diagnostics_report(&self) -> FfiDiagnosticsReport {
        let snapshot = match self.lock() {
            Ok(guard) => guard.core().snapshot(),
            Err(_) => crate::CoreSnapshot::default(),
        };
        let track = match self.persistence.lock() {
            Ok(guard) => guard,
            // A poisoned track still holds valid metadata; diagnostics must
            // never fail, so read through the poison error.
            Err(poisoned) => poisoned.into_inner(),
        };
        FfiDiagnosticsReport {
            account_count: u64::try_from(snapshot.accounts.len()).unwrap_or(u64::MAX),
            contact_count: u64::try_from(snapshot.contacts.len()).unwrap_or(u64::MAX),
            conversation_count: u64::try_from(snapshot.conversations.len()).unwrap_or(u64::MAX),
            message_count: u64::try_from(snapshot.messages.len()).unwrap_or(u64::MAX),
            reaction_count: u64::try_from(snapshot.reactions.len()).unwrap_or(u64::MAX),
            state_path: track.path.as_ref().map(|path| path.to_string_lossy().into_owned()),
            state_size_bytes: track.size_bytes,
            state_schema_version: track.schema_version,
            state_quarantined: track.quarantined,
            state_last_saved_at_epoch_ms: track.last_saved_at_epoch_ms,
            state_last_loaded_at_epoch_ms: track.last_loaded_at_epoch_ms,
        }
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
            disconnect_kind: value.disconnect_kind.map(Into::into),
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

    #[test]
    fn register_account_rejects_empty_password_and_identifiers_without_network() {
        let core = MindChatCoreHandle::new();
        assert_eq!(
            core.register_account(
                "alice".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
                String::new(),
            ),
            Err(MindChatBindingError::InvalidInput { detail: "a password is required".to_owned() })
        );
        assert_eq!(
            core.register_account(
                String::new(),
                "example.org".to_owned(),
                "Alice".to_owned(),
                "s3cret".to_owned(),
            ),
            Err(MindChatBindingError::InvalidInput { detail: "a username is required".to_owned() })
        );
        assert_eq!(
            core.register_account(
                "alice".to_owned(),
                String::new(),
                "Alice".to_owned(),
                "s3cret".to_owned(),
            ),
            Err(MindChatBindingError::InvalidInput {
                detail: "a server hostname is required".to_owned(),
            })
        );
        // No registration worker or session may have been started.
        assert_eq!(core.poll_transport_events(0).expect("zero poll"), 0);
        assert!(core.snapshot().expect("snapshot").accounts.is_empty());
    }

    #[test]
    fn delete_account_removes_all_account_state_from_snapshot() {
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
        core.set_capabilities(account_id, vec![FfiProtocolCapability::MessageReactions])
            .expect("reactions capability");
        core.add_reaction(message_id, "bob@example.org".to_owned(), "👍".to_owned())
            .expect("reaction");
        core.drain_events().expect("initial events");

        core.delete_account(account_id).expect("account deleted");
        let snapshot = core.snapshot().expect("snapshot");
        assert!(snapshot.accounts.is_empty());
        assert!(snapshot.conversations.is_empty());
        assert!(snapshot.messages.is_empty());
        assert!(snapshot.reactions.is_empty());
        assert_eq!(
            core.drain_events().expect("events"),
            vec![FfiCoreEvent::AccountChanged { account_id }]
        );
    }

    #[test]
    fn delete_conversation_removes_messages_and_reactions_from_snapshot() {
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
        core.set_capabilities(account_id, vec![FfiProtocolCapability::MessageReactions])
            .expect("reactions capability");
        core.add_reaction(message_id, "bob@example.org".to_owned(), "👍".to_owned())
            .expect("reaction");
        core.drain_events().expect("initial events");

        core.delete_conversation(conversation_id).expect("conversation deleted");
        let snapshot = core.snapshot().expect("snapshot");
        assert_eq!(snapshot.accounts.len(), 1, "the account survives conversation deletion");
        assert!(snapshot.conversations.is_empty());
        assert!(snapshot.messages.is_empty());
        assert!(snapshot.reactions.is_empty());
        assert_eq!(
            core.drain_events().expect("events"),
            vec![FfiCoreEvent::ConversationChanged { conversation_id }]
        );
    }

    #[test]
    fn delete_account_and_conversation_reject_unknown_ids() {
        let core = MindChatCoreHandle::new();
        assert_eq!(
            core.delete_account(999),
            Err(MindChatBindingError::NotFound { detail: "unknown account 999".to_owned() })
        );
        assert_eq!(
            core.delete_conversation(999),
            Err(MindChatBindingError::NotFound { detail: "unknown conversation 999".to_owned() })
        );
    }

    #[test]
    fn update_account_display_name_propagates_to_snapshot_and_validates() {
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
            core.update_account_display_name(account_id, "   ".to_owned()),
            Err(MindChatBindingError::InvalidInput {
                detail: "a display name is required".to_owned(),
            })
        );
        assert_eq!(
            core.update_account_display_name(999, "Alicia".to_owned()),
            Err(MindChatBindingError::NotFound { detail: "unknown account 999".to_owned() })
        );

        core.update_account_display_name(account_id, "Alicia".to_owned())
            .expect("display name updated");
        assert_eq!(core.snapshot().expect("snapshot").accounts[0].display_name, "Alicia");
        assert_eq!(
            core.drain_events().expect("events"),
            vec![FfiCoreEvent::AccountChanged { account_id }]
        );
    }

    #[test]
    fn registration_refusal_surfaces_as_ui_safe_connection_error() {
        // The transport's caps-gating detail (a server without
        // jabber:iq:register) must reach Kotlin as a displayable
        // ConnectionFailed, never as an Internal error.
        let error = MindChatBindingError::from(TransportError::ConnectionFailed(
            "this server does not support in-band registration".to_owned(),
        ));
        assert_eq!(
            error,
            MindChatBindingError::ConnectionFailed {
                detail: "this server does not support in-band registration".to_owned(),
            }
        );
    }

    fn ffi_proxy(host: &str, port: u16, kind: FfiProxyKind) -> FfiProxyConfig {
        FfiProxyConfig { host: host.to_owned(), port, kind }
    }

    #[test]
    fn bridge_proxy_library_round_trips_without_passwords() {
        let core = MindChatCoreHandle::new();
        let account_id = core
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        core.drain_events().expect("initial events");

        core.set_account_proxies(
            account_id,
            Some(vec![
                ffi_proxy("proxy.example.org", 1080, FfiProxyKind::Socks5),
                ffi_proxy("proxy.example.org", 8080, FfiProxyKind::HttpConnect),
            ]),
        )
        .expect("proxy library set");

        let proxies = core.account_proxies(account_id).expect("proxy library read");
        assert_eq!(proxies.len(), 2);
        assert_eq!(proxies[0].kind, FfiProxyKind::Socks5);
        assert_eq!(proxies[0].port, 1080);
        assert_eq!(proxies[0].host, "proxy.example.org");
        assert_eq!(proxies[1].kind, FfiProxyKind::HttpConnect);
        assert_eq!(proxies[1].port, 8080);
        // The config type has no password field at all, so nothing secret can
        // cross this boundary.
        assert_eq!(
            core.drain_events().expect("events"),
            vec![FfiCoreEvent::AccountChanged { account_id }]
        );
    }

    #[test]
    fn bridge_proxy_library_clears_and_validates() {
        let core = MindChatCoreHandle::new();
        let account_id = core
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        core.drain_events().expect("initial events");

        core.set_account_proxies(account_id, None).expect("clearing the library succeeds");
        assert!(core.account_proxies(account_id).expect("empty library reads").is_empty());

        assert_eq!(
            core.set_account_proxies(
                account_id,
                Some(vec![ffi_proxy("", 1080, FfiProxyKind::Socks5)]),
            ),
            Err(MindChatBindingError::InvalidInput {
                detail: "invalid proxy configuration: a proxy or tunnel host is required"
                    .to_owned(),
            })
        );
        assert_eq!(
            core.set_account_proxies(
                account_id,
                Some(vec![ffi_proxy("proxy.example.org", 0, FfiProxyKind::Socks5)]),
            ),
            Err(MindChatBindingError::InvalidInput {
                detail: "invalid proxy configuration: a proxy port must not be zero".to_owned(),
            })
        );
        assert_eq!(
            core.set_account_proxies(999, None),
            Err(MindChatBindingError::NotFound { detail: "unknown account 999".to_owned() })
        );
    }

    #[test]
    fn bridge_proxy_library_survives_state_restore() {
        let first = MindChatCoreHandle::new();
        let account_id = first
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        first
            .set_account_proxies(
                account_id,
                Some(vec![ffi_proxy("proxy.example.org", 1080, FfiProxyKind::Socks5)]),
            )
            .expect("proxy library set");
        let temp = TempState::new("proxy-restore");

        first.save_state(temp.path().to_string_lossy().into_owned()).expect("save succeeds");

        let second = MindChatCoreHandle::new();
        assert!(
            second.load_state(temp.path().to_string_lossy().into_owned()).expect("load succeeds")
        );
        let restored = second.account_proxies(account_id).expect("restored library reads");
        assert_eq!(restored, vec![ffi_proxy("proxy.example.org", 1080, FfiProxyKind::Socks5)]);
    }

    #[test]
    fn bridge_connect_account_with_proxy_validates_without_network() {
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
            core.connect_account_with_proxy(account_id, String::new(), None, None),
            Err(MindChatBindingError::InvalidInput { detail: "a password is required".to_owned() })
        );
        assert_eq!(
            core.connect_account_with_proxy(
                account_id,
                "s3cret".to_owned(),
                Some(ffi_proxy("", 1080, FfiProxyKind::Socks5)),
                None,
            ),
            Err(MindChatBindingError::InvalidInput {
                detail: "invalid proxy configuration: a proxy or tunnel host is required"
                    .to_owned(),
            })
        );
        // Neither validation failure may start a worker.
        assert_eq!(core.poll_transport_events(0).expect("zero poll"), 0);
        assert_eq!(
            core.snapshot().expect("snapshot").accounts[0].connection_state,
            FfiConnectionState::Offline
        );
    }

    #[test]
    fn bridge_test_proxy_reports_invalid_config_without_network() {
        let core = MindChatCoreHandle::new();
        let probe = core.test_proxy(ffi_proxy("", 1080, FfiProxyKind::Socks5), None);
        assert!(!probe.ok);
        assert!(probe.error.as_deref().is_some_and(|detail| detail.contains("host is required")));
    }

    #[test]
    fn ffi_disconnect_kind_maps_all_variants_both_ways() {
        let domain = [
            DisconnectKind::AuthenticationFailed,
            DisconnectKind::TlsVerificationFailed,
            DisconnectKind::ServerRefused,
            DisconnectKind::NetworkLost,
            DisconnectKind::Cancelled,
            DisconnectKind::Unknown,
        ];
        let ffi = [
            FfiDisconnectKind::AuthenticationFailed,
            FfiDisconnectKind::TlsVerificationFailed,
            FfiDisconnectKind::ServerRefused,
            FfiDisconnectKind::NetworkLost,
            FfiDisconnectKind::Cancelled,
            FfiDisconnectKind::Unknown,
        ];
        assert_eq!(domain.len(), ffi.len(), "the domain and FFI enums stay in lockstep");
        for (domain_kind, ffi_kind) in domain.into_iter().zip(ffi) {
            assert_eq!(FfiDisconnectKind::from(domain_kind), ffi_kind);
            assert_eq!(DisconnectKind::from(ffi_kind), domain_kind);
        }
    }

    #[test]
    fn account_projection_carries_the_disconnect_kind() {
        use std::collections::BTreeSet;
        let account = crate::Account {
            id: 7,
            jid: "alice@example.org".to_owned(),
            server: "example.org".to_owned(),
            display_name: "Alice".to_owned(),
            connection_state: ConnectionState::Failed,
            capabilities: BTreeSet::new(),
            connection_error: Some("not authorized".to_owned()),
            disconnect_kind: Some(DisconnectKind::AuthenticationFailed),
            auto_reconnect: true,
            proxies: Vec::new(),
        };
        let ffi: FfiAccount = account.into();
        assert_eq!(ffi.connection_state, FfiConnectionState::Failed);
        assert_eq!(ffi.connection_error.as_deref(), Some("not authorized"));
        assert_eq!(ffi.disconnect_kind, Some(FfiDisconnectKind::AuthenticationFailed));
    }

    #[test]
    fn diagnostics_report_counts_current_snapshot_records() {
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
        core.set_capabilities(account_id, vec![FfiProtocolCapability::MessageReactions])
            .expect("reactions capability");
        core.add_reaction(message_id, "bob@example.org".to_owned(), "👍".to_owned())
            .expect("reaction");
        core.upsert_contact(
            account_id,
            "bob@example.org".to_owned(),
            "Bob".to_owned(),
            FfiContactPresence::Online,
            Some("Available".to_owned()),
        )
        .expect("contact");

        let report = core.diagnostics_report();
        assert_eq!(report.account_count, 1);
        assert_eq!(report.contact_count, 1);
        assert_eq!(report.conversation_count, 1);
        assert_eq!(report.message_count, 1);
        assert_eq!(report.reaction_count, 1);
        // A fresh handle has never saved or loaded: no persistence metadata.
        assert!(report.state_path.is_none());
        assert!(report.state_size_bytes.is_none());
        assert!(report.state_schema_version.is_none());
        assert!(!report.state_quarantined);
        assert!(report.state_last_saved_at_epoch_ms.is_none());
        assert!(report.state_last_loaded_at_epoch_ms.is_none());
    }

    #[test]
    fn diagnostics_report_excludes_bodies_avatar_paths_and_jids() {
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
        // Secret-looking content injected into every user-supplied string the
        // snapshot can hold: a message body (which may contain a typed
        // password), a contact status (avatar path), and JIDs.
        let body_marker = "hunter2-secret-password-in-body";
        let avatar_marker = "/data/user/0/com.mindchat/avatars/bob.png";
        let jid_marker = "bob@example.org";
        let message_id = core
            .send_text(
                conversation_id,
                "alice@example.org".to_owned(),
                body_marker.to_owned(),
                None,
                200,
            )
            .expect("message");
        core.set_capabilities(account_id, vec![FfiProtocolCapability::MessageReactions])
            .expect("reactions capability");
        core.add_reaction(message_id, jid_marker.to_owned(), "👍".to_owned()).expect("reaction");
        core.upsert_contact(
            account_id,
            jid_marker.to_owned(),
            "Bob".to_owned(),
            FfiContactPresence::Online,
            Some(avatar_marker.to_owned()),
        )
        .expect("contact");

        // Sanity: the markers really live in the snapshot, so the redaction
        // assertion below is meaningful rather than vacuously green.
        let snapshot = core.snapshot().expect("snapshot");
        let snapshot_debug = format!("{snapshot:?}");
        assert!(snapshot_debug.contains(body_marker));
        assert!(snapshot_debug.contains(avatar_marker));
        assert!(snapshot_debug.contains(jid_marker));

        let rendered = format!("{:?}", core.diagnostics_report());
        for marker in [body_marker, avatar_marker, jid_marker] {
            assert!(
                !rendered.contains(marker),
                "the diagnostics report must not contain the snapshot marker {marker:?}"
            );
        }
    }

    #[test]
    fn diagnostics_report_type_has_no_secret_fields() {
        let core = MindChatCoreHandle::new();
        core.add_account(
            "alice@example.org".to_owned(),
            "example.org".to_owned(),
            "Alice".to_owned(),
        )
        .expect("account");
        let rendered = format!("{:?}", core.diagnostics_report());

        // Field-name audit: the record is counters + persistence metadata
        // only. No password, body, avatar, or JID field can ever be part of
        // the report (Rust would reject adding one without updating this).
        for forbidden in ["password", "body", "avatar", "jid", "secret", "token"] {
            assert!(!rendered.contains(forbidden), "the report must have no {forbidden} field");
        }
        for expected in [
            "account_count",
            "contact_count",
            "conversation_count",
            "message_count",
            "reaction_count",
            "state_path",
            "state_size_bytes",
            "state_schema_version",
            "state_quarantined",
            "state_last_saved_at_epoch_ms",
            "state_last_loaded_at_epoch_ms",
        ] {
            assert!(rendered.contains(expected), "the report must expose {expected}");
        }
    }

    #[test]
    fn diagnostics_report_tracks_save_and_load_metadata() {
        let first = MindChatCoreHandle::new();
        first
            .add_account(
                "alice@example.org".to_owned(),
                "example.org".to_owned(),
                "Alice".to_owned(),
            )
            .expect("account");
        let temp = TempState::new("diag-persist");
        first.save_state(temp.path().to_string_lossy().into_owned()).expect("save succeeds");

        let saved = first.diagnostics_report();
        assert_eq!(
            saved.state_path.as_deref(),
            Some(temp.path().to_string_lossy().as_ref()),
            "the saved path is the file passed to save_state"
        );
        let on_disk = std::fs::metadata(temp.path()).expect("state file exists").len();
        assert_eq!(saved.state_size_bytes, Some(on_disk));
        assert_eq!(saved.state_schema_version, Some(1));
        assert!(!saved.state_quarantined);
        assert!(saved.state_last_saved_at_epoch_ms.is_some());
        assert!(saved.state_last_loaded_at_epoch_ms.is_none());
        assert_eq!(saved.account_count, 1);

        let second = MindChatCoreHandle::new();
        assert!(
            second.load_state(temp.path().to_string_lossy().into_owned()).expect("load succeeds")
        );
        let loaded = second.diagnostics_report();
        assert_eq!(loaded.state_schema_version, Some(1));
        assert_eq!(loaded.state_size_bytes, Some(on_disk));
        assert!(!loaded.state_quarantined);
        assert!(loaded.state_last_loaded_at_epoch_ms.is_some());
        assert!(loaded.state_last_saved_at_epoch_ms.is_none());
        assert_eq!(loaded.account_count, 1);
    }

    #[test]
    fn diagnostics_report_flags_quarantined_load_failure() {
        let core = MindChatCoreHandle::new();
        let temp = TempState::new("diag-quarantine");
        std::fs::write(temp.path(), b"this is not json").expect("write corrupt file");
        assert!(core.load_state(temp.path().to_string_lossy().into_owned()).is_err());

        let report = core.diagnostics_report();
        assert!(
            report.state_quarantined,
            "a failed load must be flagged so the UI can show the quarantine notice"
        );
        assert_eq!(report.state_path.as_deref(), Some(temp.path().to_string_lossy().as_ref()));
        assert!(report.state_last_loaded_at_epoch_ms.is_some());
        assert_eq!(report.account_count, 0, "no snapshot was restored");
    }

    #[test]
    fn diagnostics_report_records_unsupported_version_on_quarantine() {
        let core = MindChatCoreHandle::new();
        let temp = TempState::new("diag-version");
        let json = r#"{
            "schema_version": 99,
            "snapshot": {
                "accounts": [],
                "contacts": [],
                "conversations": [],
                "messages": [],
                "reactions": []
            }
        }"#;
        std::fs::write(temp.path(), json).expect("write versioned file");
        assert!(core.load_state(temp.path().to_string_lossy().into_owned()).is_err());

        let report = core.diagnostics_report();
        assert!(report.state_quarantined);
        assert_eq!(
            report.state_schema_version,
            Some(99),
            "the refused schema version is the useful diagnostics signal"
        );
    }
}
