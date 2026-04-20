package com.yuroyami.kmpssot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * DSL for the kmp-ssot plugin. Applied to the **root project** only.
 *
 * Every identity field is optional. A field gets propagated iff (a) its
 * `propagate*` toggle is true (default true) AND (b) the value is actually
 * set. Leave a field unset to opt out of that piece of propagation
 * completely — the plugin won't touch the corresponding platform settings.
 *
 * Usage in the root build.gradle.kts:
 * ```
 * kmpSsot {
 *     appName      = "Jetzy"
 *     versionName  = "0.3.0"
 *     bundleIdBase = "com.yuroyami.jetzy"
 *
 *     // Module structure.
 *     sharedModule     = "shared"     // REQUIRED — KMP shared module dir name
 *     androidAppModule = "androidApp" // default "androidApp"
 *
 *     // Bundle ID suffixes are both null by default. When bundleIdBase is
 *     // set, applicationId = "${bundleIdBase}${androidApplicationIdSuffix}"
 *     // (same shape for iOS). With both suffixes null, both platforms share
 *     // one ID. Set them when you want platform-specific differentiation.
 *     iosBundleSuffix            = ".ios"    // or null
 *     androidApplicationIdSuffix = null      // or ".android", etc.
 *
 *     javaVersion = 21
 *
 *     // Auto-detected from {sharedModule}/src/commonMain/composeResources/values-*.
 *     // Set explicitly to override.
 *     // locales = listOf("en", "ar", "fr")
 *
 *     // Toggles — all default true.
 *     // propagateAppName    = true
 *     // propagateBundleId   = true
 *     // propagateVersion    = true
 *     // propagateLocaleList = true
 *     // syncIos             = true
 * }
 * ```
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
     * Set explicitly to override auto-detection.
     */
    abstract val locales: ListProperty<String>

    // --- Module structure ----------------------------------------------------

    /** KMP shared module directory name (e.g. "shared", "composeApp"). REQUIRED. */
    abstract val sharedModule: Property<String>

    /** Android application module directory name (e.g. "androidApp", "app"). Defaults to "androidApp". */
    abstract val androidAppModule: Property<String>

    // --- File paths -----------------------------------------------------------

    /** Path (relative to root project) to the iOS Xcode project file. */
    abstract val iosProjectPath: Property<String>

    // --- Toggles (all default true) ------------------------------------------

    abstract val propagateAppName: Property<Boolean>
    abstract val propagateBundleId: Property<Boolean>
    abstract val propagateVersion: Property<Boolean>
    abstract val propagateLocaleList: Property<Boolean>

    /** Master switch for the iOS pbxproj rewrite task. If false, no iOS sync happens at all. */
    abstract val syncIos: Property<Boolean>

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
