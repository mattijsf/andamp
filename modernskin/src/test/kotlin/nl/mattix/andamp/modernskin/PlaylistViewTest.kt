// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.Player
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the playlist editor's list answers a finger: a tap selects a row, a second tap on it plays
 * it, and a double tap, whose second tap reaches the list as a double tap rather than a press and
 * a release, plays the row under it.
 */
class PlaylistViewTest {
    // a main window and a playlist window whose list starts at its top-left corner
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1">""" +
                        """<layout id="normal" w="300" h="100"/></container>""" +
                        """<container id="pl" name="Playlist" default_visible="0"><layout id="normal" w="300" h="120">""" +
                        """<component id="list" param="guid:{45F3F7C1-A6F3-4ee6-A15E-125E92FC3F8D}" """ +
                        """x="0" y="0" w="300" h="120"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val player =
        FakePlayer().apply {
            clock = { 0L }
            playlist = listOf(Track("One", 1_000), Track("Two", 2_000), Track("Three", 3_000))
        }

    private val rt =
        SkinRuntime(files, TestHost(files), player).apply {
            clock = { 0L }
            load()
            openWindow(checkNotNull(container("pl")))
        }

    /** The point of the stack in the middle of row [i]: rows are 15 px, from 3 px under the list's top. */
    private fun row(i: Int): Pair<Int, Int> {
        rt.ensureLayout()
        val window = rt.windowRect(checkNotNull(rt.container("pl")?.current))
        return window.left + 40 to window.top + 3 + i * 15 + 7
    }

    private fun tap(at: Pair<Int, Int>) {
        rt.pointerDown(at.first, at.second)
        rt.pointerUp(at.first, at.second)
    }

    @Test
    fun `a tap selects a row, and a second tap on it plays it`() {
        tap(row(1))
        assertEquals("selected only", Player.Status.STOPPED, player.status)
        assertEquals(0, player.trackIndex)

        tap(row(1))
        assertEquals(Player.Status.PLAYING, player.status)
        assertEquals(1, player.trackIndex)
    }

    @Test
    fun `a double tap plays the row under it`() {
        val at = row(2)
        tap(at)
        rt.doubleTap(at.first, at.second)

        assertEquals(Player.Status.PLAYING, player.status)
        assertEquals(2, player.trackIndex)
    }

    @Test
    fun `a double tap under the last row plays nothing`() {
        val at = row(5)
        tap(at)
        rt.doubleTap(at.first, at.second)

        assertEquals(Player.Status.STOPPED, player.status)
        assertEquals(0, player.trackIndex)
    }
}
