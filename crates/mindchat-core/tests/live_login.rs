//! Live-server validation for the XMPP transport.
//!
//! These tests are skipped unless `MINDCHAT_LIVE_TESTS=1` is set, because they
//! require network egress to a public XMPP server. They prove the full login
//! chain (DNS -> TCP -> `StartTLS` -> SASL) against a real server and guard the
//! Android device failure mode where DNS configuration is unreliable.

use mindchat_core::{
    ConnectionRequest, SecretString, TokioXmppTransport, TransportEvent, XmppTransport,
    xmpp::RegisterRequest,
};
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

fn live_enabled() -> bool {
    std::env::var("MINDCHAT_LIVE_TESTS").is_ok()
}

fn live_server() -> String {
    std::env::var("MINDCHAT_LIVE_SERVER").unwrap_or_else(|_| "jabber.ru".to_owned())
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread().enable_all().build().expect("tokio runtime")
}

/// A tiny local TCP forwarder: it accepts connections on an ephemeral
/// loopback port and pipes bytes bidirectionally to the real XMPP server.
/// Stopping it severs every established client connection, exactly like a
/// network path loss; starting a new one lets the reconnector re-establish
/// the stream. TLS keeps verifying against the JID domain, so the XMPP
/// client cannot tell the forwarder from a direct path.
struct Forwarder {
    port: u16,
    stop: Arc<AtomicBool>,
    accepted: Arc<std::sync::Mutex<Vec<TcpStream>>>,
    join: Option<thread::JoinHandle<()>>,
}

impl Forwarder {
    fn start(target: std::net::IpAddr, target_port: u16) -> Self {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind local forwarder");
        let port = listener.local_addr().expect("forwarder local address").port();
        let stop = Arc::new(AtomicBool::new(false));
        let accepted = Arc::new(std::sync::Mutex::new(Vec::new()));
        let stop_thread = stop.clone();
        let accepted_thread = accepted.clone();
        let join = thread::spawn(move || {
            listener.set_nonblocking(true).expect("nonblocking listener");
            while !stop_thread.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((client, _)) => {
                        let Ok(server) = TcpStream::connect((target, target_port)) else {
                            continue;
                        };
                        // Keep a handle so stop() can sever the connection.
                        if let Ok(clone) = client.try_clone() {
                            accepted_thread.lock().expect("accepted lock").push(clone);
                        }
                        pump(client, server);
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(20));
                    }
                    Err(_) => break,
                }
            }
        });
        Self { port, stop, accepted, join: Some(join) }
    }

    fn stop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        // Shut every accepted client socket down so the XMPP worker observes
        // the loss immediately instead of waiting for a read timeout.
        for stream in self.accepted.lock().expect("accepted lock").drain(..) {
            let _ = stream.shutdown(Shutdown::Both);
        }
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

/// Pipes one accepted connection to the live server in both directions.
fn pump(client: TcpStream, server: TcpStream) {
    let (mut client_to_server, mut server_to_client) = (client, server);
    let (server_back, client_back) = (server_to_client.try_clone(), client_to_server.try_clone());
    thread::spawn(move || {
        let Ok(mut server) = server_back else {
            return;
        };
        let mut buffer = [0u8; 8192];
        loop {
            match client_to_server.read(&mut buffer) {
                Ok(0) | Err(_) => {
                    let _ = server.shutdown(Shutdown::Both);
                    break;
                }
                Ok(count) => {
                    if server.write_all(&buffer[..count]).is_err() {
                        break;
                    }
                }
            }
        }
    });
    thread::spawn(move || {
        let Ok(mut client) = client_back else {
            return;
        };
        let mut buffer = [0u8; 8192];
        loop {
            match server_to_client.read(&mut buffer) {
                Ok(0) | Err(_) => {
                    let _ = client.shutdown(Shutdown::Both);
                    break;
                }
                Ok(count) => {
                    if client.write_all(&buffer[..count]).is_err() {
                        break;
                    }
                }
            }
        }
    });
}

/// Polls the transport until an event matching `predicate` arrives or the
/// deadline passes.
fn poll_for<F>(
    transport: &mut TokioXmppTransport,
    deadline: Instant,
    mut predicate: F,
) -> Option<TransportEvent>
where
    F: FnMut(&TransportEvent) -> bool,
{
    while Instant::now() < deadline {
        match transport.next_event().expect("event poll must not fail") {
            Some(event) if predicate(&event) => return Some(event),
            Some(_) => {}
            None => thread::sleep(Duration::from_millis(150)),
        }
    }
    None
}

#[test]
fn live_resolve_endpoint_finds_jabber_ru() {
    if !live_enabled() {
        return;
    }
    let server = live_server();
    let _ = runtime()
        .block_on(mindchat_core::resolve_endpoint(&server))
        .expect("server must resolve through SRV or OS fallback");
}

#[test]
fn live_login_chain_reaches_sasl_on_jabber_ru() {
    if !live_enabled() {
        return;
    }
    let mut transport = TokioXmppTransport::new();
    let account_id = 1;
    let server = live_server();
    let jid = format!("mindchat-live-{}@{server}", std::process::id());
    transport
        .connect(ConnectionRequest {
            account_id,
            jid,
            server,
            password: SecretString::new("definitely-wrong-password"),
            auto_reconnect: true,
        })
        .expect("worker must start");

    let deadline = Instant::now() + Duration::from_secs(30);
    let mut terminal: Option<TransportEvent> = None;
    while Instant::now() < deadline {
        match transport.next_event().expect("event poll must not fail") {
            Some(event @ TransportEvent::Disconnected { .. }) => {
                terminal = Some(event);
                break;
            }
            // A randomly named account cannot authenticate, so reaching
            // Online is unexpected; keep polling for the terminal state.
            Some(_) => {}
            None => std::thread::sleep(Duration::from_millis(150)),
        }
    }

    let terminal = terminal.expect("transport must reach a terminal Disconnected event");
    let TransportEvent::Disconnected { account_id: seen_account, recoverable, detail } = terminal
    else {
        panic!("unexpected terminal event");
    };
    assert_eq!(seen_account, account_id);
    // The full chain works when the server rejects the bogus credentials with
    // an authentication failure (non-recoverable) instead of a connection error.
    assert!(
        !recoverable,
        "expected an authentication failure (chain reached SASL); got: {}",
        detail.as_deref().unwrap_or("<no detail>")
    );
    assert!(detail.is_some(), "auth failures must carry a UI-safe detail");
}

#[test]
fn live_blackhole_connect_terminates_within_30_seconds() {
    if !live_enabled() {
        return;
    }
    let mut transport = TokioXmppTransport::new();
    let account_id = 2;
    // An explicit IP in a non-routable range: resolution is immediate, the
    // connect hangs at the OS level, and the bounded connect phase must still
    // emit a terminal Disconnected within ~30 seconds.
    transport
        .connect(ConnectionRequest {
            account_id,
            jid: "blackhole@10.255.255.1".to_owned(),
            server: "10.255.255.1".to_owned(),
            password: SecretString::new("irrelevant"),
            auto_reconnect: true,
        })
        .expect("worker must start");

    let started = Instant::now();
    let deadline = started + Duration::from_secs(32);
    let mut terminal: Option<TransportEvent> = None;
    while Instant::now() < deadline {
        match transport.next_event().expect("event poll must not fail") {
            Some(event @ TransportEvent::Disconnected { .. }) => {
                terminal = Some(event);
                break;
            }
            Some(TransportEvent::Connected { .. }) => {
                panic!("blackhole endpoint must never connect");
            }
            Some(_) => {}
            None => std::thread::sleep(Duration::from_millis(150)),
        }
    }

    let elapsed = started.elapsed();
    let terminal = terminal.expect("blackhole connect must terminate within 32 seconds");
    assert!(
        elapsed <= Duration::from_secs(32),
        "blackhole connect took {elapsed:?}, exceeding the 32 second bound"
    );
    let TransportEvent::Disconnected { account_id: seen_account, recoverable, detail: _ } =
        terminal
    else {
        panic!("unexpected terminal event");
    };
    assert_eq!(seen_account, account_id);
    assert!(
        recoverable,
        "an unroutable endpoint is a recoverable connection failure, not an auth failure"
    );
}

#[test]
fn live_wrong_password_fails_fast_without_preflight() {
    if !live_enabled() {
        return;
    }
    let mut transport = TokioXmppTransport::new();
    let account_id = 3;
    let server = live_server();
    let jid = format!("mindchat-wrong-pw-{}@{server}", std::process::id());
    transport
        .connect(ConnectionRequest {
            account_id,
            jid,
            server,
            password: SecretString::new("definitely-wrong-password"),
            auto_reconnect: true,
        })
        .expect("worker must start");

    let started = Instant::now();
    let deadline = started + Duration::from_secs(20);
    let mut terminal: Option<TransportEvent> = None;
    while Instant::now() < deadline {
        match transport.next_event().expect("event poll must not fail") {
            Some(event @ TransportEvent::Disconnected { .. }) => {
                terminal = Some(event);
                break;
            }
            Some(TransportEvent::Connected { .. }) => {
                panic!("bogus credentials must never authenticate");
            }
            Some(_) => {}
            None => std::thread::sleep(Duration::from_millis(150)),
        }
    }

    let elapsed = started.elapsed();
    let terminal = terminal.expect("wrong password must fail within 20 seconds");
    assert!(
        elapsed <= Duration::from_secs(20),
        "wrong-password rejection took {elapsed:?}, exceeding the 20 second bound"
    );
    let TransportEvent::Disconnected { account_id: seen_account, recoverable, detail } = terminal
    else {
        panic!("unexpected terminal event");
    };
    assert_eq!(seen_account, account_id);
    assert!(
        !recoverable,
        "rejected credentials must be non-recoverable; got detail: {}",
        detail.as_deref().unwrap_or("<no detail>")
    );
    assert!(
        detail.is_some() && !detail.as_deref().unwrap_or("").is_empty(),
        "auth failures must carry a precise UI-safe detail"
    );
}

#[test]
fn live_reconnect_after_tcp_forwarder_drop() {
    if !live_enabled() {
        return;
    }
    let server = live_server();
    let username = format!("mindchat-rec-{}", std::process::id());
    let password = format!("mindchat-rec-pw-{}", std::process::id());

    // A real authenticated session is required to observe a mid-session
    // reconnect, so register a throwaway account first. Servers that refuse
    // in-band registration skip the test silently (like every live test
    // skips when the network is unavailable).
    let mut registrar = TokioXmppTransport::new();
    if registrar
        .register(RegisterRequest {
            username: username.clone(),
            server: server.clone(),
            password: SecretString::new(password.clone()),
        })
        .is_err()
    {
        return;
    }

    // Resolve the live server once so the forwarder can target it directly;
    // the XMPP client itself connects through the loopback forwarder.
    let target = runtime().block_on(async {
        tokio::net::lookup_host((server.as_str(), 5222))
            .await
            .expect("resolve the live server")
            .next()
            .expect("at least one server address")
    });
    let mut forwarder = Forwarder::start(target.ip(), target.port());

    let mut transport = TokioXmppTransport::new();
    let account_id = 4;
    let jid = format!("{username}@{server}");
    transport
        .connect(ConnectionRequest {
            account_id,
            jid,
            server: format!("127.0.0.1:{}", forwarder.port),
            password: SecretString::new(password),
            auto_reconnect: true,
        })
        .expect("worker must start");

    // Phase 1: the session must come Online through the forwarder.
    let first_online = poll_for(&mut transport, Instant::now() + Duration::from_secs(40), |event| {
        matches!(event, TransportEvent::Connected { account_id: seen, .. } if *seen == account_id)
    })
    .expect("registered account must connect through the forwarder within 40 seconds");
    let _ = first_online;

    // Phase 2: sever the path and bring it back after the first backoff
    // window, so the jittered reconnector can re-establish the stream.
    forwarder.stop();
    thread::sleep(Duration::from_secs(2));
    let mut forwarder = Forwarder::start(target.ip(), target.port());

    // The loss must surface as a recoverable Disconnected (Offline in the
    // UI), and then a second Connected must arrive once the stream is back.
    let disconnected =
        poll_for(&mut transport, Instant::now() + Duration::from_secs(30), |event| {
            matches!(
                event,
                TransportEvent::Disconnected { account_id: seen, recoverable: true, .. }
                    if *seen == account_id
            )
        })
        .expect("forwarder drop must surface as a recoverable Disconnected");
    let _ = disconnected;

    let second_online = poll_for(
        &mut transport,
        Instant::now() + Duration::from_secs(60),
        |event| {
            matches!(event, TransportEvent::Connected { account_id: seen, .. } if *seen == account_id)
        },
    )
    .expect("reconnect must re-establish the session within the retry budget");
    let _ = second_online;

    let _ = transport.disconnect(account_id);
    forwarder.stop();
}
