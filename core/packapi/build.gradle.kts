// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.android.library)
    // no version: AGP 9 compiles Kotlin itself and brings parcelize with it, and refuses a
    // version asked for here
    id("org.jetbrains.kotlin.plugin.parcelize")
    alias(libs.plugins.detekt)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

android {
    namespace = "nl.mattix.andamp.core.packapi"

    buildFeatures {
        aidl = true
    }
}

dokka {
    dokkaSourceSets.configureEach {
        // IMusicSourcePack and IPackListener are .aidl, so they reach the compiler as
        // generated Java, which Dokka drops by default. Their javadoc documents the calls
        // of the contract, so generated files are kept.
        suppressGeneratedFiles = false
    }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
