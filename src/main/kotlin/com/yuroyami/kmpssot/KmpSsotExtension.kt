package com.yuroyami.kmpssot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * DSL for the kmp-ssot plugin. Applied to the **root project** only.
 *
 * Scope: genuinely cross-platform identity (app name, version, bundle ID,
 * locales) plus shared toolchain bits (Java version). Android-only toolchain
 * (compileSdk, minSdk, targetSdk, ndkVersion) is intentionally **not** here —
 * those belong in each Android module where they are actually relevant, and
 * keeping them out sidesteps KGP 2.3's android-target validator entirely.
 *
 * Usage in the root build.gradle.kts:
 * ```
 * kmpSsot {
 *     appName      = "Jetzy"
 *     versionName  = "0.3.0"
 *     bundleIdBase = "com.yuroyami.jetzy"
 *
 *     iosBundleSuffix = ".ios"
 *     javaVersion     = 21
 *
 *     locales = listOf("en", "ar", "fr")
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

    abstract val iosBundleSuffix: Property<String>
    abstract val androidApplicationIdSuffix: Property<String>

    // --- Shared toolchain (cross-platform) -----------------------------------

    abstract val javaVersion: Property<Int>

    // --- Localization ---------------------------------------------------------

    /** Locales supported by the app (BCP-47-ish tags, e.g. "en", "fr", "pt-BR"). */
    abstract val locales: ListProperty<String>

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
