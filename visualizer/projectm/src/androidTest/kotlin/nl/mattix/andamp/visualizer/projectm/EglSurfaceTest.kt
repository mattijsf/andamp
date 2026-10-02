// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import android.graphics.SurfaceTexture
import android.opengl.GLES30
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The EGL path needs a real driver, so it is tested on a device. A detached
 * [SurfaceTexture] stands in for the TextureView's.
 */
@RunWith(AndroidJUnit4::class)
class EglSurfaceTest {
    @Test
    fun an_es3_context_comes_up_renders_and_releases() {
        val texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(SIZE, SIZE)
        val egl = EglSurface(texture)
        try {
            egl.makeCurrent()
            GLES30.glViewport(0, 0, SIZE, SIZE)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            assertTrue("eglSwapBuffers succeeds", egl.swapBuffers())
        } finally {
            egl.release()
            texture.release()
        }
    }

    /**
     * Guards against a see-through visual: a MilkDrop preset writes whatever
     * alpha its equations produce, and a TextureView composites what it is given.
     */
    @Test
    fun makeOpaque_fills_alpha_and_leaves_the_colors_alone() {
        val texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(SIZE, SIZE)
        val egl = EglSurface(texture)
        try {
            egl.makeCurrent()
            GLES30.glViewport(0, 0, SIZE, SIZE)
            // a frame a preset could have left behind: colored, fully transparent
            GLES30.glClearColor(RED, GREEN, BLUE, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

            egl.makeOpaque()

            val pixel = java.nio.ByteBuffer.allocateDirect(4)
            GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            assertEquals(0xFF, pixel.get(3).toInt() and 0xFF)
            assertEquals(0xFF, pixel.get(0).toInt() and 0xFF) // red stayed red
            assertEquals(0x00, pixel.get(1).toInt() and 0xFF)
            assertEquals(0x00, pixel.get(2).toInt() and 0xFF)
        } finally {
            egl.release()
            texture.release()
        }
    }

    /** Releasing twice can happen in a teardown race; it must not throw. */
    @Test
    fun releasing_twice_is_harmless() {
        val texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(SIZE, SIZE)
        val egl = EglSurface(texture)
        egl.release()
        egl.release()
        texture.release()
    }

    private companion object {
        const val SIZE = 64
        const val RED = 1f
        const val GREEN = 0f
        const val BLUE = 0f
    }
}
