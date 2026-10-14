// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.baselineprofile

import android.graphics.Point
import android.view.WindowInsets
import android.view.WindowManager
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlin.math.roundToInt

const val PACKAGE = "nl.mattix.andamp"

/** How long a screen or a menu row may take to appear. */
private const val APPEARS_MS = 5_000L

/** How long the first-launch dialog may take, which waits for the skin to be decoded. */
private const val WELCOME_MS = 3_000L

/** Flings each way through the Preferences list: one stops short of the far end on a tall screen. */
private const val FLINGS = 3

/** Taps on the options button before the journey gives up on the main menu. */
private const val TAPS = 3

/** How long one tap on the options button has to open the menu. */
private const val MENU_MS = 2_000L

/** Opens the app from the launcher and waits for its first frame. */
fun MacrobenchmarkScope.startPlayer() {
    startActivityAndWait()
}

/** Answers the first-launch dialog, when it is there, by keeping the skin on screen. */
fun MacrobenchmarkScope.keepTheSkin() {
    device.wait(Until.findObject(By.text("Keep this")), WELCOME_MS)?.click()
    device.wait(Until.gone(By.text("Keep this")), APPEARS_MS)
}

/** Closes and reopens the playlist with its button, then opens the library from the main menu and closes it. */
fun MacrobenchmarkScope.browseTheWindows() {
    val player = PlayerLayout(device)
    repeat(2) {
        device.click(player.playlistButton)
        device.waitForIdle()
    }
    openMainMenu(player)
    pick("Media Library")
    // the library is drawn on a canvas, so there is no row to wait for before back closes it
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}

/** Opens Preferences from the main menu, scrolls its list down and up, and goes back to the player. */
fun MacrobenchmarkScope.openPreferences() {
    openMainMenu(PlayerLayout(device))
    pick("Options")
    pick("Preferences...")
    checkNotNull(device.wait(Until.findObject(By.text("Preferences")), APPEARS_MS)) { "Preferences did not open" }
    val list = checkNotNull(device.wait(Until.findObject(By.scrollable(true)), APPEARS_MS)) { "Preferences has no list" }
    repeat(FLINGS) { list.fling(Direction.DOWN) }
    repeat(FLINGS) { list.fling(Direction.UP) }
    device.pressBack()
    device.waitForIdle()
}

/** Taps the options button until the main menu is open: a tap while the player recomposes can go unanswered. */
private fun MacrobenchmarkScope.openMainMenu(player: PlayerLayout) {
    repeat(TAPS) {
        device.click(player.optionsButton)
        if (device.wait(Until.hasObject(By.text("Media Library")), MENU_MS)) return
    }
    error("the options button at ${player.optionsButton} opened no menu")
}

/** Taps the menu row [text] and waits for the menu to close or move on to a submenu. */
private fun MacrobenchmarkScope.pick(text: String) {
    val row = checkNotNull(device.wait(Until.findObject(By.text(text)), APPEARS_MS)) { "the menu has no row \"$text\"" }
    row.click()
    device.wait(Until.gone(By.text(text)), APPEARS_MS)
}

private fun UiDevice.click(at: Point) = click(at.x, at.y)

/**
 * Where the main window's buttons are on this screen.
 *
 * The skinned windows are drawn on a canvas and have nothing UiAutomator can find, so the buttons
 * are tapped by position. The position is worked out as the app lays the windows out, upright and
 * at Normal Size: `playerScale` picks the largest whole scale at which the 275-pixel-wide stack of
 * main, equalizer and playlist (406 pixels high) fits under the status bar, and `ClassicStack`
 * centers it and puts the main window below the status bar and the top edge's system gestures.
 */
private class PlayerLayout(
    device: UiDevice,
) {
    private val insets =
        InstrumentationRegistry
            .getInstrumentation()
            .context
            .getSystemService(WindowManager::class.java)
            .maximumWindowMetrics.windowInsets
    private val statusBar = insets.getInsets(WindowInsets.Type.statusBars()).top
    private val top = maxOf(statusBar, insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).top)
    private val scale = minOf(device.displayWidth / MAIN_W, (device.displayHeight - statusBar) / STACK_H)
    private val left = (device.displayWidth.toFloat() / scale - MAIN_W) / 2

    /** The title bar's options button, which opens the main menu. */
    val optionsButton = at(10.5f, 7.5f)

    /** The PL button that shows and hides the playlist. */
    val playlistButton = at(253.5f, 64f)

    /** The point [x], [y] of the main window, in the skin's pixels, on the screen. */
    private fun at(
        x: Float,
        y: Float,
    ) = Point(((left + x) * scale).roundToInt(), top + (y * scale).roundToInt())

    private companion object {
        const val MAIN_W = 275
        const val STACK_H = 406
    }
}
