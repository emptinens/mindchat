//! Internal extension policy boundary.
//!
//! MindChat does not load third-party code in the base application. These
//! types define the manifest, permission, command, and event vocabulary that
//! a future sandboxed extension runtime must enforce.
//!
//! This module is the canonical home of the extension contract
//! (`EXTENSION_API_VERSION = 1`, manifest shape, permissions; no third-party
//! loader). It is referenced by `ROADMAP.md`; the repository keeps no
//! separate extensions document by design.

use crate::{AccountId, ConversationId, CoreCommand, CoreError, CoreEvent, MessageId};
use std::collections::BTreeSet;
use std::fmt;

/// Version of the internal extension contract understood by this core.
pub const EXTENSION_API_VERSION: u16 = 1;

const MAX_EXTENSION_ID_CHARS: usize = 128;
const MAX_EXTENSION_NAME_CHARS: usize = 80;
const MAX_EXTENSION_VERSION_CHARS: usize = 64;

/// Permission that an extension can request and a host can approve.
///
/// Permissions are deliberately narrow: event observation exposes only stable
/// IDs, and command permissions are checked before the core mutates state.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum ExtensionPermission {
    ObserveAccountChanges,
    ObserveRosterChanges,
    ObserveConversationChanges,
    ObserveMessageChanges,
    SendMessages,
    MarkConversationsRead,
    AddReactions,
}

impl ExtensionPermission {
    /// Stable manifest spelling for this permission.
    #[must_use]
    pub const fn manifest_name(self) -> &'static str {
        match self {
            Self::ObserveAccountChanges => "observe_account_changes",
            Self::ObserveRosterChanges => "observe_roster_changes",
            Self::ObserveConversationChanges => "observe_conversation_changes",
            Self::ObserveMessageChanges => "observe_message_changes",
            Self::SendMessages => "send_messages",
            Self::MarkConversationsRead => "mark_conversations_read",
            Self::AddReactions => "add_reactions",
        }
    }

    /// Converts a stable manifest spelling into a known permission.
    #[must_use]
    pub fn from_manifest_name(value: &str) -> Option<Self> {
        match value {
            "observe_account_changes" => Some(Self::ObserveAccountChanges),
            "observe_roster_changes" => Some(Self::ObserveRosterChanges),
            "observe_conversation_changes" => Some(Self::ObserveConversationChanges),
            "observe_message_changes" => Some(Self::ObserveMessageChanges),
            "send_messages" => Some(Self::SendMessages),
            "mark_conversations_read" => Some(Self::MarkConversationsRead),
            "add_reactions" => Some(Self::AddReactions),
            _ => None,
        }
    }
}

/// Metadata intended for a future signed extension package.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ExtensionManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub api_version: u16,
    pub permissions: BTreeSet<ExtensionPermission>,
}

impl ExtensionManifest {
    /// Constructs manifest metadata. Call [`Self::validate`] before accepting
    /// metadata from an untrusted package or catalog.
    #[must_use]
    pub fn new(
        id: impl Into<String>,
        name: impl Into<String>,
        version: impl Into<String>,
        permissions: impl IntoIterator<Item = ExtensionPermission>,
    ) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            version: version.into(),
            api_version: EXTENSION_API_VERSION,
            permissions: permissions.into_iter().collect(),
        }
    }

    /// Validates stable metadata rules used by the future package verifier.
    pub fn validate(&self) -> Result<(), ExtensionManifestError> {
        if self.id.trim().is_empty() {
            return Err(ExtensionManifestError::EmptyId);
        }
        if self.id.chars().count() > MAX_EXTENSION_ID_CHARS || !is_valid_extension_id(&self.id) {
            return Err(ExtensionManifestError::InvalidId);
        }
        if self.name.trim().is_empty() || self.name.chars().count() > MAX_EXTENSION_NAME_CHARS {
            return Err(ExtensionManifestError::InvalidName);
        }
        if self.version.trim().is_empty()
            || self.version.chars().count() > MAX_EXTENSION_VERSION_CHARS
        {
            return Err(ExtensionManifestError::InvalidVersion);
        }
        if self.api_version != EXTENSION_API_VERSION {
            return Err(ExtensionManifestError::UnsupportedApiVersion {
                requested: self.api_version,
                supported: EXTENSION_API_VERSION,
            });
        }
        Ok(())
    }
}

/// Manifest metadata rejected before an extension can receive events or issue commands.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ExtensionManifestError {
    EmptyId,
    InvalidId,
    InvalidName,
    InvalidVersion,
    UnsupportedApiVersion { requested: u16, supported: u16 },
}

impl fmt::Display for ExtensionManifestError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::EmptyId => formatter.write_str("extension id cannot be empty"),
            Self::InvalidId => formatter.write_str(
                "extension id must be a lowercase, dot-separated reverse-domain identifier",
            ),
            Self::InvalidName => formatter.write_str("extension name is invalid"),
            Self::InvalidVersion => formatter.write_str("extension version is invalid"),
            Self::UnsupportedApiVersion { requested, supported } => {
                write!(
                    formatter,
                    "extension API {requested} is unsupported; core supports {supported}"
                )
            }
        }
    }
}

impl std::error::Error for ExtensionManifestError {}

/// An ID-only event visible to a permissioned extension.
///
/// Event delivery deliberately never includes message text, account credentials,
/// database handles, or cryptographic material.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ExtensionEvent {
    AccountChanged { account_id: AccountId },
    RosterChanged { account_id: AccountId },
    ConversationChanged { conversation_id: ConversationId },
    MessageAdded { message_id: MessageId },
    MessageChanged { message_id: MessageId },
}

/// Verified manifest plus host-approved permissions for an extension runtime.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ExtensionPolicy {
    manifest: ExtensionManifest,
    granted_permissions: BTreeSet<ExtensionPermission>,
}

impl ExtensionPolicy {
    /// Creates an enforceable policy from valid metadata and host-approved grants.
    ///
    /// Every grant must have been requested in the manifest. A future package
    /// verifier and consent flow are responsible for choosing the grants; this
    /// core merely keeps the distinction explicit and enforces the result.
    pub fn new(
        manifest: ExtensionManifest,
        granted_permissions: impl IntoIterator<Item = ExtensionPermission>,
    ) -> Result<Self, ExtensionPolicyError> {
        manifest.validate().map_err(ExtensionPolicyError::InvalidManifest)?;
        let granted_permissions = granted_permissions.into_iter().collect::<BTreeSet<_>>();
        if let Some(permission) =
            granted_permissions.iter().find(|permission| !manifest.permissions.contains(permission))
        {
            return Err(ExtensionPolicyError::UndeclaredGrant {
                extension_id: manifest.id.clone(),
                permission: *permission,
            });
        }
        Ok(Self { manifest, granted_permissions })
    }

    /// Returns the validated manifest metadata.
    #[must_use]
    pub fn manifest(&self) -> &ExtensionManifest {
        &self.manifest
    }

    /// Returns the subset of manifest permissions approved by the host.
    #[must_use]
    pub fn granted_permissions(&self) -> &BTreeSet<ExtensionPermission> {
        &self.granted_permissions
    }

    /// Checks the permission required by one command before core mutation.
    pub fn authorize_command(&self, command: &CoreCommand) -> Result<(), ExtensionCommandError> {
        let permission = required_permission(command);
        self.granted_permissions.contains(&permission).then_some(()).ok_or_else(|| {
            ExtensionCommandError::PermissionDenied {
                extension_id: self.manifest.id.clone(),
                permission,
            }
        })
    }

    /// Filters core events into the ID-only stream allowed by host grants.
    #[must_use]
    pub fn visible_events(&self, events: &[CoreEvent]) -> Vec<ExtensionEvent> {
        events
            .iter()
            .filter_map(|event| match event {
                CoreEvent::AccountChanged(account_id)
                    if self
                        .granted_permissions
                        .contains(&ExtensionPermission::ObserveAccountChanges) =>
                {
                    Some(ExtensionEvent::AccountChanged { account_id: *account_id })
                }
                CoreEvent::RosterChanged(account_id)
                    if self
                        .granted_permissions
                        .contains(&ExtensionPermission::ObserveRosterChanges) =>
                {
                    Some(ExtensionEvent::RosterChanged { account_id: *account_id })
                }
                CoreEvent::ConversationChanged(conversation_id)
                    if self
                        .granted_permissions
                        .contains(&ExtensionPermission::ObserveConversationChanges) =>
                {
                    Some(ExtensionEvent::ConversationChanged { conversation_id: *conversation_id })
                }
                CoreEvent::MessageAdded(message_id)
                    if self
                        .granted_permissions
                        .contains(&ExtensionPermission::ObserveMessageChanges) =>
                {
                    Some(ExtensionEvent::MessageAdded { message_id: *message_id })
                }
                CoreEvent::MessageChanged(message_id)
                    if self
                        .granted_permissions
                        .contains(&ExtensionPermission::ObserveMessageChanges) =>
                {
                    Some(ExtensionEvent::MessageChanged { message_id: *message_id })
                }
                _ => None,
            })
            .collect()
    }
}

/// Failure while converting declared manifest permissions into host grants.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ExtensionPolicyError {
    InvalidManifest(ExtensionManifestError),
    UndeclaredGrant { extension_id: String, permission: ExtensionPermission },
}

impl fmt::Display for ExtensionPolicyError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidManifest(error) => {
                write!(formatter, "invalid extension manifest: {error}")
            }
            Self::UndeclaredGrant { extension_id, permission } => write!(
                formatter,
                "extension {extension_id} cannot be granted undeclared {} permission",
                permission.manifest_name()
            ),
        }
    }
}

impl std::error::Error for ExtensionPolicyError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::InvalidManifest(error) => Some(error),
            Self::UndeclaredGrant { .. } => None,
        }
    }
}

/// An extension command denied by policy or rejected by domain validation.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ExtensionCommandError {
    PermissionDenied { extension_id: String, permission: ExtensionPermission },
    Core(CoreError),
}

impl fmt::Display for ExtensionCommandError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::PermissionDenied { extension_id, permission } => {
                write!(
                    formatter,
                    "extension {extension_id} lacks {} permission",
                    permission.manifest_name()
                )
            }
            Self::Core(error) => write!(formatter, "core rejected extension command: {error}"),
        }
    }
}

impl std::error::Error for ExtensionCommandError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::PermissionDenied { .. } => None,
            Self::Core(error) => Some(error),
        }
    }
}

impl From<CoreError> for ExtensionCommandError {
    fn from(value: CoreError) -> Self {
        Self::Core(value)
    }
}

/// Returns the single permission required to issue a command.
#[must_use]
pub const fn required_permission(command: &CoreCommand) -> ExtensionPermission {
    match command {
        CoreCommand::SendText { .. } => ExtensionPermission::SendMessages,
        CoreCommand::MarkConversationRead { .. } => ExtensionPermission::MarkConversationsRead,
        CoreCommand::AddReaction { .. } => ExtensionPermission::AddReactions,
    }
}

fn is_valid_extension_id(id: &str) -> bool {
    let mut segments = id.split('.');
    let Some(first) = segments.next() else {
        return false;
    };
    let mut segment_count = 1;
    if !is_valid_extension_id_segment(first) {
        return false;
    }
    for segment in segments {
        segment_count += 1;
        if !is_valid_extension_id_segment(segment) {
            return false;
        }
    }
    segment_count >= 2
}

fn is_valid_extension_id_segment(segment: &str) -> bool {
    let mut characters = segment.chars();
    matches!(characters.next(), Some(first) if first.is_ascii_lowercase())
        && !segment.ends_with('-')
        && characters.all(|character| {
            character.is_ascii_lowercase() || character.is_ascii_digit() || character == '-'
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn manifest_requires_a_stable_reverse_domain_id() {
        let mut manifest = ExtensionManifest::new("quick-replies", "Quick replies", "1.0.0", []);
        assert_eq!(manifest.validate(), Err(ExtensionManifestError::InvalidId));

        manifest.id = "org.mindchat.quick-replies".to_owned();
        assert_eq!(manifest.validate(), Ok(()));

        manifest.id = "org.mindchat.quick-replies-".to_owned();
        assert_eq!(manifest.validate(), Err(ExtensionManifestError::InvalidId));
    }

    #[test]
    fn permission_manifest_names_round_trip_and_reject_unknown_values() {
        assert_eq!(
            ExtensionPermission::from_manifest_name(
                ExtensionPermission::SendMessages.manifest_name()
            ),
            Some(ExtensionPermission::SendMessages)
        );
        assert_eq!(ExtensionPermission::from_manifest_name("read_everything"), None);
    }

    #[test]
    fn policy_filters_events_by_specific_observation_permissions() {
        let policy = ExtensionPolicy::new(
            ExtensionManifest::new(
                "org.mindchat.status-dot",
                "Status dot",
                "1.0.0",
                [
                    ExtensionPermission::ObserveMessageChanges,
                    ExtensionPermission::ObserveRosterChanges,
                ],
            ),
            [ExtensionPermission::ObserveMessageChanges, ExtensionPermission::ObserveRosterChanges],
        )
        .expect("manifest");
        let events = [
            CoreEvent::AccountChanged(1),
            CoreEvent::ConversationChanged(2),
            CoreEvent::MessageAdded(3),
            CoreEvent::MessageChanged(4),
            CoreEvent::RosterChanged(5),
        ];

        assert_eq!(
            policy.visible_events(&events),
            vec![
                ExtensionEvent::MessageAdded { message_id: 3 },
                ExtensionEvent::MessageChanged { message_id: 4 },
                ExtensionEvent::RosterChanged { account_id: 5 },
            ]
        );
    }

    #[test]
    fn policy_denies_requested_commands_that_the_host_did_not_grant() {
        let policy = ExtensionPolicy::new(
            ExtensionManifest::new(
                "org.mindchat.status-dot",
                "Status dot",
                "1.0.0",
                [ExtensionPermission::MarkConversationsRead],
            ),
            [],
        )
        .expect("manifest");

        assert_eq!(
            policy.authorize_command(&CoreCommand::MarkConversationRead { conversation_id: 1 }),
            Err(ExtensionCommandError::PermissionDenied {
                extension_id: "org.mindchat.status-dot".to_owned(),
                permission: ExtensionPermission::MarkConversationsRead,
            })
        );
    }

    #[test]
    fn policy_rejects_a_grant_that_the_manifest_did_not_request() {
        assert_eq!(
            ExtensionPolicy::new(
                ExtensionManifest::new("org.mindchat.status-dot", "Status dot", "1.0.0", []),
                [ExtensionPermission::SendMessages],
            ),
            Err(ExtensionPolicyError::UndeclaredGrant {
                extension_id: "org.mindchat.status-dot".to_owned(),
                permission: ExtensionPermission::SendMessages,
            })
        );
    }
}
