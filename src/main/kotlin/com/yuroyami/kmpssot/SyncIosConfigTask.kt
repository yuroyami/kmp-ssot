package com.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Rewrites the iOS Xcode project.pbxproj in place so that:
 *  - MARKETING_VERSION                  == versionName
 *  - CURRENT_PROJECT_VERSION            == versionCode
 *  - INFOPLIST_KEY_CFBundleDisplayName  == appName
 *  - PRODUCT_BUNDLE_IDENTIFIER          == bundleId
 *
 * Idempotent: if the file already matches, no write occurs (timestamp preserved).
 * Writes only the keys the pbxproj already contains — never adds new entries.
 */
@DisableCachingByDefault(because = "Trivial file rewrite; caching adds overhead without payoff.")
abstract class SyncIosConfigTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Sync iOS project.pbxproj from kmpSsot { } config."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val versionCode: Property<Int>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val bundleId: Property<String>

    @TaskAction
    fun sync() {
        val file = pbxprojFile.asFile.get()
        if (!file.exists()) {
            logger.warn("[kmpSsot] pbxproj not found at ${file.path} — skipping iOS sync.")
            return
        }

        val original = file.readText()
        var updated = original

        updated = updated.replace(
            Regex("""MARKETING_VERSION = [^;]+;"""),
            "MARKETING_VERSION = ${versionName.get()};"
        )
        updated = updated.replace(
            Regex("""CURRENT_PROJECT_VERSION = [^;]+;"""),
            "CURRENT_PROJECT_VERSION = ${versionCode.get()};"
        )
        updated = updated.replace(
            Regex("""INFOPLIST_KEY_CFBundleDisplayName = [^;]+;"""),
            "INFOPLIST_KEY_CFBundleDisplayName = ${appName.get()};"
        )
        updated = updated.replace(
            Regex("""PRODUCT_BUNDLE_IDENTIFIER = [^;]+;"""),
            "PRODUCT_BUNDLE_IDENTIFIER = ${bundleId.get()};"
        )

        if (updated != original) {
            file.writeText(updated)
            logger.lifecycle(
                "[kmpSsot] iOS pbxproj synced: " +
                        "${appName.get()} v${versionName.get()} (${versionCode.get()}), id=${bundleId.get()}"
            )
        } else {
            logger.info("[kmpSsot] iOS pbxproj already in sync.")
        }
    }
}
