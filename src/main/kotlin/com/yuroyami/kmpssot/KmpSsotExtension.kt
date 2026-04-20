package com.yuroyami.kmpssot

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
 * App logo: set both [appLogoXml] and [appLogoPng] to enable logo propagation,
 * or leave both unset. Setting only one fails configuration.
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
     * Source XML vector drawable for the app logo. Propagated to Android only:
     *  - `${androidAppModule}/src/main/res/drawable/ic_launcher.xml`
     *  - `${androidAppModule}/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`
     *    (adaptive icon wrappers referencing the drawable + background color)
     *  - `${androidAppModule}/src/main/res/values/ic_launcher_background.xml`
     *    (color resource for the adaptive icon background)
     *
     * If you want the same vector available to Compose via `vectorResource(...)`,
     * place a copy in your `composeResources/drawable/` yourself — the plugin
     * deliberately does not propagate into Compose resources.
     */
    abstract val appLogoXml: RegularFileProperty

    /**
     * Source PNG (ideally 1024x1024) for the iOS app icon. Propagated to
     * `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
     * with a single-image universal `Contents.json`. Source is automatically
     * resized to 1024x1024 if it isn't already.
     */
    abstract val appLogoPng: RegularFileProperty

    /**
     * Background color for the Android adaptive icon wrapper. Hex string.
     * Defaults to `"#FFFFFF"`.
     */
    abstract val appLogoBackgroundColor: Property<String>

    // --- File paths -----------------------------------------------------------

    /** Path (relative to root project) to the iOS Xcode project file. */
    abstract val iosProjectPath: Property<String>

    /** Path (relative to root project) to the iOS Podfile. */
    abstract val iosPodfilePath: Property<String>

    // --- Toggles (all default true) ------------------------------------------

    abstract val propagateAppName: Property<Boolean>
    abstract val propagateBundleId: Property<Boolean>
    abstract val propagateVersion: Property<Boolean>
    abstract val propagateLocaleList: Property<Boolean>
    abstract val propagateLogo: Property<Boolean>
    abstract val propagateSharedModule: Property<Boolean>

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
