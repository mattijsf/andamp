// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import android.graphics.SurfaceTexture
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The playlist against real `.milk` files on disk, since projectM loads presets
 * by filesystem path.
 *
 * The presets are written by the test: preset packs carry their authors' terms
 * (NOTICE.md), and these are the minimum that parses.
 */
@RunWith(AndroidJUnit4::class)
class ProjectMPlaylistTest {
    private lateinit var texture: SurfaceTexture
    private lateinit var egl: EglSurface
    private lateinit var dir: File
    private val engine = ProjectMEngine()

    @Before
    fun setUp() {
        texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(WIDTH, HEIGHT)
        egl = EglSurface(texture)
        egl.makeCurrent()
        engine.create(WIDTH, HEIGHT, MESH, MESH, FPS)

        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        dir = File(cache, "preset-test-${System.nanoTime()}").apply { mkdirs() }
        writePreset("one.milk", zoom = "1.01")
        writePreset("two.milk", zoom = "0.99")
        File(dir, "nested").mkdirs()
        writePreset("nested/three.milk", zoom = "1.00")
        File(dir, "notes.txt").writeText("not a preset")
    }

    @After
    fun tearDown() {
        engine.destroy()
        egl.release()
        texture.release()
        dir.deleteRecursively()
    }

    @Test
    fun a_directory_of_presets_becomes_the_playlist_including_subdirectories() {
        assertEquals(3, engine.loadPresetDirectory(dir.absolutePath))
        assertEquals(3, engine.playlistSize)
    }

    @Test
    fun the_playlist_walks_forwards_and_backwards() {
        engine.loadPresetDirectory(dir.absolutePath)
        val first = engine.playlistPosition

        engine.playlistNext()
        val second = engine.playlistPosition
        assertNotEquals(first, second)

        engine.playlistPrevious()
        assertEquals(first, engine.playlistPosition)
    }

    @Test
    fun a_playlist_entry_names_the_file_it_came_from() {
        engine.loadPresetDirectory(dir.absolutePath)
        val names = (0 until engine.playlistSize).map { File(engine.playlistItem(it)!!).name }
        assertEquals(setOf("one.milk", "two.milk", "three.milk"), names.toSet())
    }

    @Test
    fun a_loaded_preset_renders_without_a_gl_error() {
        engine.loadPresetDirectory(dir.absolutePath)
        engine.setTextureSearchPaths(listOf(dir.absolutePath))
        repeat(FRAMES) {
            engine.renderFrame()
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        }
    }

    @Test
    fun the_playlist_can_be_read_out_in_order_and_jumped_into_by_index() {
        engine.loadPresetDirectory(dir.absolutePath)
        val paths = engine.playlistPaths()

        // the list a menu or a screen shows has to be the engine's own order,
        // because that is what the index means
        assertEquals(engine.playlistSize, paths.size)
        assertEquals(paths, (0 until engine.playlistSize).map { engine.playlistItem(it) })

        engine.playlistGoTo(2)
        assertEquals(2, engine.playlistPosition)
        assertEquals(paths[2], engine.playlistItem(engine.playlistPosition))

        engine.renderFrame()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    /**
     * projectm_playlist_set_position turns an out-of-range index into 0, so a
     * stale index would restart the pack. The engine refuses it.
     */
    @Test
    fun an_index_outside_the_playlist_is_refused_rather_than_clamped_to_the_first_preset() {
        engine.loadPresetDirectory(dir.absolutePath)
        engine.playlistGoTo(1)
        val before = engine.playlistPosition

        assertFalse(engine.playlistGoTo(engine.playlistSize + 10))
        assertFalse(engine.playlistGoTo(-1))

        assertEquals(before, engine.playlistPosition)
    }

    @Test
    fun shuffle_is_accepted_and_still_leaves_a_preset_playing() {
        engine.loadPresetDirectory(dir.absolutePath)
        engine.playlistSetShuffle(true)
        engine.playlistNext()
        assertNotNull(engine.playlistItem(engine.playlistPosition))
        engine.renderFrame()
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    @Test
    fun a_directory_with_no_presets_in_it_adds_nothing() {
        val empty = File(dir, "empty").apply { mkdirs() }
        assertEquals(0, engine.loadPresetDirectory(empty.absolutePath))
        assertEquals(0, engine.playlistSize)
        // and the engine keeps rendering: the idle preset is still there
        engine.renderFrame()
        assertTrue(engine.isAlive)
    }

    private fun writePreset(
        name: String,
        zoom: String,
    ) {
        File(dir, name).writeText(
            """
            [preset00]
            fRating=2.000000
            fDecay=0.960000
            zoom=$zoom
            per_frame_1=rot = rot + 0.01;
            """.trimIndent(),
        )
    }

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 128
        const val MESH = 24
        const val FPS = 60
        const val FRAMES = 5
    }
}
