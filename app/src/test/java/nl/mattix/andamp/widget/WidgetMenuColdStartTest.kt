// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.wal.WalGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The widget's menu opened while a modern skin is worn, in a process that starts with it: the
 * skin's look is set after the menu is first built, and the classic windows read unticked once it
 * is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WidgetMenuColdStartTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** The modern skin the library holds as the listener's choice, worn before the player starts. */
    private val worn: String = wearModernSkin()

    /**
     * The player the menu acts on, with a mock backend, placed where the menu looks for it, and its
     * classic playlist editor and equalizer open. A field, so it is in place before the rule starts
     * the menu.
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
        )[WinampViewModel::class.java].also {
            it.state.plVisible = true
            it.state.eqVisible = true
        }

    /** Lets go of the pick below. */
    private val letGo = CountDownLatch(1)

    init {
        // a pick that holds the loads' lock until it is let go, so the start's load of the worn
        // skin waits until the menu is up, as a slow start does
        val held =
            object : InputStream() {
                override fun read(): Int {
                    letGo.await(30, TimeUnit.SECONDS)
                    return -1
                }
            }
        player.skinOps.load(held, "Held.wsz") {}
    }

    @get:Rule
    val compose = createAndroidComposeRule<WidgetMenuActivity>()

    @After
    fun forgetSwitch() = WalGate.forget()

    /** Stores a modern skin with one window as the listener's choice, with modern skins on; returns its id. */
    private fun wearModernSkin(): String {
        WalGate.write(app, true)
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
        val library = SkinLibrary(app)
        val id = library.idFor(file.readBytes())
        library.storeModern(file, id)
        library.setName(id, "Bento")
        library.choose(id)
        return id
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

    private fun find(
        label: String,
        items: List<AmpMenuItem>,
    ): AmpMenuItem.Action? =
        items.filterIsInstance<AmpMenuItem.Action>().firstOrNull { it.label == label }
            ?: items.filterIsInstance<AmpMenuItem.Submenu>().firstNotNullOfOrNull { find(label, it.items) }

    @Test
    fun `the classic playlist editor and equalizer read unticked once the modern skin's look is set`() {
        val menu = compose.activity
        try {
            assertNull("the look is not set yet", player.skinOps.wal)
            assertEquals(
                "the menu built first ticks the classic windows",
                true,
                find("Playlist Editor", checkNotNull(menu.menu).items)?.checked,
            )

            letGo.countDown()
            awaitThe { player.skinOps.wal != null }
            assertEquals(worn, player.skinOps.currentId)
            assertTrue("the classic windows are open underneath", player.state.plVisible && player.state.eqVisible)

            val items = checkNotNull(menu.menu) { "the menu is open" }.items
            listOf("Playlist Editor", "Equalizer").forEach { label ->
                val entry = checkNotNull(find(label, items)) { "$label is on the menu" }
                assertEquals("$label is unticked", false, entry.checked)
            }
        } finally {
            letGo.countDown()
            compose.runOnUiThread { AppViewModels.viewModelStore.clear() }
        }
    }
}
