// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

android {
    namespace = "nl.mattix.andamp.androidauto"

    defaultConfig {
        applicationId = "nl.mattix.andamp.androidauto"
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // the runner for the on-device test that lends a surface to the installed player
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric reads the manifest and the strings
        }
    }
}

dependencies {
    implementation(project(":core:remotewindow"))
    implementation(libs.androidx.car.app)
    // the part of the library that talks to Android Auto
    implementation(libs.androidx.car.app.projected)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.car.app.testing)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
