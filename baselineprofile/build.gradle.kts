// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.detekt)
}

// The baseline profile of `:app`, written on a device, and the startup benchmark that measures it.
//
// ```
// ./gradlew :app:generateBaselineProfile
// ```
//
// runs `BaselineProfileGenerator` on the connected device and writes the profile into
// `app/src/main/generated/baselineProfiles`, where it is committed. A release build packages the
// committed file and needs no device.
//
// ```
// ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
// ```
//
// runs `StartupBenchmark`, which starts the app cold with and without the profile.
//
// Both need a device on API 33 or later; neither is part of the gate.
android {
    namespace = "nl.mattix.andamp.baselineprofile"
    targetProjectPath = ":app"

    defaultConfig {
        // the journey finds the player's buttons from the window insets WindowMetrics reports
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // lets both run on an emulator, whose timings are only compared with each other
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
}

baselineProfile {
    // the device adb sees
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
