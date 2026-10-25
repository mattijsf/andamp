// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.media3

import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.playback.PlaybackBackend

/**
 * A backend whose presses that start the music call [comeForward] first.
 *
 * The player asks for the audio focus as the music starts, and from Android 15 that is refused to
 * an app that is neither the app in front nor running a foreground service; see
 * [PlaybackService.comeForward]. Play and a row played start the music from any state. Pause,
 * next and previous start it only from paused, and are passed straight on otherwise.
 */
internal class ForegroundFirst(
    private val backend: PlaybackBackend,
    private val comeForward: () -> Unit,
) : PlaybackBackend by backend {
    override fun play() {
        comeForward()
        backend.play()
    }

    override fun playAt(index: Int) {
        comeForward()
        backend.playAt(index)
    }

    override fun pause() {
        if (paused()) comeForward()
        backend.pause()
    }

    override fun next() {
        if (paused()) comeForward()
        backend.next()
    }

    override fun previous() {
        if (paused()) comeForward()
        backend.previous()
    }

    private fun paused() = backend.state.value.transport == Transport.Paused
}
