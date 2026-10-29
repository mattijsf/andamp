// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.IntOffset
import nl.mattix.andamp.modernskin.skin.SavedSettings
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import org.json.JSONObject

/**
 * What a modern skin remembers between runs, per skin: its color theme, the settings it keeps by
 * attribute name (Winamp's studio.xnf), and its scripts' private strings and numbers. Kept in the
 * "wal_skins" preferences, one JSON value per skin id. Where the listener moved the skin, upright
 * and sideways, is kept beside it, one more value for each.
 */
internal class WalSettings(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Puts back what [id] kept, into a runtime that has not loaded yet: its scripts read it on start. */
    fun restoreBeforeLoad(
        id: String,
        rt: SkinRuntime,
    ) {
        val saved = read(id) ?: return

        fun <T> entries(
            key: String,
            read: (JSONObject, String) -> T,
        ): Map<String, T> {
            val o = saved.optJSONObject(key) ?: return emptyMap()
            return o.keys().asSequence().associateWith { read(o, it) }
        }
        rt.restoreSettings(
            SavedSettings(
                attributes = entries(SETTINGS, JSONObject::getString),
                strings = entries(STRINGS, JSONObject::getString),
                ints = entries(INTS, JSONObject::getInt),
            ),
        )
    }

    /** Puts back the color theme [id] wore, once its runtime has loaded the skin's themes. */
    fun restoreTheme(
        id: String,
        rt: SkinRuntime,
    ) {
        read(id)?.optString(THEME)?.takeIf { it.isNotEmpty() }?.let { rt.selectColorTheme(it) }
    }

    /** Keeps what [rt], the runtime of skin [id], holds now. */
    fun save(
        id: String,
        rt: SkinRuntime,
    ) {
        snapshot(id, rt)?.let { keep(id, it) }
    }

    /**
     * What [rt], the runtime of skin [id], holds now, as [keep] writes it; null when it is too
     * large to keep. Reads the runtime, so it runs on the engine thread.
     */
    fun snapshot(
        id: String,
        rt: SkinRuntime,
    ): String? {
        val kept = rt.savedSettings()
        val o =
            JSONObject()
                .put(THEME, rt.colorTheme.orEmpty())
                .put(SETTINGS, JSONObject(kept.attributes))
                .put(STRINGS, JSONObject(kept.strings))
                .put(INTS, JSONObject(kept.ints))
        val text = o.toString()
        // the preferences file holds every skin and is read whole: a skin keeps at most this much
        if (text.length > MAX_CHARS) {
            Log.w(TAG, "settings of $id too large to keep (${text.length} characters)")
            return null
        }
        return text
    }

    /** Keeps [text], a [snapshot] of skin [id]. */
    fun keep(
        id: String,
        text: String,
    ) {
        prefs.edit().putString(id, text).apply()
    }

    /** Drops what skin [id] kept, when it leaves the phone, where it was moved to included. */
    fun forget(id: String) {
        prefs
            .edit()
            .remove(id)
            .remove(placeKey(id, sideways = false))
            .remove(placeKey(id, sideways = true))
            .apply()
    }

    /**
     * How far the listener moved skin [id]'s stack from where the engine puts it, upright or
     * [sideways], in device px; null while it sits there.
     */
    fun place(
        id: String,
        sideways: Boolean,
    ): IntOffset? {
        val o = prefs.getString(placeKey(id, sideways), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return runCatching { IntOffset(o.getInt(DX), o.getInt(DY)) }.getOrNull()
    }

    /** Keeps where the listener moved skin [id]'s stack, upright or [sideways] (see [place]); null forgets it. */
    fun keepPlace(
        id: String,
        sideways: Boolean,
        move: IntOffset?,
    ) {
        val key = placeKey(id, sideways)
        if (move == null) {
            if (prefs.contains(key)) prefs.edit().remove(key).apply()
            return
        }
        val o = JSONObject().put(DX, move.x).put(DY, move.y)
        prefs.edit().putString(key, o.toString()).apply()
    }

    private fun read(id: String): JSONObject? = prefs.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    internal companion object {
        /**
         * The preferences file of modern skins: each skin's value, its places, and the modern-skin
         * switches ([WalGate], [WalSharp]).
         */
        const val PREFS = "wal_skins"
        private const val THEME = "theme"
        private const val SETTINGS = "settings"
        private const val STRINGS = "strings"
        private const val INTS = "ints"
        private const val TAG = "WalSettings"
        private const val DX = "dx"
        private const val DY = "dy"

        /** Where a skin's place is kept, apart from the value the engine thread writes whole. */
        private fun placeKey(
            id: String,
            sideways: Boolean,
        ) = if (sideways) "$id.place.sideways" else "$id.place"

        /** About 256 KB of JSON per skin. */
        private const val MAX_CHARS = 256 * 1024
    }
}
