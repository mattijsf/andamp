// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SkinLoader
import kotlin.coroutines.CoroutineContext

/**
 * Skins fetched to be previewed without being installed.
 *
 * Opening a skin in the browser downloads and parses it as installing would, and the
 * browser draws a working player in it. Nothing is written to the library. The last
 * [MOST_KEPT] skins are kept in memory, oldest dropped first, while the browser is open.
 */
class SkinPreviews(
    private val scope: CoroutineScope,
    /** What a skin missing a sheet borrows from: the player's base skin. */
    private val fallback: () -> Skin?,
    private val io: CoroutineContext = Dispatchers.IO,
    private val download: suspend (url: String) -> ByteArray = ::museumDownload,
) {
    private val ready = mutableStateMapOf<String, Skin>()
    private val order = ArrayDeque<String>()
    private val fetching = mutableMapOf<String, Job>()

    /** The skin for [md5] if it has loaded; null while it is loading or after a failure. */
    fun of(md5: String): Skin? = ready[md5]

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
                        runCatching {
                            SkinLoader.load(download(skin.downloadUrl).inputStream(), fallback(), skin.filename)
                        }.getOrNull()
                    }
                if (loaded != null) keep(skin.md5, loaded)
            }
    }

    private fun keep(
        md5: String,
        skin: Skin,
    ) {
        ready[md5] = skin
        order.remove(md5)
        order.addLast(md5)
        while (order.size > MOST_KEPT) {
            ready.remove(order.removeFirst())
        }
    }

    /** The browser is closing: cancels the downloads and drops the skins. */
    fun forgetAll() {
        fetching.values.forEach { it.cancel() }
        fetching.clear()
        ready.clear()
        order.clear()
    }

    private companion object {
        const val MOST_KEPT = 4
    }
}
