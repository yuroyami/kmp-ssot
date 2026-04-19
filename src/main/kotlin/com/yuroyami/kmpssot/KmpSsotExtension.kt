package com.yuroyami.kmpssot

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * DSL for the kmp-ssot plugin. Applied to the **root project** only.
 *
 * Usage in the root build.gradle.kts:
 * ```
 * kmpSsot {
 *     appName      = "Jetzy"
 *     versionName  = "0.3.0"
 *     bundleIdBase = "com.yuroyami.jetzy"
 *
 *     iosBundleSuffix = ".ios"
 *     compileSdk      = 36
 *     minSdk          = 26
 *     javaVersion     = 21
 *
 *     locales = listOf("en", "ar", "fr")   // propagated to Android resourceConfigurations + iOS knownRegions
 *
 *     // All toggles default to true. Flip one to false to opt out of that
 *     // piece of SSOT propagation while leaving the rest intact.
 *     // propagateAppName    = true
 *     // propagateBundleId   = true
 *     // propagateVersion    = true
 *     // propagateLocaleList = true
 *     // syncIos             = true
 * }
 * ```
 */
abstract class KmpSsotExtension {

    // --- Core identity --------------------------------------------------------

    abstract val appName: Property<String>
    abstract val versionName: Property<String>
    abstract val bundleIdBase: Property<String>

    abstract val iosBundleSuffix: Property<String>
    abstract val androidApplicationIdSuffix: Property<String>

    // --- Build toolchain ------------------------------------------------------

    abstract val compileSdk: Property<Int>
    abstract val minSdk: Property<Int>
    abstract val targetSdk: Property<Int>
    abstract val javaVersion: Property<Int>
    abstract val ndkVersion: Property<String>

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
