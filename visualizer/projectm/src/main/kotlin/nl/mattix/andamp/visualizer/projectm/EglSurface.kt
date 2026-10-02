// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.projectm

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30

/**
 * An OpenGL ES 3 context bound to a [SurfaceTexture], driven by hand.
 *
 * `GLSurfaceView` is a `SurfaceView`, which punches a hole through this app's
 * translucent window, so the EGL setup is done here for a TextureView's texture.
 * It lives on the thread that constructed it: every method here, and every
 * projectM call, must run on that one thread.
 */
internal class EglSurface(
    texture: SurfaceTexture,
) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed: ${eglError()}" }

        val config = chooseConfig()
        context =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${eglError()}" }

        surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${eglError()}" }
    }

    /** Binds the context to the calling thread. Must be the thread that built this. */
    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed: ${eglError()}" }
    }

    /**
     * Writes 1 into the frame's alpha channel and leaves the colors alone.
     *
     * A MilkDrop preset writes whatever alpha its equations produce, and a
     * TextureView composites what it is given, so without this the home screen
     * shows through the visual. An alpha-less EGL config does not help:
     * `EGL_ALPHA_SIZE` is a minimum, the TextureView's own buffer is RGBA
     * either way, and `isOpaque` does not reach it.
     */
    fun makeOpaque() {
        GLES30.glColorMask(false, false, false, true)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glColorMask(true, true, true, true)
    }

    /** Posts the rendered frame. False means the surface went away and the loop should stop. */
    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
    }

    private fun chooseConfig(): EGLConfig {
        val attributes =
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                // projectM renders into its own framebuffers and needs neither
                EGL14.EGL_DEPTH_SIZE,
                0,
                EGL14.EGL_STENCIL_SIZE,
                0,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no ES 3 EGL config: ${eglError()}"
        }
        return checkNotNull(configs[0])
    }

    private fun eglError(): String = "0x${Integer.toHexString(EGL14.eglGetError())}"

    private companion object {
        /** EGL_OPENGL_ES3_BIT_KHR — not in EGL14, which stops at ES 2. */
        const val EGL_OPENGL_ES3_BIT = 0x0040
    }
}
