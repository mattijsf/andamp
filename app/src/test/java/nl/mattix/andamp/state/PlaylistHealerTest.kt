// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.net.Uri
import nl.mattix.andamp.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Re-finding rows whose uri stopped opening. A document uri dies with its
 * grant and cannot be revived, but the file is usually still on the device
 * under a library uri that needs no grant at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistHealerTest {
    private val document =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.mp3"
    private val library = "content://media/external/audio/media/42"

    private fun track(
        uri: String?,
        durationMs: Long = 200_000,
        id: String = "t",
    ) = Track(id, "artist", "title", durationMs, uri = uri)

    @Test
    fun `a dead row is replaced by its library equivalent`() {
        val healed =
            PlaylistHealer.heal(
                listOf(track(document)),
                playable = { false },
                lookUp = { name, _ -> if (name == "song.mp3") Uri.parse(library) else null },
            )

        assertEquals(library, healed.single().uri)
        assertEquals("the healed row keeps its title", "title", healed.single().title)
    }

    @Test
    fun `a row that still plays is left completely alone`() {
        var lookups = 0
        val healed =
            PlaylistHealer.heal(
                listOf(track(document)),
                playable = { true },
                lookUp = { _, _ ->
                    lookups++
                    Uri.parse(library)
                },
            )

        assertEquals(document, healed.single().uri)
        assertEquals("a working row is not looked up", 0, lookups)
    }

    @Test
    fun `a dead row the library cannot place is kept, not dropped`() {
        val healed = PlaylistHealer.heal(listOf(track(document)), playable = { false }, lookUp = { _, _ -> null })

        assertEquals(1, healed.size)
        assertEquals("an unplaced dead row keeps its uri", document, healed.single().uri)
    }

    @Test
    fun `the name to search for is the file's, decoded`() {
        assertEquals("song.mp3", PlaylistHealer.displayNameOf(track(document)))
        assertEquals("a b.mp3", PlaylistHealer.displayNameOf(track("content://x/document/a%20b.mp3")))
        assertNull("a uri with no filename gives nothing to search for", PlaylistHealer.displayNameOf(track("content://x/42")))
        assertNull(PlaylistHealer.displayNameOf(track(null)))
    }

    @Test
    fun `the duration is passed on to the lookup`() {
        var seen = 0L
        PlaylistHealer.heal(listOf(track(document, durationMs = 123_456)), playable = { false }, lookUp = { _, d ->
            seen = d
            null
        })

        assertEquals(123_456L, seen)
    }

    @Test
    fun `what heal found is expressed as replacements, keyed by track`() {
        val original = listOf(track(document, id = "one"), track(null, id = "mock"))
        val healed = listOf(track(library, id = "one"), track(null, id = "mock"))

        assertEquals(mapOf("one" to library), PlaylistHealer.replacements(original, healed))
        assertEquals(emptyMap<String, String>(), PlaylistHealer.replacements(original, original))
    }

    @Test
    fun `replacements apply to the queue as it is now, not the one that was read`() {
        // a track added while the lookups ran stays in the queue
        val added = track("content://media/external/audio/media/9", id = "added")
        val live = listOf(track(document, id = "one"), added)

        val updated = PlaylistHealer.applyTo(live, mapOf("one" to library))

        assertEquals(listOf(library, added.uri), updated.map { it.uri })
    }

    @Test
    fun `a replacement for a track that has since been removed is dropped`() {
        val live = listOf(track(document, id = "other"))

        assertEquals(live, PlaylistHealer.applyTo(live, mapOf("gone" to library)))
    }
}
