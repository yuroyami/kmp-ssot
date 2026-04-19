# kmp-ssot

A standalone Gradle plugin that provides a **single source of truth** for KMP app
configuration — `appName`, `versionName`, `versionCode`, `bundleId`, `compileSdk`,
`minSdk`, `javaVersion` — and propagates it to both Android and iOS builds
automatically.

No more hardcoding the app name in three places, or watching the iOS version
drift from the Android version.

---

## What it does

Apply the plugin to a KMP app module, declare values once in a `kmpSsot { }` block,
and the plugin:

- Auto-configures the Android application extension (`applicationId`, `versionName`,
  `versionCode`, `minSdk`, `compileSdk`, `targetSdk`, `compileOptions`, `ndkVersion`,
  plus `appName` as a manifest placeholder).
- Registers a `syncIosConfig` task that rewrites
  `iosApp/iosApp.xcodeproj/project.pbxproj` on every iOS build so that
  `MARKETING_VERSION`, `CURRENT_PROJECT_VERSION`,
  `INFOPLIST_KEY_CFBundleDisplayName`, and `PRODUCT_BUNDLE_IDENTIFIER` all
  match the DSL.
- Hooks `syncIosConfig` to `linkPod*FrameworkIos*` and
  `embedAndSignAppleFrameworkForXcode`, so iOS builds never drift.

---

## Consuming the plugin

### 1. Configure `pluginManagement` in `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
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

### 2. Apply in your app module

```kotlin
// androidApp/build.gradle.kts  (or wherever the Android application lives)
plugins {
    id("com.android.application")
    kotlin("multiplatform")
    id("com.yuroyami.kmpssot") version "0.1.0"
}

kmpSsot {
    appName      = "Jetzy"
    versionName  = "0.3.0"
    bundleIdBase = "com.yuroyami.jetzy"

    // Optional:
    iosBundleSuffix            = ".ios"        // default ""
    androidApplicationIdSuffix = ""             // default ""
    compileSdk                 = 36             // default 36
    minSdk                     = 26             // default 26
    javaVersion                = 21             // default 21
    iosProjectPath             = "iosApp/iosApp.xcodeproj/project.pbxproj" // default
}
```

### 3. Two one-time consumer-side changes

**AndroidManifest.xml** — replace the hardcoded label with the placeholder:

```xml
<application
    android:label="${appName}"
    ... />
```

**iOS Info.plist** — replace the hardcoded version literal with the Xcode build-setting
reference, so `CFBundleShortVersionString` follows `MARKETING_VERSION`
(which the plugin rewrites):

```xml
<key>CFBundleShortVersionString</key>
<string>$(MARKETING_VERSION)</string>
```

That's it. Every build now reads from the `kmpSsot { }` block.

---

## Credentials for GitHub Packages

GitHub Packages requires authentication even for public reads. Generate a
[classic PAT](https://github.com/settings/tokens) with scope `read:packages`
(publishers also need `write:packages`) and put it in `~/.gradle/gradle.properties`:

```properties
gpr.user=yuroyami
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Or export `GITHUB_ACTOR` / `GITHUB_TOKEN` in the environment (which is what CI
usually does).

---

## Publishing (maintainer only)

```bash
./gradlew publishAllPublicationsToGitHubPackagesRepository \
    -PkmpSsot.version=0.1.0 \
    -Pgpr.user=yuroyami \
    -Pgpr.key=$GITHUB_TOKEN
```

GitHub Packages accepts the upload instantly — no review, no publishing delay.

---

## Design notes

- `versionCode` is derived, not declared. The formula is
  `"1" + dot-segments-zero-padded-to-3`, matching the syncplay-mobile convention
  (`0.3.0` → `1000003000`). Monotonically increasing for any semver bump.
- The plugin touches `project.pbxproj` with idempotent regex replacements —
  writes only occur when values actually change, so file timestamps are stable.
- Android wiring happens in `afterEvaluate` so the consumer's `kmpSsot { }`
  block is fully parsed first.
- `ndkVersion` is optional — set it only when the consumer needs native deps
  (e.g. an app using MPV or a JNI bridge).
