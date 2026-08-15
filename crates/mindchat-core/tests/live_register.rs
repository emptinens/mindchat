//! Live-server XEP-0077 in-band registration through the public FFI boundary.
//!
//! The Android app consumes `MindChatCoreHandle` exclusively (via `UniFFI`), so
//! this test drives that exact public interface over the real network. The
//! point is not a specific server verdict: it is that the registration flow
//! always completes with a terminal result and never hangs, returning either a
//! created (and connecting/online) account or a UI-safe refusal detail.
//!
//! Skipped unless `MINDCHAT_LIVE_REGISTER=1` is set, matching the other live
//! suites. The target server can be overridden with `MINDCHAT_LIVE_SERVER`.

use mindchat_core::ffi::{FfiConnectionState, MindChatBindingError, MindChatCoreHandle};
use std::time::{Duration, Instant};

fn live_enabled() -> bool {
    std::env::var("MINDCHAT_LIVE_REGISTER").is_ok()
}

fn live_server() -> String {
    std::env::var("MINDCHAT_LIVE_SERVER").unwrap_or_else(|_| "jabber.ru".to_owned())
}

#[test]
fn live_register_flow_reaches_a_terminal_result() {
    if !live_enabled() {
        return;
    }
    let server = live_server();
    let username = format!("mindchat-reg-{}", std::process::id());
    let password = format!("mindchat-pw-{}", std::process::id());
    let handle = MindChatCoreHandle::new();

    let started = Instant::now();
    let result = handle.register_account(
        username.clone(),
        server.clone(),
        "live register".to_owned(),
        password,
    );

    match result {
        Ok(account_id) => {
            // The account must leave `Connecting` within the connect bound and
            // reach a terminal state; a freshly registered account is expected
            // to be Online, but a server-side auth hiccup must still surface
            // as a terminal Failed instead of a hang.
            let deadline = started + Duration::from_secs(70);
            loop {
                let _ = handle.poll_transport_events(128).expect("poll must not fail");
                let _ = handle.drain_events().expect("drain must not fail");
                let snapshot = handle.snapshot().expect("snapshot must not fail");
                let Some(account) = snapshot.accounts.iter().find(|item| item.id == account_id)
                else {
                    panic!("registered account {account_id} disappeared from the snapshot");
                };
                if account.connection_state != FfiConnectionState::Connecting {
                    assert!(
                        matches!(
                            account.connection_state,
                            FfiConnectionState::Online | FfiConnectionState::Failed
                        ),
                        "registration must end Online or a UI-safe Failed, not {:?}",
                        account.connection_state
                    );
                    break;
                }
                assert!(Instant::now() < deadline, "account stuck on Connecting past the bound");
                std::thread::sleep(Duration::from_millis(150));
            }
        }
        Err(error) => {
            // A refusal must be a UI-safe, non-empty detail. The variants that
            // carry a `detail` string are exactly the ones Kotlin can render.
            match &error {
                MindChatBindingError::ConnectionFailed { detail }
                | MindChatBindingError::InvalidInput { detail }
                | MindChatBindingError::NotFound { detail }
                | MindChatBindingError::Internal { detail } => {
                    assert!(!detail.is_empty(), "refusal detail must not be empty");
                }
                MindChatBindingError::AuthenticationFailed
                | MindChatBindingError::CapabilityUnavailable { .. } => {}
            }
        }
    }
}
