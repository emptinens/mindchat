# Changelog

All notable changes to MindChat are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Release reports from before 0.1.7 (specs, investigation write-ups, per-release
reports, `STATUS.md`, `PLAN.md`, `docs/`) were removed from the repository in
the roadmap cleanup commit; their content lives in git history.

## [Unreleased]

### Added

- `ROADMAP.md`: four-release plan (0.1.7 through 0.2.0) synthesized from 20
  domain advisor reports: customization and QoL polish (0.1.7), network and
  release-build hardening with a zero-log purge (0.1.8), storage encryption,
  transport hardening, OMEMO and privacy controls (0.1.9), donations and
  release engineering (0.2.0).
- `CHANGELOG.md`: canonical release history (this file).

### Changed

- Repository documentation restructured: `PLAN.md`, `STATUS.md` and `docs/`
  removed; README rewritten; CONTRIBUTING extended with security invariants
  and the native build; vendor patch rationale added to `Cargo.toml`.

## [0.1.6] - 2026-08-14

### Added

- Floating Material 3 Expressive dock: raised centered pill
  (`surfaceContainerHighest`, 28 dp shape, shadow) with an animated
  `secondaryContainer` selection pill, replacing the edge-to-edge
  `NavigationBar`.
- Detailed settings screen with M3 Expressive categories: Appearance
  (dynamic color, comfortable layout, accent row), Accounts (app lock,
  manage accounts, edit profile), Privacy (search / encryption switches with
  explicit not-implemented-yet state, no fake backing), Notifications
  (future rows), Storage (local size estimate, clear profile images with
  confirmation), About (version, licenses, repository link).

### Changed

- Poll path optimization: structural snapshot diffing skips UI rebuild and
  recomposition when nothing changed (~53 µs per unchanged poll); CONNECTING
  accounts always rebuild so stall detection keeps working.
- Reactions indexed by message id: message mapping is O(messages + reactions)
  instead of O(messages × reactions) (3.59 ms -> 0.49 ms in the fixture).
- 125/125 EN/RU string parity.

### Fixed

- Settings toggles pinned through the public gateway contract
  (`GatewayCustomizationContractTest`), removing Preview/Native drift risk.
- 70/70 JVM tests green across 8 suites.

## [0.1.5] - 2026-08-14

### Added

- XEP-0077 in-band registration: bounded one-shot session reusing the
  DNS/TLS machinery, capability-gated, with UI-safe refusals.
- Account management UI (Telegram-style modal drawer from the top-bar avatar
  chip): per-account connection state, one-tap switching, overflow menu
  (Edit profile, Reconnect, Disconnect, Rename, Delete with confirmation),
  add account.
- Per-account profiles: avatar (local image picker, copied into app
  storage), status message, display name, fixed M3 Expressive accent set.
- Conversation management: mark read, open as group, delete with
  confirmation; group-chat leave/delete.
- Contract-test discipline: management surface pinned through the public
  gateway (`GatewayManagementContractTest`), shared registration and
  fallback rules in `GatewayInput.kt`, pure snapshot mapping
  (`SnapshotMappingTest`), bindings regenerated from the current cdylib.

### Changed

- Material 3 Expressive theming: expressive color, type and shape polish.

## [0.1.4] - 2026-08-14

### Added

- Guaranteed terminal transport events: accounts can never stick in
  Connecting (worker panic synthesizes a recoverable `Disconnected`).
- XEP-0184 message receipts (request, acknowledgement, delivery projection).
- Event polling and outbox flushing made resilient (failing events consumed
  and skipped; Kotlin survives mid-batch failures).
- M3 Expressive login flow with stalled-connection recovery.
- Domain DTOs and capability gating for receipts, typing, markers and
  replies (protocol support remains partial; capabilities mark what is real).

### Fixed

- Always-Connecting investigation: bounded connect phase (per-attempt 10 s,
  whole phase 30 s), terminal auth failure classification, cancel-in-flight
  honored, EOF recovery.
- Local persistence restored across restarts (see 0.1.3).

## [0.1.3] - 2026-08-14

### Added

- Local persistence: accounts, chats and history restored across restarts;
  atomic, versioned JSON state file with a size bound and corrupt-file
  quarantine.
- Audit follow-ups for persistence and UI hardening.

## [0.1.2] - 2026-08-14

### Added

- Bounded connect phase so login can never hang (per-send, DNS, SRV,
  per-attempt and whole-phase timeouts).
- Vendored `tokio-xmpp` patch (root `Cargo.toml` `[patch.crates-io]`):
  terminal auth failure surfaced as `Suspended` instead of silent retry.
- Material 3 Expressive theming foundation; XMPP sessions wired into
  Android; local roster projections; extension policy boundary
  (`EXTENSION_API_VERSION = 1`, no third-party loader).

## 0.1.0/0.1.1

Foundational commits (Android app shell, Rust core, UniFFI boundary,
extension policy, roster projections). No tagged releases.

[Unreleased]: https://github.com/emptinens/mindchat/compare/v0.1.6...HEAD
[0.1.6]: https://github.com/emptinens/mindchat/compare/v0.1.4...v0.1.6
[0.1.4]: https://github.com/emptinens/mindchat/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/emptinens/mindchat/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/emptinens/mindchat/releases/tag/v0.1.2
