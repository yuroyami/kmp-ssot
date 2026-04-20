package com.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Propagates [sourceXml] (a vector drawable) to:
 *  - `${androidResDir}/drawable/ic_launcher.xml`
 *  - `${androidResDir}/mipmap-anydpi-v26/ic_launcher.xml` (adaptive icon wrapper)
 *  - `${androidResDir}/values/ic_launcher_background.xml` (color resource)
 *  - `${composeResourcesDir}/drawable/ic_launcher.xml` (for Compose vectorResource)
 *
 * Idempotent — files are only rewritten if their content actually changes.
 */
@DisableCachingByDefault(because = "Trivial file copy + small generated XML; caching adds overhead.")
abstract class SyncAndroidLogoTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Propagate the app logo XML to Android resources and Compose resources."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val sourceXml: RegularFileProperty
    @get:Internal abstract val androidResDir: DirectoryProperty
    @get:Internal abstract val composeResourcesDir: DirectoryProperty
    @get:Internal abstract val backgroundColor: Property<String>

    @TaskAction
    fun sync() {
        val src = sourceXml.asFile.get()
        if (!src.exists()) {
            logger.warn("[kmpSsot] appLogoXml not found at ${src.path} — skipping Android logo.")
            return
        }
        val sourceContent = src.readText()

        // 1. drawable/ic_launcher.xml — direct copy of user XML
        val resDir = androidResDir.asFile.get()
        writeIfChanged(resDir.resolve("drawable/ic_launcher.xml"), sourceContent)

        // 2. mipmap-anydpi-v26/ic_launcher.xml — adaptive icon wrapper
        writeIfChanged(
            resDir.resolve("mipmap-anydpi-v26/ic_launcher.xml"),
            buildAdaptiveIconWrapper()
        )
        writeIfChanged(
            resDir.resolve("mipmap-anydpi-v26/ic_launcher_round.xml"),
            buildAdaptiveIconWrapper()
        )

        // 3. values/ic_launcher_background.xml — background color resource
        writeIfChanged(
            resDir.resolve("values/ic_launcher_background.xml"),
            buildBackgroundColorXml(backgroundColor.get())
        )

        // 4. composeResources/drawable/ic_launcher.xml — for Compose vectorResource
        val composeDir = composeResourcesDir.asFile.get()
        writeIfChanged(composeDir.resolve("drawable/ic_launcher.xml"), sourceContent)

        logger.lifecycle("[kmpSsot] Android logo synced from ${src.name}.")
    }

    private fun buildAdaptiveIconWrapper(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |    <background android:drawable="@color/ic_launcher_background"/>
        |    <foreground android:drawable="@drawable/ic_launcher"/>
        |</adaptive-icon>
        |""".trimMargin()

    private fun buildBackgroundColorXml(color: String): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<resources>
        |    <color name="ic_launcher_background">$color</color>
        |</resources>
        |""".trimMargin()

    private fun writeIfChanged(target: java.io.File, content: String) {
        target.parentFile.mkdirs()
        if (!target.exists() || target.readText() != content) {
            target.writeText(content)
        }
    }
}
