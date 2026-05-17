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
 * Append-only for those: existing keys are never overwritten. When an existing
 * key holds a hardcoded literal instead of the expected `$(…)` reference, a
 * warning is logged — the pbxproj-level rewrite will do nothing for that field
 * until the user aligns the Info.plist with SSOT, so silent misbehaviour is
 * avoided.
 *
 * Also propagates DSL-driven boolean feature flags from `kmpSsot { ios { ... } }`:
 *
 *   ITSAppUsesNonExemptEncryption     ← ios.usesNonExemptEncryption
 *   CADisableMinimumFrameDurationOnPhone ← ios.proMotion120Hz
 *
 * For those, the DSL is the source of truth: insert if missing, overwrite if
 * the current plist value differs.
 *
 * Each string sanitizer is gated by the corresponding `propagate*` toggle.
 * Each boolean sanitizer is gated by whether its DSL property is set.
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

    // Not declared as task inputs — values are read at execution via `isPresent`.
    // @Optional only applies to input annotations and conflicts with @Internal under Gradle 9.
    @get:Internal abstract val usesNonExemptEncryption: Property<Boolean>
    @get:Internal abstract val proMotion120Hz: Property<Boolean>

    @TaskAction
    fun sanitize() {
        val file = infoPlistFile.asFile.get()
        if (!file.exists()) {
            logger.info("[kmpSsot] Info.plist not found at ${file.path} — skipping sanitization (generated plist?).")
            return
        }

        val stringEntries = buildList {
            if (propagateAppName.get()) {
                add(StringEntry("CFBundleDisplayName", "\$(PRODUCT_NAME)"))
                add(StringEntry("CFBundleName",        "\$(PRODUCT_NAME)"))
            }
            if (propagateVersion.get()) {
                add(StringEntry("CFBundleShortVersionString", "\$(MARKETING_VERSION)"))
                add(StringEntry("CFBundleVersion",            "\$(CURRENT_PROJECT_VERSION)"))
            }
        }
        val boolEntries = buildList {
            if (usesNonExemptEncryption.isPresent) {
                add(BoolEntry("ITSAppUsesNonExemptEncryption", usesNonExemptEncryption.get()))
            }
            if (proMotion120Hz.isPresent) {
                add(BoolEntry("CADisableMinimumFrameDurationOnPhone", proMotion120Hz.get()))
            }
        }
        if (stringEntries.isEmpty() && boolEntries.isEmpty()) return

        val original = file.readText()
        var updated = original
        val inserted = mutableListOf<String>()
        val overwritten = mutableListOf<String>()

        for (entry in stringEntries) {
            val existing = findStringValueFor(updated, entry.name)
            if (existing == null) {
                updated = insertStringBeforeRootDictClose(updated, entry)
                inserted += entry.name
            } else if (existing != entry.value) {
                logger.warn(
                    "[kmpSsot] Info.plist <$existing> has hardcoded <${entry.name}> — " +
                            "kmpSsot propagation will NOT reach the home screen / version metadata. " +
                            "Change it to <string>${entry.value}</string> to restore SSOT."
                )
            }
        }

        for (entry in boolEntries) {
            val existing = findBoolValueFor(updated, entry.name)
            when (existing) {
                null -> {
                    updated = insertBoolBeforeRootDictClose(updated, entry)
                    inserted += entry.name
                }
                entry.value -> { /* already correct */ }
                else -> {
                    updated = replaceBoolValue(updated, entry.name, entry.value)
                    overwritten += entry.name
                }
            }
        }

        if (updated != original) {
            file.writeText(updated)
            val parts = buildList {
                if (inserted.isNotEmpty()) add("inserted ${inserted.joinToString(", ")}")
                if (overwritten.isNotEmpty()) add("overwrote ${overwritten.joinToString(", ")}")
            }
            logger.lifecycle("[kmpSsot] Info.plist sanitized: ${parts.joinToString("; ")}.")
        } else {
            logger.info("[kmpSsot] Info.plist already sanitized.")
        }
    }

    private data class StringEntry(val name: String, val value: String)
    private data class BoolEntry(val name: String, val value: Boolean)

    /** Returns the `<string>…</string>` value immediately following `<key>name</key>`, or null. */
    private fun findStringValueFor(xml: String, keyName: String): String? {
        val keyPattern = Regex("""<key>${Regex.escape(keyName)}</key>\s*<string>([^<]*)</string>""")
        return keyPattern.find(xml)?.groupValues?.get(1)
    }

    /** Returns the boolean value following `<key>name</key>`, or null if absent / not a bool. */
    private fun findBoolValueFor(xml: String, keyName: String): Boolean? {
        val pattern = Regex("""<key>${Regex.escape(keyName)}</key>\s*<(true|false)/>""")
        return pattern.find(xml)?.groupValues?.get(1)?.let { it == "true" }
    }

    private fun insertStringBeforeRootDictClose(xml: String, entry: StringEntry): String =
        insertBeforeRootDictClose(xml, entry.name) { indent ->
            "$indent<key>${entry.name}</key>\n$indent<string>${entry.value}</string>\n"
        }

    private fun insertBoolBeforeRootDictClose(xml: String, entry: BoolEntry): String =
        insertBeforeRootDictClose(xml, entry.name) { indent ->
            val boolTag = if (entry.value) "<true/>" else "<false/>"
            "$indent<key>${entry.name}</key>\n$indent$boolTag\n"
        }

    /**
     * Insert a key/value block immediately before the final `</dict></plist>` pair.
     * Matches only the *outermost* dict close — nested dicts inside the plist
     * (e.g. UIApplicationSceneManifest) are left alone. Indentation is sniffed
     * from an existing `<key>` so insertions visually match the surrounding
     * formatting.
     */
    private fun insertBeforeRootDictClose(
        xml: String,
        keyNameForLog: String,
        render: (indent: String) -> String,
    ): String {
        val rootClose = Regex("""</dict>\s*</plist>\s*$""")
        val match = rootClose.find(xml) ?: run {
            logger.warn("[kmpSsot] Info.plist has no recognizable </dict></plist> tail — skipping insertion of $keyNameForLog.")
            return xml
        }
        val block = render(detectItemIndent(xml))
        return xml.substring(0, match.range.first) + block + xml.substring(match.range.first)
    }

    /** Replace the boolean value following `<key>name</key>` in-place, preserving surrounding whitespace. */
    private fun replaceBoolValue(xml: String, keyName: String, value: Boolean): String {
        val pattern = Regex("""(<key>${Regex.escape(keyName)}</key>\s*)<(?:true|false)/>""")
        val newBool = if (value) "<true/>" else "<false/>"
        return pattern.replace(xml, "$1$newBool")
    }

    /** Find the whitespace-only indentation of the first existing `<key>` (tabs/spaces, no newline). */
    private fun detectItemIndent(xml: String): String {
        val firstKey = Regex("""\n([ \t]*)<key>""").find(xml)
        return firstKey?.groupValues?.get(1) ?: "\t"
    }
}
