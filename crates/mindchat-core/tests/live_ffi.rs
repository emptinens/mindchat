//! Live-server validation through the public FFI boundary.
//!
//! The Android app consumes `MindChatCoreHandle` exclusively (via `UniFFI`).
//! These tests drive that exact public interface over the real network to
//! prove the account state machine reaches terminal states and can never
//! freeze on `Connecting` (the "always Connecting" bug), that a failed
//! session can be retried, and that an in-flight connect can be cancelled.
//!
//! Skipped unless `MINDCHAT_LIVE_TESTS=1` is set, like `live_login`.

use mindchat_core::ffi::{FfiConnectionState, MindChatCoreHandle};
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
