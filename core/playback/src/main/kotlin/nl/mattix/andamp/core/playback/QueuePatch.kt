// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.playback

import nl.mattix.andamp.core.model.Track

/**
 * Metadata arriving for entries that are already in the queue: match on [Track.id], take
 * the patch's metadata, and keep the entry's own [Track.uri] whatever the patch says, so
 * that a metadata update cannot change what is playing.
 */
object QueuePatch {
    fun apply(
        queue: List<Track>,
        patched: List<Track>,
    ): List<Track> {
        if (patched.isEmpty() || queue.isEmpty()) return queue
        val byId = patched.associateBy { it.id }
        return queue.map { entry -> byId[entry.id]?.copy(uri = entry.uri) ?: entry }
    }
}
