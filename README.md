# MindChat

Private, open-source XMPP messaging for Android.

MindChat connects directly to the XMPP server you choose. There are no
MindChat servers, no cloud account, and no telemetry. The UI is Material 3
Expressive on Jetpack Compose, and the domain logic runs in a Rust core
behind a single generated FFI boundary.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![CI](https://github.com/emptinens/mindchat/actions/workflows/verify.yml/badge.svg)](https://github.com/emptinens/mindchat/actions/workflows/verify.yml)

## Features

- Multi-account chat with one-tap profile switching and per-account profiles
  and accents.
- One-to-one messaging with roster, presence, and XEP-0184 message receipts.
- XEP-0077 in-band registration where the server supports it.
- StartTLS and direct TLS, SRV or explicit-host resolution, XEP-0030 service
  discovery.
- Material 3 Expressive adaptive UI with dynamic color and a floating dock.
- Optional biometric or device-credential app lock.
- English and Russian, with strict 1:1 string parity.
- Local persistence of non-secret state; credentials are never stored.
- Zero logs, zero analytics, zero crash reporting, zero network traffic
  beyond the XMPP connection you configure.

In development: MUC, MAM, OMEMO, UnifiedPush, plugins. See
[ROADMAP.md](ROADMAP.md).

## Privacy by design

MindChat operates no servers, has no cloud account, and never transmits
anything to MindChat-operated infrastructure. The app talks only to the XMPP
server the user chooses.

MindChat logs nothing. There is no analytics, no crash reporting, and no
telemetry; failures are surfaced as typed UI error states. Credentials are
passed directly to the Rust session and are never persisted. The only
permissions requested are the ones the feature set needs (`INTERNET`), and
backups of app data are disabled.

One honest caveat: the local state file (non-secret message history and
settings) is not yet encrypted at rest. Storage encryption is planned for
0.1.9; see [ROADMAP.md](ROADMAP.md).

## Screenshots

<!-- Add 3-4 screenshots here when the 0.2.0 UI is stable.
     Put them in screenshots/ and reference them with relative paths, e.g.
     ![Chats](screenshots/chats.png) -->

## Install

### F-Droid

An F-Droid listing is in preparation for 0.2.0. The project builds
reproducibly from source, and F-Droid rebuilds will use its own signing key.

### APK

Release APKs are attached to GitHub Releases. Locally built APKs are signed
with the debug certificate; install them only on your own devices.

## Build from source

Prerequisites:

- JDK 17 or newer
- Android SDK Platform 36 and Build-Tools 35.0.0 / 36.0.0
- NDK (for the native build) and `cargo-ndk`
- Rust toolchain pinned by `rust-toolchain.toml`
- `uniffi` CLI 0.32.0: `cargo install uniffi --version 0.32.0 --features cli --locked`

Rust checks:

```sh
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

Android build (regenerates UniFFI bindings and assembles the native ABIs):

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

CI is the authoritative native assembly. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the full developer procedure, including the opt-in live test suites.

## Roadmap

Current release: 0.1.6. The plan for 0.1.7 through 0.2.0 (customization and
QoL polish, network and build hardening, security and privacy, donations and
release engineering) lives in [ROADMAP.md](ROADMAP.md).

## Contributing

Contributions are welcome in the Rust core, the Kotlin UI, and translations.
MindChat accepts no third-party code loaders by design. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License

Apache-2.0. See [LICENSE](LICENSE).
