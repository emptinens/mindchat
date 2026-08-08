# MindChat Android Presentation Layer Report

Generated 2026-08-08. Scope: `app/src/main/java/com/mindchat/app/` (7 files), `app/src/main/res/` (6 files), `app/src/androidTest/` and `app/src/test/` (2 files), `app/AndroidManifest.xml`, `app/build.gradle.kts`. Rust internals are out of scope; only the Kotlin-side call surface into native code is noted.

## 1. Architecture

### App shape and navigation approach

Single-activity app. `MainActivity : FragmentActivity, AppLockHost` (MainActivity.kt:10) sets Compose content once in `onCreate` (MainActivity.kt:32-35). There is no navigation library: no `NavHost`, no routes as strings. Navigation is state-driven inside one composable tree:

- A private `Destination` enum with exactly 3 values: `Chats`, `Contacts`, `Settings` (MindChatApp.kt:77-81).
- `var destination by rememberSaveable { mutableStateOf(Destination.Chats) }` (MindChatApp.kt:175) selects the bottom-bar tab.
- `var selectedConversationId by rememberSaveable { mutableStateOf<Long?>(null) }` (MindChatApp.kt:176) is the chat-detail pseudo-route. When a matching conversation exists, `ChatScreen` replaces the entire `Scaffold` and the function returns early (MindChatApp.kt:211-220). Setting it back to `null` (onBack) pops the detail.
- Three dialogs are boolean flags: `showAddAccount`, `showAddContact`, `showNewConversation` (MindChatApp.kt:177-179), rendered as `AlertDialog`s before the shell.

### Screen list

5 screens (all `@Composable`):

| Screen | Location | Purpose |
|---|---|---|
| `AppLockedScreen` | MindChatApp.kt:128-167 | Full-screen lock gate, automatic unlock prompt |
| `ConversationsScreen` | MindChatApp.kt:391-420 | Chat list for active account, empty state |
| `ChatScreen` | MindChatApp.kt:505-567 | Message list + composer for one conversation |
| `ContactsScreen` | MindChatApp.kt:662-716 | Contact list for active account, empty state |
| `SettingsScreen` | MindChatApp.kt:740-803 | Appearance/privacy/about settings |

Supporting composables: `MindChatShell` (170), `MindChatTopBar` (322) with per-account `AssistChip` selector (356-380), `EmptyConversations` (423), `ConversationRow` (444), `Composer` (570), `MessageBubble` (597), `EmptyContacts` (719), `Avatar` (837), `PresenceDot` (854), `UnreadBadge` (871), and 3 dialogs `AddAccountDialog` (888), `AddContactDialog` (959), `NewConversationDialog` (997).

### State-holder pattern

Three layers of state:

1. **Core-projected UI state**: `MindChatUiState` (MindChatGateway.kt:69-78) is a plain data class holding `accounts`, `contacts`, `activeAccountId`, `conversations`, `messagesByConversation`, plus customization flags. It is the single source of truth for the UI.
2. **Gateway interface**: `MindChatGateway` (MindChatGateway.kt:85-97) exposes `val state: MindChatUiState` and command methods (`selectAccount`, `addAccount`, `addContact`, `openConversation`, `sendText`, `pollTransport`, three `toggle*`). Two implementations:
   - `NativeMindChatGateway` (MindChatGateway.kt:117-379), `@Stable`, holds `var state by mutableStateOf(...)` (125) so Compose recomposes on refresh.
   - `PreviewMindChatGateway` (MindChatGateway.kt:402-531), a seeded in-memory fake used for Compose previews and debug builds.
   - `MindChatGatewayFactory.create(context)` (MindChatGateway.kt:104-113) tries `NativeMindChatGateway` and, on `LinkageError`, falls back to the preview gateway only in `BuildConfig.DEBUG`.
3. **Local UI state**: `rememberSaveable`/`remember` in composables for tab, conversation selection, dialog fields, and the message draft (MindChatApp.kt:175-179, 511, 892-896, 963-964, 1002-1004).

The app lock uses a separate holder: `AppLockStateMachine` (AppLock.kt:31-80) wrapped by `AppLockViewModel : ViewModel()` (AppLock.kt:83-98), obtained via `by viewModels()` (MainActivity.kt:11) so it survives configuration changes. The activity implements `AppLockHost` (AppLock.kt:100-107) to expose lock state and unlock requests to Compose.

### How UI talks to Rust (UniFFI)

- **Binding generation**: `generateUniffiKotlin` Gradle task (app/build.gradle.kts:28-44) runs `scripts/generate-uniffi-kotlin.sh`, emitting Kotlin DTOs into `build/generated/source/uniffi/main/kotlin`, which is added as a source dir (build.gradle.kts:8, 85). Kotlin imports `com.mindchat.core.*` (`FfiCoreSnapshot`, `FfiConnectionState`, `FfiContactPresence`, `FfiConversationKind`, `FfiDeliveryState`, `FfiMessageDirection`, `FfiProtocolCapability`, `MindChatBindingException`, `MindChatCoreHandle`, MindChatGateway.kt:8-16). The binding depends on JNA (build.gradle.kts:103-108).
- **Handle**: `NativeMindChatGateway` constructs `MindChatCoreHandle()` (MindChatGateway.kt:118). All calls are synchronous: `addAccount`, `connectAccount`, `upsertContact`, `openConversation`, `sendText`, `pollTransportEvents`, `flushOutbox`, `snapshot`, `drainEvents`.
- **Event polling, not callbacks**: the UI runs an infinite `LaunchedEffect` loop `gateway.pollTransport(); delay(750)` (MindChatApp.kt:94-99). `pollTransport()` is `suspend` and wraps native calls in `withContext(Dispatchers.IO)` (MindChatGateway.kt:211), so JNI/JNA work never blocks the Compose main dispatcher. It calls `core.pollTransportEvents(32U)` (213), snapshots, and flushes the outbox for accounts that are online (218-233); snapshot state is only assigned after resuming on the main dispatcher (238-241).
- **Snapshot projection**: `refresh()` (MindChatGateway.kt:284-287) sets `state = snapshotToUiState(snapshot)` then calls `core.drainEvents()`. `snapshotToUiState` (295-378) is a pure mapping from `Ffi*` DTOs to UI models: presence mapping (301-307, 344-350), capabilities to `supportsGroupChats` (309), message direction to `mine` (329), outbox delivery state (330-334), reactions grouped by emoji (319-323), conversation kind to `isGroup` (364), timestamps formatted with `DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())` (396-398). It also maintains `activeAccountId`, defaulting to the first account (312-314).
- **Threading model**: main dispatcher for Compose and state writes; `Dispatchers.IO` for native polling; no threads are spawned by the Kotlin layer. Errors surface as `MindChatBindingException`, caught and swallowed in favor of a refreshed snapshot (e.g. MindChatGateway.kt:160-164, 195-197, 234-236).

## 2. Theming

- **Material 3**: `MaterialTheme(colorScheme = colors)` wrapping a full-screen `Surface` (MindChatApp.kt:109-124). Components are Material 3 (`Scaffold`, `NavigationBar`, `TopAppBar`, `AssistChip`, `AlertDialog`, `Card`, `Switch`, `ListItem`).
- **Dynamic color**: when the `dynamicColor` preference is on and `Build.VERSION.SDK_INT >= S`, the scheme is `dynamicDarkColorScheme(context)` or `dynamicLightColorScheme(context)` (MindChatApp.kt:100-103); otherwise a static `darkColorScheme()`/`lightColorScheme()` (105-106).
- **Dark mode**: follows the system via `isSystemInDarkTheme()` only; no in-app theme toggle.
- **Adaptive layout**: none via `WindowSizeClass` or foldable APIs. One fixed bottom `NavigationBar` (MindChatApp.kt:230-258), a `FloatingActionButton` shown only when an account is active (260-283), and a chat composer pinned to the bottom of `ChatScreen` (539-548). The only "density" adaptation is the `comfortableLayout` preference, which changes the conversation-list vertical spacing between `8.dp` and `2.dp` (MindChatApp.kt:409).
- **XML theme**: `Theme.MindChat` extends platform `@android:style/Theme.Material.Light.NoActionBar` with `windowLightStatusBar=false` and `windowActionModeOverlay=true` (values/styles.xml:2-5). Edge-to-edge is enabled via `enableEdgeToEdge()` (MainActivity.kt:32).
- Launcher icon is a hand-written vector, `ic_mindchat.xml` (48dp, indigo `#3F51B5` chat bubble).

## 3. Biometric gate (app lock)

- **Enabling**: `MainActivity.setAppLockEnabled` (MainActivity.kt:45-53) refuses to enable when `isAuthenticationAvailable` is false, updates the `AppLockViewModel`, and adds/clears `WindowManager.LayoutParams.FLAG_SECURE` so the task switcher cannot screenshot the app when locked.
- **Availability**: `AndroidAppLockAuthenticator.isAuthenticationAvailable` checks `BiometricManager.from(activity).canAuthenticate(ALLOWED_AUTHENTICATORS) == BIOMETRIC_SUCCESS` (AndroidAppLockAuthenticator.kt:12-14). Allowed authenticators are `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` (44-47), so a device PIN/pattern/password also works.
- **Unlock flow**: `MainActivity.requestAppUnlock` (MainActivity.kt:55-61) calls `appLockViewModel.beginAuthentication()`, which transitions `LOCKED -> AUTHENTICATING` and refuses if not lockable (AppLock.kt:55-59), then shows a system `BiometricPrompt` with title/subtitle from strings (AndroidAppLockAuthenticator.kt:22-41). Callbacks (`onAuthenticationSucceeded`/`onAuthenticationError`) run on the main executor and drive the state machine (26-33).
- **Locks on background**: `MainActivity.onStop` calls `appLockViewModel.onAppBackgrounded()` unless `isChangingConfigurations` (MainActivity.kt:38-43). The state machine locks and increments `automaticPromptNonce` when enabled and not already authenticating (AppLock.kt:49-53). On return, `AppLockedScreen` auto-invokes the prompt via `LaunchedEffect(state.automaticPromptNonce)` (MindChatApp.kt:129-133).
- **State machine**: `AppLockStatus` enum `UNLOCKED/LOCKED/AUTHENTICATING` (AppLock.kt:8-12); `AppLockUiState.blocksContent = isEnabled && status != UNLOCKED` (19-20). Late callbacks after disable are harmless because transitions guard on `isEnabled && status == AUTHENTICATING` (61-71).
- **What it stores**: nothing credential-related. The class doc states it "deliberately stores no credential material: Android's system biometric/device-credential prompt owns authentication" (AppLock.kt:26-30). The only persisted bit is the `appLockEnabled` boolean in SharedPreferences (MindChatPreferences.kt:31). If the preference is enabled but biometrics are unavailable at startup, `MainActivity` resets it to false (MainActivity.kt:26-30).

## 4. Localization

- **Key counts**: 51 `<string>` entries in `values/strings.xml` and 51 in `values-ru/strings.xml`. Key sets are identical: **0 missing in Russian, 0 extra** (verified by `comm` on sorted `name="..."` lists).
- **Language selection**: standard Android resource resolution via the `values-ru` qualifier. There is no in-app language picker and no locale override. Timestamps are localized independently through `DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())` (MindChatGateway.kt:396-398).
- `android:supportsRtl="true"` is set (AndroidManifest.xml:14).
- The `display_name` key differs in meaning across locales ("Display name" vs "Имя", values-ru:21) but all keys are translated; Russian strings are complete including the `coming_soon` roadmap text (values-ru:52).

## 5. Notifications

- **Declared, not implemented**: `android.permission.POST_NOTIFICATIONS` is declared (AndroidManifest.xml:4) but there is no runtime permission request, no `NotificationChannel`, no `NotificationManager` usage, and no notification-related Kotlin code anywhere in `app/src`. The app never posts notifications, so there is no notification privacy handling to report: no message previews ever leave the app.
- **Declared, not implemented**: `android.permission.RECORD_AUDIO` (AndroidManifest.xml:5) is also unused in Kotlin; no audio/voice feature exists yet.
- **Data protection at rest**: backups are disabled entirely: `android:allowBackup="false"` (AndroidManifest.xml:8), `backup_rules.xml` excludes root, and `data_extraction_rules.xml` excludes root for cloud backup and device transfer. In-app privacy is handled by the app lock (Section 3), which blocks content behind biometrics and `FLAG_SECURE`.

## 6. Settings

All settings live in `SettingsScreen` (MindChatApp.kt:739-803), grouped under "Appearance", "Privacy", and "About" section titles:

| Setting | Key | Default | Control |
|---|---|---|---|
| Use system colors | `dynamic_color` | `true` | `Switch` (MindChatApp.kt:762-766) |
| Comfortable layout | `comfortable_layout` | `true` | `Switch` (767-771) |
| App lock | `app_lock_enabled` | `false` | `Switch` (775-783), disabled when no biometrics |
| Diagnostics | (none) | - | Static `ListItem`, no action (784-787) |
| About / privacy summary | (none) | - | Static `ListItem` + `coming_soon` text (790-800) |

**Persistence**: a single `SharedPreferences` file `"mindchat_customization"` (`Context.MODE_PRIVATE`), keys `dynamic_color`, `comfortable_layout`, `app_lock_enabled` (MindChatPreferences.kt:42-47). The `MindChatPreferences` interface (15-19) has two implementations: `SharedPreferencesMindChatPreferences` (Android storage) and `InMemoryMindChatPreferences` (tests/previews, 51-61). Toggles go through `NativeMindChatGateway.updateCustomization` which writes preferences then refreshes state (MindChatGateway.kt:289-293); customization values are projected into every `MindChatUiState` (374-376). Preferences are read once at gateway creation (122) and not observed as a flow, so cross-process changes would not propagate.

## 7. Tests

### Unit tests, `app/src/test` (JVM): `AppLockStateMachineTest.kt` - 6 test functions, 16 `org.junit.Assert` calls

1. `enablingLockBlocksContentAndRequestsOneAutomaticPrompt` (line 10): enabling sets `isEnabled`, `blocksContent`, `canRequestAuthentication`, and `automaticPromptNonce == 1`. 4 assertions.
2. `cancellationKeepsTheGateLockedWithoutReopeningTheSystemPrompt` (line 22): cancel returns state to `LOCKED`, keeps `canRequestAuthentication`, nonce stays 1. 4 assertions.
3. `backgroundingAfterSuccessLocksAgainAndSchedulesAnotherPrompt` (line 35): after unlock, backgrounding re-locks and bumps nonce to 2. 2 assertions.
4. `backgroundingWhileAlreadyLockedSchedulesAuthenticationOnReturn` (line 48): backgrounding while locked keeps `LOCKED` and bumps nonce to 2. 2 assertions.
5. `disablingLockMakesLateAuthenticationCallbacksHarmless` (line 61): disabling then a late success callback leaves lock disabled/unlocked. 3 assertions.
6. `successWithoutAnActivePromptCannotUnlockTheGate` (line 75): success with no active `AUTHENTICATING` transition is ignored. 1 assertion.

### Instrumented tests, `app/src/androidTest` (device): `MindChatAppTest.kt` - 4 test functions, 14 assertions (12 JUnit + 2 Compose `assertIsDisplayed`)

1. `addAccountFlowIsAvailableBeforeAnyAccountExists` (line 19): launches real `MainActivity` via `createAndroidComposeRule<MainActivity>()`, asserts "MindChat" and the add-account dialog fields ("JID") are displayed; clicks the "Add account" content description. 2 Compose assertions. Note this uses the debug fallback gateway since no native lib is present in instrumented runs.
2. `customizationChoicesSurviveGatewayRecreation` (line 26): `PreviewMindChatGateway` + `InMemoryMindChatPreferences`; toggling all three customizations then recreating the gateway restores the toggled values. 3 assertions.
3. `localContactsAreScopedToTheActiveAccountAndUseTheProvidedDisplayName` (line 41): adding a contact scopes it to `activeAccountId`, keeps the display name, and opening the same address twice returns the same conversation id. 4 assertions.
4. `accountSetupRequiresAPasswordBeforeThePreviewSessionIsCreated` (line 56): empty password is rejected without adding an account; non-empty password adds one with the given JID. 5 assertions.

Coverage notes: the state machine is fully unit-tested; gateway behavior is only tested against the preview implementation; there are no tests for `NativeMindChatGateway`, no tests for string completeness, no screenshot/compose UI tests beyond the one launch test, and no tests for `MainActivity`'s `FLAG_SECURE`/lifecycle wiring.

## 8. Gaps / TODOs

- **No `TODO`/`FIXME` markers** exist in any file in scope (grep across `app/src`, manifest, and `build.gradle.kts` returned nothing).
- **Placeholder screens/items**: the Diagnostics `ListItem` is inert with no click action (MindChatApp.kt:784-787); the About section is informational plus a "coming soon" note (790-800, string `coming_soon`: "Connection, roster synchronization, and encrypted storage are the next core milestones.").
- **Stubbed features**:
  - `PreviewMindChatGateway` is a deliberate in-memory fake for previews/native-free debug builds (MindChatGateway.kt:400-531), activated by `LinkageError` in debug (108-111).
  - `openLocalConversation` is documented as a "Test and development utility until server-backed contact search lands" (MindChatGateway.kt:261-262).
  - Group chats are capability-gated: the MUC switch is disabled unless `FfiProtocolCapability.MULTI_USER_CHAT` is advertised (MindChatApp.kt:190-192, MindChatGateway.kt:309), and opening a MUC that fails validation returns `null` with the comment "A MUC action remains disabled by capability discovery in production" (278-281).
- **Declared permissions never exercised**: `POST_NOTIFICATIONS` and `RECORD_AUDIO` (AndroidManifest.xml:4-5) have no corresponding code; notifications and voice messages are unimplemented.
- **Not implemented**: no notification posting/channels, no contact search UI, no roster sync UI (deferred to core milestones per `coming_soon`), no in-app language selector, no adaptive/responsive layout for large screens, no theme customization beyond the two toggles.

## Metrics

- `kotlin_loc`: **1947** main (`app/src/main/java/com/mindchat/app`, 7 files) + 150 test = **2097** total in scope
- `kotlin_file_count`: **9** (7 main + 1 unit test + 1 instrumented test)
- `composable_count`: **23** (all `@Composable`-annotated functions in `app/src/main`, including the `MindChatApp` overloads and `deliveryLabel`)
- `screen_count`: **5** (AppLockedScreen, ConversationsScreen, ChatScreen, ContactsScreen, SettingsScreen)
- `route_count`: **3** (`Destination` enum: Chats, Contacts, Settings; chat detail is a state-based pseudo-route via `selectedConversationId`)
- `test_count_android_unit`: **6**
- `test_count_android_instrumented`: **4**
- `assertion_count`: **30** (16 unit JUnit + 14 instrumented: 12 JUnit + 2 Compose)
- `string_count_en`: **51**
- `string_count_ru`: **51**
- `strings_missing_in_ru`: **none** (key sets identical)
- `todo_count`: **0** (no TODO/FIXME markers in scope)
- Top-level package layout (source tree only; `com.mindchat.core` is generated build output, not checked in):
  - `com.mindchat.app` - 7 main files (`MainActivity.kt`, `MindChatApp.kt`, `MindChatGateway.kt`, `AppLock.kt`, `AndroidAppLockAuthenticator.kt`, `MindChatPreferences.kt`, `MindChatApp.kt` UI in same file) + 2 test files
  - `com.mindchat.app` (test) - 1 file; `com.mindchat.app` (androidTest) - 1 file
