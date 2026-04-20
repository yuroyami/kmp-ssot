package com.yuroyami.kmpssot

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

class KmpSsotPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        check(target == target.rootProject) {
            "com.yuroyami.kmpssot must be applied to the root project. " +
                    "Apply it in the root build.gradle.kts, not in a submodule."
        }

        val ext = target.extensions.create<KmpSsotExtension>("kmpSsot").apply {
            // Suffixes: null by default. When unset, applicationId/bundleId = bundleIdBase raw.
            // The .orElse("") in the extension's derived providers makes this concatenate cleanly.
            javaVersion.convention(21)
            iosProjectPath.convention("iosApp/iosApp.xcodeproj/project.pbxproj")
            androidAppModule.convention("androidApp")
            propagateAppName.convention(true)
            propagateBundleId.convention(true)
            propagateVersion.convention(true)
            propagateLocaleList.convention(true)
            syncIos.convention(true)

            // Auto-detect locales from {sharedModule}/src/commonMain/composeResources/values-*.
            // Lazy: the provider is only evaluated when locales is read, by which point
            // sharedModule must be set (we validate that in afterEvaluate below).
            locales.convention(target.provider { autoDetectLocales(target, this) })
        }

        // Enforce sharedModule presence at the latest responsible moment.
        target.afterEvaluate {
            if (!ext.sharedModule.isPresent) {
                throw GradleException(
                    "kmpSsot { sharedModule = \"...\" } is required. " +
                            "Set it to the directory name of your KMP shared module " +
                            "(e.g. \"shared\" or \"composeApp\") in the root build.gradle.kts."
                )
            }
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

    // --- Locale auto-detection ----------------------------------------------

    private fun autoDetectLocales(root: Project, ext: KmpSsotExtension): List<String> {
        if (!ext.sharedModule.isPresent) return emptyList()
        val sharedDir = root.file(ext.sharedModule.get())
        val composeRes = sharedDir.resolve("src/commonMain/composeResources")
        if (!composeRes.isDirectory) return emptyList()
        return composeRes
            .listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            ?.map { it.name.removePrefix("values-") }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
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
