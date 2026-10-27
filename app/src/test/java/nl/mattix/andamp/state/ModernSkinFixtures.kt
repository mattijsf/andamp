// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.os.Looper
import androidx.compose.material3.ColorScheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext

/** A zip holding [names], each with a few bytes. With a skin.xml among them it reads as a modern skin. */
internal fun zipOf(vararg names: String): ByteArray = zipWith(*names.map { it to it }.toTypedArray())

/**
 * A zip holding each path with its text. Every entry carries the same fixed time, so the same
 * entries always give the same bytes and the same content hash.
 */
internal fun zipWith(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name).apply { time = FIXTURE_ZIP_TIME })
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/** 1 January 2000, 00:00 local time: the time every fixture zip entry carries. */
private val FIXTURE_ZIP_TIME = java.util.GregorianCalendar(2000, 0, 1).timeInMillis

/**
 * A modern skin that loads: one window with a play button. [seed] sets the window's width, so the
 * bytes, and so the id, differ from another one's.
 */
internal fun loadingModernSkin(seed: Int = 0): ByteArray =
    zipWith(
        "skin.xml" to """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
        "player.xml" to
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="${200 + seed}" h="100">""" +
            """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/></layout></container>""",
    )

/**
 * Stores a modern skin in the library as an import does, streamed to a temporary file first,
 * and returns its id. [seed] makes the bytes, and so the id, differ from another one's. The skin
 * [loads] when asked; otherwise its files are not a skin the engine can start.
 */
internal fun SkinLibrary.addModern(
    context: Context,
    name: String,
    seed: Int = 0,
    loads: Boolean = false,
): String {
    val bytes = if (loads) loadingModernSkin(seed) else zipOf("skin.xml", "xml/player-$seed.xml")
    val id = idFor(bytes)
    val tmp = File.createTempFile("modern", ".tmp", context.cacheDir).apply { writeBytes(bytes) }
    storeModern(tmp, id)
    setName(id, name)
    return id
}

/** The main dispatcher, counting what it is handed, so a test can tell a coroutine has come back from IO. */
internal class CountingMain : CoroutineDispatcher() {
    private val dispatched = AtomicInteger()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatched.incrementAndGet()
        Dispatchers.Main.dispatch(context, block)
    }

    /**
     * Runs [action], then waits up to 10 s, without running main, until something is handed to
     * main; whether it was.
     */
    fun awaitDispatch(action: () -> Unit): Boolean {
        val before = dispatched.get()
        action()
        val deadline = System.currentTimeMillis() + 10_000
        while (dispatched.get() == before && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return dispatched.get() > before
    }
}

/**
 * A palette that, once [arm]ed, holds the first read made off the main thread until [release]:
 * a load that reads it waits there. [holding] opens when a read is held.
 */
internal class HeldPalette(
    private val palette: (Context, Boolean) -> ColorScheme?,
) : (Context, Boolean) -> ColorScheme? {
    private val armed = AtomicBoolean(false)
    private val go = CountDownLatch(1)
    val holding = CountDownLatch(1)

    fun arm() = armed.set(true)

    fun release() = go.countDown()

    override fun invoke(
        context: Context,
        dark: Boolean,
    ): ColorScheme? {
        if (Looper.myLooper() != Looper.getMainLooper() && armed.compareAndSet(true, false)) {
            holding.countDown()
            go.await(10, TimeUnit.SECONDS)
        }
        return palette(context, dark)
    }
}
