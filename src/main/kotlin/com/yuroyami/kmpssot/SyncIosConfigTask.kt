package com.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Rewrites iOS configuration files in place to match the kmpSsot { } DSL.
 *
 * pbxproj keys (gated per `propagate*` toggle + value presence):
 *  - propagateVersion     → MARKETING_VERSION, CURRENT_PROJECT_VERSION
 *  - propagateAppName     → INFOPLIST_KEY_CFBundleDisplayName, INFOPLIST_KEY_CFBundleName
 *  - propagateBundleId    → PRODUCT_BUNDLE_IDENTIFIER (every occurrence)
 *  - propagateLocaleList  → knownRegions
 *
 * Shared module rename (gated on `propagateSharedModule`):
 *  - Podfile lines `pod 'X', :path => '../X'` rewritten if X != sharedModule
 *  - Every `import X` in `iosApp/**/*.swift` rewritten to `import sharedModule`
 *
 * All rewrites are idempotent — no file is touched if its content already
 * matches the desired state.
 */
@DisableCachingByDefault(because = "Trivial text rewrite; caching adds overhead without payoff.")
abstract class SyncIosConfigTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Sync iOS pbxproj + Podfile + Swift imports from the kmpSsot { } DSL."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val pbxprojFile: RegularFileProperty
    @get:Internal abstract val podfile: RegularFileProperty
    @get:Internal abstract val iosAppDir: DirectoryProperty

    @get:Internal abstract val versionName: Property<String>
    @get:Internal abstract val versionCode: Property<Int>
    @get:Internal abstract val appName: Property<String>
    @get:Internal abstract val bundleId: Property<String>
    @get:Internal abstract val locales: ListProperty<String>
    @get:Internal abstract val sharedModule: Property<String>

    @get:Internal abstract val propagateVersion: Property<Boolean>
    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val propagateBundleId: Property<Boolean>
    @get:Internal abstract val propagateLocaleList: Property<Boolean>
    @get:Internal abstract val propagateSharedModule: Property<Boolean>

    @TaskAction
    fun sync() {
        syncPbxproj()
        syncSharedModuleReferences()
    }

    // --- pbxproj rewrites ----------------------------------------------------

    private fun syncPbxproj() {
        val file = pbxprojFile.asFile.get()
        if (!file.exists()) {
            logger.warn("[kmpSsot] pbxproj not found at ${file.path} — skipping pbxproj sync.")
            return
        }

        val original = file.readText()
        var updated = original

        if (propagateVersion.get() && versionName.isPresent) {
            updated = updated.replace(
                Regex("""MARKETING_VERSION = [^;]+;"""),
                "MARKETING_VERSION = ${versionName.get()};"
            )
            updated = updated.replace(
                Regex("""CURRENT_PROJECT_VERSION = [^;]+;"""),
                "CURRENT_PROJECT_VERSION = ${versionCode.get()};"
            )
        }

        if (propagateAppName.get() && appName.isPresent) {
            val n = appName.get()
            updated = updated.replace(
                Regex("""INFOPLIST_KEY_CFBundleDisplayName = [^;]+;"""),
                "INFOPLIST_KEY_CFBundleDisplayName = $n;"
            )
            updated = updated.replace(
                Regex("""INFOPLIST_KEY_CFBundleName = [^;]+;"""),
                "INFOPLIST_KEY_CFBundleName = $n;"
            )
        }

        if (propagateBundleId.get() && bundleId.isPresent) {
            updated = updated.replace(
                Regex("""PRODUCT_BUNDLE_IDENTIFIER = [^;]+;"""),
                "PRODUCT_BUNDLE_IDENTIFIER = ${bundleId.get()};"
            )
        }

        if (propagateLocaleList.get()) {
            val requested = locales.get()
            if (requested.isNotEmpty()) {
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
                "[kmpSsot] iOS pbxproj synced: " +
                        "name=${appName.orNull ?: "[unchanged]"}, " +
                        "v=${versionName.orNull ?: "[unchanged]"} (${versionCode.orNull ?: "-"}), " +
                        "id=${bundleId.orNull ?: "[unchanged]"}, " +
                        "locales=${locales.orNull?.takeIf { it.isNotEmpty() } ?: "[unchanged]"}"
            )
        } else {
            logger.info("[kmpSsot] iOS pbxproj already in sync.")
        }
    }

    // --- Shared module rename SSOT ------------------------------------------

    private fun syncSharedModuleReferences() {
        if (!propagateSharedModule.get()) return
        if (!sharedModule.isPresent) return

        val newName = sharedModule.get()
        val oldName = detectOldSharedModuleName() ?: return

        if (oldName == newName) {
            logger.info("[kmpSsot] Shared module name already \"$newName\"; nothing to rewrite.")
            return
        }

        rewritePodfile(oldName, newName)
        rewriteSwiftImports(oldName, newName)
        logger.lifecycle("[kmpSsot] Shared module references migrated: \"$oldName\" → \"$newName\". Run `pod install` in iosApp/ to refresh the Pods workspace.")
    }

    /** Read the current Podfile to extract the existing `pod 'X', :path => '../X'` name. */
    private fun detectOldSharedModuleName(): String? {
        val file = podfile.asFile.orNull?.takeIf { it.exists() } ?: return null
        val match = Regex("""pod\s+['"]([^'"]+)['"],\s*:path\s*=>\s*['"]\.\.\/([^'"]+)['"]""")
            .find(file.readText()) ?: return null
        val podName = match.groupValues[1]
        val pathName = match.groupValues[2]
        // Only treat as a shared-module pod if pod name == path tail (the common case).
        return if (podName == pathName) podName else null
    }

    private fun rewritePodfile(oldName: String, newName: String) {
        val file = podfile.asFile.orNull?.takeIf { it.exists() } ?: return
        val original = file.readText()
        val updated = original.replace(
            Regex("""pod\s+['"]${Regex.escape(oldName)}['"],\s*:path\s*=>\s*['"]\.\.\/${Regex.escape(oldName)}['"]"""),
            "pod '$newName', :path => '../$newName'"
        )
        if (updated != original) file.writeText(updated)
    }

    private fun rewriteSwiftImports(oldName: String, newName: String) {
        val dir = iosAppDir.asFile.orNull?.takeIf { it.isDirectory } ?: return
        val pattern = Regex("""(^|\n)\s*import\s+${Regex.escape(oldName)}\b""")
        var rewritten = 0
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .forEach { swift ->
                val original = swift.readText()
                val updated = pattern.replace(original) { m ->
                    m.value.replaceFirst("import $oldName", "import $newName")
                }
                if (updated != original) {
                    swift.writeText(updated)
                    rewritten++
                }
            }
        if (rewritten > 0) {
            logger.lifecycle("[kmpSsot] Rewrote `import $oldName` → `import $newName` in $rewritten Swift file(s).")
        }
    }
}
