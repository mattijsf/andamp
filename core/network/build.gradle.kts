// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.maven.publish)
}

// Whether the phone has a network, from the phone's own connectivity service.
//
// `:core:playback` holds the NetworkWatch interface and the reconnect policy, and is plain
// Kotlin. The one class that needs Android is here, with the permission it needs in its own
// manifest, so every APK that uses it gets the permission merged in.
android {
    namespace = "nl.mattix.andamp.core.network"
}

dependencies {
    // NetworkWatch, the interface it implements
    api(project(":core:playback"))
}
