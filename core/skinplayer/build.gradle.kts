// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
    alias(libs.plugins.detekt)
}

// What a skin, classic or modern, may read from and ask of the player it drives. Whatever
// implements it can sit under both skin systems; it depends on nothing.
kotlin {
    jvmToolchain(17)
    // every public name is part of the contract; the dump in api/ makes a change to it a visible one
    explicitApi()
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

dependencies {
    testImplementation(libs.junit)

    // the contract tests ship as test fixtures, so an implementation in another module runs them
    testFixturesApi(libs.junit)
}
