// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.presets

import android.content.Context
import android.content.SharedPreferences
import nl.mattix.andamp.visualizer.core.PresetGaps
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What a preset could not show, kept across launches by the preset's content: its size and
 * SHA-256. A pack imported again, or the same preset in another pack or under another name,
 * finds its problems again; a preset whose file changed starts over.
 */
data class PresetFingerprint(
    val size: Long,
    val sha256: String,
) {
    companion object {
        /** The fingerprint of [file]; reads all of it. */
        fun of(file: File): PresetFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val chunk = ByteArray(CHUNK)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    digest.update(chunk, 0, read)
                }
            }
            return PresetFingerprint(file.length(), digest.digest().joinToString("") { "%02x".format(it) })
        }

        private const val CHUNK = 16 * 1024
    }
}

/** Keeps the problems of presets by [PresetFingerprint]; a preset that plays whole is not kept. */
class PresetHealthStore(
    context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Map<PresetFingerprint, PresetGaps> {
        val list =
            try {
                JSONArray(prefs.getString(LIST, null) ?: return emptyMap())
            } catch (_: JSONException) {
                return emptyMap()
            }
        return buildMap {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val sha = item.optString(SHA).takeIf { it.isNotEmpty() } ?: continue
                val missing = item.optJSONArray(MISSING)
                put(
                    PresetFingerprint(item.optLong(SIZE), sha),
                    PresetGaps(
                        unreadable = item.optBoolean(UNREADABLE),
                        missing = List(missing?.length() ?: 0) { missing!!.optString(it) },
                        reason = item.optString(REASON).takeIf { item.has(REASON) },
                        blank = item.optBoolean(BLANK),
                    ),
                )
            }
        }
    }

    fun save(health: Map<PresetFingerprint, PresetGaps>) {
        val list = JSONArray()
        health.forEach { (fingerprint, gaps) ->
            if (gaps.none) return@forEach
            list.put(
                JSONObject().apply {
                    put(SIZE, fingerprint.size)
                    put(SHA, fingerprint.sha256)
                    if (gaps.unreadable) put(UNREADABLE, true)
                    if (gaps.blank) put(BLANK, true)
                    if (gaps.missing.isNotEmpty()) put(MISSING, JSONArray(gaps.missing))
                    gaps.reason?.let { put(REASON, it) }
                },
            )
        }
        prefs.edit().putString(LIST, list.toString()).apply()
    }

    private companion object {
        const val PREFS = "preset_health"
        const val LIST = "list"
        const val SIZE = "size"
        const val SHA = "sha256"
        const val UNREADABLE = "unreadable"
        const val BLANK = "blank"
        const val MISSING = "missing"
        const val REASON = "reason"
    }
}
