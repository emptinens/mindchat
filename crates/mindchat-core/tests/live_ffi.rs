//! Live-server validation through the public FFI boundary.
//!
//! The Android app consumes `MindChatCoreHandle` exclusively (via `UniFFI`).
//! These tests drive that exact public interface over the real network to
//! prove the account state machine reaches terminal states and can never
//! freeze on `Connecting` (the "always Connecting" bug), that a failed
//! session can be retried, and that an in-flight connect can be cancelled.
//!
//! Skipped unless `MINDCHAT_LIVE_TESTS=1` is set, like `live_login`.

use mindchat_core::ffi::{FfiConnectionState, FfiProxyConfig, FfiProxyKind, MindChatCoreHandle};
use std::io::{Read, Write};
use std::time::{Duration, Instant};

fn live_enabled() -> bool {
    std::env::var("MINDCHAT_LIVE_TESTS").is_ok()
}

fn live_server() -> String {
    std::env::var("MINDCHAT_LIVE_SERVER").unwrap_or_else(|_| "jabber.ru".to_owned())
}

fn account_state(
    handle: &MindChatCoreHandle,
    account_id: u64,
) -> Option<(FfiConnectionState, Option<String>)> {
    let snapshot = handle.snapshot().expect("snapshot must not fail");
    snapshot
        .accounts
        .into_iter()
        .find(|account| account.id == account_id)
        .map(|account| (account.connection_state, account.connection_error))
}

/// Pumps transport events exactly like `MindChatGateway.pollTransport` until
/// the account leaves `Connecting`, then returns the terminal state.
fn pump_until_terminal(
    handle: &MindChatCoreHandle,
    account_id: u64,
    deadline: Instant,
) -> (FfiConnectionState, Option<String>) {
    loop {
        let _ = handle.poll_transport_events(128).expect("poll_transport_events must not fail");
        let _ = handle.drain_events().expect("drain_events must not fail");
        match account_state(handle, account_id) {
            Some((FfiConnectionState::Connecting, _)) | None => {
                assert!(Instant::now() < deadline, "account stuck on Connecting past the bound");
                std::thread::sleep(Duration::from_millis(150));
            }
            Some((state, error)) => return (state, error),
        }
    }
}

/// Minimal blocking SOCKS5 server (RFC 1928) that tunnels the CONNECT request
/// to whatever host:port the client asks for. Returns the listening port.
///
/// The client sends `jabber.ru` as a domain (ATYP 0x03) because `MindChat`'s
/// proxy mode resolves DNS only at the proxy, which this loopback server
/// emulates by resolving the domain itself.
fn start_socks5_tunnel() -> u16 {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind local SOCKS5 tunnel");
    let port = listener.local_addr().expect("local address").port();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(client) = stream else { continue };
            std::thread::spawn(move || {
                let _ = serve_socks5_tunnel(client);
            });
        }
    });
    port
}

fn serve_socks5_tunnel(mut client: std::net::TcpStream) -> std::io::Result<()> {
    let mut greeting = [0u8; 2];
    client.read_exact(&mut greeting)?;
    if greeting[0] != 0x05 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "not SOCKS5"));
    }
    let mut methods = vec![0u8; usize::from(greeting[1])];
    client.read_exact(&mut methods)?;
    client.write_all(&[0x05, 0x00])?; // no authentication

    let mut header = [0u8; 4];
    client.read_exact(&mut header)?;
    let host = match header[3] {
        0x01 => {
            let mut octets = [0u8; 4];
            client.read_exact(&mut octets)?;
            std::net::Ipv4Addr::from(octets).to_string()
        }
        0x04 => {
            let mut octets = [0u8; 16];
            client.read_exact(&mut octets)?;
            std::net::Ipv6Addr::from(octets).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            client.read_exact(&mut len)?;
            let mut bytes = vec![0u8; usize::from(len[0])];
            client.read_exact(&mut bytes)?;
            String::from_utf8_lossy(&bytes).into_owned()
        }
        atyp => {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("unsupported address type {atyp}"),
            ));
        }
    };
    let mut port_bytes = [0u8; 2];
    client.read_exact(&mut port_bytes)?;
    let port = u16::from_be_bytes(port_bytes);

    // The "proxy" resolves the target domain itself and opens the tunnel.
    let upstream = std::net::TcpStream::connect((host.as_str(), port))?;
    client.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])?;
    relay(client, upstream)
}

/// Minimal blocking HTTP CONNECT server (RFC 7231 §4.3.6) that tunnels the
/// requested authority. Returns the listening port.
fn start_http_connect_tunnel() -> u16 {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind local HTTP tunnel");
    let port = listener.local_addr().expect("local address").port();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(client) = stream else { continue };
            std::thread::spawn(move || {
                let _ = serve_http_connect_tunnel(client);
            });
        }
    });
    port
}

fn serve_http_connect_tunnel(mut client: std::net::TcpStream) -> std::io::Result<()> {
    let mut request = Vec::new();
    loop {
        let mut byte = [0u8; 1];
        let read = client.read(&mut byte)?;
        if read == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "connection closed before the CONNECT request completed",
            ));
        }
        request.push(byte[0]);
        if request.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    let text = String::from_utf8_lossy(&request);
    let status_line = text.lines().next().unwrap_or_default();
    let mut parts = status_line.split_whitespace();
    if parts.next() != Some("CONNECT") {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "expected a CONNECT request",
        ));
    }
    let authority = parts
        .next()
        .ok_or_else(|| std::io::Error::new(std::io::ErrorKind::InvalidData, "missing authority"))?;
    let (host, port) = authority.rsplit_once(':').ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::InvalidData, "authority has no port")
    })?;
    let port = port
        .parse::<u16>()
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidData, "invalid port"))?;

    let upstream = std::net::TcpStream::connect((host, port))?;
    client.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n")?;
    relay(client, upstream)
}

/// Bidirectionally copies two blocking streams until either side closes.
fn relay(mut a: std::net::TcpStream, mut b: std::net::TcpStream) -> std::io::Result<()> {
    let mut a_copy = a.try_clone()?;
    let mut b_copy = b.try_clone()?;
    let a_to_b = std::thread::spawn(move || std::io::copy(&mut a, &mut b));
    let b_to_a = std::thread::spawn(move || std::io::copy(&mut b_copy, &mut a_copy));
    let _ = a_to_b.join();
    let _ = b_to_a.join();
    Ok(())
}

/// Drives one full connect through a loopback proxy tunnel and asserts the
/// account reaches a terminal `Failed` state (never stuck on `Connecting`).
fn assert_proxy_connect_reaches_terminal_failure(proxy: FfiProxyConfig) {
    let server = live_server();
    let jid = format!("mindchat-ffi-proxy-{}@{server}", std::process::id());
    let handle = MindChatCoreHandle::new();
    let account_id = handle
        .add_account(jid.clone(), server.clone(), "live proxy ffi".to_owned())
        .expect("add_account must register the account");
    handle
        .connect_account_with_proxy(
            account_id,
            "definitely-wrong-password".to_owned(),
            Some(proxy),
            None,
        )
        .expect("connect_account_with_proxy must start the worker");

    let started = Instant::now();
    let (state, error) =
        pump_until_terminal(&handle, account_id, started + Duration::from_secs(35));
    assert_eq!(state, FfiConnectionState::Failed, "bogus credentials must fail through the proxy");
    assert!(
        error.as_deref().is_some_and(|detail| !detail.is_empty()),
        "proxy failures must carry a UI-safe detail"
    );
}

#[test]
fn live_ffi_connect_through_local_socks5_tunnel_reaches_terminal_state() {
    if !live_enabled() {
        return;
    }
    let port = start_socks5_tunnel();
    assert_proxy_connect_reaches_terminal_failure(FfiProxyConfig {
        host: "127.0.0.1".to_owned(),
        port,
        kind: FfiProxyKind::Socks5,
    });
}

#[test]
fn live_ffi_connect_through_local_http_connect_tunnel_reaches_terminal_state() {
    if !live_enabled() {
        return;
    }
    let port = start_http_connect_tunnel();
    assert_proxy_connect_reaches_terminal_failure(FfiProxyConfig {
        host: "127.0.0.1".to_owned(),
        port,
        kind: FfiProxyKind::HttpConnect,
    });
}

#[test]
fn live_ffi_connect_reaches_terminal_state_not_stuck_connecting() {
    if !live_enabled() {
        return;
    }
    let server = live_server();
    let jid = format!("mindchat-ffi-{}@{server}", std::process::id());
    let handle = MindChatCoreHandle::new();

    // Exactly what MindChatGateway.kt does: add the account, start the
    // session with the password, then poll + drain until the snapshot moves.
    let account_id = handle
        .add_account(jid.clone(), server.clone(), "live ffi".to_owned())
        .expect("add_account must register the account");
    handle
        .connect_account(account_id, "definitely-wrong-password".to_owned())
        .expect("connect_account must start the worker");

    let started = Instant::now();
    let (state, error) =
        pump_until_terminal(&handle, account_id, started + Duration::from_secs(35));
    // Bogus credentials on a reachable server must end in a terminal Failed
    // state with a UI-safe detail, not Connecting.
    assert_eq!(state, FfiConnectionState::Failed, "bogus credentials must fail authentication");
    assert!(
        error.as_deref().is_some_and(|detail| !detail.is_empty()),
        "auth failures must carry a UI-safe detail"
    );

    // A failed session must be retryable: a fresh connect starts a new worker
    // and again reaches a terminal state instead of resurrecting the old one.
    handle
        .connect_account(account_id, "still-wrong-password".to_owned())
        .expect("retry connect must start a fresh worker");
    let started = Instant::now();
    let (state, _) = pump_until_terminal(&handle, account_id, started + Duration::from_secs(35));
    assert_eq!(state, FfiConnectionState::Failed, "retry must fail identically");
}

#[test]
fn live_ffi_disconnect_cancels_an_in_flight_connect() {
    if !live_enabled() {
        return;
    }
    let handle = MindChatCoreHandle::new();
    // Explicit non-routable IP: resolution is immediate and the TCP connect
    // hangs at the OS level, leaving the account stuck on Connecting until
    // either the 30 s connect bound or the user cancels.
    let account_id = handle
        .add_account(
            "blackhole@10.255.255.1".to_owned(),
            "10.255.255.1".to_owned(),
            "live cancel".to_owned(),
        )
        .expect("add_account must register the account");
    handle
        .connect_account(account_id, "irrelevant".to_owned())
        .expect("connect_account must start the worker");
    let (state, _) = account_state(&handle, account_id).expect("account must exist");
    assert_eq!(state, FfiConnectionState::Connecting);

    // The gateway's Cancel path: disconnect while the worker is mid-connect
    // must complete promptly and project the account as Offline.
    let started = Instant::now();
    handle.disconnect_account(account_id).expect("disconnect during Connecting must succeed");
    let elapsed = started.elapsed();
    assert!(
        elapsed <= Duration::from_secs(5),
        "cancel during Connecting took {elapsed:?}, exceeding the 5 s bound"
    );
    let (state, _) = account_state(&handle, account_id).expect("account must still exist");
    assert_eq!(state, FfiConnectionState::Offline);
}
