// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.skinplayer.Entry
import nl.mattix.andamp.skinplayer.SkinPlayer
import nl.mattix.andamp.skinplayer.SkinPlayerContract
import nl.mattix.andamp.wal.DeferredControls

/**
 * Andamp's player meets the contract every skin relies on, over the fake backend the player tests
 * use: a command reaches the state once main has run what was posted and the backend has answered.
 */
class AndampSkinPlayerContractTest : SkinPlayerContract() {
    private val posted = ArrayDeque<Runnable>()
    private lateinit var controls: DeferredControls

    override fun player(playlist: List<Entry>): SkinPlayer {
        val state =
            WinampState().apply {
                this.playlist =
                    playlist.mapIndexed { n, entry ->
                        Track(
                            id = "song-$n",
                            artist = "Artist $n",
                            title = entry.title,
                            durationMs = entry.lengthMs,
                            uri = "file:///music/$n.mp3",
                        )
                    }
                currentIndex = 0
            }
        controls = DeferredControls(state)
        return AndampSkinPlayer(
            controls = controls,
            winamp = state,
            tapOf = { null },
            positionMs = { controls.reportedMs },
            openFile = {},
            post = { posted += it },
            clock = { controls.now },
        )
    }

    override fun settle(player: SkinPlayer) {
        while (posted.isNotEmpty()) posted.removeFirst().run()
        controls.apply()
    }
}
