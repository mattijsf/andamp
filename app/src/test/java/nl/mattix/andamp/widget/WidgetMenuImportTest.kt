// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.backend.mock.MockBackend
import nl.mattix.andamp.state.AppViewModels
import nl.mattix.andamp.state.FakeTracks
import nl.mattix.andamp.state.InMemoryEqPresetStore
import nl.mattix.andamp.state.WinampViewModel
import org.junit.Assert.assertFalse
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
 * Load skin... on the widget's menu: after the picker answers, the window stays while the picked
 * file loads, so a notice about it has somewhere to show, and closes once the notice is answered.
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
        val wal =
            File(app.cacheDir, "Bento.wal").apply {
                ZipOutputStream(outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("skin.xml"))
                    zip.write("<WinampAbstractionLayer version=\"0.8\"/>".toByteArray())
                    zip.closeEntry()
                }
            }
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
            assertTrue(
                "the notice names the modern skin",
                player.state.prompt!!
                    .title
                    .contains("modern skin"),
            )
            assertFalse("the window stays for the notice", menu.isFinishing)

            compose.runOnUiThread { player.state.prompt!!.onConfirm() }
            compose.waitForIdle()
            assertTrue("answered, the window closes", menu.isFinishing)
        } finally {
            letGo.countDown()
            compose.runOnUiThread { AppViewModels.viewModelStore.clear() }
        }
    }
}
