// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

android {
    namespace = "nl.mattix.andamp.visualizer.avs"

    // r27+ is the floor for 16 KB page alignment, required from Android 15
    ndkVersion = "28.2.13676358"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                // the source paths the compiler writes into the library - every
                // assert's __FILE__ - are made relative to the repository. As
                // absolute paths they put whoever built it and wherever it was
                // checked out into every debug APK. Release strips them already;
                // this makes the library the same wherever it is built
                val repo = rootDir.absolutePath
                cFlags += "-ffile-prefix-map=$repo=."
                cppFlags += "-ffile-prefix-map=$repo=."
            }
        }

        // arm64 is the only first-class target; x86_64 keeps the emulator usable
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// An Android library because of AvsView and the JNI binding to the ns-eel
// evaluator. The parser and the pipeline use no android.* and are tested on the JVM.
dependencies {
    api(project(":visualizer:core"))
    implementation(project(":core:playback"))
    implementation(project(":core:player"))
    testImplementation(libs.junit)

    // the evaluator is native, so it and the renderers that script in it are tested on device
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
