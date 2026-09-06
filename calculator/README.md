# NovaCalc

A native Android calculator. No WebView, no JavaScript, no internet permission:
Kotlin and Jetpack Compose end to end, Material 3 with dynamic colour, and an
exact-decimal calculation engine that is unit-tested to the last edge case.

## What it does

| Area | Details |
|------|---------|
| Basic | `+ − × ÷`, percent (`200 + 10% = 220`), sign toggle, smart `( )` key, decimal point, backspace (long-press clears), AC |
| Live preview | The result of what you have typed so far shows under the expression, before you press `=` |
| Scientific | sin cos tan and inverses, ln log and their inverses (eˣ, 10ˣ), √ ∛, x², xʸ, x!, 1/x, \|x\|, π, e, DEG/RAD |
| Memory | MC MR M+ M− MS, persisted across restarts, with an `M = …` chip at the top |
| History | Every calculation with time stamps, grouped by day; tap a result to reuse it, tap the expression to edit it, delete one or clear all |
| Clipboard | Copy the result with one tap; long-press the display to copy or paste an expression |
| Settings | System/Light/Dark theme, dynamic colour (Android 12+), haptics, keep screen on, angle unit, decimal places (0–12), thousands separators |
| Polish | Splash screen, adaptive + themed launcher icon, edge-to-edge, predictive back, landscape layout with both pads side by side, restores the expression you were typing |
| Accessibility | Every key has a spoken label; touch targets are large; the app registers as the device calculator (`APP_CALCULATOR`) |

## Engine

Arithmetic runs on `BigDecimal` with 34 significant digits, so `0.1 + 0.2` is
exactly `0.3` and `9999999999999999 × 9999999999999999` is exact. Functions go
through IEEE doubles and are rounded to 15 significant digits, with the exact
quadrant cases handled by hand (`sin 180° = 0`, `tan 90°` is an error, not
`1.6e16`). Precedence and associativity follow mathematics: `2^3^2 = 512`,
`-2^2 = -4`, implicit multiplication works (`2π`, `3(4+5)`, `2sin(30)`), and a
missing closing parenthesis at the end is implied.

## Building

```
cd calculator
./gradlew :app:testDebugUnitTest      # engine, editor, formatter and view-model tests
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew :app:connectedDebugAndroidTest   # instrumented UI tests on a connected device or emulator
```

Release builds are signed with the keystore named by `NOVACALC_KEYSTORE`,
`NOVACALC_KEYSTORE_PASSWORD`, `NOVACALC_KEY_ALIAS` and `NOVACALC_KEY_PASSWORD`
(environment variables or `-P` Gradle properties). Without one the release is
signed with the debug key so it still installs. CI (`.github/workflows/calculator.yml`)
runs the unit tests, builds the APK as an artifact, and runs the instrumented
suite on an API 30 emulator.

## Layout

```
calculator/
  app/src/main/java/app/novacalc/
    engine/     Tokenizer, Evaluator, NumberFormatter, InputToken, CalculatorEditor  (pure Kotlin)
    data/       CalculatorSettings, HistoryEntry, DataStore repositories
    ui/         CalculatorViewModel, CalculatorScreen, Keypad, HistorySheet, SettingsScreen, theme
  app/src/test/           JUnit tests for the engine, editor, formatter and view model
  app/src/androidTest/    Compose UI tests that drive the real activity
```

Requirements: minSdk 26 (Android 8.0), targetSdk 36. Licence: Apache 2.0, the
same as the rest of this repository.
