// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import nl.mattix.andamp.R
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.state.SkinKind
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.addModern
import nl.mattix.andamp.state.loadingModernSkin
import nl.mattix.andamp.ui.window.testViewModel
import nl.mattix.andamp.wal.WalGate
import nl.mattix.andamp.wal.WalRender
import nl.mattix.andamp.wal.WalSettings
import nl.mattix.andamp.wal.WalSharp
import nl.mattix.andamp.wal.switchModernSkinsOn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Skins section on Preferences > Player: where it sits and what it shows, and that its two
 * switches reach the player as the app wires them.
 *
 * A plain Application, because AndAmpApp reads both switches at startup on a thread that would race
 * the values the tests store.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the player's skins decode through BitmapFactory
@Config(sdk = [35], application = Application::class)
class ModernSkinSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Every player a test made; each is stopped after the test, so none follows the switch in the next one. */
    private val made = ArrayList<WinampViewModel>()

    /** The section as Preferences last filled it. */
    private lateinit var shown: ModernSkinPrefs

    @Before
    fun fromNothing() {
        WalGate.clear(app)
        WalSharp.forget(app)
        app
            .getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        app
            .getSharedPreferences("skins", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(app.filesDir, "skins").deleteRecursively()
    }

    @After
    fun tearDown() {
        stopPlayers()
        WalGate.clear(app)
        WalGate.forget()
        WalSharp.forget()
    }

    private fun stopPlayers() {
        made.forEach {
            it.skinOps.wal?.release(save = false)
            it.viewModelScope.cancel()
        }
    }

    private fun text(id: Int) = app.getString(id)

    private fun tag(value: String) = compose.onNodeWithTag(value)

    private val modernSwitch get() = tag("prefs.modern.switch")

    private val sharpSwitch get() = tag("prefs.sharp.switch")

    private fun SemanticsNodeInteraction.top() = performScrollTo().getUnclippedBoundsInRoot().top

    /** Preferences > Player in a scrolling column, as the page shows it. */
    private fun showPlayerPage(sections: PrefsSections) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) { PlayerPage(sections) }
        }
    }

    /** Preferences > Player with the section filled as Preferences fills it. */
    private fun showWiredPage() {
        compose.setContent {
            shown = modernSkinPrefsOf(LocalContext.current)
            Column(Modifier.verticalScroll(rememberScrollState())) { PlayerPage(PrefsSections(modern = shown)) }
        }
    }

    /** A player whose start has finished. */
    private fun startedPlayer(): WinampViewModel =
        testViewModel().also { vm ->
            made += vm
            await("the player has started") { vm.skin != null }
        }

    /** Lets the player's threads and main run until [condition] holds, for at most 20 s. */
    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 20_000
        do {
            // what the frame clock does in the app: snapshot writes reach the view model's collectors
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) {
                compose.waitForIdle()
                return
            }
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
        fail(what)
    }

    /** The switch's value a new process reads. The players stop first, so none follows the forget. */
    private fun storedSwitch(): Boolean {
        stopPlayers()
        WalGate.forget(app)
        return WalGate.read(app)
    }

    @Test
    fun `the Skins section comes last, reads off, holds the note, and has no sharp row while off`() {
        showPlayerPage(PrefsSections(palette = PalettePrefs(offered = true)))

        val heading = compose.onNodeWithText(text(R.string.prefs_player_skins_section))
        heading.performScrollTo().assertIsDisplayed()
        modernSwitch.performScrollTo().assertIsOff()
        compose.onNodeWithText(text(R.string.prefs_modern_skins_off)).assertIsDisplayed()
        val note = compose.onNodeWithText(text(R.string.prefs_modern_skins_note))
        note.performScrollTo().assertIsDisplayed()
        sharpSwitch.assertDoesNotExist()

        val colors = compose.onNodeWithText(text(R.string.prefs_player_colors_section)).top()
        assertTrue("after Colors, the section that came last before it", heading.top() > colors)
        assertTrue(modernSwitch.top() > tag("prefs.palette.switch").top())
        assertTrue("the note under the switch", note.top() > modernSwitch.top())
    }

    @Test
    fun `while on, Sharp pixels shows under the note`() {
        showPlayerPage(PrefsSections(modern = ModernSkinPrefs(enabled = true)))

        modernSwitch.performScrollTo().assertIsOn()
        compose.onNodeWithText(text(R.string.prefs_modern_skins_on)).assertIsDisplayed()
        sharpSwitch.performScrollTo().assertIsOff()
        compose.onNodeWithText(text(R.string.prefs_sharp_pixels_off)).assertIsDisplayed()
        val note = compose.onNodeWithText(text(R.string.prefs_modern_skins_note)).top()
        assertTrue(sharpSwitch.top() > note)
    }

    @Test
    fun `switching on turns modern skins on at once and stores it, and a modern skin picked next is added with no offer`() {
        val vm = startedPlayer()
        showWiredPage()
        modernSwitch.performScrollTo().assertIsOff()
        sharpSwitch.assertDoesNotExist()

        // no frame passes between the tap and the check
        compose.mainClock.autoAdvance = false
        modernSwitch.performClick()
        assertTrue("on in the frame of the tap", WalGate.on)
        compose.mainClock.autoAdvance = true

        modernSwitch.assertIsOn()
        sharpSwitch.performScrollTo().assertIsOff()

        val bytes = loadingModernSkin()
        val id = SkinLibrary(app).idFor(bytes)
        vm.skinOps.load(bytes.inputStream(), "Bento.wal") {}
        await("the picked modern skin is on") { vm.skinOps.currentId == id && vm.skinOps.wal != null }
        assertNull("no offer", vm.state.modernOffer)
        assertTrue(vm.state.skinEntries.any { it.id == id && it.kind == SkinKind.MODERN })

        assertTrue("stored", storedSwitch())
    }

    @Test
    fun `switching off while a modern skin is worn puts the last classic skin back and hides the modern rows`() {
        switchModernSkinsOn(app)
        val library = SkinLibrary(app)
        library.choose(BundledSkins.LIGHT.id)
        val modern = library.addModern(app, "Bento", loads = true)
        library.choose(modern)
        val vm = startedPlayer()
        await("the modern skin is worn") { vm.skinOps.currentId == modern && vm.skinOps.wal != null }
        showWiredPage()
        modernSwitch.performScrollTo().assertIsOn()
        sharpSwitch.assertExists()

        modernSwitch.performClick()

        assertFalse(WalGate.on)
        await("the last classic skin is back") { vm.skinOps.currentId == BundledSkins.LIGHT.id && vm.skinOps.wal == null }
        assertEquals(BundledSkins.LIGHT.id, library.currentId)
        await("the modern rows leave the Skins menu") { vm.state.skinEntries.none { it.kind == SkinKind.MODERN } }
        modernSwitch.assertIsOff()
        sharpSwitch.assertDoesNotExist()
        assertFalse("stored", storedSwitch())
    }

    @Test
    fun `off, on, off, on in one frame ends on, stored and shown`() {
        switchModernSkinsOn(app)
        showWiredPage()
        modernSwitch.performScrollTo().assertIsOn()

        compose.runOnUiThread {
            shown.onEnabled(false)
            shown.onEnabled(true)
            shown.onEnabled(false)
            shown.onEnabled(true)
        }

        modernSwitch.assertIsOn()
        assertTrue(WalGate.on)
        assertTrue("stored", storedSwitch())
        compose.waitForIdle()
        modernSwitch.assertIsOn()
    }

    @Test
    fun `after Turn on and add, the switch shows on`() {
        val vm = startedPlayer()
        showWiredPage()
        modernSwitch.performScrollTo().assertIsOff()
        val bytes = loadingModernSkin()
        val id = SkinLibrary(app).idFor(bytes)

        vm.skinOps.load(bytes.inputStream(), "Bento.wal") {}
        await("the offer shows") { vm.state.modernOffer != null }
        compose.runOnUiThread { vm.state.modernOffer!!.accept() }
        await("the skin is on") { vm.skinOps.currentId == id }

        assertTrue(WalGate.on)
        modernSwitch.assertIsOn()
        sharpSwitch.performScrollTo().assertIsOff()
    }

    @Test
    fun `the sharp switch sets the drawing, draws the skin again and is stored`() {
        switchModernSkinsOn(app)
        showWiredPage()
        val redraws = WalRender.redraws.intValue
        val stored = app.getSharedPreferences(WalSettings.PREFS, Context.MODE_PRIVATE)

        sharpSwitch.performScrollTo().assertIsOff().performClick()

        sharpSwitch.assertIsOn()
        compose.onNodeWithText(text(R.string.prefs_sharp_pixels_on)).assertIsDisplayed()
        assertTrue(WalSharp.on)
        assertTrue(WalRender.direct)
        assertEquals("drawn again once", redraws + 1, WalRender.redraws.intValue)
        assertTrue(stored.getBoolean(WalSharp.KEY, false))

        sharpSwitch.performClick()

        sharpSwitch.assertIsOff()
        assertFalse(WalSharp.on)
        assertFalse(WalRender.direct)
        assertEquals(redraws + 2, WalRender.redraws.intValue)
        assertFalse(stored.getBoolean(WalSharp.KEY, true))
    }

    @Test
    fun `opened before anything has read them, the switches show the stored values`() {
        WalGate.write(app, true)
        WalSharp.change(app, true)
        // a new process: nothing read yet
        WalGate.forget(app)
        WalSharp.forget(app)
        assertFalse(WalGate.on)

        showWiredPage()

        await("the stored values are read") { WalGate.on && WalSharp.on }
        modernSwitch.performScrollTo().assertIsOn()
        sharpSwitch.performScrollTo().assertIsOn()
    }
}
