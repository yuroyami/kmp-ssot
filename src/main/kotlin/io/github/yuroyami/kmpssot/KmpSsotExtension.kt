package io.github.yuroyami.kmpssot

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * DSL for the kmp-ssot plugin. Applied to the **root project** only.
 *
 * Every identity field is optional. A field gets propagated iff (a) its
 * `propagate*` toggle is true (default true) AND (b) the value is set.
 * Leave a field unset to opt out of that piece of propagation completely.
 *
 * App logo: set both [appLogoPngForeground] and [appLogoPngBackground] to
 * enable logo propagation, or leave both unset. Setting only one fails
 * configuration.
 */
abstract class KmpSsotExtension {

    // --- Identity -------------------------------------------------------------

    abstract val appName: Property<String>
    abstract val versionName: Property<String>
    abstract val bundleIdBase: Property<String>

    /** Suffix appended to bundleIdBase for the iOS bundle id. Null = no suffix. */
    abstract val iosBundleSuffix: Property<String>

    /** Suffix appended to bundleIdBase for the Android applicationId. Null = no suffix. */
    abstract val androidApplicationIdSuffix: Property<String>

    // --- Shared toolchain (cross-platform) -----------------------------------

    abstract val javaVersion: Property<Int>

    // --- Localization ---------------------------------------------------------

    /**
     * Locales supported by the app. Defaults to auto-detection from
     * `{sharedModule}/src/commonMain/composeResources/values-*` directories.
     */
    abstract val locales: ListProperty<String>

    // --- Module structure ----------------------------------------------------

    /** KMP shared module directory name (e.g. "shared", "composeApp"). REQUIRED. */
    abstract val sharedModule: Property<String>

    /** Android application module directory name. Defaults to "androidApp". */
    abstract val androidAppModule: Property<String>

    // --- App logo -------------------------------------------------------------

    /**
     * Foreground layer of the app logo, as a square PNG with an alpha channel.
     * Treated as the full Android adaptive-icon canvas (108dp). Design content
     * inside the inner safe zone (~66dp of 108dp ≈ 61.1% centred) — anything
     * outside may be cropped on Android by launcher masks.
     *
     * Recommended source size: 1024×1024. Minimum useful size: 432×432
     * (xxxhdpi adaptive-icon foreground).
     *
     * Propagated to:
     *  - Android: density-bucketed `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png`
     *    plus an adaptive-icon wrapper in `mipmap-anydpi-v26/`, plus composited
     *    legacy `ic_launcher{,_round}.png` for pre-API-26 devices.
     *  - iOS: composited over [appLogoPngBackground], flattened to an opaque
     *    1024×1024 RGB PNG (App Store marketing icons must not have alpha).
     */
    abstract val appLogoPngForeground: RegularFileProperty

    /**
     * Background layer of the app logo, as a square PNG. Alpha is allowed but
     * the BG should be effectively opaque — any transparency will read as
     * white on the iOS flattened output. A flat-colour PNG works fine.
     *
     * Treated as the full Android adaptive-icon canvas (108dp), same as
     * [appLogoPngForeground]. Recommended source size: 1024×1024.
     */
    abstract val appLogoPngBackground: RegularFileProperty

    // --- File paths -----------------------------------------------------------

    /** Path (relative to root project) to the iOS Xcode project file. */
    abstract val iosProjectPath: Property<String>

    /** Path (relative to root project) to the iOS Podfile. */
    abstract val iosPodfilePath: Property<String>

    /** Path (relative to root project) to the iOS Info.plist. */
    abstract val iosInfoPlistPath: Property<String>

    // --- Toggles (all default true) ------------------------------------------

    abstract val propagateAppName: Property<Boolean>
    abstract val propagateBundleId: Property<Boolean>
    abstract val propagateVersion: Property<Boolean>
    abstract val propagateLocaleList: Property<Boolean>
    abstract val propagateLogo: Property<Boolean>
    abstract val propagateSharedModule: Property<Boolean>

    /** Master switch for the iOS pbxproj rewrite task. If false, no iOS sync happens at all. */
    abstract val syncIos: Property<Boolean>

    /**
     * Ensure the iOS `Info.plist` has the SSOT-pointing keys the sync task relies on
     * (`CFBundleDisplayName`, `CFBundleName`, `CFBundleShortVersionString`, `CFBundleVersion`).
     * Append-only: never overwrites existing values — just inserts missing keys pointing at
     * the corresponding build variable. A warning is logged if an existing value is hardcoded
     * in a way that will defeat SSOT propagation. Default true.
     */
    abstract val sanitizeIosProject: Property<Boolean>

    /**
     * Delete logo artefacts left behind by pre-FG/BG plugin versions:
     *  - `${androidAppModule}/src/main/res/drawable/ic_launcher.xml`
     *  - `${androidAppModule}/src/main/res/values/ic_launcher_background.xml`
     *
     * Default false — opt-in migration helper. When true, the
     * `cleanupLegacyAppLogoArtifacts` task runs as a dependency of
     * `syncAndroidLogo`. The task is also always registered for one-shot
     * manual invocation (`./gradlew cleanupLegacyAppLogoArtifacts`).
     */
    abstract val cleanupLegacyLogoArtifacts: Property<Boolean>

    // --- Derived values (read-only) ------------------------------------------

    /** versionCode derived from versionName via "1" + zero-padded dot segments. */
    val versionCode: Provider<Int>
        get() = versionName.map { vn ->
            ("1" + vn.split(".").joinToString("") { it.padStart(3, '0') }).toInt()
        }

    val androidApplicationId: Provider<String>
        get() = bundleIdBase.zip(androidApplicationIdSuffix.orElse("")) { base, suffix -> base + suffix }

    val iosBundleId: Provider<String>
        get() = bundleIdBase.zip(iosBundleSuffix.orElse("")) { base, suffix -> base + suffix }
}
