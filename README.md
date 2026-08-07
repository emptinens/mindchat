# MindChat

MindChat is an open-source Android XMPP/Jabber client with a native Material 3
Expressive interface and a Rust domain core.

## Current state

This repository contains a runnable foundation:

- Kotlin + Jetpack Compose Android shell targeting Android 8+;
- Material 3 adaptive/chat/settings UI;
- Rust domain core with typed account, conversation, message, reaction, receipt,
  roster-contact/subscription, and capability models, a reconnect-safe outgoing
  projection, and a concrete Tokio/XMPP transport behind an internal coordinator;
- UniFFI-generated Kotlin contract and Android ABI build pipeline;
- local persistence for non-sensitive appearance and interaction preferences;
- optional biometric/device-credential app gate with secure-window handling;
- internal extension manifest and permission policy for future sandboxed
  customization, without a third-party code loader;
- unit tests for the Rust state machine and CI for formatting, linting, and tests.

The Rust transport uses StartTLS, SRV or explicit-host connection resolution,
initial service discovery, roster-result/push projection, and normalized contact
presence. The Android account form hands a password directly to the Rust session
startup call, then polls normalized native events and flushes queued text on an
IO dispatcher; it never stores the password in preferences or saved Compose state. Encrypted SQLCipher
persistence, account registration/subscription commands, MUC/MAM, OMEMO,
UnifiedPush integration, and the plugin runtime remain explicit follow-up
milestones described in [PLAN.md](PLAN.md).

The pre-runtime extension contract is described in
[docs/EXTENSIONS.md](docs/EXTENSIONS.md).

## Local checks

    cargo fmt --all -- --check
    cargo clippy --workspace --all-targets -- -D warnings
    cargo test --workspace

The Android project requires JDK 17+, Android SDK Platform 36, an NDK,
`cargo-ndk`, and the UniFFI CLI:

    cargo install cargo-ndk --locked
    cargo install uniffi --version 0.32.0 --features cli --locked
    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

Once those are installed:

    ./gradlew :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug

## License

Apache-2.0. See [LICENSE](LICENSE).
