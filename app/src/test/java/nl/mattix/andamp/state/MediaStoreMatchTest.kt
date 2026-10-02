// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.state.MediaStoreMatch.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Choosing a library entry by name, where names repeat across albums. */
class MediaStoreMatchTest {
    private val intro = Candidate(id = 1, displayName = "01 - Intro.mp3", durationMs = 90_000)
    private val otherIntro = Candidate(id = 2, displayName = "01 - Intro.mp3", durationMs = 240_000)

    @Test
    fun `a single name match is taken`() {
        assertEquals(intro, MediaStoreMatch.best(listOf(intro), "01 - Intro.mp3", 90_000))
    }

    @Test
    fun `duration picks between files of the same name`() {
        assertEquals(otherIntro, MediaStoreMatch.best(listOf(intro, otherIntro), "01 - Intro.mp3", 239_500))
    }

    @Test
    fun `a name that matches nothing yields nothing`() {
        assertNull(MediaStoreMatch.best(listOf(intro), "02 - Other.mp3", 90_000))
    }

    @Test
    fun `no duration and an ambiguous name refuses to guess`() {
        assertNull(MediaStoreMatch.best(listOf(intro, otherIntro), "01 - Intro.mp3", 0))
        assertEquals(intro, MediaStoreMatch.best(listOf(intro), "01 - Intro.mp3", 0))
    }

    @Test
    fun `a duration nowhere near any candidate is not a match`() {
        assertNull(MediaStoreMatch.best(listOf(intro, otherIntro), "01 - Intro.mp3", 12_000))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(intro, MediaStoreMatch.best(listOf(intro), "01 - INTRO.MP3", 90_000))
    }

    @Test
    fun `a known path settles it outright`() {
        val here = intro.copy(id = 7, path = "/storage/emulated/0/Music/01 - Intro.mp3")

        assertEquals(here, MediaStoreMatch.best(listOf(otherIntro, here), "01 - Intro.mp3", 0, preferPath = here.path))
    }
}
