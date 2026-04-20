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
            javaVersion.convention(21)
            iosProjectPath.convention("iosApp/iosApp.xcodeproj/project.pbxproj")
            iosPodfilePath.convention("iosApp/Podfile")
            androidAppModule.convention("androidApp")
            appLogoBackgroundColor.convention("#FFFFFF")
            propagateAppName.convention(true)
            propagateBundleId.convention(true)
            propagateVersion.convention(true)
            propagateLocaleList.convention(true)
            propagateLogo.convention(true)
            propagateSharedModule.convention(true)
            syncIos.convention(true)

            // Auto-detect locales from {sharedModule}/src/commonMain/composeResources/values-*.
            locales.convention(target.provider { autoDetectLocales(target, this) })
        }

        target.afterEvaluate {
            if (!ext.sharedModule.isPresent) {
                throw GradleException(
                    "kmpSsot { sharedModule = \"...\" } is required. Set it to the directory " +
                            "name of your KMP shared module (e.g. \"shared\" or \"composeApp\")."
                )
            }
            // Logo: enforce paired-or-neither.
            val xmlSet = ext.appLogoXml.isPresent
            val pngSet = ext.appLogoPng.isPresent
            if (xmlSet xor pngSet) {
                throw GradleException(
                    "kmpSsot { appLogoXml + appLogoPng } must be set together. " +
                            "Either provide both (Android XML + iOS PNG) or neither."
                )
            }
        }

        val syncIosTask = registerSyncIosTask(target, ext)
        val syncIosLogoTask = registerSyncIosLogoTask(target, ext)
        val syncAndroidLogoTask = registerSyncAndroidLogoTask(target, ext)

        target.subprojects {
            val sub = this
            plugins.withId("com.android.application") {
                wireAndroidApp(sub, ext)
                hookAndroidLogoTask(sub, syncAndroidLogoTask, ext)
            }
            plugins.withId("com.android.library") { wireAndroidLibrary(sub, ext) }
            plugins.withId("org.jetbrains.kotlin.multiplatform") {
                hookIosFrameworkTasks(sub, syncIosTask, syncIosLogoTask, ext)
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

    // --- Task registration --------------------------------------------------

    private fun registerSyncIosTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SyncIosConfigTask> =
        root.tasks.register<SyncIosConfigTask>("syncIosConfig") {
            onlyIf { ext.syncIos.get() }
            pbxprojFile.set(root.layout.projectDirectory.file(ext.iosProjectPath))
            podfile.set(root.layout.projectDirectory.file(ext.iosPodfilePath))
            iosAppDir.set(root.layout.projectDirectory.dir("iosApp"))
            versionName.set(ext.versionName)
            versionCode.set(ext.versionCode)
            appName.set(ext.appName)
            if (ext.bundleIdBase.isPresent) bundleId.set(ext.iosBundleId)
            locales.set(ext.locales)
            sharedModule.set(ext.sharedModule)
            propagateVersion.set(ext.propagateVersion)
            propagateAppName.set(ext.propagateAppName)
            propagateBundleId.set(ext.propagateBundleId)
            propagateLocaleList.set(ext.propagateLocaleList)
            propagateSharedModule.set(ext.propagateSharedModule)
        }

    private fun registerSyncIosLogoTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SyncIosLogoTask> =
        root.tasks.register<SyncIosLogoTask>("syncIosLogo") {
            onlyIf { ext.syncIos.get() && ext.propagateLogo.get() && ext.appLogoPng.isPresent }
            sourcePng.set(ext.appLogoPng)
            appiconsetDir.set(root.layout.projectDirectory.dir("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"))
        }

    private fun registerSyncAndroidLogoTask(
        root: Project,
        ext: KmpSsotExtension,
    ): TaskProvider<SyncAndroidLogoTask> =
        root.tasks.register<SyncAndroidLogoTask>("syncAndroidLogo") {
            onlyIf { ext.propagateLogo.get() && ext.appLogoXml.isPresent }
            sourceXml.set(ext.appLogoXml)
            // Resolve lazily — androidAppModule may not be set yet at register time.
            androidResDir.set(root.layout.projectDirectory.dir(
                ext.androidAppModule.map { "$it/src/main/res" }
            ))
            backgroundColor.set(ext.appLogoBackgroundColor)
        }

    // --- Hooking new tasks --------------------------------------------------

    private fun hookIosFrameworkTasks(
        project: Project,
        syncIosTask: TaskProvider<SyncIosConfigTask>,
        syncIosLogoTask: TaskProvider<SyncIosLogoTask>,
        ext: KmpSsotExtension,
    ) {
        if (!ext.syncIos.get()) return
        val iosTaskFilter: (org.gradle.api.Task) -> Boolean = {
            it.name.startsWith("linkPodReleaseFrameworkIos") ||
                    it.name.startsWith("linkPodDebugFrameworkIos") ||
                    it.name == "embedAndSignAppleFrameworkForXcode"
        }
        project.tasks.matching(iosTaskFilter).configureEach {
            dependsOn(syncIosTask)
            dependsOn(syncIosLogoTask)
        }
    }

    private fun hookAndroidLogoTask(
        project: Project,
        syncAndroidLogoTask: TaskProvider<SyncAndroidLogoTask>,
        ext: KmpSsotExtension,
    ) {
        if (!ext.propagateLogo.get()) return
        // preBuild runs before resource processing — we want logo files in place by then.
        project.tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(syncAndroidLogoTask)
        }
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
