// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

android {
    namespace = "nl.mattix.andamp.core.remotewindow"

    buildFeatures {
        aidl = true
    }
}
