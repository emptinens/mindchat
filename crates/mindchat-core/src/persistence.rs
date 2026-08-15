//! Versioned, atomic, bounded JSON persistence for non-secret core snapshots.
//!
//! This module stores [`CoreSnapshot`] state in a single JSON file:
//! accounts, contacts, conversations, messages, and reactions. It is the only
//! place session ephemera are cleared on restore, and it never touches
//! passwords or other secret material (those live only in transport
//! [`ConnectionRequest`](crate::ConnectionRequest) values and are never part
//! of a snapshot).

use crate::{ConnectionState, ContactPresence, CoreSnapshot};
use std::collections::BTreeSet;
use std::fmt;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

/// Schema version of the on-disk [`PersistedState`] format.
///
/// Any other version is refused on load; 0.1.3 never migrates files.
pub const CURRENT_SCHEMA_VERSION: u32 = 1;

/// Upper bound for an acceptable state file, in bytes.
///
/// Larger files are refused on load as a corrupt or hostile-file guard. The
/// bound also indirectly caps the memory cost of deserializing a snapshot on
/// the startup thread, so it is kept far below archive-sized histories.
pub const MAX_STATE_FILE_BYTES: u64 = 16 * 1024 * 1024;

/// On-disk envelope wrapping one [`CoreSnapshot`] with a schema version.
#[derive(Debug, serde::Deserialize, serde::Serialize)]
pub struct PersistedState {
    /// Format version of the persisted snapshot.
    pub schema_version: u32,
    /// The durable state captured at save time.
    pub snapshot: CoreSnapshot,
}

/// Non-secret metadata about a persisted state file, safe for the
/// diagnostics report (ROADMAP 6.5). Contains no snapshot content.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StateFileMetadata {
    /// Size of the state file in bytes.
    pub size_bytes: u64,
    /// Schema version stored in the file envelope.
    pub schema_version: u32,
}

/// Failure while saving or loading a state file.
///
/// All variants render UI-safe messages that contain no secret material.
#[derive(Debug)]
pub enum PersistenceError {
    /// The underlying file system rejected the operation.
    Io(std::io::Error),
    /// The file did not parse as a valid `PersistedState`.
    Corrupt(String),
    /// The file carries a schema version this release refuses to load.
    UnsupportedVersion(u32),
    /// The file exceeded [`MAX_STATE_FILE_BYTES`].
    TooLarge(u64),
}

impl fmt::Display for PersistenceError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "state persistence I/O error: {error}"),
            Self::Corrupt(detail) => write!(formatter, "corrupt state file: {detail}"),
            Self::UnsupportedVersion(version) => {
                write!(formatter, "unsupported state schema version {version}")
            }
            Self::TooLarge(bytes) => write!(formatter, "state file too large: {bytes} bytes"),
        }
    }
}

impl std::error::Error for PersistenceError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            Self::Corrupt(_) | Self::UnsupportedVersion(_) | Self::TooLarge(_) => None,
        }
    }
}

/// Writes `snapshot` to `path` atomically as versioned, pretty-printed JSON.
///
/// Bytes are serialized first, written to a unique `<path>.<pid>.<counter>.tmp`
/// staging file, flushed to disk with `sync_all`, and finally renamed over
/// `path`. A crash mid-write leaves the previous file intact and only a stale
/// staging file behind, which a later save leaves untouched and the OS
/// eventually reclaims. Concurrent saves from other threads each write their
/// own staging file, so the last `rename` always installs one complete
/// snapshot. On error the staging file is removed best-effort.
///
/// # Errors
///
/// Returns [`PersistenceError::Io`] when the file system rejects any step of
/// the write, [`PersistenceError::Corrupt`] if the snapshot cannot be
/// serialized (which cannot happen for valid in-memory state), and
/// [`PersistenceError::TooLarge`] when the serialized file would exceed
/// [`MAX_STATE_FILE_BYTES`].
pub fn save_state(snapshot: &CoreSnapshot, path: &Path) -> Result<(), PersistenceError> {
    let persisted =
        PersistedState { schema_version: CURRENT_SCHEMA_VERSION, snapshot: snapshot.clone() };
    let bytes = serde_json::to_vec_pretty(&persisted)
        .map_err(|error| PersistenceError::Corrupt(error.to_string()))?;
    // Enforce the same bound that load_state applies, so the app can never
    // write a file it will refuse (and rename aside as corrupt) on the next
    // launch.
    let byte_count = u64::try_from(bytes.len()).unwrap_or(u64::MAX);
    if byte_count > MAX_STATE_FILE_BYTES {
        return Err(PersistenceError::TooLarge(byte_count));
    }
    let tmp_path = tmp_path_for(path);

    let write_result = std::fs::File::create(&tmp_path)
        .and_then(|mut file| {
            file.write_all(&bytes)?;
            file.sync_all()?;
            drop(file);
            std::fs::rename(&tmp_path, path)
        })
        .map_err(PersistenceError::Io);

    if write_result.is_err() {
        let _result = std::fs::remove_file(&tmp_path);
    }
    write_result
}

/// Loads and sanitizes a snapshot from `path`.
///
/// A missing file returns `Ok(None)`. Files larger than
/// [`MAX_STATE_FILE_BYTES`] are refused with [`PersistenceError::TooLarge`],
/// unparseable content with [`PersistenceError::Corrupt`], and any schema
/// version other than [`CURRENT_SCHEMA_VERSION`] with
/// [`PersistenceError::UnsupportedVersion`]. Loaded snapshots are sanitized so
/// restored state never projects a stale connection or capability set.
///
/// # Errors
///
/// Returns the typed [`PersistenceError`] described above for every failure
/// mode except a missing file.
pub fn load_state(path: &Path) -> Result<Option<CoreSnapshot>, PersistenceError> {
    load_state_with_metadata(path).map(|option| option.map(|(snapshot, _)| snapshot))
}

/// Loads a snapshot like [`load_state`] and also returns the file's
/// non-secret metadata for the diagnostics report (ROADMAP 6.5).
///
/// # Errors
///
/// Returns the same typed [`PersistenceError`] as [`load_state`].
pub fn load_state_with_metadata(
    path: &Path,
) -> Result<Option<(CoreSnapshot, StateFileMetadata)>, PersistenceError> {
    let metadata = match std::fs::metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(PersistenceError::Io(error)),
    };
    if metadata.len() > MAX_STATE_FILE_BYTES {
        return Err(PersistenceError::TooLarge(metadata.len()));
    }

    let bytes = std::fs::read(path).map_err(PersistenceError::Io)?;
    let byte_count = u64::try_from(bytes.len()).unwrap_or(u64::MAX);
    if byte_count > MAX_STATE_FILE_BYTES {
        return Err(PersistenceError::TooLarge(byte_count));
    }

    let persisted: PersistedState = serde_json::from_slice(&bytes)
        .map_err(|error| PersistenceError::Corrupt(error.to_string()))?;
    if persisted.schema_version != CURRENT_SCHEMA_VERSION {
        return Err(PersistenceError::UnsupportedVersion(persisted.schema_version));
    }
    Ok(Some((
        sanitize_snapshot(persisted.snapshot),
        StateFileMetadata { size_bytes: byte_count, schema_version: persisted.schema_version },
    )))
}

/// Clears session ephemera from a restored snapshot.
///
/// Accounts are forced to `Offline` with no error, no capability set, and no
/// disconnect classification, contacts to `Offline` presence with no status,
/// while conversations, messages, and reactions are kept verbatim because
/// they are durable state. Pending and failed outgoing messages stay pending
/// for the reconnect retry path.
fn sanitize_snapshot(mut snapshot: CoreSnapshot) -> CoreSnapshot {
    for account in &mut snapshot.accounts {
        account.connection_state = ConnectionState::Offline;
        account.connection_error = None;
        account.disconnect_kind = None;
        account.capabilities = BTreeSet::new();
    }
    for contact in &mut snapshot.contacts {
        contact.presence = ContactPresence::Offline;
        contact.status = None;
    }
    snapshot
}

/// Returns a unique `<path>.<pid>.<counter>.tmp` staging name for atomic saves.
///
/// Uniqueness matters: a concurrent save from another thread (for example the
/// poll loop and the `ON_STOP` lifecycle flush in Kotlin) writes its own
/// staging file, so the final `rename` always installs one complete snapshot
/// instead of interleaving two writers on a shared staging path.
fn tmp_path_for(path: &Path) -> PathBuf {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let mut tmp = path.as_os_str().to_os_string();
    tmp.push(format!(".{}.{}.tmp", std::process::id(), COUNTER.fetch_add(1, Ordering::Relaxed)));
    PathBuf::from(tmp)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        Account, AccountSetup, Contact, Conversation, ConversationKind, DeliveryState, Message,
        MessageDirection, MessageKind, MindChatCore, ProtocolCapability, Reaction,
        RosterSubscription,
    };
    use std::sync::atomic::{AtomicU64, Ordering};

    /// Unique state-file path under the system temp dir for one test.
    ///
    /// The parent directory is created eagerly so every test starts from a
    /// deterministic location, and is removed when the guard drops.
    struct TempState {
        path: PathBuf,
    }

    impl TempState {
        fn new(label: &str) -> Self {
            static COUNTER: AtomicU64 = AtomicU64::new(0);
            let dir = std::env::temp_dir().join(format!(
                "mindchat-persistence-{label}-{}-{}",
                std::process::id(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&dir).expect("create test temp directory");
            Self { path: dir.join("mindchat_state.json") }
        }

        fn path(&self) -> &Path {
            &self.path
        }
    }

    impl Drop for TempState {
        fn drop(&mut self) {
            if let Some(parent) = self.path.parent() {
                let _result = std::fs::remove_dir_all(parent);
            }
        }
    }

    fn populated_core() -> MindChatCore {
        let mut core = MindChatCore::default();
        let alice = core
            .add_account(AccountSetup::new("alice@example.org", "example.org", "Alice"))
            .expect("alice account");
        let mila = core
            .add_account(AccountSetup::new("mila@example.net", "example.net", "Mila"))
            .expect("mila account");

        core.upsert_contact(
            alice,
            "bob@example.org",
            "Bob",
            ContactPresence::Online,
            Some("Available".to_owned()),
        )
        .expect("bob contact");
        core.sync_roster_contact(alice, "bob@example.org", "Bob", RosterSubscription::Mutual)
            .expect("bob subscription");
        core.upsert_contact(mila, "nina@example.net", "Nina", ContactPresence::Away, None)
            .expect("nina contact");
        core.sync_roster_contact(mila, "nina@example.net", "Nina", RosterSubscription::Inbound)
            .expect("nina subscription");

        core.set_capabilities(
            alice,
            [ProtocolCapability::MultiUserChat, ProtocolCapability::MessageReactions],
        )
        .expect("alice capabilities");
        let direct = core
            .open_conversation(alice, ConversationKind::Direct, "bob@example.org", "Bob", 1_000)
            .expect("direct conversation");
        core.open_conversation(
            alice,
            ConversationKind::MultiUserChat,
            "room@example.org",
            "Room",
            2_000,
        )
        .expect("MUC conversation");

        let outgoing = core
            .send_text(direct, "alice@example.org", "hello", None, 3_000)
            .expect("outgoing message");
        let reply = core
            .send_text(direct, "alice@example.org", "hello again", Some(outgoing), 4_000)
            .expect("reply message");
        core.set_delivery_state(reply, DeliveryState::Delivered).expect("delivery state");
        let incoming = core
            .receive_text(direct, "bob@example.org", "received", 5_000)
            .expect("incoming message");
        core.add_reaction(incoming, "bob@example.org", "👍").expect("reaction");
        core
    }

    #[test]
    fn snapshot_json_round_trip_preserves_all_records() {
        let core = populated_core();
        let original = core.snapshot();
        let temp = TempState::new("round-trip");

        save_state(&original, temp.path()).expect("save succeeds");
        let loaded = load_state(temp.path()).expect("load succeeds").expect("file exists");

        assert_eq!(loaded, sanitize_snapshot(original));
        assert_eq!(loaded.accounts.len(), 2);
        assert!(
            loaded
                .accounts
                .iter()
                .all(|account| account.connection_state == ConnectionState::Offline)
        );
        assert!(loaded.accounts.iter().all(|account| account.capabilities.is_empty()));
        assert!(loaded.accounts.iter().all(|account| account.connection_error.is_none()));
        assert_eq!(loaded.contacts.len(), 2);
        assert!(loaded.contacts.iter().all(|contact| contact.presence == ContactPresence::Offline));
        assert!(loaded.contacts.iter().all(|contact| contact.status.is_none()));
        assert_eq!(
            loaded.contacts.iter().map(|contact| contact.subscription).collect::<Vec<_>>(),
            vec![RosterSubscription::Mutual, RosterSubscription::Inbound]
        );
        assert_eq!(loaded.conversations.len(), 2);
        assert_eq!(loaded.messages.len(), 3);
        assert_eq!(loaded.messages[1].in_reply_to, Some(loaded.messages[0].id));
        assert_eq!(loaded.messages[1].delivery_state, DeliveryState::Delivered);
        assert_eq!(loaded.reactions.len(), 1);
        assert_eq!(loaded.reactions[0].emoji, "👍");
    }

    #[test]
    fn load_state_returns_none_for_missing_file() {
        let temp = TempState::new("missing");
        let missing = temp.path().with_extension("never-written.json");
        assert_eq!(load_state(&missing).expect("missing file is not an error"), None);
    }

    #[test]
    fn load_state_defaults_auto_reconnect_for_pre_018_snapshots() {
        // 0.1.7 snapshots have no `auto_reconnect` field; a restore must fall
        // back to the default (true) so existing accounts silently gain the
        // network-robustness behavior instead of losing it.
        let temp = TempState::new("auto-reconnect-default");
        let json = r#"{
            "schema_version": 1,
            "snapshot": {
                "accounts": [{
                    "id": 7,
                    "jid": "alice@example.org",
                    "server": "example.org",
                    "display_name": "Alice",
                    "connection_state": "Offline",
                    "capabilities": [],
                    "connection_error": null
                }],
                "contacts": [],
                "conversations": [],
                "messages": [],
                "reactions": []
            }
        }"#;
        std::fs::write(temp.path(), json).expect("write versioned file");
        let loaded = load_state(temp.path()).expect("load succeeds").expect("file exists");
        assert!(
            loaded.accounts[0].auto_reconnect,
            "pre-0.1.8 snapshots must restore with auto-reconnect enabled"
        );
        assert!(
            loaded.accounts[0].proxies.is_empty(),
            "pre-0.1.8 snapshots have no proxy library and must restore empty"
        );
    }

    #[test]
    fn proxy_library_survives_restore_without_any_secret() {
        use crate::{ProxyConfig, ProxyKind};
        let mut core = MindChatCore::default();
        let account_id = core
            .add_account(AccountSetup::new("alice@example.org", "example.org", "Alice"))
            .expect("account");
        core.set_account_proxies(
            account_id,
            vec![
                ProxyConfig::new("proxy.example.org", 1080, ProxyKind::Socks5).expect("socks5"),
                ProxyConfig::new("proxy.example.org", 8080, ProxyKind::HttpConnect)
                    .expect("http connect"),
            ],
        )
        .expect("proxies stored");
        let temp = TempState::new("proxy-library");

        save_state(&core.snapshot(), temp.path()).expect("save succeeds");
        let loaded = load_state(temp.path()).expect("load succeeds").expect("file exists");

        assert_eq!(
            loaded.accounts[0].proxies,
            vec![
                ProxyConfig::new("proxy.example.org", 1080, ProxyKind::Socks5).expect("socks5"),
                ProxyConfig::new("proxy.example.org", 8080, ProxyKind::HttpConnect)
                    .expect("http connect"),
            ]
        );
        let content = std::fs::read_to_string(temp.path()).expect("read saved file");
        assert!(
            !content.contains("password"),
            "the persisted proxy library must never contain credentials"
        );
    }

    #[test]
    fn load_state_rejects_corrupt_json() {
        let temp = TempState::new("corrupt");
        std::fs::write(temp.path(), b"this is not json").expect("write corrupt file");
        assert!(matches!(load_state(temp.path()), Err(PersistenceError::Corrupt(_))));
    }

    #[test]
    fn load_state_rejects_unsupported_schema_version() {
        let temp = TempState::new("version");
        let persisted = PersistedState { schema_version: 99, snapshot: CoreSnapshot::default() };
        std::fs::write(temp.path(), serde_json::to_vec(&persisted).expect("serialize"))
            .expect("write versioned file");
        assert!(matches!(load_state(temp.path()), Err(PersistenceError::UnsupportedVersion(99))));
    }

    #[test]
    fn load_state_rejects_oversized_file() {
        let temp = TempState::new("oversized");
        let oversized = usize::try_from(MAX_STATE_FILE_BYTES).expect("64 MiB fits usize") + 1;
        std::fs::write(temp.path(), vec![b' '; oversized]).expect("write oversized file");
        assert!(matches!(load_state(temp.path()), Err(PersistenceError::TooLarge(_))));
    }

    #[test]
    fn save_state_is_atomic_temp_renamed() {
        let core = populated_core();
        let temp = TempState::new("atomic");

        save_state(&core.snapshot(), temp.path()).expect("save succeeds");
        let parent = temp.path().parent().expect("temp path has a parent");
        let stray_tmp = std::fs::read_dir(parent)
            .expect("read temp directory")
            .filter_map(Result::ok)
            .any(|entry| entry.file_name().to_string_lossy().ends_with(".tmp"));
        assert!(!stray_tmp, "staging file must be renamed away");

        let content = std::fs::read_to_string(temp.path()).expect("read saved file");
        let parsed: serde_json::Value = serde_json::from_str(&content).expect("valid JSON");
        assert_eq!(parsed["schema_version"], 1);
        assert_eq!(parsed["snapshot"]["accounts"].as_array().map(Vec::len), Some(2));
    }

    #[test]
    fn load_state_sanitizes_session_ephemera() {
        let snapshot = CoreSnapshot {
            accounts: vec![
                Account {
                    id: 7,
                    jid: "alice@example.org".to_owned(),
                    server: "example.org".to_owned(),
                    display_name: "Alice".to_owned(),
                    connection_state: ConnectionState::Online,
                    capabilities: BTreeSet::from([ProtocolCapability::Receipts]),
                    connection_error: Some("not authorized".to_owned()),
                    disconnect_kind: Some(crate::DisconnectKind::ServerRefused),
                    auto_reconnect: true,
                    proxies: Vec::new(),
                },
                Account {
                    id: 8,
                    jid: "mila@example.net".to_owned(),
                    server: "example.net".to_owned(),
                    display_name: "Mila".to_owned(),
                    connection_state: ConnectionState::Connecting,
                    capabilities: BTreeSet::new(),
                    connection_error: None,
                    disconnect_kind: None,
                    auto_reconnect: true,
                    proxies: Vec::new(),
                },
            ],
            contacts: vec![Contact {
                account_id: 7,
                jid: "bob@example.org".to_owned(),
                display_name: "Bob".to_owned(),
                presence: ContactPresence::DoNotDisturb,
                status: Some("Busy".to_owned()),
                subscription: RosterSubscription::Mutual,
            }],
            conversations: vec![Conversation {
                id: 1,
                account_id: 7,
                kind: ConversationKind::Direct,
                address: "bob@example.org".to_owned(),
                title: "Bob".to_owned(),
                unread_count: 3,
                last_activity_epoch_ms: 42,
            }],
            messages: vec![Message {
                id: 9,
                conversation_id: 1,
                sender: "alice@example.org".to_owned(),
                body: "hello".to_owned(),
                direction: MessageDirection::Outgoing,
                kind: MessageKind::Text,
                sent_at_epoch_ms: 1,
                delivery_state: DeliveryState::Pending,
                in_reply_to: None,
                attachment: None,
            }],
            reactions: vec![Reaction {
                id: 2,
                message_id: 9,
                emoji: "👍".to_owned(),
                actor: "bob@example.org".to_owned(),
            }],
        };
        let temp = TempState::new("sanitize");

        save_state(&snapshot, temp.path()).expect("save succeeds");
        let loaded = load_state(temp.path()).expect("load succeeds").expect("file exists");

        assert_eq!(loaded.accounts[0].connection_state, ConnectionState::Offline);
        assert!(loaded.accounts[0].capabilities.is_empty());
        assert_eq!(loaded.accounts[0].connection_error, None);
        assert_eq!(
            loaded.accounts[0].disconnect_kind, None,
            "the disconnect classification is session ephemera and must not survive a restore"
        );
        assert_eq!(loaded.accounts[1].connection_state, ConnectionState::Offline);
        assert_eq!(loaded.contacts[0].presence, ContactPresence::Offline);
        assert_eq!(loaded.contacts[0].status, None);

        assert_eq!(loaded.accounts[1].display_name, "Mila");
        assert_eq!(loaded.contacts[0].subscription, RosterSubscription::Mutual);
        assert_eq!(loaded.conversations[0].unread_count, 3);
        assert_eq!(loaded.messages[0].body, "hello");
        assert_eq!(loaded.messages[0].delivery_state, DeliveryState::Pending);
        assert_eq!(loaded.reactions[0].emoji, "👍");
    }

    #[test]
    fn load_state_with_metadata_reports_size_and_version_without_content() {
        let core = populated_core();
        let temp = TempState::new("metadata");

        save_state(&core.snapshot(), temp.path()).expect("save succeeds");
        let (snapshot, metadata) =
            load_state_with_metadata(temp.path()).expect("load succeeds").expect("file exists");

        assert_eq!(metadata.schema_version, CURRENT_SCHEMA_VERSION);
        let on_disk = std::fs::metadata(temp.path()).expect("state file metadata");
        assert_eq!(metadata.size_bytes, on_disk.len());
        // The metadata carries only counts-relevant envelope data: no account
        // JIDs, display names, message bodies, or reactions.
        assert_eq!(snapshot.accounts.len(), 2);
        assert_eq!(snapshot.messages.len(), 3);
    }

    #[test]
    fn load_state_with_metadata_returns_none_for_missing_file() {
        let temp = TempState::new("metadata-missing");
        let missing = temp.path().with_extension("never-written.json");
        assert_eq!(load_state_with_metadata(&missing).expect("missing file is not an error"), None);
    }
}
