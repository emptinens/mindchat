//! Live-server validation for the XMPP transport.
//!
//! These tests are skipped unless `MINDCHAT_LIVE_TESTS=1` is set, because they
//! require network egress to a public XMPP server. They prove the full login
//! chain (DNS -> TCP -> `StartTLS` -> SASL) against a real server and guard the
//! Android device failure mode where DNS configuration is unreliable.

use mindchat_core::{
    ConnectionRequest, SecretString, TokioXmppTransport, TransportEvent, XmppTransport,
};
use std::time::{Duration, Instant};

fn live_enabled() -> bool {
    std::env::var("MINDCHAT_LIVE_TESTS").is_ok()
}

fn init_logger() {
    let _ = env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info"))
        .try_init();
}

fn live_server() -> String {
    std::env::var("MINDCHAT_LIVE_SERVER").unwrap_or_else(|_| "jabber.ru".to_owned())
}

fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread().enable_all().build().expect("tokio runtime")
}

#[test]
fn live_resolve_endpoint_finds_jabber_ru() {
    if !live_enabled() {
        eprintln!("skipped: set MINDCHAT_LIVE_TESTS=1");
        return;
    }
    let server = live_server();
    let address = runtime().block_on(mindchat_core::resolve_endpoint(&server));
    let address = address.expect("server must resolve through SRV or OS fallback");
    eprintln!("{server} resolves to {address}");
}

#[test]
fn live_login_chain_reaches_sasl_on_jabber_ru() {
    if !live_enabled() {
        eprintln!("skipped: set MINDCHAT_LIVE_TESTS=1");
        return;
    }
    init_logger();
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
        })
        .expect("worker must start");

    let deadline = Instant::now() + Duration::from_secs(30);
    let mut saw_connected = false;
    let mut terminal: Option<TransportEvent> = None;
    while Instant::now() < deadline {
        match transport.next_event().expect("event poll must not fail") {
            Some(TransportEvent::Connected { .. }) => {
                // A randomly named account cannot authenticate, so reaching
                // Online is unexpected; record it and keep polling.
                saw_connected = true;
            }
            Some(event @ TransportEvent::Disconnected { .. }) => {
                terminal = Some(event);
                break;
            }
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
    eprintln!(
        "login chain result: recoverable={recoverable} detail={} saw_connected={saw_connected}",
        detail.as_deref().unwrap_or("<none>")
    );
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
        eprintln!("skipped: set MINDCHAT_LIVE_TESTS=1");
        return;
    }
    init_logger();
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
    let TransportEvent::Disconnected { account_id: seen_account, recoverable, detail } = terminal
    else {
        panic!("unexpected terminal event");
    };
    assert_eq!(seen_account, account_id);
    eprintln!(
        "blackhole connect terminated after {elapsed:?}: recoverable={recoverable} detail={}",
        detail.as_deref().unwrap_or("<none>")
    );
    assert!(
        recoverable,
        "an unroutable endpoint is a recoverable connection failure, not an auth failure"
    );
}

#[test]
fn live_wrong_password_fails_fast_without_preflight() {
    if !live_enabled() {
        eprintln!("skipped: set MINDCHAT_LIVE_TESTS=1");
        return;
    }
    init_logger();
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
    eprintln!(
        "wrong-password failure after {elapsed:?}: recoverable={recoverable} detail={}",
        detail.as_deref().unwrap_or("<none>")
    );
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
