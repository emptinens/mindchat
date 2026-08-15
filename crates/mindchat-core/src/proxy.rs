//! Proxy connect strategy contract (ROADMAP 6.2 P2-2, consumed by 6.3).
//!
//! This release defines the contract only. [`ConnectStrategy`] is the
//! internal Rust seam an account's connect path will use: `Direct` keeps the
//! current behavior, while the proxy variants reserve their place and fail
//! recoverably until 6.3 (party 4) implements the handshakes. Nothing here
//! is exposed through UniFFI yet; the FFI plumbing and the concrete SOCKS5 /
//! HTTP CONNECT clients land in 6.3 (`src/proxy.rs`, `ffi.rs`,
//! `SettingsScreen.kt`).

use std::fmt;
use std::str::FromStr;

/// How an account's XMPP connection is established.
///
/// A non-direct strategy never silently falls back to a direct connection:
/// the connect path rejects it as a recoverable connection failure (see
/// `xmpp::strategy_connect_failure`) until the handshake is implemented.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum ConnectStrategy {
    /// Connect directly to the resolved XMPP endpoints (current behavior).
    #[default]
    Direct,
    /// Establish the stream through an HTTP CONNECT proxy (6.3).
    HttpConnect,
    /// Establish the stream through a SOCKS5 proxy (6.3).
    Socks5,
}

impl ConnectStrategy {
    /// Stable string form, used by the future persistence and FFI mapping.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Direct => "direct",
            Self::HttpConnect => "http_connect",
            Self::Socks5 => "socks5",
        }
    }

    /// Parses the stable name produced by [`Self::as_str`].
    #[must_use]
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "direct" => Some(Self::Direct),
            "http_connect" => Some(Self::HttpConnect),
            "socks5" => Some(Self::Socks5),
            _ => None,
        }
    }
}

impl fmt::Display for ConnectStrategy {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

/// Parse failure for a [`ConnectStrategy`] name.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct UnknownConnectStrategy;

impl fmt::Display for UnknownConnectStrategy {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("unknown connect strategy")
    }
}

impl std::error::Error for UnknownConnectStrategy {}

impl FromStr for ConnectStrategy {
    type Err = UnknownConnectStrategy;

    fn from_str(name: &str) -> Result<Self, Self::Err> {
        Self::from_name(name).ok_or(UnknownConnectStrategy)
    }
}

#[cfg(test)]
mod tests {
    use super::ConnectStrategy;

    #[test]
    fn default_strategy_is_direct() {
        assert_eq!(ConnectStrategy::default(), ConnectStrategy::Direct);
    }

    #[test]
    fn stable_names_round_trip() {
        for strategy in
            [ConnectStrategy::Direct, ConnectStrategy::HttpConnect, ConnectStrategy::Socks5]
        {
            let name = strategy.as_str();
            assert_eq!(ConnectStrategy::from_name(name), Some(strategy));
            assert_eq!(name.parse::<ConnectStrategy>().expect("stable name parses"), strategy);
            assert_eq!(strategy.to_string(), name);
        }
    }

    #[test]
    fn unknown_names_do_not_parse() {
        assert_eq!(ConnectStrategy::from_name("direct "), None);
        assert_eq!(ConnectStrategy::from_name(""), None);
        assert_eq!(ConnectStrategy::from_name("socks"), None);
        assert_eq!(ConnectStrategy::from_name("Direct"), None);
        assert_eq!(
            "direct".parse::<ConnectStrategy>().expect("lowercase name parses"),
            ConnectStrategy::Direct
        );
        assert!("SOCKS5".parse::<ConnectStrategy>().is_err());
    }
}
