pluginManagement {
    // Include plugin-build so our plugin is resolved from source, not the Plugin Portal
    includeBuild("plugin-build")
    plugins {
        // Pin KMP version here so sub-projects don't need to re-declare it
        // (avoids "already on classpath with unknown version" from the composite build)
        kotlin("multiplatform") version "2.4.20"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

plugins {
    id("com.gradle.develocity") version "4.4.0"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

develocity {
    buildScan.termsOfUseUrl = "https://gradle.com/terms-of-service"
    buildScan.termsOfUseAgree = "yes"
    buildScan.publishing.onlyIf {
        System.getenv("GITHUB_ACTIONS") == "true" &&
            it.buildResult.failures.isNotEmpty()
    }
}

rootProject.name = "dev.nucleusframework.nna"

include(":examples:calculator")
include(":examples:systeminfo")
include(":examples:benchmark")
