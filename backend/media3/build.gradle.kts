// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

android {
    namespace = "nl.mattix.andamp.backend.media3"

    defaultConfig {
        // the runner for the on-device cost benchmarks in androidTest
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // the cost benchmarks in androidTest run against a release build, because
    // ART optimizes a debug build differently
    testBuildType = "release"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    api(project(":core:playback"))
    // the network watch StationRedial uses
    implementation(project(":core:network"))
    implementation(project(":core:dsp"))
    implementation(project(":core:plugin"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.media3.exoplayer)
    // .m3u8 stations. DefaultMediaSourceFactory finds the HLS factory by
    // reflection, so this dependency has no call site
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(testFixtures(project(":core:playback")))
}
