// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which source the library window shows ([LibrarySources.showing]) when the
 * pick and what can be shown differ: a sign-out, a source that is not
 * installed, a ticked entry picked again. The second source is made up for
 * the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibrarySourcesTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(name: String = "library-${System.nanoTime()}") = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val example = MusicSource("EXAMPLE", "Example")
    private val both = listOf(MusicSource.LOCAL, example)

    private fun sources(
        signedIn: Boolean = true,
        present: List<MusicSource> = both,
        store: LibrarySourceStore = LibrarySourceStore(prefs()),
    ) = LibrarySources(store, present, if (signedIn) setOf(example) else emptySet())

    @Test
    fun `nothing picked shows the phone`() {
        assertEquals(MusicSource.LOCAL, sources().showing)
    }

    @Test
    fun `a pick moves the window, and is remembered`() {
        val prefs = prefs()
        val first = sources(store = LibrarySourceStore(prefs))

        assertEquals(LibrarySources.Pick.Show, first.pick(example, open = false))
        assertEquals(example, first.showing)
        assertEquals(
            "a second launch shows the picked source",
            example,
            sources(store = LibrarySourceStore(prefs)).showing,
        )
    }

    @Test
    fun `a ticked entry closes its window, and the other entry moves it`() {
        val sources = sources()

        assertEquals(LibrarySources.Pick.Close, sources.pick(MusicSource.LOCAL, open = true))
        assertEquals(LibrarySources.Pick.Show, sources.pick(example, open = true))
        assertEquals(example, sources.showing)
    }

    @Test
    fun `a source with nobody signed in asks for the sign-in and moves nothing`() {
        val sources = sources(signedIn = false)

        assertEquals(LibrarySources.Pick.SignInFirst, sources.pick(example, open = true))
        assertEquals(MusicSource.LOCAL, sources.showing)
    }

    @Test
    fun `a sign-out takes the window back to the phone, and a sign-in brings it back`() {
        val sources = sources()
        sources.pick(example, open = false)

        sources.signedOut(example)
        assertEquals("a sign-out shows the phone", MusicSource.LOCAL, sources.showing)

        sources.signedIn(example)
        assertEquals(example, sources.showing)
    }

    @Test
    fun `a source that is not installed shows the phone whatever was picked before`() {
        val prefs = prefs()
        sources(store = LibrarySourceStore(prefs)).pick(example, open = false)

        val phoneOnly = sources(present = listOf(MusicSource.LOCAL), signedIn = false, store = LibrarySourceStore(prefs))

        assertEquals(MusicSource.LOCAL, phoneOnly.showing)
        assertEquals(LibrarySources.Pick.Nothing, phoneOnly.pick(example, open = false))
    }

    @Test
    fun `the reach is reported at once and on every sign-in and sign-out`() {
        val told = mutableListOf<SourceReach>()
        val sources = LibrarySources(LibrarySourceStore(prefs()), both, emptySet(), onReach = { told += it })

        sources.signedIn(example)
        sources.signedOut(example)

        assertEquals(
            listOf(SourceAbsence.SIGNED_OUT, null, SourceAbsence.SIGNED_OUT),
            told.map { it.absence(example) },
        )
    }

    @Test
    fun `a source this build has never heard of reads back as the phone`() {
        val prefs = prefs()
        prefs.edit().putString("source", "SOMETHING_ELSE").commit()

        assertEquals(MusicSource.LOCAL, sources(store = LibrarySourceStore(prefs)).showing)
    }
}
