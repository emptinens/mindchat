//! Protocol adapter boundary.
//!
//! A production implementation speaks XMPP and converts protocol-specific
//! stanzas to these domain-safe requests and events. The core intentionally
//! depends on this trait rather than on a particular XMPP crate.

use crate::{
    AccountId, ContactPresence, ConversationId, ConversationKind, DeliveryState, MessageId,
    ProtocolCapability, RosterSubscription,
};
use std::collections::BTreeSet;
use std::fmt;

/// Password-like input that must not appear in diagnostic output.
#[derive(Clone, Eq, PartialEq)]
pub struct SecretString(String);

impl SecretString {
    /// Wraps a secret supplied by Android's credential flow.
    #[must_use]
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    /// Consumes the wrapper at the single hand-off point to the transport.
    #[must_use]
    pub fn into_inner(self) -> String {
        self.0
    }
}

impl fmt::Debug for SecretString {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("SecretString([redacted])")
    }
}

/// Account and connection data needed by an XMPP transport adapter.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ConnectionRequest {
    pub account_id: AccountId,
    pub jid: String,
    pub server: String,
    pub password: SecretString,
    /// Whether a mid-session network loss should be retried automatically
    /// (XEP-0198 resume). When `false` the transport exits immediately on a
    /// disconnect, exactly like the 0.1.7 behavior; a user-requested
    /// disconnect is immediate either way.
    pub auto_reconnect: bool,
    /// Proxy tunnel configuration (ROADMAP 6.3). `None` connects directly.
    /// Non-secret: host/port/kind only, never a password.
    pub proxy: Option<crate::ProxyConfig>,
    /// Proxy credentials for the handshake, handed over at connect time like
    /// the account password and never persisted or rendered.
    pub proxy_password: Option<SecretString>,
}

/// Outgoing message data after the domain core has validated it.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OutgoingMessage {
    pub account_id: AccountId,
    pub conversation_id: ConversationId,
    pub message_id: MessageId,
    pub kind: ConversationKind,
    pub recipient: String,
    pub body: String,
    pub in_reply_to: Option<MessageId>,
}

/// State change emitted by an XMPP transport implementation.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TransportEvent {
    Connected {
        account_id: AccountId,
        capabilities: BTreeSet<ProtocolCapability>,
    },
    Disconnected {
        account_id: AccountId,
        recoverable: bool,
        /// Human-readable failure reason for the UI, when the transport has one.
        detail: Option<String>,
        /// Typed disconnect cause (ROADMAP 6.5). Prose stays display-only;
        /// this is the only control-flow signal derived from a disconnect.
        kind: crate::DisconnectKind,
    },
    /// Capabilities received after XEP-0030 service discovery completes.
    CapabilitiesDiscovered {
        account_id: AccountId,
        capabilities: BTreeSet<ProtocolCapability>,
    },
    /// A roster item supplied by an XMPP roster result or push.
    RosterContactUpsert {
        account_id: AccountId,
        jid: String,
        display_name: String,
        subscription: RosterSubscription,
    },
    /// A roster item removed by an XMPP roster result or push.
    RosterContactRemoved {
        account_id: AccountId,
        jid: String,
    },
    /// A presence update for an existing roster contact.
    ContactPresenceUpdated {
        account_id: AccountId,
        jid: String,
        presence: ContactPresence,
        status: Option<String>,
    },
    IncomingText {
        account_id: AccountId,
        kind: ConversationKind,
        address: String,
        sender: String,
        body: String,
        received_at_epoch_ms: u64,
    },
    DeliveryUpdated {
        /// Account on which the receipt was received.
        account_id: AccountId,
        /// Bare JID of the sender that acknowledged the message.
        sender: String,
        message_id: MessageId,
        state: DeliveryState,
    },
}

/// Failure boundary for implementations of the XMPP transport adapter.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TransportError {
    AuthenticationFailed,
    ConnectionFailed(String),
    ProtocolViolation(String),
    Unsupported(String),
}

impl fmt::Display for TransportError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::AuthenticationFailed => formatter.write_str("authentication failed"),
            Self::ConnectionFailed(detail) => write!(formatter, "connection failed: {detail}"),
            Self::ProtocolViolation(detail) => write!(formatter, "protocol violation: {detail}"),
            Self::Unsupported(detail) => write!(formatter, "unsupported by transport: {detail}"),
        }
    }
}

impl std::error::Error for TransportError {}

/// Internal async-ready boundary for XMPP implementations.
///
/// Implementations may buffer work internally; a future Tokio adapter can
/// expose the same event-polling shape to the platform-neutral core.
pub trait XmppTransport {
    fn connect(&mut self, request: ConnectionRequest) -> Result<(), TransportError>;
    fn disconnect(&mut self, account_id: AccountId) -> Result<(), TransportError>;
    fn send(&mut self, message: OutgoingMessage) -> Result<(), TransportError>;
    fn next_event(&mut self) -> Result<Option<TransportEvent>, TransportError>;
}

#[cfg(test)]
mod tests {
    use super::SecretString;

    #[test]
    fn secret_debug_output_is_redacted() {
        let secret = SecretString::new("not-for-logs");
        assert_eq!(format!("{secret:?}"), "SecretString([redacted])");
    }
}
