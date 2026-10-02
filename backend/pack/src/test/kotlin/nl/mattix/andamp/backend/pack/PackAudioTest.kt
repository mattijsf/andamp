// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import nl.mattix.andamp.core.packapi.PackApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pack's audio as [PackAudio] reads it: bytes arrive in order, the end of a pipe returns
 * zero and the next read opens another, and a pack that cannot be reached returns zero.
 *
 * The pack answers the binding directly and every dispatcher is unconfined, so everything
 * runs on the test's own thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackAudioTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun clientFor(): PackClient =
        PackClient(
            app,
            PackApi.ACTION_BIND,
            null,
            CoroutineScope(Dispatchers.Unconfined),
            Dispatchers.Unconfined,
            Dispatchers.Unconfined,
        )

    @Test
    fun `the bytes the pack writes into the pipe are the bytes read`() {
        val pack = FakePack()
        pack.audio = { stretch(1, 2, 3, 4) }
        app.install(pack)
        val audio = PackAudio(clientFor(), IDLE_MS)
        val buffer = ByteArray(8)

        val read = audio.read(buffer)

        assertEquals(4, read)
        assertEquals(listOf<Byte>(1, 2, 3, 4), buffer.take(4))
    }

    /**
     * The pack closes its end on a seek, a skip or a stop. Returning -1 there would end the
     * render loop.
     */
    @Test
    fun `the end of a pipe returns zero and the next read opens another`() {
        val pack = FakePack()
        val stretches = ArrayDeque(listOf(stretch(1, 2, 3, 4), stretch(5, 6, 7, 8)))
        pack.audio = { stretches.removeFirstOrNull() }
        app.install(pack)
        val audio = PackAudio(clientFor(), IDLE_MS)
        val buffer = ByteArray(8)

        audio.read(buffer)
        val ended = audio.read(buffer)
        val again = audio.read(buffer)

        assertEquals("the end of a stretch reads as zero", 0, ended)
        assertEquals("the next read uses the following pipe", 4, again)
        assertEquals(listOf<Byte>(5, 6, 7, 8), buffer.take(4))
        assertEquals(2, pack.opened)
    }

    /** What this side still holds when a pipe ends is from before the pack's jump. */
    @Test
    fun `the end of a stretch that gave samples calls dropped once`() {
        val pack = FakePack()
        val stretches = ArrayDeque(listOf(stretch(1, 2, 3, 4)))
        pack.audio = { stretches.removeFirstOrNull() }
        app.install(pack)
        var dropped = 0
        val audio = PackAudio(clientFor(), IDLE_MS) { dropped++ }
        val buffer = ByteArray(8)

        audio.read(buffer)
        assertEquals("dropped is not called while the stretch plays", 0, dropped)

        audio.read(buffer)
        assertEquals(1, dropped)

        audio.read(buffer)
        assertEquals("a pack with nothing to give does not call dropped", 1, dropped)
    }

    @Test
    fun `a pack that cannot be reached reads as zero`() {
        val audio = PackAudio(clientFor(), IDLE_MS)
        val buffer = ByteArray(8)

        assertEquals(0, audio.read(buffer))
        assertEquals("a second read also reads as zero", 0, audio.read(buffer))
    }

    /**
     * A pipe hands over whatever has arrived, and a frame is four bytes. A buffer ending
     * inside a frame would put every later sample in the wrong channel.
     */
    @Test
    fun `a read returns whole frames only`() {
        val pack = FakePack()
        pack.audio = { stretch(1, 2, 3, 4, 5, 6) }
        app.install(pack)
        val audio = PackAudio(clientFor(), IDLE_MS)

        assertEquals(4, audio.read(ByteArray(8)))
    }

    /** After [PackAudio.close], a read asks the pack for nothing. */
    @Test
    fun `a closed PackAudio opens no more pipes`() {
        val pack = FakePack()
        pack.audio = { stretch(1, 2, 3, 4) }
        app.install(pack)
        val audio = PackAudio(clientFor(), IDLE_MS)

        audio.close()

        assertEquals(0, audio.read(ByteArray(8)))
        assertEquals(0, pack.opened)
    }

    /** A pack whose descriptor says it does not hand over audio is not asked for any. */
    @Test
    fun `a pack that does not hand over audio is never asked for a pipe`() {
        val pack = FakePack(descriptor = describes(handsOverAudio = false))
        pack.audio = { stretch(1, 2, 3, 4) }
        app.install(pack)
        val audio = PackAudio(clientFor(), IDLE_MS)

        assertEquals(0, audio.read(ByteArray(8)))
        assertEquals(0, pack.opened)
    }

    private companion object {
        /** The wait with nothing to open, in milliseconds; short to keep the tests fast. */
        const val IDLE_MS = 1L
    }
}
