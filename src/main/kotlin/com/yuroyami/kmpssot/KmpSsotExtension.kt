package com.yuroyami.kmpssot

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * DSL entry-point for the kmp-ssot plugin.
 *
 * Usage in a consumer module's build.gradle.kts:
 * ```
 * kmpSsot {
 *     appName      = "Jetzy"
 *     versionName  = "0.3.0"
 *     bundleIdBase = "com.yuroyami.jetzy"
 *     iosBundleSuffix = ".ios"   // optional
 *     compileSdk   = 36          // optional, defaults shown
 *     minSdk       = 26
 *     javaVersion  = 21
 *     iosProjectPath = "iosApp/iosApp.xcodeproj/project.pbxproj"  // optional
 * }
 * ```
 */
abstract class KmpSsotExtension {

    abstract val appName: Property<String>
    abstract val versionName: Property<String>
    abstract val bundleIdBase: Property<String>

    abstract val iosBundleSuffix: Property<String>
    abstract val androidApplicationIdSuffix: Property<String>

    abstract val compileSdk: Property<Int>
    abstract val minSdk: Property<Int>
    abstract val targetSdk: Property<Int>
    abstract val javaVersion: Property<Int>
    abstract val ndkVersion: Property<String>

    abstract val iosProjectPath: Property<String>

    /** versionCode derived from versionName via the syncplay-mobile formula: "1" + zero-padded dot segments. */
    val versionCode: Provider<Int>
        get() = versionName.map { vn ->
            ("1" + vn.split(".").joinToString("") { it.padStart(3, '0') }).toInt()
        }

    val androidApplicationId: Provider<String>
        get() = bundleIdBase.zip(androidApplicationIdSuffix.orElse("")) { base, suffix -> base + suffix }

    val iosBundleId: Provider<String>
        get() = bundleIdBase.zip(iosBundleSuffix.orElse("")) { base, suffix -> base + suffix }
}
