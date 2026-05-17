package io.github.yuroyami.kmpssot

import org.gradle.api.provider.Property

/**
 * iOS-specific options. Nested under `kmpSsot { ios { ... } }`.
 *
 * Every flag is optional (`Property<Boolean>` with no convention). When a flag
 * is set, the value is propagated into `Info.plist` by `sanitizeIosProject`:
 * inserted if the key is missing, overwritten if an existing value differs.
 * When unset, the key is left untouched — drop the plugin onto an existing app
 * without any iOS plist surprises.
 *
 * These differ from the SSOT-pointing string keys (`CFBundleDisplayName` etc.)
 * which are append-only and warn on divergence: those are references to Xcode
 * build settings, so the plist value is fixed. Here the DSL itself is the
 * source of truth for the value, so the DSL wins over hand-edited plist state.
 */
abstract class KmpSsotIosExtension {

    /**
     * Controls `ITSAppUsesNonExemptEncryption` in `Info.plist`.
     *
     * Setting this to `false` is the standard way to silence the App Store
     * Connect "Missing Compliance" prompt for apps that use only Apple's
     * built-in / exempt encryption (HTTPS, system frameworks). Set to `true`
     * if your app uses non-exempt encryption and you've filed the required
     * documentation.
     *
     * Unset (default): the key is not touched — useful if you're managing it
     * elsewhere or genuinely want App Store Connect to prompt every upload.
     */
    abstract val usesNonExemptEncryption: Property<Boolean>

    /**
     * Controls `CADisableMinimumFrameDurationOnPhone` in `Info.plist`.
     *
     * Set to `true` to opt the app into ProMotion's high refresh rate (up to
     * 120 Hz) on supported iPhones. Without this, iOS caps the app at 60 Hz
     * regardless of device capability. Compose Multiplatform UIs are usually
     * fine on ProMotion, but check your animation budget before flipping it.
     *
     * Unset (default): the key is not touched (iOS default behavior — 60 Hz).
     */
    abstract val proMotion120Hz: Property<Boolean>
}
