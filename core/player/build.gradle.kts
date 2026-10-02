// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
}

// The player's own half of playback: the facade the UI talks to, the queue that mixes
// sources, and what the visualizers pace themselves and read samples by.
//
// Separate from `:core:playback`, which is the contract a source is written against. A
// source never sees a facade or a mixed queue.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:playback"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // the mixed queue runs the backend contract suite over mock players
    testImplementation(testFixtures(project(":core:playback")))
}
