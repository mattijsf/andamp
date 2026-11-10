// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import nl.mattix.walkit.FakePlayer
import nl.mattix.walkit.SkinFiles
import nl.mattix.walkit.SkinRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A switch between windows apart and one stack reaches the screen at the tick the switch asks for,
 * also when nothing in the skin moves to draw a frame for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WalEngineSwitchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    // one still window: nothing in it ever asks for a frame
    private val files =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/></layout></container>""",
                ).mapValues { it.value.toByteArray() }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

            override fun names(): List<String> = m.keys.toList()
        }

    private val host = WalHost(files, app.cacheDir)
    private val frames = AtomicInteger()
    private val engine = WalEngine("switch-${runs.incrementAndGet()}", host) { frames.incrementAndGet() }
    private val report = CompletableFuture<Throwable?>()

    init {
        engine.start(onStart = { report.complete(it) }) { SkinRuntime(files, host, FakePlayer()).apply { load() } }
    }

    @After
    fun close() {
        engine.close()
        WalApart.forget()
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

    @Test(timeout = 20_000)
    fun `a resting skin shows a switch to windows apart at the tick asked for it`() {
        WalApart.forget(app)
        assertNull(report.get(5, TimeUnit.SECONDS))
        engine.fit(WalFit(1080, 2000, 3f, sideways = false))
        awaitThat("a frame with a layout") { engine.frame?.layout != null }
        val before = engine.frame!!.layout!!
        // nothing in the skin moves, so only the switch can make the next frame
        Thread.sleep(300)
        val shown = frames.get()

        WalApart.change(app, true)
        engine.requestTick(WalCause.KEY)

        awaitThat("a frame laid out apart") { frames.get() > shown && engine.frame?.layout != before }
        val apart = engine.frame!!.layout!!
        assertEquals("the box is the viewport", 1080, (apart.stackW * apart.scale).toInt())
    }

    private companion object {
        val runs = AtomicInteger()
    }
}
