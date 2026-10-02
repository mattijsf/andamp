// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Whose turn it is to have a decoder: one track at a time, across every track one backend
 * opens.
 *
 * A gapless advance opens the next track seconds before the current one ends. Without a
 * turn that means two `MediaCodec` instances alive at once, and a phone with one hardware
 * decoder instance for a format fails to create the second. The network part of opening a
 * track needs no turn. The codec waits here until the track before it has released its
 * own, which it does when it has decoded its last frame, while its tail is still buffered.
 *
 * There is one turn per backend, so a backend that was not released cannot hold the turn
 * of the backend that replaced it.
 *
 * The turn is keyed by its holder, so a give from anybody who does not hold it does
 * nothing. `StreamPcm.release` relies on that when it gives the turn for a decode thread
 * that is stuck.
 *
 * It uses nothing from Android, so the fake decoder in the JVM tests takes turns through
 * it too.
 */
internal class DecoderTurn {
    private val lock = ReentrantLock()

    /** Signalled when the turn is given, or when a waiter may have been let go of. */
    private val changed = lock.newCondition()

    /** Who has the decoder, or null when nobody does. Under [lock]. */
    private var holder: Any? = null

    /** Takes the turn if nobody has it, without waiting; answers whether [owner] holds it now. */
    fun tryTake(owner: Any): Boolean =
        lock.withLock {
            if (holder == null) holder = owner
            holder === owner
        }

    /**
     * Waits for the turn and takes it; false when [abandoned] became true first.
     *
     * [abandoned] is asked before the first wait and after every wake, and whoever makes
     * it true calls [wake], so a track released while it waits leaves at once.
     */
    fun take(
        owner: Any,
        abandoned: () -> Boolean,
    ): Boolean =
        lock.withLock {
            while (holder != null && holder !== owner && !abandoned()) changed.await()
            val wanted = !abandoned()
            if (wanted) holder = owner
            wanted
        }

    /** [owner] has released its decoder; does nothing when [owner] does not hold the turn. */
    fun give(owner: Any) =
        lock.withLock {
            if (holder === owner) {
                holder = null
                changed.signalAll()
            }
        }

    /** Whoever is waiting looks again at whether they still want the turn; see [take]. */
    fun wake() = lock.withLock { changed.signalAll() }
}
