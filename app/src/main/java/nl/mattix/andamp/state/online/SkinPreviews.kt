// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.skin.SkinLoader
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Skins fetched to be previewed without being installed.
 *
 * Opening a skin in the browser downloads it, and the browser draws a working player in it.
 * Nothing is written to the library: the skin goes to a file in [dir] for the engine to open,
 * and the last [MOST_KEPT] are kept, oldest dropped first, while the browser is open. A download
 * that is no classic skin (no main window's art) is dropped.
 */
class SkinPreviews(
    private val scope: CoroutineScope,
    /** Where the fetched skins' files go. */
    private val dir: File,
    private val io: CoroutineContext = Dispatchers.IO,
    private val download: suspend (url: String) -> ByteArray = ::museumDownload,
) {
    private val ready = mutableStateMapOf<String, SkinPreview>()
    private val order = ArrayDeque<String>()
    private val fetching = mutableMapOf<String, Job>()

    /** The skin [md5] if it has loaded; null while it is loading or after a failure. */
    fun of(md5: String): SkinPreview? = ready[md5]

    /**
     * Fetches [skin] unless it is here or already coming. A failure is not reported: the
     * museum's picture stays on screen.
     */
    fun want(skin: OnlineSkin) {
        // one download at a time: asking for another skin cancels the rest, so the skin on
        // screen is not kept waiting behind them
        fetching.filterKeys { it != skin.md5 }.forEach { (md5, job) ->
            job.cancel()
            fetching.remove(md5)
        }
        if (ready.containsKey(skin.md5) || fetching[skin.md5]?.isActive == true) return
        fetching[skin.md5] =
            scope.launch {
                val loaded =
                    withContext(io) {
                        runCatching { stored(skin.md5, download(skin.downloadUrl)) }.getOrNull()
                    }
                if (loaded != null) keep(skin.md5, loaded)
            }
    }

    /** [bytes] as the skin [md5] in a file of its own, or null when they are no classic skin. */
    private fun stored(
        md5: String,
        bytes: ByteArray,
    ): SkinPreview? {
        val members = SkinLoader.members(bytes.inputStream())
        if (MAIN_ART.none(members::containsKey)) return null
        dir.mkdirs()
        val file = File(dir, "$md5.wsz")
        val part = File(dir, "$md5.wsz.part")
        part.writeBytes(bytes)
        return if (part.renameTo(file)) SkinPreview(file, SkinLoader.readmeIn(members)) else null
    }

    private fun keep(
        md5: String,
        skin: SkinPreview,
    ) {
        ready[md5] = skin
        order.remove(md5)
        order.addLast(md5)
        while (order.size > MOST_KEPT) {
            ready.remove(order.removeFirst())?.file?.delete()
        }
    }

    /** The browser is closing: cancels the downloads and drops the skins. */
    fun forgetAll() {
        fetching.values.forEach { it.cancel() }
        fetching.clear()
        ready.values.forEach { it.file.delete() }
        ready.clear()
        order.clear()
    }

    private companion object {
        const val MOST_KEPT = 4

        /** The main window's art, which every classic skin has. */
        val MAIN_ART = listOf("main.bmp", "main.png")
    }
}

/** A skin fetched for preview: its file, for the engine, and the readme the artist packed with it. */
data class SkinPreview(
    val file: File,
    val readme: String?,
)
