package com.yuroyami.kmpssot

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Single source of truth for KMP app identity. Applied once at the **root project**.
 *
 * Every identity field in `kmpSsot { }` is optional. A field gets propagated
 * iff (a) its `propagate*` toggle is true (default true) AND (b) the value
 * is actually set in the DSL. This means you can apply the plugin to a
 * production app and declare only the bits you want centralized — e.g.
 * `versionName` + `locales` + `appName`, leaving `bundleIdBase` unset so the
 * already-registered Android applicationId and iOS PRODUCT_BUNDLE_IDENTIFIER
 * are never touched.
 */
class KmpSsotPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        check(target == target.rootProject) {
            "com.yuroyami.kmpssot must be applied to the root project. " +
                    "Apply it in the root build.gradle.kts, not in a submodule."
        }

        val ext = target.extensions.create<KmpSsotExtension>("kmpSsot").apply {
            iosBundleSuffix.convention(".iosApp")
            androidApplicationIdSuffix.convention("")
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
            // Only set bundleId if bundleIdBase is present; otherwise leave unset
            // so the task's isPresent check skips PRODUCT_BUNDLE_IDENTIFIER rewrites.
            if (ext.bundleIdBase.isPresent) bundleId.set(ext.iosBundleId)
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
        val android = project.extensions.getByType(ApplicationExtension::class.java)

        android.defaultConfig.apply {
            if (ext.propagateBundleId.get() && ext.bundleIdBase.isPresent) {
                applicationId = ext.androidApplicationId.get()
            }
            if (ext.propagateVersion.get() && ext.versionName.isPresent) {
                versionCode = ext.versionCode.get()
                versionName = ext.versionName.get()
            }
            if (ext.propagateAppName.get() && ext.appName.isPresent) {
                manifestPlaceholders["appName"] = ext.appName.get()
            }
            if (ext.propagateLocaleList.get()) {
                val l = ext.locales.get()
                if (l.isNotEmpty()) resourceConfigurations.addAll(l)
            }
        }

        val jv = JavaVersion.toVersion(ext.javaVersion.get())
        android.compileOptions.sourceCompatibility = jv
        android.compileOptions.targetCompatibility = jv
    }

    // --- Android library wiring ---------------------------------------------

    private fun wireAndroidLibrary(project: Project, ext: KmpSsotExtension) {
        val android = project.extensions.getByType(LibraryExtension::class.java)

        if (ext.propagateLocaleList.get()) {
            val l = ext.locales.get()
            if (l.isNotEmpty()) android.defaultConfig.resourceConfigurations.addAll(l)
        }

        val jv = JavaVersion.toVersion(ext.javaVersion.get())
        android.compileOptions.sourceCompatibility = jv
        android.compileOptions.targetCompatibility = jv
    }
}
