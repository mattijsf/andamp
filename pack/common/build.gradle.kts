// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "nl.mattix.andamp.pack.common"

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest to stand an application up
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // api throughout: this module's public types are stated in types from these three, so
    // a pack that depends on it has to be able to name them
    api(project(":core:playback"))
    // the contract both sides read: the AIDL and the types that cross it
    api(project(":core:packapi"))
    // the phone's network watch, which a pack passes to the stream backend; api because
    // the pack names it when it builds the backend
    api(project(":core:network"))
    api(libs.kotlinx.coroutines.core)
    // the main dispatcher the service's scope runs on; a source without it on its
    // classpath would fail as its service is created
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // the stream backend runs the playback contract suite on a virtual clock with a fake
    // decoder
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:playback")))
    // the launcher entry needs a package manager and a preferences file, which
    // Robolectric provides on a JVM
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
