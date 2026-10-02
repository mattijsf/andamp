// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.Capabilities
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.RackSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.packapi.PackApi
import nl.mattix.andamp.core.packapi.PackState
import nl.mattix.andamp.core.packapi.toPack
import nl.mattix.andamp.core.playback.AudioOut
import nl.mattix.andamp.core.playback.PlaybackBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's end of a pack's playback.
 *
 * The pack's state arrives whole, and a transport this build does not know reads as stopped.
 * Presses reach the pack in the order they were pressed. With no pack, verbs are dropped and
 * the last state is kept. With an output, the pack's transport drives the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackBackendTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val rows =
        listOf(
            Track(id = "example:track:1", artist = "Muse", title = "Hexagons", durationMs = 214_000),
            Track(id = "example:track:2", artist = "Portishead", title = "Mysterons", durationMs = 305_000),
        )

    /**
     * The scope the client and its player are given: unconfined on the test's scheduler, so
     * that work a constructor starts has run by the time a test looks.
     */
    private fun TestScope.own(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    /** A client on [own], with both dispatchers unconfined on the test's scheduler. */
    private fun TestScope.clientFor(): PackClient =
        PackClient(
            app,
            PackApi.ACTION_BIND,
            null,
            own(),
            UnconfinedTestDispatcher(testScheduler),
            UnconfinedTestDispatcher(testScheduler),
        )

    /**
     * A client on real unconfined dispatchers, for the test that reads the pipe itself. A read
     * blocks on the binder call that opens the pipe, so it must not wait on a scheduler that
     * the same test body would have to advance.
     */
    private fun renderingClient(): PackClient =
        PackClient(
            app,
            PackApi.ACTION_BIND,
            null,
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            Dispatchers.Unconfined,
        )

    private fun TestScope.playerFor(
        client: PackClient,
        startIndex: Int = 0,
        out: AudioOut? = null,
    ): PlaybackBackend = client.backend(rows, startIndex, own(), out)

    /** The pack reports [transport] to its listener. */
    private fun FakePack.says(transport: Transport) = listener?.onState(PackState(transport = transport.name))

    @Test
    fun `the queue the player was built with is set on the pack before anything is pressed`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()

            playerFor(client, startIndex = 1)
            advanceUntilIdle()

            assertEquals(listOf("example:track:1", "example:track:2"), pack.queue.map { it.id })
            assertEquals(1, pack.startedAt)
            assertNotNull("the player registers a listener on the pack", pack.listener)
        }

    @Test
    fun `the player's state is what the pack reports`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()

            pack.listener?.onState(
                PackState(
                    transport = Transport.Playing.name,
                    positionMs = 4_200,
                    currentIndex = 1,
                    queue = rows.map { it.toPack() },
                    volumeFraction = 0.5f,
                    shuffle = true,
                    streamBitrateKbps = 320,
                ),
            )

            val state = player.state.value
            assertEquals(Transport.Playing, state.transport)
            assertEquals(4_200L, state.positionMs)
            assertEquals("Mysterons", state.currentTrack?.title)
            assertEquals(0.5f, state.volumeFraction, 0.0001f)
            assertTrue(state.shuffle)
            assertEquals(320, state.streamBitrateKbps ?: 0)
        }

    /** A newer pack may name transports this build does not know. */
    @Test
    fun `an unknown transport reads as stopped`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()

            pack.listener?.onState(PackState(transport = "Teleporting"))

            assertEquals(Transport.Stopped, player.state.value.transport)
        }

    @Test
    fun `presses reach the pack in the order they were pressed`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)

            player.play()
            player.pause()
            player.next()
            player.seekTo(1_000)
            advanceUntilIdle()

            // describe and account are the client's own calls
            assertEquals(
                listOf("listen", "setQueue", "play", "pause", "next", "seekTo 1000"),
                pack.heard.filterNot { it == "describe" || it == "account" },
            )
        }

    @Test
    fun `the player's capabilities come from the pack's descriptor`() =
        runTest {
            val pack = FakePack(descriptor = describes(canSeek = false, canEditQueue = true, canAttenuate = true))
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()

            assertEquals(
                Capabilities(canSeek = false, canEditQueue = true, canAttenuate = true),
                player.capabilities,
            )
        }

    /** Without an output there is no chain, so no tap and no chain capabilities. */
    @Test
    fun `a player without an output has no tap`() =
        runTest {
            app.install(FakePack())
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()

            assertNull(player.audioTap)
            assertEquals(Capabilities(canSeek = true, canEditQueue = true, canAttenuate = true), player.capabilities)
        }

    /** The visualizer reads the chain the pack's audio is rendered through. */
    @Test
    fun `the tap is the output's own once the pack is playing`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            val player = playerFor(clientFor(), out = out)
            advanceUntilIdle()

            pack.says(Transport.Playing)

            assertNotNull("a playing pack has a tap", player.audioTap)
            assertSame(out.tap, player.audioTap)
        }

    /** The device follows the state the pack reports, not the press. */
    @Test
    fun `the pack's transport starts, pauses and stops the output`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            val player = playerFor(clientFor(), out = out)
            advanceUntilIdle()

            pack.says(Transport.Playing)
            assertTrue("Playing starts the output", out.playing)
            assertFalse("Playing leaves the output unpaused", out.held)
            assertNotNull("the output renders the pack's samples", out.samples)

            pack.says(Transport.Paused)
            assertTrue("Paused pauses the output", out.held)

            player.stop()
            pack.says(Transport.Stopped)
            assertFalse("a pressed stop stops the output at once", out.playing)
        }

    /**
     * A pack whose song broke off keeps reporting Playing with `connecting` set. The device is
     * paused for that time and resumed afterwards, without a stop in between.
     */
    @Test
    fun `a pack reconnecting mid-song pauses the output until connecting clears`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            playerFor(clientFor(), out = out)
            advanceUntilIdle()
            pack.says(Transport.Playing)

            pack.listener?.onState(PackState(transport = Transport.Playing.name, connecting = true))
            assertTrue("connecting pauses the output", out.held)
            assertTrue("connecting keeps the output started", out.playing)

            pack.says(Transport.Playing)
            assertFalse("the output resumes when connecting clears", out.held)
            assertEquals(
                "the output is not stopped in between: ${out.did}",
                listOf("start", "resume", "pause", "resume"),
                out.did,
            )
        }

    /**
     * The pack reports a finished queue when it has decoded the end of it, and that audio is
     * still in the pipe, the chain and the device's buffer. Guards against the device being
     * stopped before it has played out.
     */
    @Test
    fun `a queue that ends by itself stops the output after a delay`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            playerFor(clientFor(), out = out)
            advanceUntilIdle()

            pack.says(Transport.Playing)
            pack.says(Transport.Stopped)

            assertTrue("the output keeps playing right after the queue ends", out.playing)
            advanceUntilIdle()
            assertFalse("the output stops after the delay", out.playing)
        }

    /**
     * A state sent before the pack heard the stop arrives after the press. Guards against that
     * state turning the pressed stop into the delayed stop of a finished queue.
     */
    @Test
    fun `a Playing state after a pressed stop still stops the output at once`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            val player = playerFor(clientFor(), out = out)
            advanceUntilIdle()
            pack.says(Transport.Playing)

            player.stop()
            pack.says(Transport.Playing)
            pack.says(Transport.Stopped)

            assertFalse("the pressed stop stops the output at once", out.playing)
        }

    /**
     * A stop drops the stretch in the pipe, so the play that follows reads a new pipe. A JVM
     * pipe never blocks, so the test shows the next read being on the next stretch.
     */
    @Test
    fun `a stop drops the stretch in the pipe`() =
        runTest {
            val pack = FakePack()
            val stretches = ArrayDeque(listOf(stretch(1, 2, 3, 4), stretch(5, 6, 7, 8)))
            pack.audio = { stretches.removeFirstOrNull() }
            app.install(pack)
            val out = FakeOut()
            val player = playerFor(renderingClient(), out = out)
            advanceUntilIdle()
            pack.says(Transport.Playing)
            val samples = out.samples ?: error("a playing output has samples to read")
            val buffer = ByteArray(8)
            samples.read(buffer)

            player.stop()
            pack.says(Transport.Stopped)

            assertEquals(4, samples.read(buffer))
            assertEquals("the read after a stop comes from the next stretch", listOf<Byte>(5, 6, 7, 8), buffer.take(4))
        }

    /** The equalizer, the balance and the rack belong to the player's chain, which the output holds. */
    @Test
    fun `the equalizer, the balance, the rack and the plug-ins go to the output`() =
        runTest {
            app.install(FakePack())
            val out = FakeOut()
            val player = playerFor(clientFor(), out = out)
            advanceUntilIdle()

            player.setEqualizer(EqSettings.FLAT)
            player.setBalance(-1f)
            player.setDsp(RackSettings())
            player.setPlugins(listOf("reverb.js"))

            assertEquals(EqSettings.FLAT, out.equalizer)
            assertEquals(-1f, out.balance ?: 0f, 0.0001f)
            assertEquals(RackSettings(), out.rack)
            assertEquals(listOf("reverb.js"), out.plugins)
            assertEquals(
                Capabilities(
                    canSeek = true,
                    canEditQueue = true,
                    hasEqualizer = true,
                    hasBalance = true,
                    hasDsp = true,
                    canAttenuate = true,
                ),
                player.capabilities,
            )
        }

    /**
     * In device mode the slider is the phone's volume and the chain renders at full gain; in
     * app mode the slider is the chain's gain. The pack is told the level in both.
     */
    @Test
    fun `the output attenuates only in app volume mode`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            val player = playerFor(clientFor(), out = out)
            advanceUntilIdle()

            player.setVolume(0.4f)
            assertEquals("device mode leaves the output at full gain", 1f, out.volume, 0.0001f)

            player.setVolumeMode(VolumeMode.APP)
            assertEquals(0.4f, out.volume, 0.0001f)

            player.setVolumeMode(VolumeMode.DEVICE)
            assertEquals(1f, out.volume, 0.0001f)

            advanceUntilIdle()
            assertTrue("the pack is told the level in either mode", "setVolume 0.4" in pack.heard)
        }

    /**
     * A temporary interruption pauses the device at once and tells the pack to pause. When it
     * ends, the pack is told to play.
     */
    @Test
    fun `a temporary interruption pauses the pack and the output, and its end plays again`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            playerFor(clientFor(), out = out)
            advanceUntilIdle()
            pack.says(Transport.Playing)

            out.interruptions?.invoke(AudioOut.Interruption.PAUSE_FOR_NOW)
            advanceUntilIdle()

            assertTrue("the interruption pauses the output at once", out.held)
            assertEquals("pause", pack.heard.last())

            pack.says(Transport.Paused)
            out.interruptions?.invoke(AudioOut.Interruption.RESUME)
            advanceUntilIdle()

            assertEquals("play", pack.heard.last())
        }

    /** A permanent interruption, such as headphones unplugged, is not undone by a resume. */
    @Test
    fun `a permanent pause is not undone by a resume`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val out = FakeOut()
            playerFor(clientFor(), out = out)
            advanceUntilIdle()
            pack.says(Transport.Playing)

            out.interruptions?.invoke(AudioOut.Interruption.PAUSE)
            advanceUntilIdle()
            pack.says(Transport.Paused)
            out.interruptions?.invoke(AudioOut.Interruption.RESUME)
            advanceUntilIdle()

            assertEquals("pause", pack.heard.last())
        }

    @Test
    fun `without a pack the player stays stopped and keeps its queue`() =
        runTest {
            val client = clientFor()
            val player = playerFor(client)

            player.play()
            player.seekTo(9_000)
            player.setVolume(0.3f)
            advanceUntilIdle()

            assertEquals(Transport.Stopped, player.state.value.transport)
            assertEquals("the player keeps the rows it was built with", rows, player.state.value.queue)
            assertEquals(Capabilities(canSeek = false), player.capabilities)
        }

    /**
     * A pack's process can end and come back while a player is built on it. Guards against the
     * new process never being told to listen, which leaves the player without state.
     */
    @Test
    fun `a pack whose process came back is listened to again`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()

            // the pack's process goes, and a new one answers the next bind
            val signedInAgain = FakePack()
            app.boundToPack().onServiceDisconnected(null)
            app.install(signedInAgain)

            player.play()
            advanceUntilIdle()
            signedInAgain.listener?.onState(PackState(transport = Transport.Playing.name, positionMs = 4_242))

            assertEquals("the press binds again and reaches the new process", "play", signedInAgain.heard.last())
            assertNotNull("the new process has a listener", signedInAgain.listener)
            assertEquals(Transport.Playing, player.state.value.transport)
            assertEquals(4_242L, player.state.value.positionMs)
        }

    /** The kbps and kHz readouts are part of the pack's state, so they arrive from the new process too. */
    @Test
    fun `the stream readouts arrive from a pack whose process came back`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()

            val signedInAgain = FakePack()
            app.boundToPack().onServiceDisconnected(null)
            app.install(signedInAgain)
            player.play()
            advanceUntilIdle()

            signedInAgain.listener?.onState(
                PackState(transport = Transport.Playing.name, streamBitrateKbps = 320, streamSampleRateKhz = 44),
            )

            assertEquals(320, player.state.value.streamBitrateKbps)
            assertEquals(44, player.state.value.streamSampleRateKhz)
        }

    /** A released player's listener is not registered on a later binding. */
    @Test
    fun `a pack that came back is not given a released player's listener`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()
            player.release()
            advanceUntilIdle()

            val signedInAgain = FakePack()
            app.boundToPack().onServiceDisconnected(null)
            app.install(signedInAgain)
            client.account()
            advanceUntilIdle()

            assertNull(signedInAgain.listener)
        }

    @Test
    fun `release removes the listener and tells the pack`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()

            player.release()
            advanceUntilIdle()

            assertNull(pack.listener)
            assertEquals(listOf("stopListening", "release"), pack.heard.takeLast(2))
        }

    @Test
    fun `a press after release sends nothing`() =
        runTest {
            val pack = FakePack()
            app.install(pack)
            val client = clientFor()
            val player = playerFor(client)
            advanceUntilIdle()
            player.release()
            advanceUntilIdle()

            player.play()
            advanceUntilIdle()

            assertEquals(listOf("stopListening", "release"), pack.heard.takeLast(2))
        }
}
