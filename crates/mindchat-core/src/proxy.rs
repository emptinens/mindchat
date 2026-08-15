//! Proxy connect strategy contract and minimal SOCKS5 / HTTP CONNECT clients
//! (ROADMAP 6.2 P2-2 contract, implemented by 6.3).
//!
//! [`ConnectStrategy`] is the internal Rust seam an account's connect path
//! uses: `Direct` keeps the current resolution-and-dial behavior, while the
//! proxy variants tunnel through [`ProxyConfig`]. DNS for the XMPP server is
//! deliberately resolved only at the proxy: in SOCKS5 the hostname travels
//! verbatim as an ATYP `0x03` domain, in HTTP CONNECT it appears only in the
//! `Host` header, and the connect path never calls a local resolver for the
//! XMPP domain (SRV is skipped). Only the configured proxy hostname itself is
//! looked up, to open the TCP connection.
//!
//! Proxy credentials are never persisted by the core: [`ProxyConfig`] carries
//! no password, and secrets follow the account-password hand-off pattern
//! (runtime-only, [`connect_via_proxy`] arguments).

use base64::Engine;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::str::FromStr;
use std::time::Duration;
#[cfg(feature = "xmpp-transport")]
use std::time::Instant;
#[cfg(feature = "xmpp-transport")]
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
#[cfg(feature = "xmpp-transport")]
use tokio::net::TcpStream;

/// How an account's XMPP connection is established.
///
/// A non-direct strategy never silently falls back to a direct connection:
/// the connect path rejects a proxy strategy without configuration as a
/// recoverable connection failure (see `xmpp::strategy_connect_failure`).
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

/// SOCKS5 (RFC 1928) version byte.
pub const SOCKS5_VERSION: u8 = 0x05;
/// SOCKS5 no-authentication method (RFC 1928 §3).
pub const SOCKS5_METHOD_NO_AUTH: u8 = 0x00;
/// SOCKS5 username/password method (RFC 1929).
pub const SOCKS5_METHOD_USER_PASS: u8 = 0x02;
/// RFC 1929 authentication sub-negotiation version.
pub const SOCKS5_AUTH_VERSION: u8 = 0x01;
/// SOCKS5 CONNECT command.
pub const SOCKS5_CMD_CONNECT: u8 = 0x01;
/// SOCKS5 IPv4 address type.
pub const SOCKS5_ATYP_IPV4: u8 = 0x01;
/// SOCKS5 domain name address type (resolved by the proxy).
pub const SOCKS5_ATYP_DOMAIN: u8 = 0x03;
/// SOCKS5 IPv6 address type.
pub const SOCKS5_ATYP_IPV6: u8 = 0x04;
/// SOCKS5 successful CONNECT reply code.
pub const SOCKS5_REP_SUCCEEDED: u8 = 0x00;
/// Direct-TLS default tunnel port used when an account server has no explicit
/// port. The vendored `PreconnectedServerConnector` always runs direct TLS,
/// so the default mirrors the direct-TLS endpoint (`xmpps`/5223) rather than
/// the StartTLS port 5222.
pub const PROXY_DEFAULT_TLS_PORT: u16 = 5223;
/// Upper bound for one HTTP CONNECT response header block, guarding against a
/// hostile proxy streaming an unbounded response.
pub const MAX_HTTP_RESPONSE_HEADER_BYTES: usize = 16 * 1024;

/// How the proxy tunnel is established. Persisted non-secret; the inverse of
/// the [`ConnectStrategy`] proxy variants.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
pub enum ProxyKind {
    /// SOCKS5 (RFC 1928) with optional RFC 1929 credentials.
    #[default]
    Socks5,
    /// HTTP CONNECT (RFC 7231 §4.3.6) with optional Basic auth.
    HttpConnect,
}

impl ProxyKind {
    /// Stable string form for display and diagnostics.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Socks5 => "socks5",
            Self::HttpConnect => "http_connect",
        }
    }
}

impl fmt::Display for ProxyKind {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl From<ProxyKind> for ConnectStrategy {
    fn from(kind: ProxyKind) -> Self {
        match kind {
            ProxyKind::Socks5 => ConnectStrategy::Socks5,
            ProxyKind::HttpConnect => ConnectStrategy::HttpConnect,
        }
    }
}

/// Non-secret proxy configuration stored on an [`crate::Account`].
///
/// Deliberately carries no password: credentials are supplied at connect or
/// probe time and are never persisted by the core.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct ProxyConfig {
    /// Proxy hostname or IP literal. Only this host is resolved locally.
    pub host: String,
    /// Proxy TCP port.
    pub port: u16,
    /// Tunnel protocol.
    pub kind: ProxyKind,
}

impl ProxyConfig {
    /// Validates and builds a proxy configuration. Hosts must be non-empty
    /// and free of whitespace and line breaks (a raw host is placed inside
    /// the HTTP CONNECT request, so line breaks would allow header injection).
    pub fn new(host: impl Into<String>, port: u16, kind: ProxyKind) -> Result<Self, ProxyError> {
        let host = host.into();
        validate_host(&host)?;
        if port == 0 {
            return Err(ProxyError::InvalidConfig("a proxy port must not be zero".to_owned()));
        }
        Ok(Self { host, port, kind })
    }
}

/// The XMPP endpoint requested through the tunnel.
///
/// The hostname is sent verbatim into the tunnel (SOCKS5 ATYP `0x03` domain
/// or the HTTP `Host` header) and is never resolved by the local system.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProxyTarget {
    /// XMPP server hostname (never resolved locally in proxy mode).
    pub host: String,
    /// XMPP server port as seen by the proxy.
    pub port: u16,
}

impl ProxyTarget {
    /// Validates and builds a tunnel target.
    pub fn new(host: impl Into<String>, port: u16) -> Result<Self, ProxyError> {
        let host = host.into();
        validate_host(&host)?;
        if port == 0 {
            return Err(ProxyError::InvalidConfig("a tunnel port must not be zero".to_owned()));
        }
        Ok(Self { host, port })
    }

    /// Derives the tunnel target from an account server field.
    ///
    /// Proxy mode skips SRV and local resolution (DNS-leak protection): an
    /// explicit `host:port` (or `[v6]:port`) wins; otherwise the bare
    /// hostname is used verbatim with [`PROXY_DEFAULT_TLS_PORT`].
    pub fn from_server(server: &str) -> Result<Self, ProxyError> {
        let server = server.trim();
        if server.is_empty() {
            return Err(ProxyError::InvalidConfig("the XMPP server is empty".to_owned()));
        }
        match split_host_and_port(server)? {
            Some((host, port)) => Self::new(host, port),
            None => Self::new(server, PROXY_DEFAULT_TLS_PORT),
        }
    }
}

/// Typed failure for proxy handshakes. Renders UI-safe details with no
/// secret material (credentials never appear in messages).
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProxyError {
    /// Underlying TCP or I/O failure.
    Io(String),
    /// Invalid proxy or tunnel configuration.
    InvalidConfig(String),
    /// SOCKS5 protocol violation or malformed packet.
    SocksProtocol(String),
    /// The SOCKS5 proxy rejected every offered authentication method (0xFF).
    SocksMethodRejected,
    /// RFC 1929 username/password authentication was refused.
    SocksAuthRejected,
    /// SOCKS5 CONNECT failed; carries the RFC 1928 reply code.
    SocksConnectFailed(u8),
    /// HTTP CONNECT response carried a non-200 status.
    HttpStatus { status: u16, reason: String },
    /// HTTP CONNECT response was malformed or closed early.
    HttpMalformed(String),
}

impl fmt::Display for ProxyError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(detail) => write!(formatter, "proxy I/O error: {detail}"),
            Self::InvalidConfig(detail) => {
                write!(formatter, "invalid proxy configuration: {detail}")
            }
            Self::SocksProtocol(detail) => write!(formatter, "SOCKS5 protocol error: {detail}"),
            Self::SocksMethodRejected => {
                formatter.write_str("the SOCKS5 proxy rejected every offered authentication method")
            }
            Self::SocksAuthRejected => {
                formatter.write_str("the SOCKS5 proxy refused the supplied credentials")
            }
            Self::SocksConnectFailed(reply) => {
                write!(formatter, "the SOCKS5 proxy refused the tunnel (reply code {reply})")
            }
            Self::HttpStatus { status, reason } => write!(
                formatter,
                "the HTTP proxy refused the tunnel (status {status}{}{})",
                if reason.is_empty() { "" } else { ": " },
                reason
            ),
            Self::HttpMalformed(detail) => {
                write!(formatter, "malformed HTTP proxy response: {detail}")
            }
        }
    }
}

impl std::error::Error for ProxyError {}

impl From<std::io::Error> for ProxyError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error.to_string())
    }
}

/// Outcome of a proxy probe, safe to cross the FFI boundary.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ProxyProbe {
    /// Whether the tunnel handshake completed successfully.
    pub ok: bool,
    /// Wall-clock time of the probe in milliseconds.
    pub latency_ms: u64,
    /// UI-safe failure detail when `ok` is false.
    pub error: Option<String>,
}

/// Maps a probe result and its measured latency into a [`ProxyProbe`]. Pure,
/// so the FFI mapping is unit-testable without a network.
#[must_use]
pub fn probe_from_result(result: Result<(), ProxyError>, latency: Duration) -> ProxyProbe {
    let latency_ms = u64::try_from(latency.as_millis()).unwrap_or(u64::MAX);
    match result {
        Ok(()) => ProxyProbe { ok: true, latency_ms, error: None },
        Err(error) => ProxyProbe { ok: false, latency_ms, error: Some(error.to_string()) },
    }
}

/// Builds the SOCKS5 greeting (RFC 1928 §3): version, method count, methods.
#[must_use]
pub fn socks5_greeting(methods: &[u8]) -> Vec<u8> {
    // Methods beyond 255 are truncated defensively; the negotiation would
    // reject them anyway.
    let count = u8::try_from(methods.len()).unwrap_or(255);
    let mut greeting = Vec::with_capacity(methods.len() + 2);
    greeting.push(SOCKS5_VERSION);
    greeting.push(count);
    greeting.extend(methods.iter().take(usize::from(count)));
    greeting
}

/// Parses the SOCKS5 method reply (RFC 1928 §3): `[version, method]`.
///
/// Returns the selected method, or [`ProxyError::SocksMethodRejected`] when
/// the proxy selected `0xFF`.
pub fn parse_socks5_method_reply(bytes: &[u8]) -> Result<u8, ProxyError> {
    if bytes.len() != 2 {
        return Err(ProxyError::SocksProtocol(
            "the SOCKS5 method reply must be exactly 2 bytes".to_owned(),
        ));
    }
    if bytes[0] != SOCKS5_VERSION {
        return Err(ProxyError::SocksProtocol(format!(
            "the SOCKS5 proxy replied with version {}, expected 5",
            bytes[0]
        )));
    }
    match bytes[1] {
        0xFF => Err(ProxyError::SocksMethodRejected),
        method => Ok(method),
    }
}

/// Builds the RFC 1929 username/password sub-negotiation request.
#[must_use]
pub fn socks5_auth_request(username: &str, password: &str) -> Vec<u8> {
    let username_bytes = username.as_bytes();
    let password_bytes = password.as_bytes();
    let username_len = u8::try_from(username_bytes.len()).unwrap_or(255);
    let password_len = u8::try_from(password_bytes.len()).unwrap_or(255);
    let mut request = Vec::with_capacity(3 + username_bytes.len() + password_bytes.len());
    request.push(SOCKS5_AUTH_VERSION);
    request.push(username_len);
    request.extend(username_bytes.iter().take(usize::from(username_len)));
    request.push(password_len);
    request.extend(password_bytes.iter().take(usize::from(password_len)));
    request
}

/// Parses the RFC 1929 authentication reply: `[version, status]`.
pub fn parse_socks5_auth_reply(bytes: &[u8]) -> Result<(), ProxyError> {
    if bytes.len() != 2 {
        return Err(ProxyError::SocksProtocol(
            "the SOCKS5 authentication reply must be exactly 2 bytes".to_owned(),
        ));
    }
    if bytes[0] != SOCKS5_AUTH_VERSION {
        return Err(ProxyError::SocksProtocol(format!(
            "the SOCKS5 proxy replied with authentication version {}, expected 1",
            bytes[0]
        )));
    }
    match bytes[1] {
        0 => Ok(()),
        _ => Err(ProxyError::SocksAuthRejected),
    }
}

/// Builds a SOCKS5 CONNECT request (RFC 1928 §4) for a tunnel target.
///
/// Domain targets are encoded as ATYP `0x03` with the hostname bytes verbatim
/// (never resolved locally); IP literals use ATYP `0x01`/`0x04` without any
/// resolution.
pub fn socks5_connect_request(target: &ProxyTarget) -> Result<Vec<u8>, ProxyError> {
    let mut request = Vec::with_capacity(7 + target.host.len());
    request.push(SOCKS5_VERSION);
    request.push(SOCKS5_CMD_CONNECT);
    request.push(0x00); // reserved
    encode_address(&target.host, &mut request)?;
    request.extend_from_slice(&target.port.to_be_bytes());
    Ok(request)
}

/// Encodes a host as a SOCKS5 address: domain (ATYP 3) unless it is an IP
/// literal, which is encoded in binary without any lookup.
fn encode_address(host: &str, out: &mut Vec<u8>) -> Result<(), ProxyError> {
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        out.push(SOCKS5_ATYP_IPV4);
        out.extend_from_slice(&ip.octets());
        return Ok(());
    }
    if let Ok(ip) = host.parse::<Ipv6Addr>() {
        out.push(SOCKS5_ATYP_IPV6);
        out.extend_from_slice(&ip.octets());
        return Ok(());
    }
    let bytes = host.as_bytes();
    if bytes.is_empty() || bytes.len() > 255 {
        return Err(ProxyError::InvalidConfig(
            "a tunnel hostname must be between 1 and 255 bytes".to_owned(),
        ));
    }
    // DNS for the XMPP domain happens at the proxy: the hostname is sent
    // verbatim and never passed to a local resolver.
    out.push(SOCKS5_ATYP_DOMAIN);
    out.push(u8::try_from(bytes.len()).expect("bounded to 255"));
    out.extend_from_slice(bytes);
    Ok(())
}

/// Parses a SOCKS5 CONNECT reply (RFC 1928 §6), validating its framing.
pub fn parse_socks5_connect_reply(bytes: &[u8]) -> Result<(), ProxyError> {
    if bytes.len() < 4 {
        return Err(ProxyError::SocksProtocol(
            "the SOCKS5 connect reply is shorter than its 4-byte header".to_owned(),
        ));
    }
    if bytes[0] != SOCKS5_VERSION {
        return Err(ProxyError::SocksProtocol(format!(
            "the SOCKS5 proxy replied with version {}, expected 5",
            bytes[0]
        )));
    }
    if bytes[2] != 0x00 {
        return Err(ProxyError::SocksProtocol(
            "the reserved byte of the SOCKS5 connect reply must be zero".to_owned(),
        ));
    }
    let address_len = match bytes[3] {
        SOCKS5_ATYP_IPV4 => 4usize,
        SOCKS5_ATYP_IPV6 => 16,
        SOCKS5_ATYP_DOMAIN => {
            let Some(&len) = bytes.get(4) else {
                return Err(ProxyError::SocksProtocol(
                    "the SOCKS5 connect reply is missing its domain length byte".to_owned(),
                ));
            };
            usize::from(len) + 1
        }
        atyp => {
            return Err(ProxyError::SocksProtocol(format!(
                "unsupported address type {atyp} in the SOCKS5 connect reply"
            )));
        }
    };
    let expected = 4 + address_len + 2;
    if bytes.len() != expected {
        return Err(ProxyError::SocksProtocol(format!(
            "the SOCKS5 connect reply is {} bytes, expected {expected}",
            bytes.len()
        )));
    }
    match bytes[1] {
        SOCKS5_REP_SUCCEEDED => Ok(()),
        reply => Err(ProxyError::SocksConnectFailed(reply)),
    }
}

/// Builds an HTTP CONNECT request (RFC 7231 §4.3.6) for a tunnel target.
///
/// The XMPP hostname appears only in the request line and `Host` header;
/// resolution happens at the proxy. When `credentials` is supplied, a
/// `Proxy-Authorization: Basic` header (RFC 7617) is appended.
#[must_use]
pub fn http_connect_request(target: &ProxyTarget, credentials: Option<(&str, &str)>) -> String {
    let authority = format!("{}:{}", target.host, target.port);
    let mut request = format!("CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\n");
    if let Some((username, password)) = credentials {
        let token =
            base64::engine::general_purpose::STANDARD.encode(format!("{username}:{password}"));
        let header = format!("Proxy-Authorization: Basic {token}\r\n");
        request.push_str(&header);
    }
    request.push_str("\r\n");
    request
}

/// Parses an HTTP CONNECT response. Any status other than `200` is refused.
pub fn parse_http_connect_response(bytes: &[u8]) -> Result<(), ProxyError> {
    let text = std::str::from_utf8(bytes).map_err(|_| {
        ProxyError::HttpMalformed("the proxy response is not valid UTF-8".to_owned())
    })?;
    let status_line = text.split("\r\n").next().unwrap_or_default();
    let mut parts = status_line.split_whitespace();
    let version = parts.next().unwrap_or_default();
    if !version.starts_with("HTTP/") {
        return Err(ProxyError::HttpMalformed(format!(
            "the proxy response status line does not start with HTTP/: {status_line:?}"
        )));
    }
    let status = parts.next().and_then(|value| value.parse::<u16>().ok()).ok_or_else(|| {
        ProxyError::HttpMalformed(format!(
            "the proxy response has no parseable status code: {status_line:?}"
        ))
    })?;
    if status == 200 {
        return Ok(());
    }
    let reason = parts.collect::<Vec<_>>().join(" ");
    Err(ProxyError::HttpStatus { status, reason })
}

/// Opens a tunnel through `config` to `target` and returns the buffered
/// stream, ready for the TLS layer.
///
/// Only the configured proxy hostname is resolved locally (to open the TCP
/// connection); `target` is never looked up. The returned [`BufReader`]
/// retains any bytes the HTTP response parser over-read, so the tunneled
/// stream is not corrupted.
#[cfg(feature = "xmpp-transport")]
pub async fn connect_via_proxy(
    config: &ProxyConfig,
    proxy_password: Option<&str>,
    target: &ProxyTarget,
) -> Result<BufReader<TcpStream>, ProxyError> {
    let stream = TcpStream::connect((config.host.as_str(), config.port)).await?;
    let _ = stream.set_nodelay(true);
    match config.kind {
        ProxyKind::Socks5 => socks5_handshake(stream, proxy_password, target).await,
        ProxyKind::HttpConnect => {
            let reader = BufReader::new(stream);
            http_connect_handshake(reader, proxy_password, target).await
        }
    }
}

/// Runs the RFC 1928/1929 exchange on an established TCP connection.
#[cfg(feature = "xmpp-transport")]
async fn socks5_handshake(
    mut stream: TcpStream,
    proxy_password: Option<&str>,
    target: &ProxyTarget,
) -> Result<BufReader<TcpStream>, ProxyError> {
    let mut methods = vec![SOCKS5_METHOD_NO_AUTH];
    if proxy_password.is_some() {
        methods.push(SOCKS5_METHOD_USER_PASS);
    }
    stream.write_all(&socks5_greeting(&methods)).await?;
    let mut method_reply = [0u8; 2];
    stream.read_exact(&mut method_reply).await?;
    match parse_socks5_method_reply(&method_reply)? {
        SOCKS5_METHOD_NO_AUTH => {}
        SOCKS5_METHOD_USER_PASS => {
            let Some(password) = proxy_password else {
                return Err(ProxyError::SocksProtocol(
                    "the proxy requires credentials but none were supplied".to_owned(),
                ));
            };
            // RFC 1929 requires a username field; MindChat models a single
            // proxy secret, so the username is empty.
            stream.write_all(&socks5_auth_request("", password)).await?;
            let mut auth_reply = [0u8; 2];
            stream.read_exact(&mut auth_reply).await?;
            parse_socks5_auth_reply(&auth_reply)?;
        }
        method => {
            return Err(ProxyError::SocksProtocol(format!(
                "the proxy selected unsupported authentication method {method}"
            )));
        }
    }
    stream.write_all(&socks5_connect_request(target)?).await?;
    let reply = read_socks5_connect_reply(&mut stream).await?;
    parse_socks5_connect_reply(&reply)?;
    Ok(BufReader::new(stream))
}

/// Reads a SOCKS5 CONNECT reply whose address length depends on its type.
#[cfg(feature = "xmpp-transport")]
async fn read_socks5_connect_reply(stream: &mut TcpStream) -> Result<Vec<u8>, ProxyError> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    let address_bytes = match header[3] {
        SOCKS5_ATYP_IPV4 => 4usize,
        SOCKS5_ATYP_IPV6 => 16,
        SOCKS5_ATYP_DOMAIN => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            usize::from(len[0])
        }
        atyp => {
            return Err(ProxyError::SocksProtocol(format!(
                "unsupported address type {atyp} in the SOCKS5 connect reply"
            )));
        }
    };
    let mut tail = vec![0u8; address_bytes + 2];
    stream.read_exact(&mut tail).await?;
    let mut reply = Vec::with_capacity(4 + address_bytes + 3);
    reply.extend_from_slice(&header);
    if header[3] == SOCKS5_ATYP_DOMAIN {
        reply.push(u8::try_from(address_bytes).expect("a domain length is at most 255"));
    }
    reply.extend_from_slice(&tail);
    Ok(reply)
}

/// Runs the HTTP CONNECT exchange on an established, buffered connection.
#[cfg(feature = "xmpp-transport")]
async fn http_connect_handshake(
    mut reader: BufReader<TcpStream>,
    proxy_password: Option<&str>,
    target: &ProxyTarget,
) -> Result<BufReader<TcpStream>, ProxyError> {
    let credentials = proxy_password.map(|password| ("", password));
    let request = http_connect_request(target, credentials);
    reader.get_mut().write_all(request.as_bytes()).await?;
    let headers = read_http_response_headers(&mut reader).await?;
    parse_http_connect_response(&headers)?;
    Ok(reader)
}

/// Reads an HTTP response header block up to the empty line. Bytes read
/// beyond the terminator stay in the [`BufReader`] for the tunneled stream.
#[cfg(feature = "xmpp-transport")]
async fn read_http_response_headers(
    reader: &mut BufReader<TcpStream>,
) -> Result<Vec<u8>, ProxyError> {
    let mut headers = Vec::new();
    loop {
        let mut line = Vec::new();
        let read = reader.read_until(b'\n', &mut line).await?;
        if read == 0 {
            return Err(ProxyError::HttpMalformed(
                "the proxy closed the connection before the response completed".to_owned(),
            ));
        }
        headers.extend_from_slice(&line);
        if headers.len() > MAX_HTTP_RESPONSE_HEADER_BYTES {
            return Err(ProxyError::HttpMalformed(
                "the proxy response headers exceeded the size bound".to_owned(),
            ));
        }
        if line == b"\r\n" || line == b"\n" {
            return Ok(headers);
        }
    }
}

/// Bounded end-to-end probe of a proxy configuration.
///
/// The probe opens the tunnel to the proxy's own address (a self-CONNECT), so
/// it exercises the full handshake without an external target and without any
/// local XMPP resolution. `latency_ms` is measured wall-clock from TCP open
/// through handshake completion. The whole probe is capped at 15 seconds.
#[must_use]
#[cfg(feature = "xmpp-transport")]
pub fn proxy_probe(config: &ProxyConfig, proxy_password: Option<&str>) -> ProxyProbe {
    const PROBE_TIMEOUT: Duration = Duration::from_secs(15);
    let started = Instant::now();
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(error) => {
            return probe_from_result(Err(ProxyError::Io(error.to_string())), started.elapsed());
        }
    };
    let target = match ProxyTarget::new(config.host.clone(), config.port) {
        Ok(target) => target,
        Err(error) => return probe_from_result(Err(error), started.elapsed()),
    };
    let outcome = runtime.block_on(async {
        tokio::time::timeout(PROBE_TIMEOUT, connect_via_proxy(config, proxy_password, &target))
            .await
    });
    let result = match outcome {
        Ok(Ok(_stream)) => Ok(()),
        Ok(Err(error)) => Err(error),
        Err(_) => Err(ProxyError::Io("the proxy probe timed out".to_owned())),
    };
    probe_from_result(result, started.elapsed())
}

/// Splits a server field into an optional explicit `(host, port)`.
fn split_host_and_port(server: &str) -> Result<Option<(String, u16)>, ProxyError> {
    if let Some(rest) = server.strip_prefix('[') {
        let Some((host, suffix)) = rest.split_once(']') else {
            return Err(ProxyError::InvalidConfig("invalid bracketed server".to_owned()));
        };
        if suffix.is_empty() {
            return Ok(None);
        }
        let Some(port) = suffix.strip_prefix(':') else {
            return Err(ProxyError::InvalidConfig("invalid server port".to_owned()));
        };
        let port = port
            .parse::<u16>()
            .map_err(|_| ProxyError::InvalidConfig("invalid server port".to_owned()))?;
        return Ok(Some((host.to_owned(), port)));
    }
    if server.matches(':').count() == 1
        && let Some((host, port)) = server.rsplit_once(':')
    {
        let port = port
            .parse::<u16>()
            .map_err(|_| ProxyError::InvalidConfig("invalid server port".to_owned()))?;
        return Ok(Some((host.to_owned(), port)));
    }
    Ok(None)
}

/// Rejects hosts that would corrupt a request line or allow header injection.
fn validate_host(host: &str) -> Result<(), ProxyError> {
    let host = host.trim();
    if host.is_empty() {
        return Err(ProxyError::InvalidConfig("a proxy or tunnel host is required".to_owned()));
    }
    if host.chars().any(char::is_whitespace) || host.contains(['\r', '\n']) {
        return Err(ProxyError::InvalidConfig(
            "a proxy or tunnel host must not contain whitespace or line breaks".to_owned(),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn target(host: &str, port: u16) -> ProxyTarget {
        ProxyTarget::new(host, port).expect("valid target")
    }

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

    #[test]
    fn proxy_kind_maps_to_its_connect_strategy() {
        assert_eq!(ConnectStrategy::from(ProxyKind::Socks5), ConnectStrategy::Socks5);
        assert_eq!(ConnectStrategy::from(ProxyKind::HttpConnect), ConnectStrategy::HttpConnect);
        assert_eq!(ProxyKind::Socks5.as_str(), "socks5");
        assert_eq!(ProxyKind::HttpConnect.as_str(), "http_connect");
    }

    #[test]
    fn proxy_config_validates_host_and_port() {
        assert!(ProxyConfig::new("127.0.0.1", 1080, ProxyKind::Socks5).is_ok());
        assert!(ProxyConfig::new("proxy.example.org", 8080, ProxyKind::HttpConnect).is_ok());
        assert!(ProxyConfig::new("", 1080, ProxyKind::Socks5).is_err());
        assert!(ProxyConfig::new("host", 0, ProxyKind::Socks5).is_err());
        assert!(ProxyConfig::new("host with space", 1080, ProxyKind::Socks5).is_err());
        assert!(
            ProxyConfig::new("host\r\nInjected: yes", 1080, ProxyKind::Socks5).is_err(),
            "CR/LF in a host would allow HTTP header injection"
        );
    }

    #[test]
    fn socks5_greeting_builds_version_method_count_and_methods() {
        assert_eq!(socks5_greeting(&[0x00]), vec![0x05, 0x01, 0x00]);
        assert_eq!(socks5_greeting(&[0x00, 0x02]), vec![0x05, 0x02, 0x00, 0x02]);
    }

    #[test]
    fn socks5_method_reply_parses_selection_and_rejection() {
        assert_eq!(parse_socks5_method_reply(&[0x05, 0x00]), Ok(0x00));
        assert_eq!(parse_socks5_method_reply(&[0x05, 0x02]), Ok(0x02));
        assert_eq!(parse_socks5_method_reply(&[0x05, 0xFF]), Err(ProxyError::SocksMethodRejected));
        assert!(matches!(parse_socks5_method_reply(&[0x05]), Err(ProxyError::SocksProtocol(_))));
        assert!(matches!(
            parse_socks5_method_reply(&[0x04, 0x00]),
            Err(ProxyError::SocksProtocol(_))
        ));
    }

    #[test]
    fn socks5_auth_request_encodes_rfc1929_fields() {
        assert_eq!(
            socks5_auth_request("user", "pass"),
            vec![0x01, 0x04, b'u', b's', b'e', b'r', 0x04, b'p', b'a', b's', b's']
        );
        assert_eq!(
            socks5_auth_request("", "s3cret"),
            vec![0x01, 0x00, 0x06, b's', b'3', b'c', b'r', b'e', b't']
        );
    }

    #[test]
    fn socks5_auth_reply_parses_acceptance_and_refusal() {
        assert_eq!(parse_socks5_auth_reply(&[0x01, 0x00]), Ok(()));
        assert_eq!(parse_socks5_auth_reply(&[0x01, 0x01]), Err(ProxyError::SocksAuthRejected));
        assert!(matches!(parse_socks5_auth_reply(&[0x01]), Err(ProxyError::SocksProtocol(_))));
        assert!(matches!(
            parse_socks5_auth_reply(&[0x02, 0x00]),
            Err(ProxyError::SocksProtocol(_))
        ));
    }

    #[test]
    fn socks5_connect_request_sends_the_domain_to_the_proxy_verbatim() {
        // The XMPP hostname must travel inside the CONNECT packet as an ATYP
        // 0x03 domain (RFC 1928 §4): the proxy resolves it, never the client.
        let request = socks5_connect_request(&target("jabber.ru", 5223)).expect("valid request");
        assert_eq!(
            request,
            vec![
                0x05, 0x01, 0x00, 0x03, 0x09, b'j', b'a', b'b', b'b', b'e', b'r', b'.', b'r', b'u',
                0x14, 0x67,
            ]
        );
        let needle = b"jabber.ru";
        assert!(
            request.windows(needle.len()).any(|window| window == needle),
            "the hostname bytes must be present verbatim (DNS happens at the proxy)"
        );
    }

    #[test]
    fn socks5_connect_request_encodes_ip_literals_without_resolution() {
        let v4 = socks5_connect_request(&target("127.0.0.1", 5222)).expect("valid IPv4 target");
        assert_eq!(v4, vec![0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, 0x14, 0x66]);
        let v6 = socks5_connect_request(&target("::1", 5222)).expect("valid IPv6 target");
        let mut expected = vec![0x05, 0x01, 0x00, 0x04];
        expected.extend([0u8; 15]);
        expected.push(1);
        expected.extend_from_slice(&5222u16.to_be_bytes());
        assert_eq!(v6, expected);
    }

    #[test]
    fn socks5_connect_reply_parses_success_failure_and_framing() {
        assert_eq!(
            parse_socks5_connect_reply(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x00, 0x50]),
            Ok(())
        );
        assert_eq!(
            parse_socks5_connect_reply(&[0x05, 0x00, 0x00, 0x03, 0x02, b'a', b'b', 0x00, 0x50]),
            Ok(())
        );
        assert_eq!(
            parse_socks5_connect_reply(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]),
            Err(ProxyError::SocksConnectFailed(0x05))
        );
        assert!(matches!(
            parse_socks5_connect_reply(&[0x05, 0x00]),
            Err(ProxyError::SocksProtocol(_))
        ));
        assert!(matches!(
            parse_socks5_connect_reply(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0]),
            Err(ProxyError::SocksProtocol(_))
        ));
        assert!(matches!(
            parse_socks5_connect_reply(&[0x05, 0x00, 0x00, 0x02, 0, 0, 0, 0, 0, 0]),
            Err(ProxyError::SocksProtocol(_))
        ));
    }

    #[test]
    fn http_connect_request_builds_authority_and_host_header() {
        let request = http_connect_request(&target("jabber.ru", 5223), None);
        assert_eq!(request, "CONNECT jabber.ru:5223 HTTP/1.1\r\nHost: jabber.ru:5223\r\n\r\n");
    }

    #[test]
    fn http_connect_request_appends_basic_auth_from_rfc7617() {
        let request =
            http_connect_request(&target("proxy.example.org", 8080), Some(("", "s3cret")));
        let expected = "CONNECT proxy.example.org:8080 HTTP/1.1\r\n\
                        Host: proxy.example.org:8080\r\n\
                        Proxy-Authorization: Basic OnMzY3JldA==\r\n\r\n";
        assert_eq!(request, expected);
    }

    #[test]
    fn http_connect_response_parses_200_and_rejects_other_statuses() {
        assert_eq!(
            parse_http_connect_response(b"HTTP/1.1 200 Connection established\r\n\r\n"),
            Ok(())
        );
        assert_eq!(parse_http_connect_response(b"HTTP/1.0 200 OK\r\n\r\n"), Ok(()));
        assert_eq!(
            parse_http_connect_response(b"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n"),
            Err(ProxyError::HttpStatus {
                status: 407,
                reason: "Proxy Authentication Required".to_owned(),
            })
        );
        assert!(matches!(
            parse_http_connect_response(b"garbage\r\n\r\n"),
            Err(ProxyError::HttpMalformed(_))
        ));
        assert!(matches!(
            parse_http_connect_response(b"HTTP/1.1\r\n\r\n"),
            Err(ProxyError::HttpMalformed(_))
        ));
    }

    #[test]
    fn proxy_target_from_server_skips_srv_and_defaults_to_direct_tls_port() {
        assert_eq!(
            ProxyTarget::from_server("jabber.ru").expect("bare domain"),
            target("jabber.ru", PROXY_DEFAULT_TLS_PORT)
        );
        assert_eq!(
            ProxyTarget::from_server("jabber.ru:5222").expect("explicit port"),
            target("jabber.ru", 5222)
        );
        assert_eq!(
            ProxyTarget::from_server("[::1]:5222").expect("bracketed IPv6"),
            target("::1", 5222)
        );
        assert!(ProxyTarget::from_server("").is_err());
        assert!(ProxyTarget::from_server("jabber.ru:not-a-port").is_err());
    }

    #[test]
    fn proxy_mode_never_resolves_the_xmpp_hostname_locally() {
        // DNS-leak guard: the derived target keeps the bare hostname and
        // default port (never an IP, never an SRV answer), and both wire
        // formats embed the hostname verbatim for the proxy to resolve.
        let derived = ProxyTarget::from_server("jabber.ru").expect("derived target");
        assert_eq!(derived.host, "jabber.ru");
        assert!(derived.host.parse::<Ipv4Addr>().is_err());
        assert!(derived.host.parse::<Ipv6Addr>().is_err());

        let socks = socks5_connect_request(&derived).expect("SOCKS5 request");
        assert!(socks.contains(&(SOCKS5_ATYP_DOMAIN)));
        assert!(socks.windows(9).any(|w| w == b"jabber.ru"));

        let http = http_connect_request(&derived, None);
        assert!(http.starts_with("CONNECT jabber.ru:5223 HTTP/1.1"));
        assert!(http.contains("Host: jabber.ru:5223"));
    }

    #[test]
    fn probe_mapping_reports_latency_and_error() {
        let ok = probe_from_result(Ok(()), Duration::from_millis(42));
        assert!(ok.ok);
        assert_eq!(ok.latency_ms, 42);
        assert_eq!(ok.error, None);

        let failed =
            probe_from_result(Err(ProxyError::SocksAuthRejected), Duration::from_millis(7));
        assert!(!failed.ok);
        assert_eq!(failed.latency_ms, 7);
        assert!(
            failed.error.as_deref().is_some_and(|detail| detail.contains("credentials")),
            "probe errors must be UI-safe and descriptive"
        );
    }
}
