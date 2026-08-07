# MindChat extension boundary

MindChat has an internal extension contract for future customization and
automation. The Android app currently **does not load, download, interpret, or
execute third-party extension code**. There is no plugin catalog, package
parser, dynamic class loader, scripting engine, or native ABI for extensions in
this repository.

The Rust core provides data-only manifest types and a mandatory policy object
so that a later sandboxed runtime has one narrow, testable command and event
boundary to integrate with.

## Manifest shape

`EXTENSION_API_VERSION` is currently `1`. A future signed package descriptor
maps to `ExtensionManifest` using this shape:

```toml
id = "org.example.quick-replies"
name = "Quick replies"
version = "1.0.0"
api_version = 1
permissions = [
  "observe_message_changes",
  "send_messages",
]
```

The core accepts only lowercase, dot-separated reverse-domain IDs with at
least two segments. It also bounds identifier, display-name, and version
lengths, and rejects API versions other than the one it understands. The
manifest lists *requested* permissions. A host constructs `ExtensionPolicy`
with an explicitly approved subset; it rejects any grant not requested by the
manifest. Parsing, signature verification, package installation, and the
consent UI deliberately remain outside the current core.

`ExtensionPermission::from_manifest_name` is the canonical mapping for a
future parser; unknown permission spellings must be rejected rather than
silently ignored.

## Permissions

| Permission | Grants |
| --- | --- |
| `observe_account_changes` | ID-only account-change events |
| `observe_conversation_changes` | ID-only conversation-change events |
| `observe_message_changes` | ID-only message-added and message-changed events |
| `send_messages` | `CoreCommand::SendText` |
| `mark_conversations_read` | `CoreCommand::MarkConversationRead` |
| `add_reactions` | `CoreCommand::AddReaction` |

`ExtensionPolicy::visible_events` filters each event separately according to
the host-approved grants. It never provides message bodies, roster data,
account passwords, database handles, OMEMO material, transport objects, or
Android objects. `ExtensionPolicy` checks each command before the core mutates
state.

For message and reaction commands, `MindChatCore::execute_extension_command`
derives the sender from the account that owns the target conversation. An
extension cannot select a JID to impersonate through this boundary. Existing
domain validation and server-capability checks still apply after permission
authorization.

## Required work before any extension runtime

The following remain intentionally unimplemented:

1. signed package format, key rotation, verification, and revocation;
2. an isolated runtime with resource quotas and a deterministic lifecycle;
3. user-facing consent, per-account/per-conversation scope, disable/uninstall,
   and data-erasure behavior;
4. a stable SDK, compatibility test suite, documentation, and catalog policy;
5. audit logging and failure containment; and
6. a renderer/UI contribution model that preserves Material 3 accessibility
   and localization behavior.

Until those parts exist, extension manifests and policies are only internal
core contracts exercised by unit tests.
