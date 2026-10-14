// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.androidauto

import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.car.app.OnDoneCallback
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
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

    /** The id of the player this app belongs to. */
    private val player = RemoteWindowLink.playerOf(car.packageName)

    /** A player that lends its window. */
    private fun installThePlayer() {
        val service = ComponentName(player, "RemoteWindowService")
        shadowOf(car.packageManager).apply {
            addServiceIfNotPresent(service)
            addIntentFilterForService(service, IntentFilter(RemoteWindowLink.ACTION))
        }
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
    fun `the button on the surface is passed on`() {
        installThePlayer()
        var asked = 0
        val button = buttonOf(WindowScreen(car, onButton = { asked++ }))

        button.onClickDelegate?.sendClick(object : OnDoneCallback {})
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, asked)
    }

    @Test
    fun `on the main window the button shows the list of songs`() {
        installThePlayer()

        assertEquals(R.drawable.ic_playlist, iconOf(buttonOf(WindowScreen(car))))
    }

    /** It lies over the X of the equalizer and the list, which is hard to hit beside it. */
    @Test
    fun `on another window the button is an X`() {
        installThePlayer()
        val screen = WindowScreen(car)

        screen.mainUp = false

        assertEquals(R.drawable.ic_close, iconOf(buttonOf(screen)))
    }

    private fun buttonOf(screen: WindowScreen): Action =
        (screen.onGetTemplate() as NavigationTemplate).actionStrip?.actions?.single() as Action

    private fun iconOf(button: Action): Int {
        val icon = checkNotNull(button.icon)
        assertEquals(CarIcon.TYPE_CUSTOM, icon.type)
        return checkNotNull(icon.icon).resId
    }

    @Test
    fun `without a player that lends its window the screen says what to do`() {
        val template = WindowScreen(car).onGetTemplate()

        assertTrue(template is MessageTemplate)
        assertEquals(
            "Install or update Andamp on this phone to see it here.",
            (template as MessageTemplate).message.toString(),
        )
    }

    /** An Andamp from before it could lend its window is installed, and has nothing to bind to. */
    @Test
    fun `a player too old to lend its window counts as none`() {
        shadowOf(car.packageManager).installPackage(PackageInfo().apply { packageName = player })

        assertTrue(WindowScreen(car).onGetTemplate() is MessageTemplate)
    }

    @Test
    fun `the screen lends to the Andamp this app's id is named after`() {
        installThePlayer()

        assertEquals(car.packageName.removeSuffix(".androidauto"), Player.reach(car)?.`package`)
    }

    /** A build with a second id belongs to the player with that id, and leaves the other alone. */
    @Test
    fun `an id names its player by what comes before the last word`() {
        assertEquals("nl.mattix.andamp", RemoteWindowLink.playerOf("nl.mattix.andamp.androidauto"))
        assertEquals("nl.mattix.andamp.dev", RemoteWindowLink.playerOf("nl.mattix.andamp.dev.androidauto"))
    }
}
