package com.yuroyami.kmpssot

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Single source of truth for KMP app config.
 *
 * When applied to a module this plugin:
 *  1. Registers the [KmpSsotExtension] as `kmpSsot { }`.
 *  2. Registers the `syncIosConfig` task that rewrites
 *     `iosApp/iosApp.xcodeproj/project.pbxproj` from the DSL.
 *  3. If the Kotlin Multiplatform plugin is applied, hooks
 *     `syncIosConfig` as a dependency of iOS framework link tasks.
 *  4. If the Android Application plugin is applied, auto-configures
 *     applicationId, versionName, versionCode, minSdk, compileSdk,
 *     Java compile options, NDK version, and the `appName` manifest placeholder.
 *
 * Consumer responsibilities (outside the plugin's reach):
 *  - Change `android:label="..."` to `android:label="${'$'}{appName}"` in AndroidManifest.xml.
 *  - Ensure iOS Info.plist uses `$(MARKETING_VERSION)` for CFBundleShortVersionString
 *    (so the Xcode build setting wins, rather than a stale literal).
 */
class KmpSsotPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val ext = target.extensions.create<KmpSsotExtension>("kmpSsot").apply {
            iosBundleSuffix.convention("")
            androidApplicationIdSuffix.convention("")
            compileSdk.convention(36)
            minSdk.convention(26)
            javaVersion.convention(21)
            iosProjectPath.convention("iosApp/iosApp.xcodeproj/project.pbxproj")
        }

        registerSyncIosTask(target, ext)
        wireAndroidWhenApplied(target, ext)
    }

    private fun registerSyncIosTask(target: Project, ext: KmpSsotExtension) {
        val syncIos = target.tasks.register<SyncIosConfigTask>("syncIosConfig") {
            // iosApp/ lives at the KMP project root, not in the module applying this plugin.
            pbxprojFile.set(target.rootProject.layout.projectDirectory.file(ext.iosProjectPath))
            versionName.set(ext.versionName)
            versionCode.set(ext.versionCode)
            appName.set(ext.appName)
            bundleId.set(ext.iosBundleId)
        }

        // Hook into KMP iOS framework tasks so every iOS build keeps pbxproj in sync.
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            target.tasks.matching {
                it.name.startsWith("linkPodReleaseFrameworkIos") ||
                        it.name.startsWith("linkPodDebugFrameworkIos") ||
                        it.name == "embedAndSignAppleFrameworkForXcode"
            }.configureEach { dependsOn(syncIos) }
        }
    }

    private fun wireAndroidWhenApplied(target: Project, ext: KmpSsotExtension) {
        target.plugins.withId("com.android.application") {
            // finalizeDsl runs after the consumer's android { } block but before AGP
            // locks the DSL — the correct window for setting compileSdk, versionCode, etc.
            val components = target.extensions
                .getByType(ApplicationAndroidComponentsExtension::class.java)

            components.finalizeDsl { android ->
                android.compileSdk = ext.compileSdk.get()
                ext.ndkVersion.orNull?.let { android.ndkVersion = it }

                android.defaultConfig.applicationId = ext.androidApplicationId.get()
                android.defaultConfig.versionCode = ext.versionCode.get()
                android.defaultConfig.versionName = ext.versionName.get()
                android.defaultConfig.minSdk = ext.minSdk.get()
                android.defaultConfig.targetSdk = ext.targetSdk.orNull ?: ext.compileSdk.get()
                android.defaultConfig.manifestPlaceholders["appName"] = ext.appName.get()

                val jv = JavaVersion.toVersion(ext.javaVersion.get())
                android.compileOptions.sourceCompatibility = jv
                android.compileOptions.targetCompatibility = jv
            }
        }
    }
}
