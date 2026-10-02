// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.SkinOps
import nl.mattix.andamp.state.readAtMost
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a row in the browser can do about installing. */
interface SkinInstalls {
    fun isInstalled(md5: String): Boolean

    /** Every museum hash this phone has a skin for. */
    fun installedHashes(): Set<String>

    /** Those same skins as the browser shows them, listed from the phone without a request. */
    fun installedSkins(): List<OnlineSkin>

    /** Drops the record of a skin that was removed from the library elsewhere. */
    fun forgetId(id: String)

    fun isBusy(md5: String): Boolean

    fun install(skin: OnlineSkin)

    fun uninstall(skin: OnlineSkin)

    /** The last message for the listener; the browser clears it. */
    var message: String?
}

/**
 * Installs a museum skin and uninstalls it.
 *
 * Installing also applies the skin: the download goes through the path a picked file takes
 * ([SkinOps.load] parses, stores and applies), and the museum's hash is recorded against
 * the library's id.
 */
class OnlineSkinOps(
    private val skinOps: SkinOps,
    private val library: SkinLibrary,
    private val installed: InstalledSkins,
    private val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val download: suspend (url: String) -> ByteArray = ::museumDownload,
) : SkinInstalls {
    /** md5s of skins being fetched, so their rows can show it. */
    val busy = mutableStateMapOf<String, Boolean>()

    override var message by mutableStateOf<String?>(null)

    private val known = mutableStateMapOf<String, String>()

    init {
        known.putAll(installed.all())
    }

    override fun isInstalled(md5: String): Boolean = known.containsKey(md5)

    override fun installedHashes(): Set<String> = known.keys.toSet()

    override fun installedSkins(): List<OnlineSkin> {
        val named = library.list().associate { it.id to it.name }
        return known.entries
            .mapNotNull { (md5, id) -> named[id]?.let { skinAt(md5, it) } }
            .sortedBy { it.filename.lowercase() }
    }

    override fun isBusy(md5: String): Boolean = busy.containsKey(md5)

    override fun install(skin: OnlineSkin) {
        if (busy.containsKey(skin.md5) || isInstalled(skin.md5)) return
        busy[skin.md5] = true
        scope.launch {
            val bytes =
                withContext(io) {
                    @Suppress("TooGenericExceptionCaught") // a failed download becomes a message
                    try {
                        download(skin.downloadUrl)
                    } catch (e: Exception) {
                        Log.i(TAG, "could not fetch ${skin.filename}", e)
                        null
                    }
                }
            if (bytes == null || bytes.isEmpty()) {
                busy.remove(skin.md5)
                message = "Could not download ${skin.filename}"
                return@launch
            }
            // the install is recorded only when load reports an id: a file that does not
            // parse gives none. The row stays busy until then, so it does not offer
            // Install again while the parse is running
            skinOps.load(bytes.inputStream(), skin.filename) { id ->
                busy.remove(skin.md5)
                if (id == null) {
                    message = "Could not read ${skin.filename}"
                } else {
                    installed.remember(skin.md5, id)
                    known[skin.md5] = id
                }
            }
            // success sets no message: the tile shows a tick and the player wears the skin
        }
    }

    /** A skin was removed from the library elsewhere (the Skin Manager), so its record goes too. */
    override fun forgetId(id: String) {
        known.entries
            .filter { it.value == id }
            .map { it.key }
            .forEach { md5 ->
                known.remove(md5)
                installed.forget(md5)
            }
    }

    override fun uninstall(skin: OnlineSkin) {
        val id = known[skin.md5] ?: return
        skinOps.remove(id)
        installed.forget(skin.md5)
        known.remove(skin.md5)
        // no message here either
    }

    private companion object {
        const val TAG = "OnlineSkinOps"
    }
}

/**
 * A download bounded to [MAX_BYTES], on the client everything else in the browser uses
 * ([MuseumHttp]). Cancelling the coroutine cancels the call. The call has its own, longer
 * timeout, because a skin is much larger than a thumbnail.
 */
internal suspend fun museumDownload(url: String): ByteArray =
    suspendCancellableCoroutine { waiting ->
        val call = MuseumHttp.client.newCall(Request.Builder().url(url).build())
        call.timeout().timeout(SKIN_TIMEOUT_S, TimeUnit.SECONDS)
        waiting.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) = waiting.resumeWithException(e)

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    response.use { answer ->
                        try {
                            if (!answer.isSuccessful) throw IOException("the museum answered ${answer.code}")
                            waiting.resume(answer.body.byteStream().readAtMost(MAX_BYTES)) { call.cancel() }
                        } catch (failure: IOException) {
                            waiting.resumeWithException(failure)
                        }
                    }
                }
            },
        )
    }

/** The whole-call timeout for a skin download. */
private const val SKIN_TIMEOUT_S = 60L
private const val MAX_BYTES = 32 * 1024 * 1024
