# kmp-ssot

A Gradle plugin that gives a Kotlin Multiplatform project **one `kmpSsot { }`
block at the root** and propagates cross-platform identity (app name, version,
bundle ID, locales) to Android and iOS builds automatically.

One source of truth, per-concern opt-out toggles, every field optional.

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
    id("com.yuroyami.kmpssot") version "0.4.0"
    // ...your other plugins, typically with .apply(false)...
}

kmpSsot {
    appName      = "Jetzy"
    versionName  = "0.3.0"
    bundleIdBase = "com.yuroyami.jetzy"

    iosBundleSuffix            = ".iosApp"     // default ".iosApp"
    androidApplicationIdSuffix = ""             // default ""
    javaVersion                = 21

    // Propagated to Android resourceConfigurations (app + library) and to
    // iOS pbxproj knownRegions. Leave empty to leave locales alone.
    locales = listOf("en", "ar", "fr")

    // Per-concern toggles — all default true. Flip a single flag to opt out.
    // propagateAppName     = true
    // propagateBundleId    = true
    // propagateVersion     = true
    // propagateLocaleList  = true
    // syncIos              = true   // master switch for the iOS pbxproj rewrite
}
```

**Every identity field is optional.** A field gets propagated iff (a) its
`propagate*` toggle is on AND (b) the value is set. This lets you drop the
plugin onto a live production app and centralize only the parts you want —
e.g. `versionName` + `locales`, leaving `bundleIdBase` unset so already-
registered Android applicationId and iOS `PRODUCT_BUNDLE_IDENTIFIER` are
never touched.

Application is **root-only** by design. Submodules declare their own local
concerns (namespace, signing, Android toolchain, plugin list) and pick up
cross-platform identity from the root DSL automatically.

### 3. Scope: cross-platform identity only

`kmpSsot { }` covers cross-platform identity and shared toolchain:
app name, version, bundle ID, Java version, locales. It does **not** cover
`compileSdk`, `minSdk`, `targetSdk`, or `ndkVersion` — those are Android-only
toolchain values, belong in each Android module, and sidestep KGP 2.3's
android-target validator entirely:

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

**iOS Info.plist** — use Xcode build-setting references so the pbxproj drives
both version numbers and the launcher name:
```xml
<key>CFBundleShortVersionString</key>
<string>$(MARKETING_VERSION)</string>
<key>CFBundleVersion</key>
<string>$(CURRENT_PROJECT_VERSION)</string>
```

---

## What gets auto-wired

| Where | Values |
|---|---|
| `com.android.application` | `applicationId` (when bundleIdBase set), `versionCode/Name` (when versionName set), `compileOptions` source/target from `javaVersion`, `manifestPlaceholders["appName"]` (when appName set), `resourceConfigurations` (from `locales`) |
| `com.android.library` | `compileOptions`, `resourceConfigurations` |
| `org.jetbrains.kotlin.multiplatform` | `syncIosConfig` hooked into `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode` |
| iOS pbxproj (rewritten idempotently) | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_CFBundleDisplayName`, `INFOPLIST_KEY_CFBundleName`, `PRODUCT_BUNDLE_IDENTIFIER`, `knownRegions` |

`versionCode` is derived from `versionName` via the formula
`"1" + dot-segments-padded-to-3` (e.g. `0.3.0` → `1000003000`).

## iOS launcher name

The iOS launcher shows `CFBundleDisplayName` (falling back to `CFBundleName`
on older iOS versions). Both are synthesized at build time by Xcode from the
pbxproj's `INFOPLIST_KEY_CFBundleDisplayName` and `INFOPLIST_KEY_CFBundleName`
build settings. The plugin rewrites both on every iOS build when `appName` is
set and `propagateAppName` is on, so the home-screen name stays consistent
with the DSL across iOS versions.

## Known limitations

- **Multi-target iOS projects**: `PRODUCT_BUNDLE_IDENTIFIER` is rewritten for
  *every* `PRODUCT_BUNDLE_IDENTIFIER` line in the pbxproj — main app, tests,
  extensions all get the same value. If you have multiple iOS targets with
  distinct bundle IDs (widget, share extension, tests), set
  `propagateBundleId = false` and manage bundle IDs manually.
- **Android-only toolchain**: `compileSdk` / `minSdk` / `targetSdk` /
  `ndkVersion` are *intentionally* out of scope — declare them in each
  Android module's own build file.

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
  already match, so timestamps stay stable and Xcode doesn't think the project
  has been externally modified on every build.
- The plugin deliberately does not auto-apply `kotlin("multiplatform")` or any
  Android plugin. It listens for them; consumers decide the plugin list.
