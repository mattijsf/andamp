// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.app.Application
import android.graphics.SurfaceTexture
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.visualizer.projectm.ProjectMView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The visualizer's render thread only exists while somebody can see it.
 *
 * A TextureView keeps its surface across a stop, so nothing about the surface says the
 * app left the screen; without this gate the preset would keep rendering in the background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectMViewTest {
    private val textures = mutableListOf<SurfaceTexture>()

    @After
    fun tearDown() = textures.forEach { it.release() }

    private fun texture() = SurfaceTexture(0).also { textures += it }

    @Test
    fun `a surface arriving while off screen does not start rendering`() {
        val view = ProjectMView(ApplicationProvider.getApplicationContext<Application>())
        view.rendering = false

        view.onSurfaceTextureAvailable(texture(), SIZE, SIZE)

        assertFalse("no render thread starts off screen", renderThreadRunning())
    }

    @Test
    fun `going off screen stops the render thread`() {
        val view = ProjectMView(ApplicationProvider.getApplicationContext<Application>())
        view.onSurfaceTextureAvailable(texture(), SIZE, SIZE)

        view.rendering = false

        assertFalse("the render thread stops off screen", renderThreadRunning())
    }

    @Test
    fun `coming back without a surface is harmless`() {
        val view = ProjectMView(ApplicationProvider.getApplicationContext<Application>())

        view.rendering = false
        view.rendering = true

        assertFalse(renderThreadRunning())
    }

    private fun renderThreadRunning() = Thread.getAllStackTraces().keys.any { it.name == "projectM" && it.isAlive }

    private companion object {
        const val SIZE = 64
    }
}
