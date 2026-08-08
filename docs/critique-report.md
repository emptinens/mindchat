# Critique Report: MindChat Consolidated Report

Reviewer: critical reviewer. Date: 2026-08-08.
Method: re-ran LOC/test/string/FFI counts against the tree, executed both Rust
test suites (`cargo test --workspace` and `--all-features`), spot-checked every
claimed `file:line` evidence in `xmpp.rs`, `lib.rs`, `ffi.rs`, `transport.rs`,
`extension.rs`, `MindChatGateway.kt`, `MindChatApp.kt`, `AndroidManifest.xml`,
`app/build.gradle.kts`, `verify.yml`, `PLAN.md`, `README.md`, and the three
other source reports.

---

## 1. Verified numbers (with evidence)

| Claim | Evidence (this run) | Verdict |
|---|---|---|
| rust_loc_total = 4041 (lib 1857, xmpp 722, transport 150, extension 463, ffi 849) | `wc -l crates/mindchat-core/src/*.rs` → 1857 + 722 + 150 + 463 + 849 = 4041 | **ok** |
| Kotlin LOC 1947 main + 150 test = 2097 | `find app/src -name '*.kt' \| xargs wc -l` → main 62+1053+49+61+108+614 = 1947; tests 67+83 = 150; total 2097 | **ok** (LOC value) |
| MindChatApp.kt is the biggest Kotlin file | 1053 lines, largest of the 8 | **ok** |
| lib.rs tests module ≈ 1257-1856, 17 tests | `mod tests` at lib.rs:1258, closes at 1857; `grep -c '#[test]'` = 17 | **ok** (range is 1258-1857, off-by-one nit) |
| ffi module feature-gated | `#[cfg(feature = "uniffi")] pub mod ffi;` at **lib.rs:19** (not ffi.rs; feature gates live in lib.rs:16-34) | **ok** (gate is in lib.rs, consistent with build-pipeline-report) |
| 33 Rust tests = 17 lib + 6 xmpp + 4 ffi + 5 extension + 1 transport | `grep -c '#[test]'` per file = 17/6/4/5/1 = 33 | **ok** |
| 29 default / 33 all-features, both pass | Re-ran: `cargo test --workspace` → 29 passed; `cargo test --workspace --all-features` → 33 passed | **ok** |
| 6 Kotlin unit, 4 instrumented | `grep -c '@Test' app/src/test -r` = 6 (AppLockStateMachineTest.kt); `app/src/androidTest -r` = 4 (MindChatAppTest.kt) | **ok** |
| Strings 51 en / 51 ru, identical key sets | Both files 51 `<string name=`; `diff` of sorted `name=` key lists = empty | **ok** |
| FFI surface = 17 functions (1 constructor + 15 methods + 1 free fn) | ffi.rs outline: `new` (438) + 15 methods (450-631) + `mindchat_binding_version` (632-635) under `#[uniffi::export] impl` (421/433); `mindchat_binding_version` at 632-635 | **ok** |
| 17 UniFFI types = 7 records + 9 enums + 1 error | ffi.rs: 7 `uniffi::Record` (255-324), 9 `uniffi::Enum` (20-197, 335), 1 `uniffi::Error` (345) | **ok** |
| TransportEvent 8 variants | transport.rs:62-102: Connected, Disconnected, CapabilitiesDiscovered, RosterContactUpsert, RosterContactRemoved, ContactPresenceUpdated, IncomingText, DeliveryUpdated | **ok** |
| CoreEvent 5 ID-only variants | lib.rs:239-245 | **ok** |
| FfiCoreEvent 5 named-field events | ffi.rs:336-342 | **ok** |
| Threading: per-account worker `mindchat-xmpp-{id}`, current-thread runtime | xmpp.rs:104-118 | **ok** |
| 5 s disconnect / 15 s send timeouts | xmpp.rs:36-37 (`SEND_TIMEOUT` 15 s, `DISCONNECT_TIMEOUT` 5 s) | **ok** |
| `MAX_TRANSPORT_EVENTS_PER_POLL = 128` | ffi.rs:17 | **ok** |
| 750 ms poll loop, IO-dispatched poll | MindChatApp.kt:94-99 (`pollTransport(); delay(750)`); `withContext(Dispatchers.IO)` at MindChatGateway.kt:211, `pollTransportEvents(32U)` at :213 | **ok** |
| Credentials: empty password rejected, SecretString-wrapped, not stored in core | ffi.rs:561-572 (`InvalidInput` on empty); `SecretString::new`; TransportCoordinator doc lib.rs:1099-1102; worker owns credential | **ok** |
| 23 composables, 5 screens | `grep -c '@Composable'` = 23; screen list (AppLocked/Conversations/Chat/Contacts/Settings) matches presentation-report §1 | **ok** |
| 16 model types | rust-core-report: 8 domain enums + 8 domain structs, all in lib.rs, line ranges match source | **ok** |
| Versions: Rust 1.97.1, UniFFI 0.32.0, Gradle 8.14.3, AGP 8.11.0, Kotlin 2.1.0, JDK 17, NDK 29.0.14206865, minSdk 26 / target 36 / compile 36, 3 ABIs | rust-toolchain.toml; Cargo.toml `uniffi 0.32.0`; gradle-wrapper 8.14.3; libs.versions.toml agp 8.11.0 / kotlin 2.1.0; verify.yml JDK 17, NDK 29.0.14206865; app/build.gradle.kts:48-54; scripts/build-rust-android.sh targets arm64-v8a, armeabi-v7a, x86_64 | **ok** |
| Milestone percents vs verification-report counts | M1 10/10=100; M2 (5D 2P 4M) → 6/11=54.5≈55; M3 (1D 4P 4M) → 3/9=33.3; M4 (1D 0P 4M) → 1/5=20; M5 5/5=100. All match the verification report's summary counts | **ok** (see M2 caveat below) |
| MUC = partial | xmpp.rs:392-395 groupchat send; ns::MUC at 479; `translate_incoming_message` drops non-chat (`if message.type_ != MessageType::Chat { return None; }` xmpp.rs:396-412); gate lib.rs:650-654 | **ok** |
| HTTP upload = partial | ns::HTTP_UPLOAD xmpp.rs:482; `attach()` projection + gate lib.rs:807-828; grep finds no upload/request code in xmpp.rs | **ok** |
| SM detect-only | `stream_capabilities_from_features` xmpp.rs:464-473 (`stream_management.is_some()`), ns::SM 481; grep for ack/resume/enable/sequence finds no SM code (only IQ-result ack at 379-380, tokio `enable_all()` at 106) | **ok** |
| Contract: 10 of 17 used by Kotlin | MindChatGateway.kt call sites: ctor :118; snapshot :145/:218/:233/:284/:295; addAccount :151; connectAccount :157; upsertContact :170; sendText :186; pollTransportEvents :213; flushOutbox :228; openConversation :269; drainEvents :286. The other 7 are uncalled anywhere in app/src | **ok** |
| No live-server/e2e infra | Repo-wide search: the only "disposable XMPP server" mention is PLAN.md:104-105; no docker-compose, no test-server crate, no `tests/` dir; CI (verify.yml) runs only lintDebug + compileDebugAndroidTestKotlin + assembleDebug for Android, no `testDebugUnitTest`/`connectedAndroidTest` | **ok** |

## 2. Corrected / flagged claims

1. **Kotlin file counts are wrong in both the dashboard JSON and the source
   reports.** Dashboard `modules` says `["Kotlin app","1947","9"]`; the
   presentation report claims "7 files" main + 9 total. Actual tree:
   **6 main `.kt` files + 2 test files = 8** (`ls app/src/main/java/com/mindchat/app/`:
   MainActivity, MindChatApp, AndroidAppLockAuthenticator, MindChatPreferences,
   AppLock, MindChatGateway; plus AppLockStateMachineTest, MindChatAppTest).
   LOC 1947/150/2097 is unaffected.
2. **M2 Done count is internally inconsistent (5 vs 7).** The M2 table row says
   `5 | 2 | 4` but its own item breakdown lists 7 done items ("transport
   adapter, StartTLS, SRV, XEP-0030, login, roster (pull+push), no FFI leak").
   The verification report has the same inconsistency: its M2 table lists 13
   rows (7 Done / 2 Partial / 4 Missing) while its summary says 5 Done. The 55%
   figure is only consistent with the 5D/2P/4M summary (6/11 = 54.5%); computed
   over the 13-row table it would be (7+1)/13 ≈ 62%. Percent retained (matches
   the source report's own summary), D-count flagged. If counting the
   row-level evidence, M2 = 7 Done / 2 Partial / 4 Missing.
3. **`README.md:92-95` citation is invalid** (§2 Credentials: "documented in
   README.md:92-95 and PLAN.md"). README.md is **53 lines**. The password
   re-entry statement is at **PLAN.md:92-95** ("credentials are re-entered
   after a process restart"); README's closest statement is README.md:26-29
   ("Encrypted SQLCipher persistence ... remain explicit follow-up
   milestones"). (Verification report's README.md:92-95 and README.md:79-80
   citations in risks 1 and 5 are likewise invalid; the subscribe/unsubscribe
   text is PLAN.md:79-80.)
4. **Feature-matrix evidence lines off by one** (inherited from the
   verification report; actual `capabilities_from_disco` arm lines in xmpp.rs):
   - Receipts: claimed xmpp.rs:483 → actual **484** (483 is `ns::PUSH`)
   - Chat markers (XEP-0333): claimed 484 → actual **485**
   - Chat states/typing (XEP-0085): claimed 485 → actual **486**
   - UnifiedPush (xmpp.rs:483) is the one correct citation at that line.
5. **Trivial off-by-ones:** lib.rs tests module is 1258-1857, not 1257-1856;
   the 17-fn surface spans ffi.rs:438-635 (claim "432-634" omits the closing
   brace of `mindchat_binding_version`). No substance.

## 3. Claims challenged and upheld

- "MUC = partial not missing": upheld. Groupchat send exists (xmpp.rs:392-395),
  incoming groupchat is dropped (chat-only translate), gate in lib.rs:650-654.
  "Partial" is the correct status.
- "HTTP upload = partial": upheld. Capability (xmpp.rs:482) + `attach()`
  projection (lib.rs:807-828) exist; grep of xmpp.rs shows no upload client.
- "SM detect-only": upheld. Detection via stream features + disco only; no
  ack/sequence/resume code exists in xmpp.rs.
- "10 of 17 FFI functions used": upheld, exact call sites listed above.
- "no live-server/e2e infra": upheld; PLAN.md:104-105 is the only trace.

## 4. Risks: basis check (all 8 real)

| Risk | Basis in source |
|---|---|
| 1. No persistence | `CoreStore` trait lib.rs:341-344 + `InMemoryStore` only; no SQLCipher dep in Cargo.toml; PLAN.md:92-95 re-entry |
| 2. MUC dropped at wire | xmpp.rs:396-412 chat-only translate |
| 3. SM detect-only + flush not idempotent | ffi.rs:600-616 gates flush on `Online`; lib.rs:1178-1194 re-sends all retained Pending/Failed with no dedup; no ack correlation |
| 4. No live-server/e2e; CI runs no Android tests | verify.yml:41 (compile only); PLAN.md:104-105 no code |
| 5. FFI on main thread | MindChatApp.kt:184 (addAccount), 195 (openConversation), 217 (sendText), 296 (openConversation) + refresh() sync; only pollTransport IO-dispatched |
| 6. Declared unused permissions | AndroidManifest.xml:4-5; zero NotificationManager/AudioRecord/request-permission code in app/src |
| 7. UniFFI CLI unpinned | generate-uniffi-kotlin.sh checks only `command -v uniffi-bindgen`, no version check |
| 8. No signing/reproducibility | app/build.gradle.kts: no signingConfigs, `isMinifyEnabled = false`, empty proguard-rules.pro, no SOURCE_DATE_EPOCH, no `--locked` in build-rust-android.sh, no F-Droid metadata |

## 5. Summary table

| Claim | Verdict | Evidence |
|---|---|---|
| rust_loc 4041 (1857/722/150/463/849) | ok | wc -l |
| Kotlin 1947 main + 150 test | ok | wc -l |
| Kotlin file counts 9 / "7 main" | **corrected → 8 (6 main + 2 test)** | ls app/src/main/java/com/mindchat/app |
| 33 Rust tests (17/6/4/5/1); 29 default / 33 all pass | ok | grep -c #[test]; cargo test runs (29 and 33 passed) |
| 6 unit + 4 instrumented | ok | grep -c '@Test' |
| 51/51 strings, identical keys | ok | diff on key lists empty |
| 17 FFI functions; 17 types (7R 9E 1Err) | ok | ffi.rs outline |
| ffi feature gate | ok | lib.rs:19 (not ffi.rs:19) |
| Milestones 100/55/33/20/100 | ok w/ caveat | M2 D-count 5 vs 7 inconsistency (55% matches 5D/2P/4M = 6/11) |
| MUC partial / HTTP upload partial / SM detect-only | ok | xmpp.rs evidence |
| 10/17 used by Kotlin | ok | MindChatGateway.kt call sites |
| no e2e infra | ok | repo-wide search |
| README.md:92-95 citation | **corrected → PLAN.md:92-95** (README is 53 lines) | README.md |
| Receipts/Markers/Typing lines 483/484/485 | **corrected → 484/485/486** | xmpp.rs:479-493 |
| lib.rs tests range 1257-1856 | ok (nit: 1258-1857) | sed/awk |
| 8 risks | ok (all grounded) | per-risk evidence above |

## 6. DASHBOARD_DATA_CORRECTED

Only one JSON correction: the Kotlin module file count.

```json
{
  "modules": {"h":[["","Module"],["loc","LOC"],["files","Files"],["note","Role"]],"r":[["lib.rs","1857","1","state machine + domain models"],["xmpp.rs","722","1","Tokio/XMPP transport"],["ffi.rs","849","1","UniFFI surface"],["extension.rs","463","1","extension policy"],["transport.rs","150","1","adapter boundary"],["Kotlin app","1947","8","Compose UI + gateway + prefs"]]}
}
```

All other DASHBOARD DATA values (4041, 1947/2097, 43 tests, milestone
100/55/33/20/100, 51/51, 3 ABIs, 17 FFI fns, minSdk 26, xeps matrix, risks
list, versions) are confirmed correct as written. If the M2 Done count is
exposed anywhere in the dashboard, use 7 (per row-level evidence) or keep 5
with the note that both figures appear in the source report.
