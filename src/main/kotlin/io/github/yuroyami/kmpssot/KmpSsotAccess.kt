package io.github.yuroyami.kmpssot

import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/**
 * Accessor for the [KmpSsotExtension] from any subproject. The plugin is
 * root-applied, so Gradle's generated DSL accessor only exists on the root
 * project — this bridges that gap.
 *
 * Usage in a subproject build.gradle.kts:
 *
 *     import io.github.yuroyami.kmpssot.kmpSsot
 *     val v = kmpSsot.versionName.get()
 */
val Project.kmpSsot: KmpSsotExtension
    get() = rootProject.extensions.getByType<KmpSsotExtension>()
