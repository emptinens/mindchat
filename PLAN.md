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
- The Rust core now includes `TokioXmppTransport`, a concrete internal
  `tokio-xmpp` adapter behind `TransportCoordinator`. It starts encrypted
  StartTLS sessions through SRV discovery or an explicit host/port, keeps all
  Tokio/XML/TLS types outside the domain and UniFFI contracts, sends direct
  text, projects incoming direct messages, and keeps retryable outgoing text
  in stable order after a snapshot restore. The core does not retain a password;
  the active transport worker owns the credential needed for its authenticated
  session and reconnect attempts.
- Initial XEP-0030 discovery projects advertised capabilities, including stream
  management when present in stream features. The concrete adapter requests the
  roster on each online transition, acknowledges roster pushes, projects roster
  add/change/removal events, and maps contact presence/status. Contact snapshots
  now preserve server-confirmed subscription direction. The Android account
  form accepts a password only in non-saveable UI memory, hands it directly to
  the native session startup call, and then clears the form when startup is
  accepted. The Rust-owned session's event polling and queued-outbox flushing
  run from an Android lifecycle-bound IO coroutine; its snapshots update
  connection, roster, incoming-message, and queued-outbox projections on the
  Compose dispatcher.
  Credentials are not persisted. Server-side subscribe / unsubscribe commands,
  account registration, and reconnect credential UX remain protocol work.
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
  work remains in milestones 2–5. Packaged Android builds now use the native
  credential/session boundary for newly configured accounts; account and message
  persistence are still intentionally absent, so credentials are re-entered
  after a process restart.

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
