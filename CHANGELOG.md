# Changelog

All notable changes to MindChat are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Release reports from before 0.1.7 (specs, investigation write-ups, per-release
reports, `STATUS.md`, `PLAN.md`, `docs/`) were removed from the repository in
the roadmap cleanup commit; their content lives in git history.

## [Unreleased]

### Fixed

- Post-release `check-zero-log.sh` accepts `--so-dir DIR` in addition to the
  existing `--so-dir=DIR` form.

### Added

- `ROADMAP.md`: four-release plan (0.1.7 through 0.2.0) synthesized from 20
  domain advisor reports: customization and QoL polish (0.1.7), network and
  release-build hardening with a zero-log purge (0.1.8), storage encryption,
  transport hardening, OMEMO and privacy controls (0.1.9), release
  engineering and repo finalization (0.2.0; donations removed by product
  decision on 2026-08-15).
- `CHANGELOG.md`: canonical release history (this file).

### Changed

- Repository documentation restructured: `PLAN.md`, `STATUS.md` and `docs/`
  removed; README rewritten; CONTRIBUTING extended with security invariants
  and the native build; vendor patch rationale added to `Cargo.toml`.

## [0.1.8] - 2026-08-15

### Added

- Zero-log purge: raw-stanza capture path deleted from the vendored
  `tokio-xmpp` (the only code able to serialize message bodies), 72
  `log::` sites and 6 example binaries removed, live tests made silent,
  and a compile-time kill switch (direct `log`/`tracing` deps with
  `max_level_off` + `release_max_level_off`) that compiles every
  transitive log macro to nothing. `scripts/check-zero-log.sh` (grep gate
  + `.so` strings check) and a CI job enforce it.
- Network robustness: jittered, budgeted reconnect with XEP-0198 stream
  resume (full jitter, 1s doubling to 30s, 5-min budget, seeded PRNG);
  `auto_reconnect` per account (user disconnect stays immediate); idle
  watchdog + XEP-0199 keep-alive bounding stale-Online to ~3 minutes;
  resolution quality (all addresses capped at 4/port, SRV sorted by
  priority then weight, worker-cached resolver, Happy Eyeballs-lite v4/v6
  race); event batching (4x128, cap 512/cycle) and flush tuning
  (4s/16, worst-case lock ~14s).
- Proxy support: hand-written SOCKS5 (RFC 1928/1929) and HTTP CONNECT
  clients (no new dependencies), DNS resolved only at the proxy (SRV
  skipped, no local leaks), credentials runtime-only and never persisted,
  TLS unchanged against the real hostname via the vendored
  PreconnectedServerConnector. Additive FFI: `FfiProxyKind`,
  `FfiProxyConfig`, `FfiProxyProbe`, `setAccountProxies`,
  `accountProxies`, `testProxy`, `connectAccountWithProxy` (158→178
  signatures). UI: Connection settings with proxy library
  (add/edit/delete/ping, latency chip), per-account assignment, masked
  password fields, AES-256-GCM keystore-backed credential store.
- Release build: R8 minification + resource shrinking (DEX 22.8MB raw →
  3.18MB), ABI splits (arm64-v8a, armeabi-v7a, x86_64; JNA legacy ABIs
  gone), staged `llvm-strip`, `scripts/verify-release.sh` (size budgets,
  v2 signature, keep-rule audit, symbol counts, sha256sums), secret-driven
  signing with debug-cert fallback, `release.yml` workflow with per-ABI
  artifacts and an emulator smoke job.
- Diagnostics contract: `FfiDisconnectKind` classification
  (AuthenticationFailed / ServerRefused / NetworkLost / Cancelled /
  Unknown) with 100% variant coverage, kind-aware bucket labels
  (terminal / configuration / retryable / internal) above display-only
  prose, one-time dismissible quarantine notice, and an opt-in
  user-triggered `FfiDiagnosticsReport` export via `ACTION_CREATE_DOCUMENT`
  (structurally redacted: no passwords, bodies or avatars; redaction
  tests).

### Changed

- Poll loop drains up to 512 events/cycle; `FLUSH_SEND_TIMEOUT` 10s→4s,
  `FLUSH_OUTBOX_MAX_BATCH` 32→16.
- DNS-over-HTTPS (P1-2) deferred: `h2` is not in the offline cargo cache;
  re-evaluated when the cache allows.

### Fixed

- Mid-session loss was terminal (worker broke on `Disconnected`, vendored
  reconnector never ran); stale "Online" could persist up to ~10 minutes;
  both bounded now.
- Version bumped to 8 / 0.1.8.

## [0.1.7] - 2026-08-15

### Added

- Appearance engine: `AppearanceProfile` with shape scale (Compact /
  Standard), density (relaxed / comfortable / compact), text scale,
  animation speed, bubble style (rounded / speech-tail / outlined) and chat
  background, plus per-account bubble/background overrides in the profile
  sheet. Live-preview Appearance screen with immediate-apply controls and
  reset; defaults reproduce 0.1.6 byte-for-byte; legacy
  `comfortable_layout` preference migrated transparently.
- Typed settings platform: `SettingsSchema.kt` (`SettingKey<T>`,
  `SettingsSnapshot`), `SettingsNavigation.kt`, catalog-driven settings
  screen with search; storage keys byte-identical to legacy
  SharedPreferences (zero migration), gateway pinned via
  `setSetting`/`setAccountSetting`.
- Material 3 Expressive polish (P0 + P1): app-local motion tokens
  (`theme/Motion.kt`; material3 1.4.0 keeps its expressive motion API
  internal), WCAG-AA semantic status colors, lock/send icons, extraLarge
  dialog shapes, extended FAB, shape/type tokens, empty-state medallions,
  snackbar hosts with transient results, hoisted motion speed.
- Micro-animations (T1-T9): message entrance (fade+slide+scale) and
  delivery crossfade, unread-badge pop, reaction pop-in/out, dock selection
  animation, destination switch transition, haptics on send/dock/account
  switch, delivery-failed snackbar, animated presence/connection dots.
  Reduce-motion honored via system animation scales; instant at scale 0.
- Perf P0: poll loop gated by `ON_START` lifecycle (no FFI while stopped,
  immediate catch-up poll on resume); single `core.snapshot()` on the
  changed-poll path (was 2); per-locale `DateTimeFormatter` cache with
  byte-identical output; `SnapshotMappingBenchmarkTest` tripwires (diff
  < 2 ms, mapping < 50 ms at 10k) with p50s recorded.
- FFI stability CI job (`scripts/check-ffi-stability.sh` + golden binding
  API snapshot, 143 signatures); FFI surface frozen in 0.1.7.

### Changed

- Gateway split into shared pure layers: `GatewayMapping.kt`
  (snapshot-to-UI mapping, skip-rebuild diffing, timestamp formatting) and
  `GatewayPolicy.kt` (stall threshold, active-account fallback), unit-tested
  directly; `toggleComfortableLayout` replaced by `setAppearance`.

### Fixed

- Version bumped to 7 / 0.1.7.

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

[Unreleased]: https://github.com/emptinens/mindchat/compare/v0.1.8...HEAD
[0.1.8]: https://github.com/emptinens/mindchat/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/emptinens/mindchat/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/emptinens/mindchat/compare/v0.1.4...v0.1.6
[0.1.4]: https://github.com/emptinens/mindchat/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/emptinens/mindchat/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/emptinens/mindchat/releases/tag/v0.1.2
