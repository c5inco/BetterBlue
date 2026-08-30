# BetterBlue for Android

A Kotlin + Jetpack Compose port of BetterBlue, the Hyundai BlueLink / Kia
Connect vehicle control app. The iOS/watchOS sources stay at the repository
root; everything here is self-contained.

## Modules

| Module | What it is |
|---|---|
| `:betterbluekit` | Pure Kotlin/JVM port of the [BetterBlueKit](https://github.com/schmidtwmark/BetterBlueKit) Swift package — the brand/region API clients, models, and HTTP layer. No Android dependencies. |
| `:app` | The Android application: Room persistence, repositories, and the Compose UI. |

Keeping the kit Android-free is deliberate: its full test suite runs on a
plain JVM in seconds, which is what makes the region parsers testable against
captured payloads.

## Building

```bash
cd android
./gradlew build            # everything available in the current environment
./gradlew :betterbluekit:test   # the API layer's test suite
./gradlew :app:assembleDebug    # the APK (requires the Android SDK)
```

`:app` is **gated on Android SDK availability** in `settings.gradle.kts`: if
neither `ANDROID_HOME`/`ANDROID_SDK_ROOT` nor an `sdk.dir` entry in
`local.properties` is present, the module is skipped with a warning and only
`:betterbluekit` builds. That keeps the kit verifiable in sandboxes and CI
images that have no Android toolchain.

### Maps API key

The map needs a Google Maps key. Put it in `android/local.properties`
(git-ignored):

```properties
MAPS_API_KEY=your-key-here
```

Without one the app still builds and runs — `VehicleMap` falls back to a
placeholder showing the vehicle's coordinates. `local.defaults.properties`
supplies the empty default so keyless checkouts and CI are never broken by a
missing secret.

### Fake vehicle mode

As on iOS, the app can run entirely without a real car. Add an account with
the **Fake (Testing)** brand, or use the test credentials
(`testaccount@betterblue.com` / `betterblue`) with any brand — both route to
`FakeApiClient` backed by Room. Each fake vehicle carries a
`DebugConfiguration` that injects failures (bad credentials, failed login,
failed status fetch, per-command failures, custom error messages) so every
error path can be exercised on a desk.

## Architecture

The layering mirrors iOS, with the SwiftData model's dual role split apart:

| iOS | Android |
|---|---|
| `BBAccount` (model **and** repository) | `AccountEntity` (Room) + `AccountRepository` |
| `BBVehicle` + `waitForStatusChange` | `VehicleEntity` + `VehicleRepository` |
| SwiftData `@Model` ×4 | Room entities ×4, status sub-objects as JSON columns |
| `CachedAPIClient` | `com.betterblue.kit.cache.CachedApiClient` |
| `AppSettings` (+ `live*()` accessors) | `AppSettings` over DataStore (Flow reads are inherently live, so the cross-process staleness workaround disappears) |
| `MainView` map + `VehicleSheetPager` | `MainScreen` + `VehicleMap` + `PersistentSheet` + `HorizontalPager` |
| `VehicleSheetPresentation` enum router | sealed `SheetRoute` |
| Actor-held continuation map | `StatusChangeBus` (`SharedFlow`) + `withTimeoutOrNull` |
| Plaintext credentials in SwiftData | AES-GCM via `KeystoreCredentialCipher` |

### Behaviors preserved deliberately

These carry real consequences and are unit-tested in the kit
(`com.betterblue.kit.policy`):

- **`shouldReauthenticate` vs `shouldRetryCommand`.** The command-retry set is
  strictly narrower — a blind retry can act on the car twice. `KIA_INVALID_REQUEST`
  re-authenticates but is never re-sent, because Kia's anti-fraud layer can
  return it *after* accepting a command.
- **Fuel-type self-heal.** The status payload's shape corrects a misclassified
  powertrain, but only ever *upgrades* specificity (gas → electric → phev), so
  a partial payload can't demote a known PHEV.
- **`STATUS_VERIFICATION_TIMEOUT` is not a failure.** It surfaces as
  `CommandOutcome.AwaitingConfirmation` and renders as a soft chip: the
  command was accepted upstream and the vehicle simply hasn't confirmed yet.
- **Device-trust anchors survive routine re-auth.** `deviceId` and
  `rememberMeToken` are kept across session expiry and dropped only by an
  explicit session reset — clearing them made every re-auth look like a new
  device and re-triggered MFA.
- **HVAC lookup tables.** Temperatures snap to the car's own table; a linear
  °F→°C conversion is silently rejected by EU vehicles.
- **Cloudflare-sensitive constants.** Kia EU's `_CCS_APP_AOS` user-agent suffix
  and Hyundai Canada's `__cf_bm` handshake are load-bearing, not cosmetic.

## Screens

The main screen is a full-bleed map with a two-detent draggable sheet over it,
one paged card per vehicle. Everything else is a modal sheet, routed through a
single sealed `SheetRoute` host so the set stays exhaustive at compile time:
vehicle settings, account, climate presets, charge limits, trips, surround
view, HTTP logs, and the fake-vehicle debug configuration. They open from the
overflow menu on the card, gated the same way iOS gates its context menu.

## Test coverage

`./gradlew :betterbluekit:test` runs 214 tests covering the measurement and
HVAC tables, date parsing, PII redaction, the surround-view JPEG splitter and
capture polling, UUIDv5 derivation, the CCSP key-path table and stamp,
per-region response parsing, the caching/dedup layer, and the retry and
fuel-type policies. Region payloads are ported from the Swift suite's captured
responses.

Logic worth testing is deliberately pushed down into `:betterbluekit` rather
than left in `:app`: the retry policies, fuel-type self-heal, and the
surround-view capture poller (a 6-minute deadline exercised with virtual time)
all live there, so they run on every build instead of relying on a device.

## Linting

`.editorconfig` configures ktlint for both modules; the tree is clean. In an
environment without the Android SDK this is the strongest available check on
`:app`, since ktlint parses with the real Kotlin frontend — it catches
malformed code even where the androidx dependencies can't resolve.

```bash
ktlint --format "app/src/main/java/**/*.kt" "betterbluekit/src/**/*.kt"
```

## Not ported

Widgets, the Wear OS app, and a live-notification analog are deliberately out
of scope for this phase. The seams are in place: all state is read through
repositories and DAOs (no ViewModel-held truth), and the kit is a plain JVM
module, so Glance widgets and a Wear module can reuse both directly.

iOS-specific pieces with no Android counterpart were dropped: the CloudKit
sync monitor (Room only — there is no cross-device sync), the display
corner-radius utility, and the legacy pre-`PersistentVehicleSheet` views.
