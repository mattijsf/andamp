// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

// The one piece of Android both engines share: the TextureView a visualizer
// renders through, with its thread, its lifecycle gate and its command queue.
// An engine module implements the loop; this owns everything around it.
android {
    namespace = "nl.mattix.andamp.visualizer.core"
}
