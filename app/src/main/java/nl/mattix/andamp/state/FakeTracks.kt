// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track

/**
 * A made-up playlist: the mock backend's queue in tests, and what [WinampState.playlist]
 * holds before the backend's first update.
 */
object FakeTracks {
    private fun track(
        n: Int,
        artist: String,
        title: String,
        sec: Int,
        kbps: Int = 192,
        khz: Int = 44,
    ) = Track("mock-$n", artist, title, sec * 1000L, kbps, khz)

    val tracks: List<Track> =
        listOf(
            track(1, "DJ Mike Llama", "Llama Whippin' Intro", 5, kbps = 56, khz = 22),
            track(2, "Neon Cassette", "Midnight Drive", 254),
            track(3, "Pixel Foundry", "Dither Me This", 198, kbps = 160),
            track(4, "The Winamperors", "It Really Whips", 223),
            track(5, "Sofa King Retro", "Dial-Up Dreams", 187, kbps = 128),
            track(6, "Llama Farm Collective", "Grazing at 44.1kHz", 305),
            track(7, "Bitcrusher Quartet", "Eight Bits Is Enough", 176, kbps = 96, khz = 32),
            track(8, "Neon Cassette", "Rewind Culture", 241),
            track(9, "Skin Deep", "Base 2.91", 212),
            track(10, "The Marquee Scrollers", "This Title Is Intentionally Way Too Long To Fit", 263),
        )
}
