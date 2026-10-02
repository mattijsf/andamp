// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:dsp"))
    // UiNode and Preset, which a plug-in's layout is described in, come from the model
    api(project(":core:model"))
    implementation(libs.luaj)

    testImplementation(libs.junit)
}

// ExamplePluginsTest reads .lua files outside this module. Declaring the directories as
// inputs makes a changed script rerun the tests.
tasks.withType<Test>().configureEach {
    inputs
        .dir(rootProject.file("docs/examples"))
        .withPropertyName("examplePlugins")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir(rootProject.file("backend/media3/src/main/resources/plugins"))
        .withPropertyName("bundledPlugins")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
