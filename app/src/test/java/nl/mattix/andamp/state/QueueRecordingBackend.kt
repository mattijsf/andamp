// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.flow.MutableStateFlow
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.playback.PlaybackBackend

/** Hand-rolled fake capturing setQueue calls; everything else is inert. */
class QueueRecordingBackend(
    private val queues: MutableList<List<Track>>,
) : PlaybackBackend {
    override val state = MutableStateFlow(BackendState())
    override val capabilities =
        Capabilities(canSeek = true, canEditQueue = true)

    override fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) {
        queues += tracks
    }

    override fun play() = Unit

    override fun pause() = Unit

    override fun stop() = Unit

    override fun next() = Unit

    override fun previous() = Unit

    override fun playAt(index: Int) = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun setVolume(fraction: Float) = Unit

    override fun setShuffle(enabled: Boolean) = Unit

    override fun setRepeat(enabled: Boolean) = Unit

    override fun setEqualizer(settings: EqSettings) = Unit

    override fun release() = Unit
}
