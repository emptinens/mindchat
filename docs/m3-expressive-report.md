# Material 3 Expressive Theming Report

Date: 2026-08-08
Scope: `mindchat` Android app (Kotlin + Jetpack Compose, Material 3)

## 1. Compose BOM choice

**Bumped `composeBom` from `2025.01.00` to `2025.09.01`** in `gradle/libs.versions.toml`.

Why:

- `2025.01.00` maps to `material3 1.3.x`, which has no expressive support at all.
- `material3 1.4.0` (stable, released 2025-09-24) is the first and only stable 1.4.x line,
  and **BOM `2025.09.01` is the first stable BOM that includes it** (verified against the
  published POM: `androidx.compose.material3:material3 = 1.4.0`, `ui = 1.9.2`,
  `foundation = 1.9.2`). Earlier BOMs (`2025.06.01`, `2025.08.01`) still map to
  `material3 1.3.2`.
- The newest stable BOMs that still carry `material3 1.4.0` (`2025.12.01`, `2026.01.00`)
  also pull `ui/foundation 1.10.x`, which pairs with newer Kotlin than the project's
  `kotlin = 2.1.0`. `2025.09.01` is the smallest, safest bump that stays green with the
  project's Kotlin 2.1.0 / AGP 8.11.0 toolchain (confirmed by a successful
  `:app:compileDebugKotlin`).

## 2. Expressive API availability (verified against the actual artifacts)

I downloaded and inspected the `material3` 1.4.0 AAR, its sources jar, and the
1.5.0-alpha25 AAR/sources jar, plus the official release notes:

- `material3 1.4.0` ships an **internal** expressive theme API only
  (`MaterialExpressiveTheme`, `MotionScheme.expressive()`, `expressiveLightColorScheme()`,
  `ExpressiveShapes`-style defaults are all `internal` in
  `androidx.compose.material3`). They are not callable from app code and require no opt-in
  because they are not public.
- The public `MaterialTheme(colorScheme, shapes, typography, content)` overload wires the
  standard motion scheme internally and is the supported theming entry point.
- **No released version of `androidx.compose.material3:material3` contains an
  `androidx.compose.material3.expressive` package or `ExpressiveButton` /
  `ExpressiveCard` / `ExpressiveFilledIconButton` classes.** I enumerated the class lists
  of 1.4.0 stable and 1.5.0-alpha25: only `ExperimentalMaterial3ExpressiveApi`,
  `MotionScheme.ExpressiveMotionSchemeImpl` and `ExpressiveMotionTokens` exist. In the
  1.5.0-alpha line the "expressive" component treatment is exposed as shape-morphing
  overloads of the standard components behind `@ExperimentalMaterial3ExpressiveApi`, not
  as `Expressive*` composables.
- Consequently `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` is **not** required for
  anything used here.

Because the task said to prefer the stable 1.4.x line and fall back gracefully when an
expressive component API is missing, the implementation does the fallback: expressive
**tokens** (shape + typography) are applied at the theme level, and the app's standard
components (`Button`, `FloatingActionButton`, `AssistChip`, `NavigationBar`, `Card`,
`OutlinedTextField`, `AlertDialog`, `FilledIconButton`) inherit the expressive shapes and
type scale automatically through `MaterialTheme` — buttons 12dp, chips 12dp, cards 16dp,
FAB 24dp, dialogs 32dp corners (up from 8/8/12/16/28).

## 3. What changed per file

### `gradle/libs.versions.toml`
- `composeBom = "2025.09.01"` (first stable BOM with `material3 1.4.0`).
- No other version changes. `material-icons-core` is still pinned by the BOM at 1.7.8 and
  remains explicitly declared (material3 1.4.0 no longer depends on it transitively).

### `app/src/main/java/com/mindchat/app/theme/Theme.kt` (new file)
- `MindChatShapes: Shapes` — Material 3 Expressive corner radii:
  `extraSmall 8dp, small 12dp, medium 16dp, large 24dp, extraLarge 32dp`.
- `MindChatTypography: Typography` — Material 3 Expressive type scale on
  `FontFamily.Default`: display/headline roles grow (displayLarge 57→64sp,
  displayMedium 45→52sp, displaySmall 36→44sp, headlineLarge 32→40sp,
  headlineMedium 28→36sp, headlineSmall 24→32sp), the two largest display styles get
  tighter letter spacing (−0.5sp / −0.25sp), and title/body/label roles keep baseline
  sizes for readability.
- `MindChatTheme(dynamicColor, darkTheme, content)` — central theme composable: dynamic
  color (Material You) on Android 12+ when enabled, static light/dark fallback on
  Android 8–11, always with the expressive shapes and typography via
  `MaterialTheme(colorScheme, shapes, typography, content)`.

### `app/src/main/java/com/mindchat/app/MindChatApp.kt`
- Replaced the inline color-scheme selection + `MaterialTheme(colorScheme = colors)`
  block with `MindChatTheme(dynamicColor = state.dynamicColor) { ... }`.
- Removed the now-unused imports (`android.os.Build`, the four `*ColorScheme`
  functions) and the unused `LocalContext` value in the private `MindChatApp`.
- All component usages (`Button`, `FloatingActionButton`, `FilledIconButton`, `Card`,
  `AssistChip`, `NavigationBar`, `OutlinedTextField`, `AlertDialog`) are unchanged and
  now pick up the expressive tokens from the theme. No navigation, dialog logic, or
  strings were touched.

### Untouched
Rust sources, `MindChatGateway.kt`, `build.gradle.kts`, scripts, version names, and
`strings.xml` were not modified.

## 4. Verification

```
JAVA_HOME=/Users/x32db/jdk17/Home ANDROID_HOME=/Users/x32db/Library/Android/sdk \
  ./gradlew :app:compileDebugKotlin -x preBuild -x buildRustAndroid
BUILD SUCCESSFUL in 7m 59s
```

The Kotlin task depends on `generateUniffiKotlin`, which rebuilt the Rust host crate
(`Finished release profile [optimized] target(s) in 1m 31s`) and regenerated the UniFFI
bindings, then `compileDebugKotlin` succeeded with the new BOM. The Android NDK
cross-compile (`buildRustAndroid`) was skipped via `-x preBuild -x buildRustAndroid`
since it is not needed for Kotlin compilation.

No unresolved compile issues.

## 5. Notes / follow-ups

- If/when material3 1.5.0 reaches stable, the expressive component overloads (shape
  morphing buttons, expressive text fields) can be adopted by bumping the BOM; the theme
  tokens in `theme/Theme.kt` are already aligned with that scale.
- `MotionScheme` expressive motion is internal in 1.4.0; the app inherits the standard
  motion scheme from the public `MaterialTheme` overload until a stable release exposes
  `MotionScheme.expressive()` publicly.
