// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADD > URL: two chained prompts, then an append through the backend's own
 * enqueue. The queue is not replaced, so the cursor and the transport stay put.
 */
class PlaylistAddUrlTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun player(): Triple<PlaylistOps, WinampState, PlayerFacade> {
        val state = WinampState()
        val facade = PlayerFacade(MockBackend(FakeTracks.tracks, scope))
        return Triple(PlaylistOps(state, facade), state, facade)
    }

    @Test
    fun `a url is asked for, then a name suggested from its host`() {
        val (ops, state, facade) = player()
        val before = facade.state.value.queue.size

        ops.promptAddUrl()
        val urlPrompt = state.namePrompt
        assertEquals("Add URL", urlPrompt?.title)

        urlPrompt?.onSubmit?.invoke("http://stream.example.org/live/mp3")
        val namePrompt = state.namePrompt
        assertEquals("Name", namePrompt?.title)
        assertEquals("stream.example.org", namePrompt?.initial)

        namePrompt?.onSubmit?.invoke("Radio Example")
        assertNull(state.namePrompt)
        val queue = facade.state.value.queue
        assertEquals(before + 1, queue.size)
        assertEquals("Radio Example", queue.last().title)
        assertEquals("http://stream.example.org/live/mp3", queue.last().uri)
        assertEquals(0L, queue.last().durationMs)
        assertTrue(queue.last().id.startsWith("url:"))
    }

    @Test
    fun `the append leaves the cursor and the transport alone`() {
        val (ops, state, facade) = player()

        ops.promptAddUrl()
        state.namePrompt?.onSubmit?.invoke("https://example.com/stream")
        state.namePrompt?.onSubmit?.invoke("Example FM")

        assertEquals(0, facade.state.value.currentIndex)
        assertEquals(Transport.Stopped, facade.state.value.transport)
    }

    @Test
    fun `something that is not a stream url re-opens the prompt to be fixed`() {
        val (ops, state, facade) = player()
        val before = facade.state.value.queue.size

        ops.promptAddUrl()
        state.namePrompt?.onSubmit?.invoke("not a url")

        val reopened = state.namePrompt
        assertEquals("Add URL", reopened?.title)
        assertEquals("not a url", reopened?.initial)
        assertEquals(before, facade.state.value.queue.size)
    }

    @Test
    fun `a blank name falls back to the url itself`() {
        val (ops, state, facade) = player()

        ops.promptAddUrl()
        state.namePrompt?.onSubmit?.invoke("http://a.example/x")
        state.namePrompt?.onSubmit?.invoke("  ")

        assertEquals(
            "http://a.example/x",
            facade.state.value.queue
                .last()
                .title,
        )
    }
}
