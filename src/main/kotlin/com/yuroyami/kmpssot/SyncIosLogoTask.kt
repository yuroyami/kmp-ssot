package com.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Propagates [sourcePng] (ideally 1024x1024) to the iOS AppIcon.appiconset:
 *  - Writes `AppIcon-1024.png` (resized from source if needed)
 *  - Writes a single-image universal `Contents.json`
 *
 * Single-size universal AppIcon requires iOS deployment target 14+, which is
 * the modern default. Xcode handles down-scaling at build time.
 *
 * Idempotent — PNG and Contents.json are rewritten only when content differs.
 */
@DisableCachingByDefault(because = "Image processing is cheap relative to total iOS build time.")
abstract class SyncIosLogoTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Propagate the app logo PNG to the iOS AppIcon.appiconset (single 1024 universal)."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val sourcePng: RegularFileProperty
    @get:Internal abstract val appiconsetDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val src = sourcePng.asFile.get()
        if (!src.exists()) {
            logger.warn("[kmpSsot] appLogoPng not found at ${src.path} — skipping iOS logo.")
            return
        }

        val source = ImageIO.read(src) ?: run {
            logger.warn("[kmpSsot] Could not decode ${src.path} as an image — skipping iOS logo.")
            return
        }

        val outDir = appiconsetDir.asFile.get().apply { mkdirs() }
        val outPng = outDir.resolve("AppIcon-1024.png")

        val resized = if (source.width == 1024 && source.height == 1024) {
            source
        } else {
            BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB).also { img ->
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.drawImage(source, 0, 0, 1024, 1024, null)
                g.dispose()
            }
        }

        // Write image — compare bytes to avoid pointless writes that bust Xcode caches.
        val newBytes = java.io.ByteArrayOutputStream().apply {
            ImageIO.write(resized, "PNG", this)
        }.toByteArray()
        if (!outPng.exists() || !outPng.readBytes().contentEquals(newBytes)) {
            outPng.writeBytes(newBytes)
        }

        // Write Contents.json (single universal 1024 entry).
        val contentsJson = """
            |{
            |  "images" : [
            |    {
            |      "filename" : "AppIcon-1024.png",
            |      "idiom" : "universal",
            |      "platform" : "ios",
            |      "size" : "1024x1024"
            |    }
            |  ],
            |  "info" : {
            |    "author" : "kmp-ssot",
            |    "version" : 1
            |  }
            |}
            |""".trimMargin()
        val contentsFile = outDir.resolve("Contents.json")
        if (!contentsFile.exists() || contentsFile.readText() != contentsJson) {
            contentsFile.writeText(contentsJson)
        }

        logger.lifecycle("[kmpSsot] iOS AppIcon synced from ${src.name} (1024x1024 universal).")
    }
}
