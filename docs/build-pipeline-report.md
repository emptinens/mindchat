# MindChat FFI Boundary & Build Pipeline Report

Scope: `crates/mindchat-core/src/ffi.rs`, `crates/mindchat-core/uniffi.toml`, `crates/mindchat-core/Cargo.toml`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `scripts/build-rust-android.sh`, `scripts/generate-uniffi-kotlin.sh`, `rust-toolchain.toml`, `rustfmt.toml`, `.github/workflows/verify.yml`, `Cargo.toml`, `Cargo.lock`. All line references are to the current tree.

## 1. UniFFI contract

The FFI surface lives entirely in `crates/mindchat-core/src/ffi.rs` (849 lines). It is compiled only under the `uniffi` cargo feature: `#[cfg(feature = "uniffi")] pub mod ffi;` (`crates/mindchat-core/src/lib.rs:19-20`) and the scaffolding macro is `uniffi::setup_scaffolding!();` (`crates/mindchat-core/src/lib.rs:23`). `ffi.rs` deliberately translates the domain model instead of exporting it: module doc says Kotlin "receives only immutable, display-safe records and commands" (`ffi.rs:3-6`). Domain types (`MindChatCore`, `ConnectionState`, `CoreError`, `TransportCoordinator`, `SecretString`, ...) are imported from `crate` (`ffi.rs:8-13`) and converted via `From` impls (`ffi.rs:28-48`, `59-79`, `91-113`, `636-735`); the internal `TransportCoordinator` is wrapped in a `Mutex` inside the object (`ffi.rs:416-419`) and Kotlin never sees the inner state machine.

### Exported functions (17 total: 1 constructor + 15 methods + 1 free function)

All under `#[uniffi::export] impl MindChatCoreHandle` (`ffi.rs:432`):

| # | Signature (Rust source form) | Location |
|---|---|---|
| 1 | `MindChatCoreHandle::new() -> Arc<Self>` (`#[uniffi::constructor]`) | ffi.rs:438-447 |
| 2 | `add_account(jid: String, server: String, display_name: String) -> Result<u64, MindChatBindingError>` | ffi.rs:450-460 |
| 3 | `set_connection_state(account_id: u64, state: FfiConnectionState) -> Result<(), MindChatBindingError>` | ffi.rs:463-469 |
| 4 | `set_capabilities(account_id: u64, capabilities: Vec<FfiProtocolCapability>) -> Result<(), MindChatBindingError>` | ffi.rs:472-481 |
| 5 | `upsert_contact(account_id: u64, jid: String, display_name: String, presence: FfiContactPresence, status: Option<String>) -> Result<(), MindChatBindingError>` | ffi.rs:484-496 |
| 6 | `open_conversation(account_id: u64, kind: FfiConversationKind, address: String, title: String, now_epoch_ms: u64) -> Result<u64, MindChatBindingError>` | ffi.rs:499-511 |
| 7 | `send_text(conversation_id: u64, sender: String, body: String, in_reply_to: Option<u64>, now_epoch_ms: u64) -> Result<u64, MindChatBindingError>` | ffi.rs:514-526 |
| 8 | `receive_text(conversation_id: u64, sender: String, body: String, now_epoch_ms: u64) -> Result<u64, MindChatBindingError>` | ffi.rs:529-540 |
| 9 | `add_reaction(message_id: u64, actor: String, emoji: String) -> Result<u64, MindChatBindingError>` | ffi.rs:543-550 |
| 10 | `mark_conversation_read(conversation_id: u64) -> Result<(), MindChatBindingError>` | ffi.rs:553-555 |
| 11 | `connect_account(account_id: u64, password: String) -> Result<(), MindChatBindingError>` | ffi.rs:561-572 |
| 12 | `disconnect_account(account_id: u64) -> Result<(), MindChatBindingError>` | ffi.rs:575-577 |
| 13 | `poll_transport_events(max_events: u32) -> Result<u32, MindChatBindingError>` (clamped to `MAX_TRANSPORT_EVENTS_PER_POLL = 128`, `ffi.rs:17, 583-594`) | ffi.rs:583-594 |
| 14 | `flush_outbox(account_id: u64) -> Result<u32, MindChatBindingError>` | ffi.rs:600-616 |
| 15 | `snapshot() -> Result<FfiCoreSnapshot, MindChatBindingError>` | ffi.rs:619-621 |
| 16 | `drain_events() -> Result<Vec<FfiCoreEvent>, MindChatBindingError>` | ffi.rs:624-626 |
| 17 | `mindchat_binding_version() -> String` (free fn, returns `env!("CARGO_PKG_VERSION")`, i.e. `"0.1.0"`; `#[uniffi::export]` at ffi.rs:630) | ffi.rs:630-634 |

### Types crossing the boundary

- **Records (7)**: `FfiAccount` (ffi.rs:255-263), `FfiContact` (ffi.rs:266-274), `FfiConversation` (ffi.rs:277-286), `FfiAttachment` (ffi.rs:289-296), `FfiMessage` (ffi.rs:299-311), `FfiReaction` (ffi.rs:314-320), `FfiCoreSnapshot` (ffi.rs:324-331).
- **Enums (9)**: `FfiConnectionState` (ffi.rs:20-26), `FfiContactPresence` (ffi.rs:51-57), `FfiRosterSubscription` (ffi.rs:82-89), `FfiConversationKind` (ffi.rs:116-120), `FfiMessageDirection` (ffi.rs:141-145), `FfiDeliveryState` (ffi.rs:157-164), `FfiMessageKind` (ffi.rs:179-184), `FfiProtocolCapability` (13 variants, ffi.rs:197-212), `FfiCoreEvent` (5 variants with payload fields, ffi.rs:335-342).
- **Error (1)**: `MindChatBindingError` (ffi.rs:345-353), variants `InvalidInput { detail }`, `NotFound { detail }`, `CapabilityUnavailable { capability }`, `AuthenticationFailed`, `ConnectionFailed { detail }`, `Internal { detail }`; `Display` at ffi.rs:355-368; conversions from `CoreError`, `TransportError`, `TransportCoordinatorError` at ffi.rs:372-410.
- **Object (1)**: `MindChatCoreHandle` (`#[derive(uniffi::Object)]`, ffi.rs:416-419) holding `Mutex<TransportCoordinator<TokioXmppTransport>>`; poisoned-lock mapped to `Internal` (ffi.rs:422-430).

Secrets never cross: `connect_account` wraps the password in `SecretString` (ffi.rs:571), rejects empty passwords (ffi.rs:566-570), and the snapshot record comment states "No XML, database handles, passwords, or cryptographic key material cross this boundary" (ffi.rs:322-323).

### ffi.rs vs the rest of the core

`ffi.rs` is a thin translation/ownership layer: it owns the lock around `TransportCoordinator<TokioXmppTransport>` and forwards every call to domain logic (`core_mut().add_account(...)`, etc.). Domain behavior lives in `lib.rs` (`MindChatCore` state machine, validation, `TransportCoordinator`, lib.rs:363-1204), `transport.rs` (trait + `SecretString`), `xmpp.rs` (concrete `TokioXmppTransport`, gated on `xmpp-transport`), and `extension.rs` (extension policy). The `uniffi` feature pulls in `xmpp-transport` (`crates/mindchat-core/Cargo.toml:16`), so a UniFFI build includes the Tokio/tokio-xmpp stack.

UniFFI config `crates/mindchat-core/uniffi.toml:1-4`: `package_name = "com.mindchat.core"`, `android = true`, `generate_immutable_records = true`.

## 2. Bindings generation

- **Tool**: `uniffi-bindgen` from the `uniffi` crate. Version is pinned to 0.32.0 on the crate side (`uniffi = { version = "0.32.0", default-features = false, optional = true }`, `crates/mindchat-core/Cargo.toml:26`) and in Cargo.lock (all of `uniffi`, `uniffi_core`, `uniffi_internal_macros`, `uniffi_macros`, `uniffi_meta`, `uniffi_pipeline` lock at 0.32.0). CI installs the CLI explicitly: `cargo install uniffi --version 0.32.0 --features cli --locked` (`.github/workflows/verify.yml:39`). The script itself only checks that `uniffi-bindgen` exists on PATH, never its version (`scripts/generate-uniffi-kotlin.sh:5-8`) - a local drift risk.
- **When**: `app/build.gradle.kts:28-44` registers a `generateUniffiKotlin` `Exec` task; every task whose name ends in `Kotlin` (except the generator itself) depends on it (`app/build.gradle.kts:119-123`), so compilation of Kotlin sources is blocked until bindings exist.
- **How**: `scripts/generate-uniffi-kotlin.sh` builds the host-side release cdylib (`cargo build --release --package mindchat-core --features uniffi`, script line 17) then runs `uniffi-bindgen generate --library "$LIB_PATH" --language kotlin --out-dir "$OUT_DIR" --no-format` (script lines 20-25). Host library default is `target/release/libmindchat_core.dylib` on Darwin, `.so` elsewhere (script lines 11-14). Uses `--library` mode, extracting the contract from the built host binary.
- **Output**: default `app/build/generated/source/uniffi/main/kotlin` (script line 10; Gradle passes `layout.buildDirectory.dir("generated/source/uniffi/main/kotlin")`, `app/build.gradle.kts:8`); wired into the source set with `java.srcDir(uniffiKotlinDir)` (`app/build.gradle.kts:85`). Kotlin package `com.mindchat.core` per uniffi.toml:2; consumed at `MindChatGateway.kt:8-16` (imports `com.mindchat.core.MindChatCoreHandle`, `MindChatBindingException`, etc.).
- **Task inputs**: `Cargo.toml`, `Cargo.lock`, `rust-toolchain.toml`, whole `crates/` tree; outputs declared as the generated dir (`app/build.gradle.kts:37-43`).

## 3. Android ABI packaging

- **Target ABIs (3)**: `arm64-v8a`, `armeabi-v7a`, `x86_64` (`scripts/build-rust-android.sh:24-29`), via `cargo ndk --platform 26`. There is no `x86` target. The same three targets are installed in CI (`verify.yml:36`). `--platform 26` matches `minSdk = 26` (`app/build.gradle.kts:53`).
- **How the cdylib enters the APK**: `app/build.gradle.kts:10-26` registers `buildRustAndroid` (`Exec`) calling `scripts/build-rust-android.sh` with output `app/build/generated/jniLibs` (`app/build.gradle.kts:7, 17`). The directory is added to the main source set via `jniLibs.srcDir(nativeJniLibsDir)` (`app/build.gradle.kts:84`), and `preBuild` depends on `buildRustAndroid` (`app/build.gradle.kts:125-127`), so AGP packages the three `libmindchat_core.so` files into the APK before `assembleDebug`. Script inputs/outputs declared at `app/build.gradle.kts:19-25`.
- **cargo-ndk**: presence-checked by the script (`build-rust-android.sh:5-8`); version pinned only in CI (`cargo-ndk --version 4.1.2`, `verify.yml:39`).
- **NDK pin**: `ndkVersion = "29.0.14206865"` (`app/build.gradle.kts:49`); script fallback `PINNED_NDK="$SDK_ROOT/ndk/29.0.14206865"` when `ANDROID_NDK_HOME` is unset (`build-rust-android.sh:13-22`); CI installs `ndk;29.0.14206865` (`verify.yml:38`). Note: an externally set `ANDROID_NDK_HOME` overrides the pin silently.
- **JDK pin**: Java 17 source/target and Kotlin `jvmTarget = "17"` (`app/build.gradle.kts:72-78`); CI `temurin` 17 (`verify.yml:30-31`).

## 4. Gradle structure

**Root module** (`build.gradle.kts:1-5`): three plugins applied `false` for the whole project: `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`. `settings.gradle.kts:1-18`: plugin repos `google()`, `mavenCentral()`, `gradlePluginPortal()`; `repositoriesMode = FAIL_ON_PROJECT_REPOS` with `google()` + `mavenCentral()`; `rootProject.name = "MindChat"`; `include(":app")`. `gradle.properties:1-4`: `org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8`, `org.gradle.parallel=true`, `android.useAndroidX=true`, `kotlin.code.style=official`.

**Version catalog** (`gradle/libs.versions.toml`), versions (lines 1-12): `agp 8.11.0`, `kotlin 2.1.0`, `composeBom 2025.01.00`, `activityCompose 1.10.0`, `coreKtx 1.15.0`, `lifecycle 2.8.7`, `biometric 1.1.0`, `jna 5.19.1`, `junit 4.13.2`, `androidxTestExtJunit 1.2.1`, `espresso 3.6.1`. Libraries (17 declarations, lines 14-31): `androidx-core-ktx`, `androidx-lifecycle-runtime-ktx`, `androidx-lifecycle-runtime-compose`, `androidx-activity-compose`, `androidx-biometric`, `androidx-compose-bom`, `androidx-compose-ui`, `androidx-compose-ui-tooling`, `androidx-compose-ui-tooling-preview`, `androidx-compose-material3`, `androidx-compose-material-icons` (`material-icons-core`), `jna`, `junit`, `androidx-compose-ui-test-junit4`, `androidx-compose-ui-test-manifest`, `androidx-test-ext-junit`, `androidx-test-espresso-core`. Plugins (3, lines 33-36): `android-application`, `kotlin-android`, `kotlin-compose`.

**App module** (`app/build.gradle.kts`): `namespace = "com.mindchat.app"`, `applicationId = "com.mindchat.app"`, `compileSdk = 36`, `ndkVersion = "29.0.14206865"`, `minSdk = 26`, `targetSdk = 36`, `versionCode = 1`, `versionName = "0.1.0-dev"` (lines 47-56); `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`, `vectorDrawables.useSupportLibrary = true` (lines 58-59). `buildTypes.release`: `isMinifyEnabled = false`, proguard from `proguard-android-optimize.txt` + `proguard-rules.pro` (lines 62-70). Compile options Java 17 (lines 72-78); `buildFeatures { compose = true; buildConfig = true }` (lines 79-82); packaging excludes `/META-INF/{AL2.0,LGPL2.1}` (lines 87-89).

Dependencies (lines 92-117): main - core-ktx, lifecycle-runtime-ktx, lifecycle-runtime-compose, activity-compose, biometric, compose-bom (platform), compose-ui, compose-ui-tooling-preview, compose-material3, material-icons-core, `jna` as an AAR (`artifact { name = "jna"; type = "aar" }`, lines 103-108; the UniFFI-generated Kotlin runtime is JNA-based, hence the AAR variant); debug - compose-ui-tooling, compose-ui-test-manifest; test - junit; androidTest - compose-bom platform, compose-ui-test-junit4, test-ext-junit, espresso-core.

**Lint config**: none declared. No `lint {}` block in either `build.gradle.kts`, no `lint.xml`, no `lintOptions` (verified by grep across `*.kts`, `*.xml`, `*.toml`, `*.properties`). CI runs `:app:lintDebug` (`verify.yml:41`) against AGP defaults.

**Test config**: unit tests in `app/src/test/java/com/mindchat/app/AppLockStateMachineTest.kt`; instrumented tests in `app/src/androidTest/java/com/mindchat/app/MindChatAppTest.kt` using compose-ui-test-junit4, test-ext-junit, espresso-core, AndroidJUnitRunner (lines 58, 113-116). CI compiles the androidTest sources (`:app:compileDebugAndroidTestKotlin`, `verify.yml:41`) but runs no instrumented tests (no emulator in CI).

## 5. Manifest (`app/src/main/AndroidManifest.xml`)

**Permissions (3)**:
- `android.permission.INTERNET` (line 3) - required for XMPP network traffic; the Rust transport (`tokio-xmpp` with `webpki-roots`, `Cargo.toml:25`) opens sockets. No network code exists in the Kotlin shell.
- `android.permission.POST_NOTIFICATIONS` (line 4) - Android 13+ runtime notification permission; declared but no notification code exists in `app/src/main/java` (grep finds no `NotificationManager`/notification usage), so it is forward-looking (chat message notifications).
- `android.permission.RECORD_AUDIO` (line 5) - declared but no `AudioRecord`/`MediaRecorder` code exists in the shell; justified by the domain model's `MessageKind::Voice` (`ffi.rs:179-184`, lib.rs:114-118) for future voice messages. No runtime request code for either runtime permission exists yet.

**Components**: exactly one, `MainActivity`, `android:exported="true"` with MAIN/LAUNCHER intent-filter (lines 16-23). **No services, receivers, or providers** - there is no background XMPP service. `MainActivity` is a `FragmentActivity` hosting Compose (`MainActivity.kt:10`).

**Application attributes** (lines 7-15): `allowBackup="false"`; `dataExtractionRules="@xml/data_extraction_rules"` and `fullBackupContent="@xml/backup_rules"` - both XML files exclude everything (`<exclude domain="root" path="." />`, `res/xml/backup_rules.xml:3`, `res/xml/data_extraction_rules.xml:3-8`, the cloud-backup rule additionally `disableIfNoEncryptionCapabilities="true"`); `supportsRtl="true"`; icon/roundIcon `@drawable/ic_mindchat`; theme `Theme.MindChat`. No `android:usesCleartextTraffic`, no network security config (XMPP over TLS only).

## 6. Build scripts

**`scripts/build-rust-android.sh`** (30 lines, `set -eu` at line 3):
1. Verify `cargo-ndk` is available, else print install hint (`cargo install cargo-ndk --locked`) and exit 1 (lines 5-8).
2. `OUT_DIR=${1:-app/src/main/jniLibs}`; `mkdir -p "$OUT_DIR"` (lines 10-11).
3. NDK resolution: if `ANDROID_NDK_HOME` unset, derive `SDK_ROOT` from `ANDROID_SDK_ROOT`/`ANDROID_HOME`, falling back to `$HOME/Library/Android/sdk` on Darwin (lines 13-17); if the pinned `ndk/29.0.14206865` directory exists, export it as `ANDROID_NDK_HOME` (lines 18-21). If no NDK is found, `cargo ndk` fails on its own.
4. Build: `cargo ndk --platform 26 --target arm64-v8a --target armeabi-v7a --target x86_64 --output-dir "$OUT_DIR" build --release --package mindchat-core --features uniffi` (lines 24-30).

Idempotency: `mkdir -p` and cargo's incremental build make re-runs cheap and safe; no clean step, no forced rebuild. Error handling: `set -eu` aborts on first failure; missing cargo-ndk produces a targeted message with exit 1. Environment override risk: a user-set `ANDROID_NDK_HOME` bypasses the version pin (lines 13-22), and the default `OUT_DIR` (`app/src/main/jniLibs`) differs from the Gradle-injected `build/generated/jniLibs` (app/build.gradle.kts:7), so manual runs can drop stale libs into a path AGP also picks up.

**`scripts/generate-uniffi-kotlin.sh`** (25 lines, `set -eu` at line 3):
1. Verify `uniffi-bindgen` is on PATH (presence only, no version check), else print install hint (`cargo install uniffi --features cli --locked`) and exit 1 (lines 5-8).
2. `OUT_DIR=${1:-app/build/generated/source/uniffi/main/kotlin}`; `LIB_PATH=${2:-target/release/libmindchat_core.{dylib|so}}` chosen by `uname -s` (lines 10-15).
3. Build host release lib: `cargo build --release --package mindchat-core --features uniffi` (line 17).
4. `mkdir -p "$OUT_DIR"` (line 18).
5. `uniffi-bindgen generate --library "$LIB_PATH" --language kotlin --out-dir "$OUT_DIR" --no-format` (lines 20-25).

Idempotency: regenerates over the same output directory; safe to re-run. Error handling: `set -eu`; missing CLI exits 1 with a hint. Risks: unpinned CLI version (bindings may mismatch the 0.32.0 scaffolding), and `--library` mode requires the host lib contract to match the per-ABI builds (both use `--features uniffi`, currently consistent).

## 7. CI (`.github/workflows/verify.yml`, 1 workflow)

Workflow `Verify` (line 1). Triggers: `pull_request` and `push` to `main` (lines 3-6). `permissions: contents: read` (line 8).

**Job `rust`** (ubuntu-latest, lines 12-22):
- `actions/checkout@v4`; `dtolnay/rust-toolchain@master` with `toolchain: 1.97.1`, `components: rustfmt, clippy` (lines 15-19).
- `cargo fmt --all -- --check` (line 20).
- `cargo clippy --workspace --all-targets --all-features -- -D warnings` (line 21).
- `cargo test --workspace --all-features` (line 22).

**Job `android`** (ubuntu-latest, lines 24-41):
- checkout; `actions/setup-java@v4` temurin 17; `android-actions/setup-android@v3`; rust-toolchain 1.97.1 with `targets: aarch64-linux-android,armv7-linux-androideabi,x86_64-linux-android` (lines 27-36).
- `yes | sdkmanager --licenses`; `sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.14206865"` (lines 37-38).
- `cargo install cargo-ndk --version 4.1.2 --locked && cargo install uniffi --version 0.32.0 --features cli --locked` (line 39).
- `chmod +x gradlew scripts/...` (line 40).
- `./gradlew :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --max-workers=2` (line 41).

No caching (no gradle/rust cache actions); `cargo install` compiles both CLIs from source on every run. No instrumented tests run. No release build in CI.

## 8. Version matrix

| Component | Version | Evidence |
|---|---|---|
| Rust toolchain | 1.97.1 (components: clippy, rustfmt; profile minimal) | `rust-toolchain.toml:2-4`; CI `verify.yml:18, 35` |
| Workspace MSRV (informational) | 1.88 | `Cargo.toml:9` (`rust-version.workspace` -> `Cargo.toml:9`) |
| UniFFI (crate + CLI) | 0.32.0 | `crates/mindchat-core/Cargo.toml:26`; Cargo.lock (uniffi* all 0.32.0); `verify.yml:39` |
| cargo-ndk | 4.1.2 (CI only; unpinned in script) | `verify.yml:39` |
| Gradle wrapper | 8.14.3 | `gradle/wrapper/gradle-wrapper.properties:3` |
| AGP | 8.11.0 | `gradle/libs.versions.toml:2` |
| Kotlin | 2.1.0 | `gradle/libs.versions.toml:3` |
| JDK | 17 (source/target/jvmTarget; CI temurin 17) | `app/build.gradle.kts:72-78`; `verify.yml:30-31` |
| NDK | 29.0.14206865 | `app/build.gradle.kts:49`; `build-rust-android.sh:18`; `verify.yml:38` |
| compileSdk / targetSdk | 36 / 36 | `app/build.gradle.kts:48, 54` |
| minSdk | 26 | `app/build.gradle.kts:53`; `build-rust-android.sh:25` (`--platform 26`) |
| Build tools | 36.0.0 (CI) | `verify.yml:38` |
| Rust edition / rustfmt | 2024; max_width 100, use_small_heuristics Max | `Cargo.toml:6`; `rustfmt.toml:1-3` |

Other: `versionCode 1`, `versionName 0.1.0-dev` (`app/build.gradle.kts:55-56`); core crate `version = "0.1.0"` (`crates/mindchat-core/Cargo.toml:3`) which is what `mindchat_binding_version()` returns (ffi.rs:632-633); crate-type `["rlib", "cdylib"]` (`Cargo.toml:10-11`); features `default = ["xmpp-transport"]`, `uniffi = ["dep:uniffi", "xmpp-transport"]`, `xmpp-transport = [...]` (`Cargo.toml:13-20`).

## 9. Release readiness gaps

1. **No release signing config**: `app/build.gradle.kts` defines no `signingConfigs`; the `release` buildType only sets minify/proguard (lines 62-70). `assembleRelease` would produce an unsigned APK. No keystore handling exists anywhere in the repo.
2. **Minification off, proguard-rules.pro empty**: `isMinifyEnabled = false` (line 64) and the file contains only two comments promising keep rules for the generated bindings "together with native linkage" (`app/proguard-rules.pro:1-2`). Enabling minify today would strip JNA/UniFFI reflection without keep rules.
3. **No reproducibility setup**: no `SOURCE_DATE_EPOCH`, no cargo `--locked` flag in the build scripts (CI installs use `--locked`, but `build-rust-android.sh:24-30` and `generate-uniffi-kotlin.sh:17` do not), no Gradle build cache configuration beyond defaults. `versionName "0.1.0-dev"` is not a release version.
4. **F-Droid compatibility**: license is Apache-2.0 (`Cargo.toml:7`), which is F-Droid-friendly, and there is no proprietary SDK; but there is no F-Droid metadata (no `fdroid` dir, no `metadata` files, no upstream commit/signing setup), the build depends on `cargo install`-ing cargo-ndk and the uniffi CLI from source (slow and version-dependent), `tokio-xmpp` pulls `ring` 0.17 (`Cargo.toml:25`), which F-Droid builds typically handle via NDK sysroot flags but require build recipe work, and the Android build requires SDK 36 / NDK 29 pinning that the F-Droid build server must replicate.
5. **Tool version drift (local)**: `generate-uniffi-kotlin.sh:5-8` checks only for the CLI's presence, not version; a dev with a different `uniffi-bindgen` generates bindings mismatched against the 0.32.0 scaffolding (ABI break at runtime).
6. **Duplicate jniLibs sources**: script default `app/src/main/jniLibs` (build-rust-android.sh:10) vs Gradle's `build/generated/jniLibs` (app/build.gradle.kts:7, 17); both are picked up by AGP, so manually run script outputs can ship stale ABIs outside Gradle's input tracking.
7. **NDK override risk**: `ANDROID_NDK_HOME` set externally bypasses the 29.0.14206865 pin (build-rust-android.sh:13-22), silently building against a different toolchain than AGP's `ndkVersion`.
8. **Unused runtime permissions**: `POST_NOTIFICATIONS` and `RECORD_AUDIO` are declared (Manifest lines 4-5) with no consuming code and no runtime-request flow; a release will trigger Play/F-Droid policy review questions and possible rejection for unused dangerous permissions.
9. **No CI caching or release verification**: no rust/gradle caches (every run compiles cargo-ndk + uniffi from source, `verify.yml:39`), no `assembleRelease`, no instrumented tests, no binary-size or ABI-consistency checks.
10. **Persistent-connection gap**: no foreground service or push mechanism is declared (Manifest has no service/receiver), so a real XMPP client cannot stay connected in background; the current polling loop lives in the foreground activity (`MindChatApp.kt:94-99` polls every 750 ms).

## Metrics block (exact numbers from source)

- `ffi_function_count = 17` (1 constructor + 15 methods + 1 free function, ffi.rs:432-634)
- `ffi_type_count = 17` (7 records + 9 enums + 1 error, ffi.rs:20-353; `MindChatCoreHandle` object type excluded)
- `ffi_loc = 849` (crates/mindchat-core/src/ffi.rs)
- `script_loc_each = { build-rust-android.sh: 30, generate-uniffi-kotlin.sh: 25 }`
- `gradle_module_count = 2` (root + `:app`, settings.gradle.kts:17-18)
- `dependency_count = 17` (catalog libraries, libs.versions.toml:14-31, all consumed)
- `plugin_count = 3` (android-application, kotlin-android, kotlin-compose, libs.versions.toml:33-36)
- `permission_count = 3` [INTERNET, POST_NOTIFICATIONS, RECORD_AUDIO] (AndroidManifest.xml:3-5)
- `abi_target_count = 3` [arm64-v8a, armeabi-v7a, x86_64] (build-rust-android.sh:25-27)
- `min_sdk = 26`, `target_sdk = 36`, `compile_sdk = 36` (app/build.gradle.kts:48, 53-54)
- `ci_workflow_count = 1` (.github/workflows/verify.yml); `ci_job_list = [rust, android]` (verify.yml:11, 24)
