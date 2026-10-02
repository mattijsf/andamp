// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
}

android {
    namespace = "nl.mattix.andamp.backend.pack"
}

dependencies {
    api(project(":core:playback"))
    // api: PackReach.Ready exposes the descriptor and the account, so their types are part
    // of this module's surface
    api(project(":core:packapi"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // core:playback's contract suites; no test in this module extends one
    testImplementation(testFixtures(project(":core:playback")))
}
