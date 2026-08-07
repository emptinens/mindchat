# Rust native binding contract

The `mindchat-core` crate builds as both `rlib` and `cdylib`. The Android app
uses generated UniFFI Kotlin bindings in the `com.mindchat.core` package. The
public ABI contains only immutable DTOs, commands, typed errors, and a
thread-safe `MindChatCoreHandle` object. Snapshot DTOs include normalized
account-scoped roster contacts but never roster XML or credentials.

## Build ABI artifacts

Install the Android NDK, `cargo-ndk`, and the version-matched UniFFI CLI:

    cargo install cargo-ndk --locked
    cargo install uniffi --version 0.32.0 --features cli --locked
    rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

Then build the ABI artifacts:

    scripts/build-rust-android.sh app/src/main/jniLibs

This emits `libmindchat_core.so` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

Generate Kotlin bindings from the same Rust metadata with:

    scripts/generate-uniffi-kotlin.sh app/build/generated/source/uniffi/main/kotlin

Gradle invokes both scripts during Android builds. It packages the generated
ABI libraries from `app/build/generated/jniLibs`; neither generated sources nor
native binaries are committed to the repository.

## Binding constraints

- UniFFI exposes immutable DTOs and command methods only. Kotlin uses the JNA
  Android AAR required by UniFFI's generated bindings.
- Kotlin receives `CoreEvent` notifications and refetches snapshots; it never
  receives XMPP XML, database connections, or cryptographic key material.
- `FfiContact` and `FfiContactPresence` are UI-safe roster projections. Kotlin
  can add/update a local projection through `upsert_contact`; subscription
  negotiation and server roster synchronization stay behind a future transport
  adapter.
- Kotlin owns Android Keystore, biometric prompts, media pickers, system
  notifications, and UnifiedPush transport registration.
- Rust owns account/session state, protocol feature discovery, normalized
  transport-event projection, reconnect-safe outgoing text queues, OMEMO
  session state, and encrypted storage access.
- The internal extension manifest and permission policy remain Rust-only. They
  are not a UniFFI plugin ABI and do not load third-party code; see
  [EXTENSIONS.md](EXTENSIONS.md).

`TransportCoordinator<T>` is an internal Rust-only helper that joins a
`MindChatCore` with an `XmppTransport` implementation. It is intentionally not
part of the UniFFI ABI: an adapter consumes a password for one connection
request, flushes pending messages in stable order, and applies normalized
events without exposing transport types or credentials to Kotlin.

`MindChatCoreHandle` is the only native object the Android gateway owns. A
native-unavailable fallback is restricted to local previews and test tooling;
release builds package the Rust ABI libraries.

## Android app lock

The optional app lock is an Android presentation concern and is not represented
in the UniFFI ABI. Its activity-scoped state machine contains only locked,
authenticating, and unlocked state. AndroidX `BiometricPrompt` requests either
a weak biometric or the device credential; no PIN, biometric sample, key, or
authentication token is persisted by MindChat.

When configured, the activity enables `FLAG_SECURE`, removes the chat UI while
locked, locks again after the app backgrounds, and retains the unlocked state
only across configuration changes. A cancelled prompt leaves the local gate
locked until the user explicitly retries. The preference cannot be enabled when
the current device lacks an enrolled biometric or device credential.
