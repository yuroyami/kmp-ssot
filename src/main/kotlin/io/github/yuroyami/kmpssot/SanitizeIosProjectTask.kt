package io.github.yuroyami.kmpssot

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Guarantees the iOS `Info.plist` has the keys the SSOT rewrite relies on, each
 * pointing at its corresponding build variable:
 *
 *   CFBundleDisplayName       → $(PRODUCT_NAME)
 *   CFBundleName              → $(PRODUCT_NAME)
 *   CFBundleShortVersionString→ $(MARKETING_VERSION)
 *   CFBundleVersion           → $(CURRENT_PROJECT_VERSION)
 *
 * Append-only: existing keys are never overwritten. When an existing key holds a
 * hardcoded literal instead of the expected `$(…)` reference, a warning is logged
 * — the pbxproj-level rewrite will do nothing for that field until the user aligns
 * the Info.plist with SSOT, so silent misbehaviour is avoided.
 *
 * Each sanitizer is gated by the corresponding `propagate*` toggle — if a field
 * isn't being propagated, its plist key isn't touched.
 *
 * If the Info.plist file doesn't exist (e.g. `GENERATE_INFOPLIST_FILE = YES`),
 * the task exits quietly — the generated-plist path is already handled by the
 * `INFOPLIST_KEY_*` pbxproj rewrites.
 */
@DisableCachingByDefault(because = "Trivial text rewrite; caching adds overhead without payoff.")
abstract class SanitizeIosProjectTask : DefaultTask() {

    init {
        group = "kmp-ssot"
        description = "Insert missing SSOT-pointing keys into iOS Info.plist before syncIosConfig runs."
        outputs.upToDateWhen { false }
    }

    @get:Internal abstract val infoPlistFile: RegularFileProperty

    @get:Internal abstract val propagateAppName: Property<Boolean>
    @get:Internal abstract val propagateVersion: Property<Boolean>

    @TaskAction
    fun sanitize() {
        val file = infoPlistFile.asFile.get()
        if (!file.exists()) {
            logger.info("[kmpSsot] Info.plist not found at ${file.path} — skipping sanitization (generated plist?).")
            return
        }

        val wanted = buildList {
            if (propagateAppName.get()) {
                add(PlistKey("CFBundleDisplayName", "\$(PRODUCT_NAME)"))
                add(PlistKey("CFBundleName",        "\$(PRODUCT_NAME)"))
            }
            if (propagateVersion.get()) {
                add(PlistKey("CFBundleShortVersionString", "\$(MARKETING_VERSION)"))
                add(PlistKey("CFBundleVersion",            "\$(CURRENT_PROJECT_VERSION)"))
            }
        }
        if (wanted.isEmpty()) return

        val original = file.readText()
        var updated = original
        val inserted = mutableListOf<String>()

        for (key in wanted) {
            val existing = findStringValueFor(updated, key.name)
            if (existing == null) {
                updated = insertKeyBeforeRootDictClose(updated, key)
                inserted += key.name
            } else if (existing != key.expectedValue) {
                logger.warn(
                    "[kmpSsot] Info.plist <$existing> has hardcoded <${key.name}> — " +
                            "kmpSsot propagation will NOT reach the home screen / version metadata. " +
                            "Change it to <string>${key.expectedValue}</string> to restore SSOT."
                )
            }
        }

        if (updated != original) {
            file.writeText(updated)
            logger.lifecycle("[kmpSsot] Info.plist sanitized: inserted ${inserted.joinToString(", ")} pointing at build variables.")
        } else {
            logger.info("[kmpSsot] Info.plist already sanitized.")
        }
    }

    private data class PlistKey(val name: String, val expectedValue: String)

    /** Returns the `<string>…</string>` value immediately following `<key>name</key>`, or null. */
    private fun findStringValueFor(xml: String, keyName: String): String? {
        val keyPattern = Regex("""<key>${Regex.escape(keyName)}</key>\s*<string>([^<]*)</string>""")
        return keyPattern.find(xml)?.groupValues?.get(1)
    }

    /**
     * Insert a `<key>…</key>\n<tab><string>…</string>` block immediately before the final
     * `</dict></plist>` pair. We match only the *outermost* dict close — nested dicts inside
     * the plist (e.g. UIApplicationSceneManifest) are left alone. Indentation is sniffed from
     * an existing `<key>` inside the same file (falling back to a single tab), so insertions
     * visually match the surrounding formatting.
     */
    private fun insertKeyBeforeRootDictClose(xml: String, key: PlistKey): String {
        val rootClose = Regex("""</dict>\s*</plist>\s*$""")
        val match = rootClose.find(xml) ?: run {
            logger.warn("[kmpSsot] Info.plist has no recognizable </dict></plist> tail — skipping insertion of ${key.name}.")
            return xml
        }
        val indent = detectItemIndent(xml)
        // `</dict>` sits on its own line preceded by `\n`, so we only need a leading indent —
        // no extra newline, or we'd leave a blank line where we inserted.
        val block = "$indent<key>${key.name}</key>\n$indent<string>${key.expectedValue}</string>\n"
        return xml.substring(0, match.range.first) + block + xml.substring(match.range.first)
    }

    /** Find the whitespace-only indentation of the first existing `<key>` (tabs/spaces, no newline). */
    private fun detectItemIndent(xml: String): String {
        val firstKey = Regex("""\n([ \t]*)<key>""").find(xml)
        return firstKey?.groupValues?.get(1) ?: "\t"
    }
}
