// :core — pure Java library. NO QuPath GUI dependencies.
// Anything in this module must be callable from a headless Groovy script.
// If you find yourself needing to import javafx.* or qupath.fx.* here, it belongs in :qupath-extension instead.

plugins {
    `java-library`
}

java {
    toolchain {
        // QuPath 0.7.0 JARs are compiled for Java 25 (LTS).
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/repositories/releases")
}

dependencies {
    // QuPath non-GUI APIs we need for ImageServer, RegionRequest, ROIs, ImageData, PathObject.
    // Bundle is provided by the qupath-extension-settings plugin in settings.gradle.kts.
    api(libs.bundles.qupath)
    implementation(libs.bundles.logging)

    testImplementation(libs.junit)
    testImplementation(libs.bundles.qupath)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
