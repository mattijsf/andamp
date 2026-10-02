// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.WinampViewModel

/**
 * Shared plumbing for widget-dispatch tests: gestures are expressed in
 * virtual window coordinates and injected at SCALE so the tests also cover
 * the device-to-virtual pointer mapping in ScaledWindowCanvas.
 *
 * Two injection realities shape these tests:
 * - Robolectric's default window (320x470px) is smaller than a scaled window,
 *   so every test class needs `qualifiers = BIG_WINDOW` or touches beyond
 *   x=320 silently miss.
 * - Touch injection quantizes by ~1 device px (±0.5 virtual px at 3x), so
 *   slider assertions either tap past the travel ends (clamped = exact) or
 *   allow ±1 on mid-travel values.
 */
const val SCALE = 3
const val BIG_WINDOW = "w900dp-h1600dp-mdpi"

/** A phone's gesture bar, in device px: what the windows have to stay clear of. */
const val GESTURE_BAR = 48

/** Room for several 25px width steps: BIG_WINDOW only has one to give. */
const val WIDE_WINDOW = "w1400dp-h1600dp-mdpi"

/** Interaction tests run on the mock backend: fast, deterministic, no ExoPlayer. */
fun testViewModel(): WinampViewModel =
    WinampViewModel(
        ApplicationProvider.getApplicationContext<Application>(),
        createBackend = { scope -> MockBackend(FakeTracks.tracks, scope) },
        presetStore = InMemoryEqPresetStore(),
    )

fun TouchInjectionScope.tapVirtual(
    x: Float,
    y: Float,
) {
    down(Offset(x * SCALE, y * SCALE))
    up()
}

fun TouchInjectionScope.dragVirtual(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
) {
    down(Offset(fromX * SCALE, fromY * SCALE))
    moveTo(Offset(toX * SCALE, toY * SCALE))
    up()
}
