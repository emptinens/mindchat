# MindChat implementation plan

## Product contract

MindChat is an Apache-2.0 Android 8+ XMPP/Jabber client for ordinary users. It
uses a Kotlin/Jetpack Compose Material 3 Expressive presentation layer and a
Rust domain core. The first public release supports multiple accounts and
arbitrary compatible XMPP servers, one-to-one conversations, MUC, roster
management, registration where a server supports it, text/media/audio messages,
localized Russian and English UI, and optional biometric app locking.

The client has no MindChat cloud account, product analytics, or mandatory
Google Play dependency. Settings are local and exportable without passwords or
OMEMO secrets. Android 12+ uses system dynamic color; Android 8–11 uses a
static Material 3 fallback. Theme packages are not a v1 feature; configurable
layout density and navigation are retained.

## Architecture

- `app/`: Kotlin Android application, Compose UI, Android Keystore, biometric
  gate, notification presentation, UnifiedPush adapter, and generated bindings.
- `crates/mindchat-core/`: Rust domain model, connection orchestration,
  protocol capabilities, persistence abstraction, encryption/key interfaces,
  and the public UniFFI-facing API.
- Kotlin communicates with Rust only through typed IDs, DTOs, commands,
  snapshots, errors, and core events. XMPP XML, crypto material, and database
  handles never cross that boundary.
- Release libraries are built as `cdylib` for `arm64-v8a`, `armeabi-v7a`, and
  `x86_64`, then packaged by Gradle. The project pins JDK/NDK versions in
  build configuration and generates Kotlin bindings as part of the build.

## Delivery milestones

1. **Foundation** — workspace, Android shell, Rust state core, typed interface,
   CI, linting, test fixtures, localization, accessibility semantics, and
   no-telemetry diagnostics policy.
2. **Protocol and persistence** — validate a Rust XMPP transport behind an
   internal adapter; implement service discovery, account login/registration,
   server-synchronized roster, MUC, MAM, Stream Management, encrypted database
   migrations, and media storage. Do not leak transport crate types through
   FFI.
3. **Messaging** — HTTP upload, receipts, markers, typing, reactions,
   corrections/retractions, replies, capability-gated shared pins, and
   reconnection-safe outgoing queues.
4. **Security and background delivery** — OMEMO device trust UX, Android
   Keystore-wrapped storage keys, optional biometric lock, UnifiedPush endpoint
   registration, and background-sync degradation when a distributor is absent.
5. **Extension readiness** — stabilize internal command/event/capability
   boundaries. The core now has a data-only manifest, narrowly scoped
   permissions, ID-only event filtering, and policy-mediated commands with
   account-owned attribution. A subsequent project supplies package parsing,
   sandboxing, user consent, SDK, documentation, signing, revocation, and a
   catalog. No third-party plugin loader belongs in the base release.

## Current implementation baseline

- Foundation is implemented: the Android Compose shell, Russian and English
  resources, adaptive Material 3 theming, Rust state model, generated UniFFI
  Kotlin binding, ABI packaging, linting, and CI all build from this workspace.
- The Rust core now includes an internal `TransportCoordinator`: it accepts a
  transport adapter, holds connection passwords only for the connect call,
  projects normalized connection/incoming/receipt events, and retries pending
  or failed outgoing text after a snapshot restore. Optional XMPP capabilities
  start unavailable and become usable only after service discovery projects
  them into the account. This is an internal adapter boundary, not an XMPP
  implementation yet.
- The Rust snapshot and UniFFI contract include account-scoped local roster
  contacts with normalized display names, presence/status projections, and
  roster-change events. Android renders that roster and can add a local contact;
  XMPP roster subscription and synchronization are still transport work.
- Customization choices (system colors, layout density, and app-lock preference)
  are persisted locally as non-sensitive Android preferences. The optional app
  lock uses AndroidX `BiometricPrompt` with biometric or device-credential
  authentication, locks on return from the background, and enables Android's
  secure-window flag while configured; it stores no authentication material.
  No account credentials, message content, or OMEMO material is stored there.
- Extension readiness is implemented only as an internal Rust policy boundary:
  validated reverse-domain manifest metadata, scoped command permissions, and
  ID-only event projections. It has no package parser, runtime, catalog, or
  third-party code loader; see `docs/EXTENSIONS.md` for the contract.
- The remaining protocol, encrypted persistence, OMEMO, push, and plugin-runtime
  work remains in milestones 2–5; the current app does not connect to an XMPP
  server.

## Acceptance and verification

- Rust tests cover account lifecycle, message ordering, reactions, read state,
  capability gates, invalid identifiers, and event delivery.
- Android tests cover navigation, adaptive layouts, Russian/English strings,
  dynamic-color fallback, screen-reader semantics, biometric gate behavior,
  and notification privacy.
- End-to-end test infrastructure uses a disposable XMPP server to validate
  MUC, MAM, SM reconnection, upload, OMEMO, and push fallback.
- Release verification builds all ABI variants, rejects Google-only runtime
  dependencies, and checks a reproducible F-Droid-compatible APK path.
