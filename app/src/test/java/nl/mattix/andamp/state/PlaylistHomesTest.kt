// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.core.player.PlayerFacade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * SAVE LIST and LOAD LIST each offer two homes for a playlist: Andamp's
 * library and a file. The tests assert two options on each side, in the same
 * order, each reaching what it names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistHomesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var state: WinampState
    private lateinit var lists: PlaylistLibrary
    private lateinit var ops: PlaylistFileOps
    private lateinit var backend: MockBackend

    /** Rows with a uri: the codec stores only rows that have one. */
    private val queue =
        (1..4).map { n ->
            nl.mattix.andamp.core.model
                .Track("t$n", "Artist $n", "Title $n", 200_000, uri = "content://media/audio/$n")
        }

    @Before
    fun setUp() {
        val dir = File(app.filesDir, "playlists").also { it.deleteRecursively() }
        lists = PlaylistLibrary(dir)
        state = WinampState()
        backend = MockBackend(queue, scope)
        ops =
            PlaylistFileOps(
                app,
                state,
                PlayerFacade(backend),
                PlaylistStore(app),
                scope,
                listsLibrary = lists,
                io = Dispatchers.Unconfined,
            )
        state.playlist = queue
    }

    private fun pick(label: String) {
        val sheet = requireNotNull(state.choiceSheet) { "a choice sheet is shown" }
        sheet.options.first { it.label == label }.onPick()
        state.choiceSheet = null
    }

    @Test
    fun `saving offers the library and a file, in that order`() {
        ops.saveList {}

        val sheet = requireNotNull(state.choiceSheet)
        assertEquals("Save playlist", sheet.title)
        assertEquals(listOf("Save in Andamp", "Export as file…"), sheet.options.map { it.label })
    }

    @Test
    fun `loading offers the same two homes, in the same order`() {
        ops.loadList {}

        val sheet = requireNotNull(state.choiceSheet)
        assertEquals("Load playlist", sheet.title)
        assertEquals(listOf("Open from Andamp", "Import from file…"), sheet.options.map { it.label })
    }

    @Test
    fun `saving in Andamp asks for a name and keeps the list under it`() {
        ops.saveList {}
        pick("Save in Andamp")

        val prompt = requireNotNull(state.namePrompt) { "a name prompt is shown" }
        prompt.onSubmit("Road trip")

        assertEquals(listOf("Road trip"), lists.list().map { it.name })
        assertEquals(queue.size, lists.load("Road trip")?.size)
    }

    @Test
    fun `exporting asks for a name and hands it to the document picker`() {
        var suggested: String? = null
        ops.saveList { name -> suggested = name }
        pick("Export as file…")

        requireNotNull(state.namePrompt).onSubmit("Road trip")

        assertEquals("Road trip.m3u", suggested)
    }

    @Test
    fun `an exported file holds the queue`() {
        val out = ByteArrayOutputStream()
        PlaylistStore(app).export(out, queue, currentIndex = 0)

        val written = out.toString()
        assertTrue("the export names the first track", written.contains(queue.first().title))
    }

    @Test
    fun `opening from Andamp lists what is saved here`() {
        lists.save("Road trip", queue.take(3))

        ops.loadList {}
        pick("Open from Andamp")

        val picker = requireNotNull(state.presetPicker) { "a picker of saved lists is shown" }
        assertEquals("Open playlist", picker.title)
        assertEquals(listOf("Road trip"), picker.entries.map { it.label })
    }

    @Test
    fun `opening a saved list plays it instead of the queue`() {
        lists.save("Road trip", queue.take(3))
        ops.loadList {}
        pick("Open from Andamp")

        requireNotNull(state.presetPicker).onConfirm(listOf("Road trip"))

        assertEquals(3, backend.state.value.queue.size)
    }

    @Test
    fun `with nothing saved the list says so rather than coming up blank`() {
        ops.loadList {}
        pick("Open from Andamp")

        val picker = requireNotNull(state.presetPicker)
        assertTrue(picker.entries.isEmpty())
        assertTrue(picker.emptyMessage.isNotEmpty())
    }

    @Test
    fun `importing from a file is the caller's picker, not a second sheet`() {
        var asked = 0
        ops.loadList { asked++ }
        pick("Import from file…")

        assertEquals(1, asked)
        assertNull("no second sheet is shown", state.choiceSheet)
    }

    @Test
    fun `a name typed empty still lands somewhere findable`() {
        ops.saveList {}
        pick("Save in Andamp")

        requireNotNull(state.namePrompt).onSubmit("   ")

        assertNotNull(lists.list().firstOrNull())
    }
}
