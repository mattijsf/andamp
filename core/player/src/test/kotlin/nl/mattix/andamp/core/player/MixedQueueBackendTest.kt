// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.model.BackendNotice
import nl.mattix.andamp.core.model.BackendState
import nl.mattix.andamp.core.model.EqSettings
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.core.model.VolumeMode
import nl.mattix.andamp.core.playback.PlaybackBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What the contract suite cannot see: which player holds which rows, and what happens at the
 * boundaries between them.
 *
 * Rows are named by the player that can open them: `a…` for one, `b…` for the other, `x…` for
 * a source no lane claims.
 */
class MixedQueueBackendTest {
    /**
     * A player that records what it was told, and whose fade keeps playing for [FADE_MS] and
     * then stops unless a play or a stop comes first.
     */
    private class Hands(
        val inner: MockBackend,
        private val scope: CoroutineScope,
    ) : PlaybackBackend by inner {
        val equalizers = mutableListOf<EqSettings>()
        var released = false

        /** How many times it was handed a whole new queue. */
        var queuesHanded = 0

        private var fade: Job? = null

        override fun setEqualizer(settings: EqSettings) {
            equalizers += settings
        }

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) {
            queuesHanded++
            inner.setQueue(tracks, startIndex)
        }

        override fun stopWithFadeout() {
            fade?.cancel()
            fade =
                scope.launch {
                    delay(FADE_MS)
                    inner.stop()
                }
        }

        override fun play() {
            fade?.cancel()
            inner.play()
        }

        override fun playAt(index: Int) {
            fade?.cancel()
            inner.playAt(index)
        }

        override fun stop() {
            fade?.cancel()
            inner.stop()
        }

        override fun release() {
            released = true
            inner.release()
        }
    }

    /**
     * A player that takes every row and plays none of them. It reports Playing and stops
     * 10 ms later, with the notice [says] when one is given. [tries] counts the calls to
     * `playAt`.
     */
    private class Unplayable(
        private val inner: MockBackend,
        private val scope: CoroutineScope,
        private val says: BackendNotice? = null,
    ) : PlaybackBackend by inner {
        var tries = 0
        private val reported = MutableStateFlow(inner.state.value)
        override val state: StateFlow<BackendState> = reported
        private var failing: Job? = null

        override fun setQueue(
            tracks: List<Track>,
            startIndex: Int,
        ) {
            inner.setQueue(tracks, startIndex)
            reported.value = inner.state.value
        }

        override fun playAt(index: Int) {
            tries++
            failing?.cancel()
            inner.playAt(index)
            reported.value = inner.state.value
            failing =
                scope.launch {
                    delay(10)
                    inner.stop()
                    // raised with a sequence number, as a real player raises one
                    reported.value = says?.let { inner.state.value.raising(it) } ?: inner.state.value
                }
        }

        override fun stop() {
            failing?.cancel()
            inner.stop()
            reported.value = inner.state.value
        }
    }

    /** A player whose reported state lags what it was told by [LAG_MS], like a player in another process. */
    private class Lagging(
        private val inner: MockBackend,
        scope: CoroutineScope,
    ) : PlaybackBackend by inner {
        private val reported = MutableStateFlow(inner.state.value)
        override val state: StateFlow<BackendState> = reported

        init {
            scope.launch {
                inner.state.collect {
                    delay(LAG_MS)
                    reported.value = it
                }
            }
        }
    }

    private fun row(
        id: String,
        seconds: Int = 2,
    ) = Track(id, "Artist", id.uppercase(), seconds * 1000L)

    private class Rig(
        val backend: MixedQueueBackend,
        val a: () -> Hands?,
        val b: () -> Hands?,
        val laneB: MixedQueueBackend.Lane,
    )

    private fun TestScope.rig(
        vararg ids: String,
        bCanBuild: () -> Boolean = { true },
        seed: Int = 7,
    ): Rig {
        var a: Hands? = null
        var b: Hands? = null
        val laneA =
            MixedQueueBackend.Lane(
                claims = { it.id.startsWith("a") },
                make = { hands().also { a = it } },
            )
        val laneB =
            MixedQueueBackend.Lane(
                claims = { it.id.startsWith("b") },
                make = { if (bCanBuild()) hands().also { b = it } else null },
            )
        val backend = MixedQueueBackend(listOf(laneA, laneB), ids.map { row(it) }, 0, backgroundScope, Random(seed))
        return Rig(backend, { a }, { b }, laneB)
    }

    private fun TestScope.hands() = Hands(MockBackend(emptyList(), backgroundScope), backgroundScope)

    private fun TestScope.unplayable(says: BackendNotice? = null) =
        Unplayable(MockBackend(emptyList(), backgroundScope), backgroundScope, says)

    /** The first lane opens `a…` rows, the second `b…` rows, with the players the test hands in. */
    private fun TestScope.composite(
        a: () -> PlaybackBackend?,
        b: () -> PlaybackBackend?,
        vararg rows: Track,
    ) = MixedQueueBackend(
        listOf(
            MixedQueueBackend.Lane({ it.id.startsWith("a") }, a),
            MixedQueueBackend.Lane({ it.id.startsWith("b") }, b),
        ),
        rows.toList(),
        0,
        backgroundScope,
        Random(seed = 7),
    )

    private fun TestScope.seconds(n: Int) {
        advanceTimeBy(n * 1000L + 1)
        runCurrent()
    }

    private val MixedQueueBackend.at get() = state.value.queue[state.value.currentIndex].id

    private val PlaybackBackend?.transport get() = this?.state?.value?.transport

    @Test
    fun `a stretch of one player's rows is handed to it as one queue`() =
        runTest {
            val rig = rig("a1", "a2", "b1", "a3")

            rig.backend.play()

            // one queue, so the player can go from one row to the next without a gap
            assertEquals(
                listOf("a1", "a2"),
                rig
                    .a()!!
                    .state.value.queue
                    .map { it.id },
            )
        }

    @Test
    fun `the end of a stretch hands over to the player of the next row`() =
        runTest {
            val rig = rig("a1", "a2", "b1")
            rig.backend.play()

            seconds(2)
            assertEquals("a2", rig.backend.at)
            // the mock player puts its own cursor back to the top at the end of its queue;
            // the handover must not follow it there
            seconds(2)

            assertEquals("b1", rig.backend.at)
            assertEquals(Transport.Playing, rig.backend.state.value.transport)
            assertEquals(Transport.Playing, rig.b().transport)
            assertEquals("the first player stops", Transport.Stopped, rig.a().transport)
        }

    /**
     * A lagging player still reports Stopped just after the press that started it. Guards
     * against that being read as a stop, which would show the end of the stretch as a stop.
     */
    @Test
    fun `a player that answers late still hands over at the end of its stretch`() =
        runTest {
            val late = Lagging(MockBackend(emptyList(), backgroundScope), backgroundScope)
            val backend = composite({ hands() }, { late }, row("b1"), row("a1"))

            backend.play()
            seconds(3)

            assertEquals("a1", backend.at)
            assertEquals(Transport.Playing, backend.state.value.transport)
        }

    @Test
    fun `a row no lane can open is stepped over`() =
        runTest {
            val rig = rig("a1", "x1", "a2")
            rig.backend.play()

            seconds(2)

            assertEquals("a2", rig.backend.at)
            assertEquals(Transport.Playing, rig.backend.state.value.transport)
        }

    @Test
    fun `previous onto a missing row goes further back`() =
        runTest {
            val rig = rig("a1", "x1", "a2")
            rig.backend.playAt(2)

            rig.backend.previous()

            assertEquals("a1", rig.backend.at)
        }

    @Test
    fun `a player that cannot be built is stepped over, and used once it can`() =
        runTest {
            var signedIn = false
            val rig = rig("b1", "a1", bCanBuild = { signedIn })

            rig.backend.play()
            assertEquals("a1", rig.backend.at)

            signedIn = true
            rig.backend.playAt(0)
            assertEquals("b1", rig.backend.at)
            assertEquals(Transport.Playing, rig.b().transport)
        }

    @Test
    fun `a press that finds nothing to play raises NothingPlayableHere`() =
        runTest {
            val rig = rig("x1", "x2")

            rig.backend.play()

            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertEquals(BackendNotice.NothingPlayableHere, rig.backend.state.value.notice)
        }

    @Test
    fun `a lane whose player cannot be built is asked once per press`() =
        runTest {
            // asking whether a player can be built can be a disk read and a native call
            var asked = 0
            val rig =
                rig(
                    "b1",
                    "b2",
                    "b3",
                    "b4",
                    "b5",
                    bCanBuild = {
                        asked++
                        false
                    },
                )

            rig.backend.play()

            assertEquals(BackendNotice.NothingPlayableHere, rig.backend.state.value.notice)
            assertEquals(1, asked)
        }

    @Test
    fun `a queue that ends on rows nobody can play stops without a notice`() =
        runTest {
            val rig = rig("a1", "x1")
            rig.backend.play()

            seconds(2)

            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertNull(rig.backend.state.value.notice)
        }

    @Test
    fun `the end of a queue over two players stops at its head`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.play()

            seconds(2)
            assertEquals("b1", rig.backend.at)
            seconds(2)

            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertEquals("the cursor returns to the head of the queue", "a1", rig.backend.at)
        }

    @Test
    fun `the equalizer set earlier reaches a player built later`() =
        runTest {
            val rig = rig("a1", "b1")
            val eq = EqSettings(enabled = true, preampDb = 3f, bandsDb = List(10) { 0f })
            rig.backend.setEqualizer(eq)
            rig.backend.play()

            seconds(2)

            assertEquals("b1", rig.backend.at)
            assertEquals(listOf(eq), rig.b()!!.equalizers)
        }

    @Test
    fun `a forgotten player stops and is released, and its rows are stepped over`() =
        runTest {
            var signedIn = true
            val rig = rig("b1", "a1", bCanBuild = { signedIn })
            rig.backend.play()
            val b = rig.b()!!

            signedIn = false
            rig.backend.forget(rig.laneB)

            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertTrue(b.released)
            assertEquals(
                "the forgotten player's rows stay in the queue",
                listOf("b1", "a1"),
                rig.backend.state.value.queue
                    .map { it.id },
            )
            rig.backend.playAt(0)
            assertEquals("the forgotten player's row is stepped over", "a1", rig.backend.at)
        }

    // --- sources that come and go while the player is running ---

    @Test
    fun `a lane added after construction plays its rows`() =
        runTest {
            // the player was built without this lane, so its rows are stepped over until
            // it is added
            var a: Hands? = null
            var c: Hands? = null
            val phone = MixedQueueBackend.Lane({ it.id.startsWith("a") }, { hands().also { a = it } })
            val backend = MixedQueueBackend(listOf(phone), listOf(row("c1"), row("a1")), 0, backgroundScope, Random(seed = 7))

            backend.play()
            assertEquals("a row no lane opens is stepped over", "a1", backend.at)

            backend.addLane(MixedQueueBackend.Lane({ it.id.startsWith("c") }, { hands().also { c = it } }))
            backend.playAt(0)

            assertEquals("c1", backend.at)
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals(Transport.Playing, c.transport)
            assertTrue(
                "the phone's player is not handed the c1 row",
                a!!
                    .state.value.queue
                    .none { it.id == "c1" },
            )
        }

    @Test
    fun `a lane removed while its row plays stops and is released, and its rows are stepped over`() =
        runTest {
            val rig = rig("b1", "a1")
            rig.backend.play()
            val b = rig.b()!!
            assertEquals(Transport.Playing, b.transport)

            rig.backend.removeLane(rig.laneB)

            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertTrue("the removed lane's player is released", b.released)
            rig.backend.playAt(0)
            assertEquals("the removed lane's row is stepped over", "a1", rig.backend.at)
            assertTrue(
                "the remaining lane is not handed the b1 row",
                rig
                    .a()!!
                    .state.value.queue
                    .none { it.id == "b1" },
            )
        }

    @Test
    fun `the phone's lane is not removed`() =
        runTest {
            var a: Hands? = null
            val phone = MixedQueueBackend.Lane({ it.id.startsWith("a") }, { hands().also { a = it } })
            val backend = MixedQueueBackend(listOf(phone), listOf(row("a1")), 0, backgroundScope)

            backend.removeLane(phone)
            backend.play()

            assertEquals(Transport.Playing, backend.state.value.transport)
            assertEquals(false, a!!.released)
        }

    @Test
    fun `shuffle across a mixed queue hands over one row at a time`() =
        runTest {
            val rig = rig("a1", "a2", "b1")
            rig.backend.setShuffle(true)

            rig.backend.playAt(0)

            assertEquals(
                listOf("a1"),
                rig
                    .a()!!
                    .state.value.queue
                    .map { it.id },
            )
            assertEquals(
                "the player's own shuffle stays off",
                false,
                rig
                    .a()!!
                    .state.value.shuffle,
            )
        }

    @Test
    fun `under shuffle the one row that can play is found`() =
        runTest {
            // guards against a walk that draws rows with repeats and misses the one
            // playable row; every seed has to find it
            repeat(20) { seed ->
                val ids = List(19) { "b$it" } + "a1"
                val rig = rig(*ids.toTypedArray(), bCanBuild = { false }, seed = seed)
                rig.backend.setShuffle(true)

                rig.backend.play()

                assertEquals("seed $seed", "a1", rig.backend.at)
                assertEquals("seed $seed", Transport.Playing, rig.backend.state.value.transport)
            }
        }

    @Test
    fun `a queue that is all one player's keeps that player's own repeat`() =
        runTest {
            val rig = rig("a1", "a2")
            rig.backend.setRepeat(true)
            rig.backend.play()

            assertEquals(
                true,
                rig
                    .a()!!
                    .state.value.repeat,
            )

            // a row from another lane ends the whole-queue arrangement: repeat is then
            // applied by the composite
            rig.backend.enqueue(listOf(row("b1")))
            assertEquals(
                false,
                rig
                    .a()!!
                    .state.value.repeat,
            )
            assertEquals(
                listOf("a1", "a2", "b1"),
                rig.backend.state.value.queue
                    .map { it.id },
            )
            assertEquals(Transport.Playing, rig.backend.state.value.transport)
        }

    @Test
    fun `rows added to the stretch at the end of the queue are appended`() =
        runTest {
            val rig = rig("b1", "a1", "a2")
            rig.backend.playAt(1)
            val a = rig.a()!!
            val handed = a.queuesHanded

            rig.backend.enqueue(listOf(row("a3")))

            assertEquals("an append hands the player no new queue", handed, a.queuesHanded)
            assertEquals(
                listOf("a1", "a2", "a3"),
                a.state.value.queue
                    .map { it.id },
            )
            // the stretch ends after the row that joined it
            seconds(2)
            seconds(2)
            assertEquals("a3", rig.backend.at)
            assertEquals(Transport.Playing, rig.backend.state.value.transport)
        }

    @Test
    fun `setQueue in a mixed queue re-cuts the stretch in hands and still hands over at its end`() =
        runTest {
            val rig = rig("a1", "a2", "b1")
            rig.backend.play()

            rig.backend.setQueue(listOf(row("a0"), row("a1"), row("a2"), row("b1")), 1)
            runCurrent()

            assertEquals(
                listOf("a0", "a1", "a2"),
                rig
                    .a()!!
                    .state.value.queue
                    .map { it.id },
            )
            assertEquals("a1", rig.backend.at)
            assertEquals(Transport.Playing, rig.backend.state.value.transport)
            seconds(2)
            seconds(2)
            assertEquals("b1", rig.backend.at)
            assertEquals(Transport.Playing, rig.b().transport)
        }

    @Test
    fun `the next player is opened before the stretch ends`() =
        runTest {
            var b: Hands? = null
            val backend = composite({ hands() }, { hands().also { b = it } }, row("a1", seconds = 20), row("b1"))
            backend.play()

            seconds(3)
            assertNull("the next player is not opened early in the row", b)
            seconds(3)

            assertTrue("the next player is opened while the row still plays", b != null)
            assertEquals(Transport.Stopped, b.transport)
            assertEquals("a1", backend.at)
        }

    @Test
    fun `repeat across players wraps from the last player back to the first`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.setRepeat(true)
            rig.backend.play()

            seconds(2)
            assertEquals("b1", rig.backend.at)
            seconds(2)

            assertEquals("a1", rig.backend.at)
            assertEquals(Transport.Playing, rig.backend.state.value.transport)
        }

    // --- where a stretch must not hand over, and where it must ---

    @Test
    fun `stop on a stretch's last row does not hand over`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.play()
            seconds(1)

            rig.backend.stop()
            seconds(2)

            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertEquals("a1", rig.backend.at)
            assertNotEquals(Transport.Playing, rig.b().transport)
        }

    @Test
    fun `a fade on a stretch's last row does not hand over`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.play()
            seconds(1)

            rig.backend.stopWithFadeout()
            seconds(3)

            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertEquals("a1", rig.backend.at)
            assertNotEquals(Transport.Playing, rig.b().transport)
        }

    @Test
    fun `play during a fade still hands over at the end of the stretch`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.play()
            seconds(1)

            rig.backend.stopWithFadeout()
            rig.backend.play()
            seconds(3)

            assertEquals("b1", rig.backend.at)
            assertEquals(Transport.Playing, rig.b().transport)
        }

    @Test
    fun `a fade cut short by a skip to another player does not stop the stretch after it`() =
        runTest {
            val rig = rig("a1", "b1", "a2")
            rig.backend.play()
            seconds(1)

            rig.backend.stopWithFadeout()
            rig.backend.next()
            assertEquals("b1", rig.backend.at)
            seconds(2)

            assertEquals("a2", rig.backend.at)
            assertEquals(Transport.Playing, rig.backend.state.value.transport)
        }

    @Test
    fun `a volume change in the moment a stretch ends still hands over`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.play()
            seconds(1)

            // a1's player stops at its end, and a volume change arrives before the
            // composite has heard of it
            rig.a()!!.inner.stop()
            rig.backend.setVolume(0.5f)
            runCurrent()

            assertEquals("b1", rig.backend.at)
            assertEquals(Transport.Playing, rig.b().transport)
        }

    // --- rows that will not play ---

    @Test
    fun `a row that will not open stops there under repeat`() =
        runTest {
            // guards against the failure being read as the end of a stretch, which would
            // retry the rows without end
            val broken = unplayable()
            val backend = composite({ broken }, { null }, row("a1"), row("b1"), row("a2"))
            backend.setRepeat(true)

            backend.play()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(1, broken.tries)
        }

    @Test
    fun `a row that will not open stops there under shuffle`() =
        runTest {
            val broken = unplayable()
            val backend = composite({ broken }, { null }, row("a1"), row("b1"), row("a2"))
            backend.setShuffle(true)

            backend.play()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(1, broken.tries)
        }

    @Test
    fun `players that each give up are tried for one pass of the queue`() =
        runTest {
            val a = unplayable(BackendNotice.SourceCannotPlay)
            val b = unplayable(BackendNotice.SourceCannotPlay)
            val backend = composite({ a }, { b }, row("a1"), row("b1"))
            backend.setRepeat(true)

            backend.play()
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
            assertTrue("each row is tried at most once: ${a.tries} and ${b.tries}", a.tries + b.tries <= 2)
        }

    @Test
    fun `a source that has given up is stepped past to another player's rows`() =
        runTest {
            val backend = composite({ hands() }, { unplayable(BackendNotice.SourceCannotPlay) }, row("b1"), row("b2"), row("a1"))

            backend.play()
            advanceTimeBy(100)
            runCurrent()

            assertEquals("a1", backend.at)
            assertEquals(Transport.Playing, backend.state.value.transport)
            assertNull("no notice is raised while the phone's own row plays", backend.state.value.notice)
        }

    @Test
    fun `a source that has given up raises its notice when nothing else is left`() =
        runTest {
            val backend = composite({ hands() }, { unplayable(BackendNotice.SourceCannotPlay) }, row("a1"), row("a2"), row("b1"))

            backend.play()
            seconds(4)
            advanceTimeBy(100)
            runCurrent()

            assertEquals(Transport.Stopped, backend.state.value.transport)
            assertEquals("b1", backend.at)
            assertEquals(BackendNotice.SourceCannotPlay, backend.state.value.notice)
        }

    // --- the slider ---

    @Test
    fun `in device mode the slider shows the phone's volume whichever player plays`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.setVolumeMode(VolumeMode.DEVICE)

            // the phone's volume moves before anything has played
            rig.a()!!.setVolume(0.3f)
            runCurrent()
            assertEquals("the slider shows the phone's volume before playback", 0.3f, rig.backend.state.value.volumeFraction)

            rig.backend.playAt(1)
            assertEquals("the slider shows the phone's volume for another player", 0.3f, rig.backend.state.value.volumeFraction)

            seconds(2)
            assertEquals(Transport.Stopped, rig.backend.state.value.transport)
            assertEquals("the slider keeps the phone's volume after playback ends", 0.3f, rig.backend.state.value.volumeFraction)
        }

    @Test
    fun `switching to app mode starts the slider at the phone player's level`() =
        runTest {
            val rig = rig("a1", "b1")
            // the other player is built, with a level of its own
            rig.backend.playAt(1)
            rig.backend.stop()
            rig.a()!!.setVolume(0.4f)
            runCurrent()

            rig.backend.setVolumeMode(VolumeMode.APP)

            assertEquals("the slider starts at the phone player's level", 0.4f, rig.backend.state.value.volumeFraction)
            assertEquals(
                "the other player is set to the slider's level",
                0.4f,
                rig
                    .b()!!
                    .state.value.volumeFraction,
            )
        }

    @Test
    fun `in app mode the slider keeps the listener's level when the phone's volume moves`() =
        runTest {
            val rig = rig("a1", "b1")
            rig.backend.setVolumeMode(VolumeMode.APP)
            rig.backend.setVolume(0.6f)

            // the phone's volume moves
            rig.a()!!.inner.setVolume(0.2f)
            runCurrent()

            assertEquals(0.6f, rig.backend.state.value.volumeFraction)
        }

    private companion object {
        const val FADE_MS = 500L
        const val LAG_MS = 50L
    }
}
