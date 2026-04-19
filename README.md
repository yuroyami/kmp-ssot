# kmp-ssot

A Gradle plugin that gives a Kotlin Multiplatform project **one `kmpSsot { }`
block at the root** and propagates every value (app name, version, bundle ID,
compile/min SDK, locale list, …) to Android application, Android library, and
iOS builds automatically.

One source of truth, automatic propagation, per-concern opt-out toggles.

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
    id("com.yuroyami.kmpssot") version "0.3.0"
    // ...your other plugins, typically with .apply(false)...
}

kmpSsot {
    appName      = "Jetzy"
    versionName  = "0.3.0"
    bundleIdBase = "com.yuroyami.jetzy"

    iosBundleSuffix            = ".ios"        // default ""
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

Application is **root-only** by design. Submodules declare their own local
concerns (namespace, signing, Android toolchain, plugin list) and pick up
cross-platform identity from the root DSL automatically.

### Scope: cross-platform identity only

`kmpSsot { }` deliberately covers cross-platform identity and toolchain:
app name, version, bundle ID, Java version, locales. It does **not** cover
`compileSdk`, `minSdk`, `targetSdk`, or `ndkVersion` — those are Android-only
toolchain values and belong in each Android module's own build file:

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

Keeping Android-only toolchain out of the SSOT block avoids a hairy reach into
KGP 2.3's android-target validator and keeps the DSL honest about what's
actually cross-platform.

### 3. Two one-time consumer-side patches

**AndroidManifest.xml** — replace the hardcoded label:
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

## What gets auto-wired

| Where | Values |
|---|---|
| Any subproject with `com.android.application` | `applicationId`, `versionCode/Name`, `compileOptions` (source/target from `javaVersion`), `manifestPlaceholders["appName"]`, `resourceConfigurations` |
| Any subproject with `com.android.library` | `compileOptions`, `resourceConfigurations` |
| Any subproject with `org.jetbrains.kotlin.multiplatform` | `syncIosConfig` hooked into `linkPod*FrameworkIos*` + `embedAndSignAppleFrameworkForXcode` tasks |
| iOS pbxproj (rewritten in place) | `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`, `INFOPLIST_KEY_CFBundleDisplayName`, `PRODUCT_BUNDLE_IDENTIFIER`, `knownRegions` |

`versionCode` is derived from `versionName` via the formula
`"1" + dot-segments-padded-to-3` (e.g. `0.3.0` → `1000003000`).

---

## Publishing (maintainer only)

```bash
# In this repo:
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
  both validate their DSL before either of those hooks runs.
- iOS `pbxproj` rewrites are **idempotent** — no file write occurs if values
  already match, so timestamps stay stable.
- The plugin deliberately does not auto-apply `kotlin("multiplatform")` or any
  Android plugin. It listens for them; consumers decide the plugin list.
