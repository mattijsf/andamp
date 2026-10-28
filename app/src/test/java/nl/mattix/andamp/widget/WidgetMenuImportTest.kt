// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.MainActivity
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.ui.Screen
import nl.mattix.andamp.wal.WalGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Load skin... on the widget's menu: after the picker answers, the window stays while the picked
 * file loads, so a notice or an offer about it has somewhere to show, and closes once it is
 * answered.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WidgetMenuImportTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /**
     * The player the menu acts on, with a mock backend, placed where the menu looks for it. A
     * field, so it is in place before the rule starts the menu.
     */
    private val player: WinampViewModel =
        ViewModelProvider(
            AppViewModels,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") // the provider asks only for WinampViewModel here
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WinampViewModel(
                        app,
                        createBackend = { scope -> MockBackend(FakeTracks.tracks, scope) },
                        presetStore = InMemoryEqPresetStore(),
                    ) as T
            },
        )[WinampViewModel::class.java]

    @get:Rule
    val compose = createAndroidComposeRule<WidgetMenuActivity>()

    @After
    fun forgetSwitch() = WalGate.forget()

    /** A modern skin with one window, as a .wal holds it, in a file the picker can hand over. */
    private fun wal(cut: Boolean = false): File {
        val file = File(app.cacheDir, "Bento.wal")
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="40" h="20">""" +
                        """<button id="play" action="PLAY" x="0" y="0" w="20" h="20"/></layout></container>""",
                )
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        // without its directory, at the end, the zip does not open, though its entries stream
        if (cut) file.writeBytes(file.readBytes().let { it.copyOf(it.size - 30) })
        return file
    }

    /** Lets the loads' threads and the main thread run until [condition] holds, for at most 20 s. */
    private fun awaitThe(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            Thread.sleep(20)
        }
        compose.waitForIdle()
        assertTrue("the condition holds within 20 s", condition())
    }

    @Test
    fun `the menu stays open until the notice about a picked modern skin is answered`() {
        WalGate.write(app, true)
        val wal = wal(cut = true)
        // an earlier pick that holds the loads' lock until it is let go, so the one under test waits
        val letGo = CountDownLatch(1)
        val held =
            object : InputStream() {
                override fun read(): Int {
                    letGo.await(30, TimeUnit.SECONDS)
                    return -1
                }
            }
        player.skinOps.load(held, "Held.wsz") {}
        val menu = compose.activity
        try {
            compose.runOnUiThread { menu.skinPicked(Uri.fromFile(wal)) }
            compose.waitForIdle()
            assertFalse("the window waits for the load", menu.isFinishing)

            letGo.countDown()
            awaitThe { player.state.prompt != null }
            assertEquals("the notice names the skin", "“Bento.wal” couldn't be opened", player.state.prompt!!.title)
            assertFalse("the window stays for the notice", menu.isFinishing)

            compose.runOnUiThread { player.state.prompt!!.onConfirm() }
            compose.waitForIdle()
            assertTrue("answered, the window closes", menu.isFinishing)
        } finally {
            letGo.countDown()
            compose.runOnUiThread { AppViewModels.viewModelStore.clear() }
        }
    }

    @Test
    fun `a modern skin added from the menu is ticked, and the menu closes once it is on`() {
        WalGate.write(app, true)
        val wal = wal()
        val id = SkinLibrary(app).idFor(wal.readBytes())
        val menu = compose.activity
        try {
            compose.runOnUiThread { menu.skinPicked(Uri.fromFile(wal)) }

            awaitThe { player.skinOps.currentId == id }
            awaitThe { menu.isFinishing }
            assertNull("nothing to say", player.state.prompt)
        } finally {
            compose.runOnUiThread { AppViewModels.viewModelStore.clear() }
        }
    }

    @Test
    fun `with modern skins off the menu stays open for the offer, and Not now closes it`() {
        WalGate.write(app, false)
        val menu = compose.activity
        try {
            compose.runOnUiThread { menu.skinPicked(Uri.fromFile(wal())) }

            awaitThe { player.state.modernOffer != null }
            assertFalse("the window stays for the offer", menu.isFinishing)

            compose.onNodeWithText("Not now").performClick()
            awaitThe { menu.isFinishing }
            assertNull(player.state.modernOffer)
            assertFalse("modern skins stay off", WalGate.on)
        } finally {
            compose.runOnUiThread { AppViewModels.viewModelStore.clear() }
        }
    }

    @Test
    fun `taking the offer from the menu adds the skin and opens the player`() {
        WalGate.write(app, false)
        val wal = wal()
        val id = SkinLibrary(app).idFor(wal.readBytes())
        val menu = compose.activity
        try {
            compose.runOnUiThread { menu.skinPicked(Uri.fromFile(wal)) }
            awaitThe { player.state.modernOffer != null }

            compose.onNodeWithTag("modern.offer.accept").performClick()

            awaitThe { player.skinOps.currentId == id }
            assertTrue("modern skins are on", WalGate.on)
            assertTrue(menu.isFinishing)
            val started = shadowOf(menu).nextStartedActivity
            assertEquals(MainActivity::class.java.name, started?.component?.className)
            assertEquals("on the player", Screen.PLAYER, started?.getStringExtra(MainActivity.GO_TO))
        } finally {
            compose.runOnUiThread { AppViewModels.viewModelStore.clear() }
        }
    }
}
