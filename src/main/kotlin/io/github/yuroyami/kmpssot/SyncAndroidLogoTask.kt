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
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Propagates the FG/BG layer PNGs to a complete Android launcher-icon resource
 * tree. Both source PNGs are treated as the full 108dp adaptive-icon canvas;
 * the foreground is expected to live inside the inner ~66dp safe zone.
 *
 * Outputs (per density bucket m/h/xh/xxh/xxxh):
 *  - `mipmap-{density}/ic_launcher_foreground.png` — adaptive icon foreground
 *  - `mipmap-{density}/ic_launcher_background.png` — adaptive icon background
 *  - `mipmap-{density}/ic_launcher.png` — legacy fallback (square mask)
 *  - `mipmap-{density}/ic_launcher_round.png` — legacy fallback (circle mask)
 *
 * Plus the API-26+ adaptive-icon wrapper:
 *  - `mipmap-anydpi-v26/ic_launcher.xml`
 *  - `mipmap-anydpi-v26/ic_launcher_round.xml`
 *
 * Idempotent — bytes are compared before writing, so unchanged outputs don't
 * thrash AGP's resource caches.
 */
@DisableCachingByDefault(because = "Image processing is cheap relative to total Android build time.")
abstract class SyncAndroidLogoTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Propagate the FG+BG app-logo PNGs to the Android launcher-icon resource tree."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val foregroundPng: RegularFileProperty
    @get:Internal abstract val backgroundPng: RegularFileProperty
    @get:Internal abstract val androidResDir: DirectoryProperty

    @TaskAction
    fun sync() {
        val fgFile = foregroundPng.asFile.get()
        val bgFile = backgroundPng.asFile.get()
        if (!fgFile.exists()) {
            logger.warn("[kmpSsot] appLogoPngForeground not found at ${fgFile.path} — skipping Android logo.")
            return
        }
        if (!bgFile.exists()) {
            logger.warn("[kmpSsot] appLogoPngBackground not found at ${bgFile.path} — skipping Android logo.")
            return
        }

        val fg = ImageIO.read(fgFile) ?: run {
            logger.warn("[kmpSsot] Could not decode ${fgFile.path} as an image — skipping Android logo.")
            return
        }
        val bg = ImageIO.read(bgFile) ?: run {
            logger.warn("[kmpSsot] Could not decode ${bgFile.path} as an image — skipping Android logo.")
            return
        }

        if (fg.width != fg.height) {
            logger.warn("[kmpSsot] appLogoPngForeground is not square (${fg.width}×${fg.height}) — output will be stretched.")
        }
        if (bg.width != bg.height) {
            logger.warn("[kmpSsot] appLogoPngBackground is not square (${bg.width}×${bg.height}) — output will be stretched.")
        }
        if (!fg.colorModel.hasAlpha()) {
            logger.warn("[kmpSsot] appLogoPngForeground has no alpha channel — it will fully cover the background. Use a PNG with transparency for proper layering.")
        }
        // xxxhdpi adaptive-icon foreground is 432px; warn below that.
        if (fg.width < 432) {
            logger.warn("[kmpSsot] appLogoPngForeground is ${fg.width}px wide — recommend ≥432px (xxxhdpi adaptive-icon size) to avoid upscaling artefacts.")
        }

        val resDir = androidResDir.asFile.get()

        DENSITIES.forEach { (qualifier, scale) ->
            val mipmap = resDir.resolve("mipmap-$qualifier")

            val adaptiveSize = (108 * scale).toInt()
            val legacySize = (48 * scale).toInt()

            // Adaptive-icon FG/BG layers — full 108dp canvas, scaled per density.
            writePngIfChanged(mipmap.resolve("ic_launcher_foreground.png"), resize(fg, adaptiveSize, adaptiveSize))
            writePngIfChanged(mipmap.resolve("ic_launcher_background.png"), resize(bg, adaptiveSize, adaptiveSize))

            // Legacy fallback — composite FG over BG, crop to the safe zone (the
            // visible region on adaptive-icon devices), resize to legacy size.
            // Square variant is the safe-zone composite directly; round masks it
            // with a circular clip.
            val legacySquare = legacyComposite(fg, bg, legacySize)
            writePngIfChanged(mipmap.resolve("ic_launcher.png"), legacySquare)
            writePngIfChanged(mipmap.resolve("ic_launcher_round.png"), applyCircleMask(legacySquare))
        }

        // Adaptive-icon wrapper XML — both ic_launcher and ic_launcher_round
        // point at the same FG/BG layers; the launcher applies its own mask.
        val adaptiveDir = resDir.resolve("mipmap-anydpi-v26")
        val adaptiveXml = buildAdaptiveIconWrapper()
        writeTextIfChanged(adaptiveDir.resolve("ic_launcher.xml"), adaptiveXml)
        writeTextIfChanged(adaptiveDir.resolve("ic_launcher_round.xml"), adaptiveXml)

        logger.lifecycle("[kmpSsot] Android logo synced from ${fgFile.name} + ${bgFile.name}.")
    }

    private fun buildAdaptiveIconWrapper(): String = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
        |    <background android:drawable="@mipmap/ic_launcher_background"/>
        |    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
        |</adaptive-icon>
        |""".trimMargin()

    /**
     * Composite FG over BG at full canvas, crop to the inner safe zone (the
     * region that actually shows on adaptive-icon launchers), then resize to
     * the requested legacy size.
     */
    private fun legacyComposite(fg: BufferedImage, bg: BufferedImage, size: Int): BufferedImage {
        val canvas = maxOf(fg.width, bg.width).coerceAtLeast(size)
        val full = BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB)
        full.createGraphics().withQuality {
            drawImage(bg, 0, 0, canvas, canvas, null)
            drawImage(fg, 0, 0, canvas, canvas, null)
        }

        // Safe zone is the inner 66/108 of the canvas, centred.
        val safe = (canvas * SAFE_ZONE_RATIO).toInt()
        val offset = (canvas - safe) / 2
        val cropped = full.getSubimage(offset, offset, safe, safe)

        return resize(cropped, size, size)
    }

    private fun applyCircleMask(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().withQuality {
            clip = Ellipse2D.Float(0f, 0f, src.width.toFloat(), src.height.toFloat())
            drawImage(src, 0, 0, null)
        }
        return out
    }

    private fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
        if (src.width == w && src.height == h) return src
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().withQuality {
            drawImage(src, 0, 0, w, h, null)
        }
        return out
    }

    private fun writePngIfChanged(target: File, image: BufferedImage) {
        val bytes = ByteArrayOutputStream().apply { ImageIO.write(image, "PNG", this) }.toByteArray()
        target.parentFile.mkdirs()
        if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
            target.writeBytes(bytes)
        }
    }

    private fun writeTextIfChanged(target: File, content: String) {
        target.parentFile.mkdirs()
        if (!target.exists() || target.readText() != content) {
            target.writeText(content)
        }
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
        // Adaptive-icon safe zone: inner 66dp of the 108dp canvas.
        private const val SAFE_ZONE_RATIO = 66.0 / 108.0

        // Density qualifier → scale factor (dp → px). Adaptive icon canvas is
        // 108dp (108×scale px); legacy launcher is 48dp (48×scale px).
        private val DENSITIES = listOf(
            "mdpi" to 1.0,
            "hdpi" to 1.5,
            "xhdpi" to 2.0,
            "xxhdpi" to 3.0,
            "xxxhdpi" to 4.0,
        )
    }
}
