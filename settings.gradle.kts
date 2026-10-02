// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "andamp"
include(":app")
include(":core:dsp")
include(":core:plugin")
include(":core:model")
include(":core:playback")
include(":core:player")
include(":core:packapi")
include(":core:network")
include(":backend:media3")
include(":backend:pack")
include(":pack:common")
include(":visualizer:core")
include(":visualizer:projectm")
include(":visualizer:avs")
