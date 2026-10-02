// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import android.graphics.SurfaceTexture
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engine on a real GL context. libprojectM renders with whatever context is
 * current, so the test owns one for the whole method, as the render thread
 * does for its loop.
 */
@RunWith(AndroidJUnit4::class)
class ProjectMEngineTest {
    private lateinit var texture: SurfaceTexture
    private lateinit var egl: EglSurface
    private val engine = ProjectMEngine()

    @Before
    fun makeContextCurrent() {
        texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(WIDTH, HEIGHT)
        egl = EglSurface(texture)
        egl.makeCurrent()
    }

    @After
    fun releaseContext() {
        engine.destroy()
        egl.release()
        texture.release()
    }

    @Test
    fun a_created_engine_renders_the_idle_preset_without_a_gl_error() {
        engine.create(WIDTH, HEIGHT, MESH, MESH, FPS)
        assertTrue(engine.isAlive)

        // no preset was loaded: projectM falls back to its built-in idle preset
        repeat(FRAMES) {
            engine.renderFrame()
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        }
        assertTrue("the idle preset draws a frame that is not blank", framebufferIsNotBlank())
    }

    @Test
    fun silent_pcm_is_accepted_and_does_not_stall_the_frame() {
        engine.create(WIDTH, HEIGHT, MESH, MESH, FPS)
        val silence = FloatArray(engine.maxSamples.coerceAtMost(MAX_PCM))
        repeat(FRAMES) {
            engine.addPcmFloat(silence)
            engine.renderFrame()
        }
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
    }

    @Test
    fun destroy_is_idempotent_and_leaves_the_engine_dead() {
        engine.create(WIDTH, HEIGHT, MESH, MESH, FPS)
        engine.destroy()
        engine.destroy()
        assertFalse(engine.isAlive)
    }

    /** Reads the rendered pixels back; the idle preset is never a flat black frame. */
    private fun framebufferIsNotBlank(): Boolean {
        val pixels = java.nio.ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4)
        GLES30.glReadPixels(0, 0, WIDTH, HEIGHT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        return (0 until pixels.capacity()).any { pixels.get(it).toInt() and 0xFF != 0 }
    }

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 128
        const val MESH = 24
        const val FPS = 60
        const val FRAMES = 10
        const val MAX_PCM = 1024
    }
}
