// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.PopupMenu
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** While a skin's menu is up the engine stands still, and closing the engine frees the menu's wait. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalEngineTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/>""" +
                        """</layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host = WalHost(files, app.cacheDir)
    private val engine = WalEngine("test", host) {}
    private val ticks = AtomicInteger()

    init {
        engine.onTicked = { _, _, _, _, _ -> ticks.incrementAndGet() }
        engine.start { SkinRuntime(files, host, FakePlayer()).apply { load() } }
    }

    @After
    fun close() = engine.close()

    /** Opens a menu from a touch on the engine thread; the future holds what the script heard. */
    private fun openMenu(): CompletableFuture<Int> {
        val heard = CompletableFuture<Int>()
        engine.post { rt ->
            val menu = PopupMenu(rt).apply { makiCall("addcommand", listOf(Value.Str("Item"), Value.Int(1))) }
            heard.complete(rt.gesture(fresh = true) { menu.makiCall("popatmouse", emptyList())!!.asInt })
        }
        return heard
    }

    /** Waits up to 5 s for [done]. */
    private fun awaitThat(
        what: String,
        done: () -> Boolean,
    ) {
        repeat(500) {
            if (done()) return
            Thread.sleep(10)
        }
        fail(what)
    }

    private fun awaitMenu(): SkinMenu {
        awaitThat("the menu shows") { engine.menu != null }
        return engine.menu!!
    }

    @Test(timeout = 20_000)
    fun `ticks asked for under a menu do not run after it`() {
        val heard = openMenu()
        val menu = awaitMenu()
        val before = ticks.get()

        repeat(5) { engine.requestTick() }
        engine.answerMenu(menu, 1)

        assertEquals(1, heard.get(5, TimeUnit.SECONDS))
        // work posted now runs after anything the menu left queued
        val after = CompletableFuture<Unit>()
        engine.post { after.complete(Unit) }
        after.get(5, TimeUnit.SECONDS)
        assertEquals("none of the five ran in a burst after the menu", before, ticks.get())
        engine.requestTick()
        awaitThat("a tick asked for after the menu runs") { ticks.get() == before + 1 }
    }

    @Test(timeout = 20_000)
    fun `closing the engine frees a menu that waits`() {
        val heard = openMenu()
        awaitMenu()

        engine.close()

        assertEquals("the script hears nothing picked", 0, heard.get(5, TimeUnit.SECONDS))
    }
}
