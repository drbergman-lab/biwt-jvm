pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

rootProject.name = "qupath-extension-biwt"

include("core")
include("qupath-extension")

qupath {
    version = "0.7.0"
}

plugins {
    // Auto-provisions JDK toolchains (downloads JDK 25 on first build for this project).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}
