package com.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Rewrites the iOS Xcode project.pbxproj in place so that its build settings
 * match the kmpSsot { } DSL.
 *
 * Each rewrite is gated by its own `propagate*` toggle. Keys touched:
 *  - propagateVersion     → MARKETING_VERSION, CURRENT_PROJECT_VERSION
 *  - propagateAppName     → INFOPLIST_KEY_CFBundleDisplayName
 *  - propagateBundleId    → PRODUCT_BUNDLE_IDENTIFIER
 *  - propagateLocaleList  → knownRegions (...)
 *
 * Idempotent: no write happens if the file is already in sync.
 */
@DisableCachingByDefault(because = "Trivial text rewrite; caching adds overhead without payoff.")
abstract class SyncIosConfigTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Sync iOS project.pbxproj from the kmpSsot { } DSL."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val pbxprojFile: RegularFileProperty

    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val versionCode: Property<Int>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val bundleId: Property<String>
    @get:Internal abstract val locales: ListProperty<String>

    @get:Internal abstract val propagateVersion: Property<Boolean>
    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val propagateBundleId: Property<Boolean>
    @get:Internal abstract val propagateLocaleList: Property<Boolean>

    @TaskAction
    fun sync() {
        val file = pbxprojFile.asFile.get()
        if (!file.exists()) {
            logger.warn("[kmpSsot] pbxproj not found at ${file.path} — skipping iOS sync.")
            return
        }

        val original = file.readText()
        var updated = original

        if (propagateVersion.get()) {
            updated = updated.replace(
                Regex("""MARKETING_VERSION = [^;]+;"""),
                "MARKETING_VERSION = ${versionName.get()};"
            )
            updated = updated.replace(
                Regex("""CURRENT_PROJECT_VERSION = [^;]+;"""),
                "CURRENT_PROJECT_VERSION = ${versionCode.get()};"
            )
        }

        if (propagateAppName.get()) {
            updated = updated.replace(
                Regex("""INFOPLIST_KEY_CFBundleDisplayName = [^;]+;"""),
                "INFOPLIST_KEY_CFBundleDisplayName = ${appName.get()};"
            )
        }

        if (propagateBundleId.get()) {
            updated = updated.replace(
                Regex("""PRODUCT_BUNDLE_IDENTIFIER = [^;]+;"""),
                "PRODUCT_BUNDLE_IDENTIFIER = ${bundleId.get()};"
            )
        }

        if (propagateLocaleList.get()) {
            val requested = locales.get()
            if (requested.isNotEmpty()) {
                // knownRegions is canonically the supported locales + Base.
                // Keep Base, don't duplicate if the user already listed it.
                val regions = buildList {
                    add("Base")
                    requested.filter { it != "Base" }.forEach { add(it) }
                }
                val replacement = regions.joinToString(
                    separator = ",\n\t\t\t\t",
                    prefix = "knownRegions = (\n\t\t\t\t",
                    postfix = ",\n\t\t\t);"
                )
                updated = updated.replace(
                    Regex("""knownRegions = \(\s*[^)]*\);""", RegexOption.DOT_MATCHES_ALL),
                    replacement
                )
            }
        }

        if (updated != original) {
            file.writeText(updated)
            logger.lifecycle(
                "[kmpSsot] iOS pbxproj synced: ${appName.orNull ?: "?"} " +
                        "v${versionName.orNull ?: "?"} (${versionCode.orNull ?: "?"}), " +
                        "id=${bundleId.orNull ?: "?"}, " +
                        "locales=${locales.orNull?.takeIf { it.isNotEmpty() } ?: "[unchanged]"}"
            )
        } else {
            logger.info("[kmpSsot] iOS pbxproj already in sync.")
        }
    }
}
