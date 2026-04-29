package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Composites the FG/BG layer PNGs into the iOS AppIcon.appiconset:
 *  - Draws BG, then FG on top (FG alpha bleeds through to BG).
 *  - Flattens to an opaque RGB 1024×1024 — App Store marketing icons MUST NOT
 *    have an alpha channel, so any FG transparency is baked against BG.
 *  - Writes `AppIcon-1024.png` and a single-image universal `Contents.json`.
 *
 * Single-size universal AppIcon requires iOS deployment target 14+, which is
 * the modern default. Xcode handles down-scaling at build time.
 *
 * Idempotent — outputs are rewritten only when bytes/text differ.
 */
@DisableCachingByDefault(because = "Image processing is cheap relative to total iOS build time.")
abstract class SyncIosLogoTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Composite FG+BG app-logo PNGs into the iOS AppIcon.appiconset (single 1024 universal)."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val foregroundPng: RegularFileProperty
    @get:Internal abstract val backgroundPng: RegularFileProperty
    @get:Internal abstract val appiconsetDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val fgFile = foregroundPng.asFile.get()
        val bgFile = backgroundPng.asFile.get()
        if (!fgFile.exists()) {
            logger.warn("[kmpSsot] appLogoPngForeground not found at ${fgFile.path} — skipping iOS logo.")
            return
        }
        if (!bgFile.exists()) {
            logger.warn("[kmpSsot] appLogoPngBackground not found at ${bgFile.path} — skipping iOS logo.")
            return
        }

        val fg = ImageIO.read(fgFile) ?: run {
            logger.warn("[kmpSsot] Could not decode ${fgFile.path} as an image — skipping iOS logo.")
            return
        }
        val bg = ImageIO.read(bgFile) ?: run {
            logger.warn("[kmpSsot] Could not decode ${bgFile.path} as an image — skipping iOS logo.")
            return
        }

        // Composite at 1024×1024 directly. Output type INT_RGB so any residual
        // alpha is composed against opaque white (App Store rejects alpha icons).
        val composite = BufferedImage(IOS_SIZE, IOS_SIZE, BufferedImage.TYPE_INT_RGB)
        composite.createGraphics().withQuality {
            // Fill white as the safety floor in case BG itself isn't fully opaque.
            color = java.awt.Color.WHITE
            fillRect(0, 0, IOS_SIZE, IOS_SIZE)
            drawImage(bg, 0, 0, IOS_SIZE, IOS_SIZE, null)
            drawImage(fg, 0, 0, IOS_SIZE, IOS_SIZE, null)
        }

        val outDir = appiconsetDir.asFile.get().apply { mkdirs() }
        val outPng = outDir.resolve("AppIcon-1024.png")
        val newBytes = ByteArrayOutputStream().apply { ImageIO.write(composite, "PNG", this) }.toByteArray()
        if (!outPng.exists() || !outPng.readBytes().contentEquals(newBytes)) {
            outPng.writeBytes(newBytes)
        }

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

        logger.lifecycle("[kmpSsot] iOS AppIcon synced from ${fgFile.name} + ${bgFile.name} (1024×1024 opaque).")
    }

    private inline fun Graphics2D.withQuality(block: Graphics2D.() -> Unit) {
        try {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            composite = AlphaComposite.SrcOver
            block()
        } finally {
            dispose()
        }
    }

    companion object {
        private const val IOS_SIZE = 1024
    }
}
