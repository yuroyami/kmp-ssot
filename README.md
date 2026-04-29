# kmp-ssot

A Gradle plugin that gives a Kotlin Multiplatform project **one `kmpSsot { }`
block at the root** and propagates cross-platform identity (app name, version,
bundle ID, locales) to Android and iOS builds automatically.

One source of truth, per-concern opt-out toggles, every identity field optional.

---

## Install

### 1. Add the GitHub Packages repo to `pluginManagement` in `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/yuroyami/kmp-ssot")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Put `gpr.user=<your-gh-username>` and `gpr.key=<PAT with read:packages>` in
`~/.gradle/gradle.properties` (0600-locked) so Android Studio can resolve
the plugin without environment variables.

### 2. Apply at the **root project** and declare once

```kotlin
// <root>/build.gradle.kts
plugins {
    id("io.github.yuroyami.kmpssot") version "1.2.0"
    // ...your other plugins, typically with .apply(false)...
}

kmpSsot {
    appName      = "Jetzy"
    versionName  = "0.3.0"
    bundleIdBase = "com.yuroyami.jetzy"   // null by default — omit to freeze existing bundle IDs
    javaVersion  = 21

    // Module structure.
    sharedModule     = "shared"       // REQUIRED — KMP shared module directory name
    androidAppModule = "androidApp"   // optional, default "androidApp"

    // Bundle-ID suffixes — both null by default. When unset, applicationId
    // and iOS bundle ID share bundleIdBase verbatim. Set them when you need
    // to differentiate (e.g. platform-specific App Store / Play Store IDs).
    // iosBundleSuffix            = ".ios"
    // androidApplicationIdSuffix = ".android"

    // Locales — auto-detected from ${sharedModule}/src/commonMain/composeResources/values-*.
    // Explicit list overrides.
    // locales = listOf("en", "ar", "fr")

    // App logo — both layers required if logo propagation is desired, or both null.
    // Design naturally (fill the canvas, iOS-marketing-icon style); the plugin
    // handles Android's adaptive-icon safe zone automatically.
    // appLogoPngForeground = file("art/logo_foreground.png")  // PNG with alpha
    // appLogoPngBackground = file("art/logo_background.png")  // opaque PNG (or solid colour)

    // Toggles — all default true. Flip a single flag to opt out.
    // propagateAppName       = true
    // propagateBundleId      = true
    // propagateVersion       = true
    // propagateLocaleList    = true
    // propagateLogo          = true
    // propagateSharedModule  = true
    // syncIos                = true

    // One-shot migration toggle (default false). When true, syncAndroidLogo
    // first deletes drawable/ic_launcher.xml + values/ic_launcher_background.xml
    // (artefacts from pre-FG/BG plugin versions). The task is also always
    // registered for manual runs: ./gradlew cleanupLegacyAppLogoArtifacts
    // cleanupLegacyLogoArtifacts = true
}
```

**Every identity field is optional.** A field propagates iff (a) its
`propagate*` toggle is on AND (b) the value is set. This lets you drop the
plugin onto a live production app and centralize only the parts you want —
e.g. `versionName` + `locales` + `appName`, leaving `bundleIdBase` unset so
already-registered Android `applicationId` and iOS `PRODUCT_BUNDLE_IDENTIFIER`
are never touched.

### 3. Scope: cross-platform identity only

`kmpSsot { }` covers cross-platform identity and shared toolchain:
app name, version, bundle ID, Java version, locales. It does **not** cover
`compileSdk`, `minSdk`, `targetSdk`, or `ndkVersion` — those are Android-only
toolchain values that belong in each Android module's own build file:

```kotlin
// androidApp/build.gradle.kts
android {
    compileSdk = 36
    defaultConfig { minSdk = 26; targetSdk = 36 }
}

// shared/build.gradle.kts  (KMP module)
kotlin.android {
    compileSdk { version = release(36) }
    minSdk = 26
}
```

### 4. Two one-time consumer-side patches

**AndroidManifest.xml** — replace the hardcoded label with the placeholder:
```xml
<application
    android:label="${appName}"
    ... />
```

**iOS Info.plist** — use Xcode build-setting references:
```xml
<key>CFBundleShortVersionString</key>
<string>$(MARKETING_VERSION)</string>
<key>CFBundleVersion</key>
<string>$(CURRENT_PROJECT_VERSION)</string>
```

---

## App logo

The logo is sourced from **two square PNG layers**:

- `appLogoPngForeground` — PNG with an alpha channel. The visible logo content.
- `appLogoPngBackground` — PNG (effectively opaque). The colour/texture behind the foreground.

**Design naturally** — fill the canvas like an iOS App Store icon. The plugin
handles Android's adaptive-icon safe zone for you: when generating the
adaptive FG layer, the source FG is centred at 66/108 (~61.1%) of the
108dp canvas with a transparent margin, so the launcher's mask and parallax
movement never crop your content. iOS and the Android legacy fallback
render the source layers at native size, so what you design is what ships.

Recommended source size: 1024×1024 (matches the iOS App Store icon).
Minimum useful size: 432×432 (xxxhdpi adaptive-icon foreground).

**Android (`syncAndroidLogo`)** — generates a complete launcher-icon resource
tree under `${androidAppModule}/src/main/res/`:

- `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png` — FG, auto-padded to the 66/108 safe zone (108×scale canvas)
- `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_background.png` — BG, fills the full 108×scale canvas
- `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` — legacy fallback (square, native composite, 48×scale)
- `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_round.png` — legacy fallback (circle-masked composite)
- `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` — API-26+ adaptive-icon wrappers

Hooked into Android `preBuild` so files are in place by resource processing.

The plugin's scope is pure Android + iOS platform propagation — it does **not**
copy anything into `commonMain/composeResources/`. If you want the logo
available to Compose, place it in `composeResources/drawable/` yourself.

**iOS (`syncIosLogo`)** — composites foreground over background at native
1024×1024, flattens to opaque RGB (App Store marketing icons must not have
alpha), writes:
- `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
- A single-image universal `Contents.json`

Requires iOS deployment target 14+ (single-size universal icon, Xcode handles
down-scaling at build time). Hooked into the iOS framework link tasks so the
icon ships with every iOS build.

If you set only one of the two logo layers, the build fails at
`afterEvaluate` — pair them, or leave both unset.

### Migrating from a pre-FG/BG plugin version

Older versions of the plugin generated two files under the Android res tree
that the new pipeline no longer produces:

- `${androidAppModule}/src/main/res/drawable/ic_launcher.xml`
- `${androidAppModule}/src/main/res/values/ic_launcher_background.xml`

After upgrading they sit there as orphan resources. Two ways to clean them up:

1. **One-shot manual run**:
   ```bash
   ./gradlew cleanupLegacyAppLogoArtifacts
   ```
2. **Set `cleanupLegacyLogoArtifacts = true`** in the DSL — `syncAndroidLogo`
   then deletes them on every run. The toggle defaults to `false` because
   it's only meaningful during the one-time migration.

Both files were 100% plugin-owned in older versions, so deletion is safe.

---

## Shared module rename SSOT

`sharedModule` is the directory name of the KMP shared module — required.
When you rename it (say from `shared` to `composeApp`):

1. Rename the directory and update `settings.gradle.kts` `include(":...")`.
2. Update `kmpSsot { sharedModule = "composeApp" }` in the root build file.
3. Reference it in your shared module's cocoapods `baseName`:
   ```kotlin
   // shared (or composeApp)/build.gradle.kts
   val ssot = rootProject.extensions.getByType<io.github.yuroyami.kmpssot.KmpSsotExtension>()
   kotlin {
       cocoapods {
           framework { baseName = ssot.sharedModule.get() }
       }
   }
   ```
4. Run `./gradlew syncIosConfig`.

The plugin detects the old name from the existing `iosApp/Podfile` line
(`pod 'X', :path => '../X'`) and rewrites:
- The Podfile `pod` name + `:path =>` references
- Every `import X` (plain form) in `iosApp/**/*.swift`

Then `pod install` in `iosApp/` (or `./gradlew :${sharedModule}:podInstall`)
refreshes the Pods workspace from the new podspec — the
`Pods.xcodeproj`, the iOS app's pbxproj linker entries, and the generated
podspec all rebuild from there, so no further manual rewrites.

`@_implementationOnly import` and Bridging-Header `#import <X/X.h>` are
*not* touched — the regex is intentionally narrow. Edit those by hand if
they exist in your project.

---

## Auto-detected locales

When `locales` is not explicitly set, the plugin scans
`${sharedModule}/src/commonMain/composeResources/` for directories named
`values-<tag>` (matching the Compose Resources convention) and uses those
tags as the locale list. If no such directories exist, the list stays empty
and no locale propagation happens.

Override by setting `locales = listOf("en", "ar")` explicitly — the explicit
list wins over auto-detection.

The list propagates to:
- Android `defaultConfig.resourceConfigurations` (app + library modules)
- iOS `project.pbxproj` `knownRegions` (preserves `Base`)

---

## What gets auto-wired

| Where | Values |
|---|---|
| `com.android.application` | `applicationId` (when bundleIdBase set), `versionCode/Name` (when versionName set), `compileOptions` source/target from `javaVersion`, `manifestPlaceholders["appName"]` (when appName set), `resourceConfigurations` (from `locales`) |
| `com.android.library` | `compileOptions`, `resourceConfigurations` |
| `org.jetbrains.kotlin.multiplatform` | `syncIosConfig` + `syncIosLogo` hooked into `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode` |
| iOS `project.pbxproj` (rewritten idempotently) | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_CFBundleDisplayName`, `INFOPLIST_KEY_CFBundleName`, `PRODUCT_BUNDLE_IDENTIFIER`, `knownRegions` |
| iOS `Podfile` (when `sharedModule` differs from current pod name) | `pod 'X', :path => '../X'` lines |
| iOS `iosApp/**/*.swift` (when `sharedModule` differs) | plain `import X` statements |
| iOS `AppIcon.appiconset/` | `AppIcon-1024.png` (FG-over-BG composite, opaque) + `Contents.json` (universal) |
| Android `${androidAppModule}/src/main/res/` | per-density `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png` (legacy composite) + `ic_launcher_foreground.png` (auto-padded to 66/108 safe zone) + `ic_launcher_background.png` (full canvas) + `mipmap-anydpi-v26/ic_launcher{,_round}.xml` adaptive wrappers |

`versionCode` is derived from `versionName` via the formula
`"1" + dot-segments-padded-to-3` (e.g. `0.3.0` → `1000003000`).

## iOS launcher name

The iOS launcher shows `CFBundleDisplayName` on iOS 13+ and falls back to
`CFBundleName` on older versions. Both are synthesized at build time by Xcode
from pbxproj's `INFOPLIST_KEY_CFBundleDisplayName` / `INFOPLIST_KEY_CFBundleName`
build settings. The plugin rewrites both keys when they exist in the pbxproj,
so the home-screen name stays consistent with the DSL.

**Note on injection**: the regex only rewrites *existing* keys — it never
inserts new ones (pbxproj has structured per-target build-config sections,
and blind injection would place the line in the wrong section). If your
Xcode template didn't emit `INFOPLIST_KEY_CFBundleName`, add the line once
manually to the main target's build config (for both Debug and Release),
and the plugin will keep it in sync from then on.

## Multi-target iOS projects

`PRODUCT_BUNDLE_IDENTIFIER` is rewritten for *every* occurrence in pbxproj —
main app, tests, and extensions all get the same value. This is intentional:
when `bundleIdBase` is set, every place the bundle ID could appear is
propagated so production signing/provisioning uses one authoritative value.

If a project genuinely needs distinct bundle IDs per target (e.g. widget
extension), set `propagateBundleId = false` and manage those IDs manually.

---

## Publishing (maintainer only)

```bash
GITHUB_ACTOR=yuroyami \
GITHUB_TOKEN="$(gh auth token)" \
  ./gradlew publishAllPublicationsToGitHubPackagesRepository
```

GitHub Packages has no review queue — publishes are instant. Bump the version
in `build.gradle.kts` (`kmpSsot.version`) before republishing; existing
versions are immutable.

## Design notes

- Android values are set **eagerly**, inside the `plugins.withId("com.android.*")`
  callback — not in `afterEvaluate`, not in `finalizeDsl`. AGP 9 and KGP 2.x
  both validate their DSL before either of those hooks fires.
- iOS pbxproj rewrites are **idempotent** — no file write occurs if values
  already match.
- The plugin deliberately does not auto-apply `kotlin("multiplatform")` or any
  Android plugin. It listens for them; consumers decide the plugin list.

## Roadmap (tentative)

- **`localeFilters` migration** (v0.7.0): switch from the deprecated
  `resourceConfigurations` to `androidResources.localeFilters` for AGP 9+.
- **xcconfig-driven iOS propagation** (v0.7.0): switch from pbxproj regex
  rewrites to writing a single `kmpssot.xcconfig` the pbxproj includes —
  cleaner propagation via Xcode build settings instead of per-key edits.
- **`force()` rename** (v0.8.0): opt-in advanced flag to actually rename the
  shared module directory on disk + update `settings.gradle.kts`. Today the
  plugin assumes the user has already renamed; `force()` would automate it.
- **`LaunchScreen.storyboard` logo injection** (v0.8.0): inject the iOS
  logo into the launch screen image view too, not just AppIcon.
