// :qupath-extension — the QuPath-facing extension. Depends on :core for all sampling/export logic.

plugins {
    id("com.gradleup.shadow") version "8.3.5"
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-biwt"
    group = "io.github.drbergmanlab.biwt"
    version = "0.1.0-SNAPSHOT"
    description = "BIWT — sample digital pathology images on a regular grid for ABM (PhysiCell) substrate initial conditions."
    automaticModule = "io.github.drbergmanlab.biwt.qupath"
}

dependencies {
    // The core sampling/CSV logic
    shadow(project(":core"))

    // QuPath APIs (GUI + non-GUI) and supporting libs
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
}
