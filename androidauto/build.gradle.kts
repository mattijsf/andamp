// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

/**
 * The key a release is signed with, found the way `:app` finds its own: `keystore.properties`
 * beside the project names the keystore file and the alias, and the two passwords come from the
 * environment. Without the file the release build is unsigned.
 */
val signing =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
val storePath: String? = signing.getProperty("storeFile")

fun secret(
    variable: String,
    property: String,
): String? = System.getenv(variable)?.takeIf { it.isNotBlank() } ?: signing.getProperty(property)

/** A semver name as one climbing integer, as `:app` counts its own: 1.2.3 becomes 10203. */
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

/**
 * The id of the player this app belongs to. It follows `:app`, which `-Pandamp.id.suffix`
 * gives a second id for trying a change beside the Andamp a store installed; this app's own id
 * is that one with `.androidauto` after it (`RemoteWindowLink`).
 */
val player = "nl.mattix.andamp" + (findProperty("andamp.id.suffix") as String?).orEmpty()

android {
    namespace = "nl.mattix.andamp.androidauto"

    defaultConfig {
        applicationId = "$player.androidauto"
        targetSdk = 36
        // release-please owns this line; it is the player's version, which this app is released with
        versionName = "0.13.0" // x-release-please-version
        versionCode = versionCodeOf(versionName!!)
        // the runner for the on-device test that lends a surface to the installed player
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // the manifest names the player, whose service this app binds to
        manifestPlaceholders["player"] = player
    }

    signingConfigs {
        create("release") {
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                keyAlias = signing.getProperty("keyAlias")
                storePassword = secret("RT_STORE_PASSWORD", "storePassword")
                keyPassword = secret("RT_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release").takeIf { storePath != null }
        }
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
