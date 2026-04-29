package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * One-shot migration helper: removes app-logo files that older plugin versions
 * generated under the Android res tree, but the current FG+BG pipeline no
 * longer produces. These would otherwise sit as orphan resources after upgrade.
 *
 * Files removed (only when present):
 *  - `${androidResDir}/drawable/ic_launcher.xml` — the old vector copy
 *  - `${androidResDir}/values/ic_launcher_background.xml` — the old colour resource
 *
 * Both files were 100% plugin-owned in older versions, so deletion is safe.
 * Empty parent directories are left alone — Gradle/AGP will recreate them as
 * needed and removing them risks surprising users with their own resources.
 */
@DisableCachingByDefault(because = "Trivial conditional file deletion.")
abstract class CleanupLegacyAppLogoArtifactsTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Remove app-logo artefacts left behind by pre-FG/BG plugin versions."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val androidResDir: DirectoryProperty

    @TaskAction
    fun cleanup() {
        val resDir = androidResDir.asFile.get()
        listOf(
            "drawable/ic_launcher.xml",
            "values/ic_launcher_background.xml",
        ).forEach { rel ->
            val file = resDir.resolve(rel)
            if (file.exists()) {
                if (file.delete()) {
                    logger.lifecycle("[kmpSsot] Removed legacy logo artefact: $rel")
                } else {
                    logger.warn("[kmpSsot] Failed to remove legacy logo artefact: ${file.path}")
                }
            }
        }
    }
}
