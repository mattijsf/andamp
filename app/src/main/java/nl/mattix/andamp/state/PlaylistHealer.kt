// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.net.Uri
import nl.mattix.andamp.core.model.Track

/**
 * Re-finds playlist entries whose uri has stopped opening.
 *
 * A document uri stops working when its grant is released. The file is usually still on the
 * phone, so the library is asked for it by name: the same recording under a
 * `content://media/...` uri. The lookup rules are in [MediaStoreMatch].
 */
object PlaylistHealer {
    /** What a row needs before it can be re-found: a name to search for. */
    fun displayNameOf(track: Track): String? =
        track.uri
            ?.substringAfterLast('/')
            ?.let { Uri.decode(it) }
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it.contains('.') }

    /**
     * [tracks] with dead rows replaced by their library equivalents.
     *
     * [playable] says whether a uri still opens and [lookUp] finds a replacement; both are
     * injected so the rules can be tested without a content provider. Rows that still play
     * and rows with no replacement are returned unchanged.
     */
    fun heal(
        tracks: List<Track>,
        playable: (String) -> Boolean,
        lookUp: (name: String, durationMs: Long) -> Uri?,
    ): List<Track> =
        tracks.map { track ->
            val uri = track.uri
            when {
                uri == null || playable(uri) -> {
                    track
                }

                else -> {
                    val name = displayNameOf(track)
                    val found = name?.let { lookUp(it, track.durationMs) }
                    if (found != null) track.copy(uri = found.toString()) else track
                }
            }
        }

    /**
     * The uris [heal] found, keyed by track id.
     *
     * Healing does slow IO between reading the queue and writing it, and the listener can
     * edit the playlist meanwhile, so the result is a set of replacements to apply to the
     * queue as it is by then.
     */
    fun replacements(
        original: List<Track>,
        healed: List<Track>,
    ): Map<String, String> =
        original
            .zip(healed)
            .filter { (before, after) -> before.uri != after.uri }
            .mapNotNull { (_, after) -> after.uri?.let { after.id to it } }
            .toMap()

    /** [tracks] with any of [replacements] that still apply. */
    fun applyTo(
        tracks: List<Track>,
        replacements: Map<String, String>,
    ): List<Track> = tracks.map { track -> replacements[track.id]?.let { track.copy(uri = it) } ?: track }
}
