// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.backend.pack

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import nl.mattix.andamp.core.playback.PcmProvider
import java.io.IOException
import java.io.InputStream

/**
 * The pack's decoded audio, as the player's own audio chain reads it.
 *
 * The pack writes raw frames into a pipe and the player reads them here, so the equalizer, the
 * effect rack, the balance and the visualizer tap act on a pack's music as they act on a
 * file's. The frames have no header; their format is the one the descriptor names.
 *
 * A pipe ends when the pack drops what it decoded: on a seek, another track or a stop. This
 * then closes the stream and returns zero, so the render loop waits and the next read asks for
 * a pipe that starts where the music then is. Returning -1 would end the render loop.
 *
 * Every read happens on the render thread the output owns, so blocking is allowed here, and
 * the binder call that opens a pipe is made here too.
 */
internal class PackAudio(
    private val client: PackClient,
    /** How long a reader waits when there is nothing to open; see [waited]. */
    private val idleMs: Long = IDLE_MS,
    /**
     * Called when a stretch that carried samples ends. The chain and the device still hold
     * audio from before the pack's jump, and the output drops it.
     */
    private val dropped: () -> Unit = {},
) : PcmProvider {
    /**
     * The stretch being read, or null between two of them. Written by the render thread and by
     * whichever thread discards or closes; closing it wakes a read waiting in it.
     */
    @Volatile
    private var stream: InputStream? = null

    /**
     * Whether the open stream has handed over a frame.
     *
     * A pipe that ends without one is a pack with nothing to give (stopped, between tracks or
     * connecting), and the reader waits before asking again. A pipe that gave samples and then
     * ended is a jump, and the next pipe is asked for at once.
     */
    private var heard = false

    /** How many waits in a row have found nothing to open; see [waited]. Render thread only. */
    private var waits = 0

    /**
     * Set by [close]. A read that is still in progress then opens no further pipe, which the
     * pack would decode into with nobody reading.
     */
    @Volatile
    private var done = false

    override fun read(into: ByteArray): Int {
        // Whole frames only: what is read goes to the device as it stands, and a buffer that
        // ends inside a frame swaps the channels for everything after it. A buffer with no
        // room for one frame waits.
        val room = into.size - into.size % PcmProvider.BYTES_PER_FRAME
        val open = if (room == 0) null else stream ?: open()
        if (open == null) return waited()
        val read =
            try {
                open.read(into, 0, room)
            } catch (expected: IOException) {
                // the pack closed its end mid-read; treated as end of stream
                -1
            }
        if (read <= 0) return ended()
        heard = true
        // frames arrived, so the pack is answering and the back-off starts over
        waits = 0
        return whole(open, into, read)
    }

    /**
     * Tops a read up to a whole number of frames.
     *
     * A pipe hands over however many bytes have arrived, and a frame is
     * [PcmProvider.BYTES_PER_FRAME] of them, so the rest of a partial frame is read before
     * returning. A stream that ends inside a frame loses that frame.
     *
     * There is always room for the rest, because [read] asks for whole frames.
     */
    private fun whole(
        open: InputStream,
        into: ByteArray,
        read: Int,
    ): Int {
        var got = read
        while (got % PcmProvider.BYTES_PER_FRAME != 0) {
            val rest = PcmProvider.BYTES_PER_FRAME - got % PcmProvider.BYTES_PER_FRAME
            val more =
                try {
                    open.read(into, got, rest)
                } catch (expected: IOException) {
                    -1
                }
            if (more <= 0) return got - got % PcmProvider.BYTES_PER_FRAME
            got += more
        }
        return got
    }

    /**
     * The stretch is over: closes the pipe and returns zero, because the music has not ended.
     *
     * [dropped] is called only when the pipe had given samples. A pipe that ended without any
     * left nothing behind, and flushing the device repeatedly while a pack sits stopped is
     * audible.
     */
    private fun ended(): Int {
        val gave = heard
        drop()
        if (!gave) return waited()
        dropped()
        return 0
    }

    /**
     * Drops the stretch being read; the next read asks the pack for a new pipe. The pack takes
     * the read end closing as this player letting go of that stretch.
     */
    override fun discard() = drop()

    /** Done with the pack's audio for good. A read waiting in the pipe is woken by this. */
    fun close() {
        done = true
        drop()
    }

    /**
     * The read end of a pipe the pack has started writing into, or null when there is nothing
     * to open. Blocks on the render thread while the call goes to the pack's process. A pack
     * whose descriptor says it does not hand over audio is not asked.
     */
    private fun open(): InputStream? {
        if (done) return null
        if (client.known?.handsOverAudio == false) return null
        val pipe: ParcelFileDescriptor = runBlocking { client.openAudio() } ?: return null
        // closed while the pack was being asked: the pipe is closed unread, so that the pack
        // does not go on decoding into it
        if (done) {
            runCatching { pipe.close() }
            return null
        }
        heard = false
        return ParcelFileDescriptor.AutoCloseInputStream(pipe).also { stream = it }
    }

    /**
     * Nothing to read and nothing to open: sleeps and returns zero.
     *
     * Zero and not -1, because an absent pack is not the end of the music and a render loop
     * that is told the end stops reading. The sleep keeps the loop from spinning.
     *
     * The sleep doubles on each consecutive wait, up to [PATIENCE] doublings, because every
     * attempt to open is a bind or a binder call.
     */
    private fun waited(): Int {
        Thread.sleep(idleMs shl waits)
        if (waits < PATIENCE) waits++
        return 0
    }

    /** Closes the stretch. [heard] is left as it was: [ended] reads it, and [open] resets it. */
    private fun drop() {
        val open = stream ?: return
        stream = null
        runCatching { open.close() }
    }

    private companion object {
        /** The first wait when there is nothing to open, in milliseconds. */
        const val IDLE_MS = 20L

        /** How many times a wait may double: sixteen times [IDLE_MS] at most. */
        const val PATIENCE = 4
    }
}
