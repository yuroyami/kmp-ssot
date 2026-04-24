plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.0"
}

group = "io.github.yuroyami"
version = providers.gradleProperty("kmpSsot.version").getOrElse("1.0.0")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

dependencies {
    // Consumers bring their own AGP; we only need types at compile time.
    compileOnly("com.android.tools.build:gradle-api:9.1.1")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.3.20")
}

gradlePlugin {
    website = "https://github.com/yuroyami/kmp-ssot"
    vcsUrl = "https://github.com/yuroyami/kmp-ssot.git"
    plugins {
        create("kmpSsot") {
            id = "io.github.yuroyami.kmpssot"
            implementationClass = "io.github.yuroyami.kmpssot.KmpSsotPlugin"
            displayName = "KMP SSOT Plugin"
            description = "Single source of truth for KMP app configuration (appName, version, bundleId) propagated to Android + iOS."
            tags = listOf("kotlin", "kotlin-multiplatform", "kmp", "android", "ios", "configuration", "versioning")
        }
    }
}

// Keep GitHub Packages as a secondary channel (internal / pre-release builds).
// Plugin Portal is the primary public distribution, set up by plugin-publish.
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/yuroyami/kmp-ssot")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
