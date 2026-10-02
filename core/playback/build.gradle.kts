// SPDX-License-Identifier: Apache-2.0

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
    alias(libs.plugins.detekt)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)

    // the contract tests ship as test fixtures so that a backend module can subclass them
    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesApi(project(":core:model"))
}
