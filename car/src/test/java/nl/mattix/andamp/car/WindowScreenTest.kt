// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.car

import android.content.pm.PackageInfo
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.core.remotewindow.RemoteWindowLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The one screen the car is shown, with and without the player on the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WindowScreenTest {
    private val car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())

    private fun installThePlayer() {
        shadowOf(car.packageManager).installPackage(PackageInfo().apply { packageName = RemoteWindowLink.PLAYER })
    }

    @Test
    fun `with the player on the phone the car is given its surface`() {
        installThePlayer()

        val template = WindowScreen(car).onGetTemplate()

        assertTrue(template is NavigationTemplate)
    }

    /** Without the button, Android Auto reports nothing that happens on the surface. */
    @Test
    fun `the surface's template carries the button that lets taps through`() {
        installThePlayer()

        val template = WindowScreen(car).onGetTemplate() as NavigationTemplate

        assertEquals(listOf(Action.PAN), template.mapActionStrip?.actions)
    }

    @Test
    fun `without the player the screen says so`() {
        val template = WindowScreen(car).onGetTemplate()

        assertTrue(template is MessageTemplate)
        assertEquals("Andamp is not installed on this phone.", (template as MessageTemplate).message.toString())
    }
}
