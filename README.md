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
    id("com.yuroyami.kmpssot") version "0.5.0"
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

    // Toggles — all default true. Flip a single flag to opt out.
    // propagateAppName     = true
    // propagateBundleId    = true
    // propagateVersion     = true
    // propagateLocaleList  = true
    // syncIos              = true
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
| `org.jetbrains.kotlin.multiplatform` | `syncIosConfig` hooked into `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode` |
| iOS `project.pbxproj` (rewritten idempotently) | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_CFBundleDisplayName`, `INFOPLIST_KEY_CFBundleName`, `PRODUCT_BUNDLE_IDENTIFIER`, `knownRegions` |

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

- **App logo propagation** (v0.6.0): take a source image (PNG or SVG), generate
  Android mipmap densities + iOS `AppIcon.appiconset` + Contents.json, update
  `LaunchScreen.storyboard`.
- **Shared-module rename awareness** (v0.6.0): rewrite iOS references (Podfile
  `:path`, pbxproj paths) when `sharedModule` differs from the canonical
  "shared" name.
- **xcconfig-driven iOS propagation** (v0.7.0): switch from pbxproj regex
  rewrites to writing a single `kmpssot.xcconfig` the pbxproj includes —
  cleaner propagation via Xcode build settings instead of per-key edits.
