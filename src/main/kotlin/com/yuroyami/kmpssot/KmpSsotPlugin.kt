package com.yuroyami.kmpssot

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Single source of truth for KMP app config. Applied once at the **root project**.
 *
 * When applied, this plugin:
 *  1. Registers the [KmpSsotExtension] as `kmpSsot { }` on the root project.
 *  2. Registers the `syncIosConfig` task on the root project.
 *  3. Walks all subprojects:
 *     - `com.android.application`     → wires full app config (applicationId, versionCode/Name,
 *       compileSdk/minSdk/targetSdk, compileOptions, ndkVersion, resourceConfigurations,
 *       manifestPlaceholders[appName]).
 *     - `com.android.library`         → wires shared bits (compileSdk/minSdk, compileOptions,
 *       ndkVersion, resourceConfigurations).
 *     - `org.jetbrains.kotlin.multiplatform` → hooks `syncIosConfig` as a dependency of
 *       `linkPod*FrameworkIos*` and `embedAndSignAppleFrameworkForXcode`, so iOS builds
 *       keep pbxproj in sync.
 *
 * Consumer responsibilities (outside the plugin's reach):
 *  - Change `android:label="..."` to `android:label="${'$'}{appName}"` in AndroidManifest.xml.
 *  - Ensure iOS Info.plist uses `$(MARKETING_VERSION)` for CFBundleShortVersionString
 *    and `$(CURRENT_PROJECT_VERSION)` for CFBundleVersion.
 */
class KmpSsotPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        check(target == target.rootProject) {
            "com.yuroyami.kmpssot must be applied to the root project. " +
                    "Apply it in the root build.gradle.kts, not in a submodule."
        }

        val ext = target.extensions.create<KmpSsotExtension>("kmpSsot").apply {
            iosBundleSuffix.convention("")
            androidApplicationIdSuffix.convention("")
            compileSdk.convention(36)
            minSdk.convention(26)
            javaVersion.convention(21)
            iosProjectPath.convention("iosApp/iosApp.xcodeproj/project.pbxproj")
            locales.convention(emptyList())
            propagateAppName.convention(true)
            propagateBundleId.convention(true)
            propagateVersion.convention(true)
            propagateLocaleList.convention(true)
            syncIos.convention(true)
        }

        val syncIosTask = registerSyncIosTask(target, ext)

        target.subprojects {
            val sub = this
            plugins.withId("com.android.application") { wireAndroidApp(sub, ext) }
            plugins.withId("com.android.library") { wireAndroidLibrary(sub, ext) }
            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                hookIosFrameworkTasks(sub, syncIosTask, ext)
            }
        }
    }

    // --- iOS pbxproj task ----------------------------------------------------

    private fun registerSyncIosTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SyncIosConfigTask> =
        root.tasks.register<SyncIosConfigTask>("syncIosConfig") {
            onlyIf { ext.syncIos.get() }
            pbxprojFile.set(root.layout.projectDirectory.file(ext.iosProjectPath))
            versionName.set(ext.versionName)
            versionCode.set(ext.versionCode)
            appName.set(ext.appName)
            bundleId.set(ext.iosBundleId)
            locales.set(ext.locales)
            propagateVersion.set(ext.propagateVersion)
            propagateAppName.set(ext.propagateAppName)
            propagateBundleId.set(ext.propagateBundleId)
            propagateLocaleList.set(ext.propagateLocaleList)
        }

    private fun hookIosFrameworkTasks(
        project: Project,
        syncIosTask: TaskProvider<SyncIosConfigTask>,
        ext: KmpSsotExtension,
    ) {
        if (!ext.syncIos.get()) return
        project.tasks.matching {
            it.name.startsWith("linkPodReleaseFrameworkIos") ||
                    it.name.startsWith("linkPodDebugFrameworkIos") ||
                    it.name == "embedAndSignAppleFrameworkForXcode"
        }.configureEach { dependsOn(syncIosTask) }
    }

    // --- Android application wiring -----------------------------------------

    private fun wireAndroidApp(project: Project, ext: KmpSsotExtension) {
        val components = project.extensions
            .getByType(ApplicationAndroidComponentsExtension::class.java)

        components.finalizeDsl { android ->
            android.compileSdk = ext.compileSdk.get()
            ext.ndkVersion.orNull?.let { android.ndkVersion = it }

            if (ext.propagateBundleId.get()) {
                android.defaultConfig.applicationId = ext.androidApplicationId.get()
            }
            if (ext.propagateVersion.get()) {
                android.defaultConfig.versionCode = ext.versionCode.get()
                android.defaultConfig.versionName = ext.versionName.get()
            }
            android.defaultConfig.minSdk = ext.minSdk.get()
            android.defaultConfig.targetSdk = ext.targetSdk.orNull ?: ext.compileSdk.get()
            if (ext.propagateAppName.get()) {
                android.defaultConfig.manifestPlaceholders["appName"] = ext.appName.get()
            }
            if (ext.propagateLocaleList.get()) {
                val l = ext.locales.get()
                if (l.isNotEmpty()) android.defaultConfig.resourceConfigurations.addAll(l)
            }

            val jv = JavaVersion.toVersion(ext.javaVersion.get())
            android.compileOptions.sourceCompatibility = jv
            android.compileOptions.targetCompatibility = jv
        }
    }

    // --- Android library wiring ---------------------------------------------

    private fun wireAndroidLibrary(project: Project, ext: KmpSsotExtension) {
        val components = project.extensions
            .getByType(LibraryAndroidComponentsExtension::class.java)

        components.finalizeDsl { android ->
            android.compileSdk = ext.compileSdk.get()
            ext.ndkVersion.orNull?.let { android.ndkVersion = it }

            android.defaultConfig.minSdk = ext.minSdk.get()
            if (ext.propagateLocaleList.get()) {
                val l = ext.locales.get()
                if (l.isNotEmpty()) android.defaultConfig.resourceConfigurations.addAll(l)
            }

            val jv = JavaVersion.toVersion(ext.javaVersion.get())
            android.compileOptions.sourceCompatibility = jv
            android.compileOptions.targetCompatibility = jv
        }
    }
}
