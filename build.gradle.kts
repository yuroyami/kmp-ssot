plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.yuroyami"
version = providers.gradleProperty("kmpSsot.version").getOrElse("0.2.1")

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
    compileOnly("com.android.tools.build:gradle-api:9.0.1")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.3.10")
}

gradlePlugin {
    plugins {
        create("kmpSsot") {
            id = "com.yuroyami.kmpssot"
            implementationClass = "com.yuroyami.kmpssot.KmpSsotPlugin"
            displayName = "KMP SSOT Plugin"
            description = "Single source of truth for KMP app configuration (appName, version, bundleId) propagated to Android + iOS."
        }
    }
}

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
