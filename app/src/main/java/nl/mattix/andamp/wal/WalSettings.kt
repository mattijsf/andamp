// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.content.Context
import android.util.Log
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
        saved.optJSONObject(SETTINGS)?.let { o -> o.keys().forEach { k -> rt.storedSettings[k] = o.getString(k) } }
        saved.optJSONObject(STRINGS)?.let { o -> o.keys().forEach { k -> rt.privateStrings[k] = o.getString(k) } }
        saved.optJSONObject(INTS)?.let { o -> o.keys().forEach { k -> rt.privateInts[k] = o.getInt(k) } }
    }

    /** Puts back the color theme [id] wore, once its runtime has loaded the skin's themes. */
    fun restoreTheme(
        id: String,
        rt: SkinRuntime,
    ) {
        read(id)?.optString(THEME)?.takeIf { it.isNotEmpty() }?.let { rt.selectGammaSet(it) }
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
        val o =
            JSONObject()
                .put(THEME, rt.currentGammaSet.orEmpty())
                .put(SETTINGS, JSONObject(rt.storedSettings.toMap()))
                .put(STRINGS, JSONObject(rt.privateStrings.toMap()))
                .put(INTS, JSONObject(rt.privateInts.toMap()))
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

    /** Where the listener moved skin [id]'s stack, upright or [sideways]; null while it sits where the engine put it. */
    fun place(
        id: String,
        sideways: Boolean,
    ): WalMove? {
        val o = prefs.getString(placeKey(id, sideways), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return runCatching { WalMove(o.getInt(DX), o.getInt(DY), o.getInt(WIDTH), o.getInt(HEIGHT)) }.getOrNull()
    }

    /** Keeps where the listener moved skin [id]'s stack, upright or [sideways]; null forgets it. */
    fun keepPlace(
        id: String,
        sideways: Boolean,
        move: WalMove?,
    ) {
        val key = placeKey(id, sideways)
        if (move == null) {
            if (prefs.contains(key)) prefs.edit().remove(key).apply()
            return
        }
        val o =
            JSONObject()
                .put(DX, move.dx)
                .put(DY, move.dy)
                .put(WIDTH, move.width)
                .put(HEIGHT, move.height)
        prefs.edit().putString(key, o.toString()).apply()
    }

    private fun read(id: String): JSONObject? = prefs.getString(id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private companion object {
        const val PREFS = "wal_skins"
        const val THEME = "theme"
        const val SETTINGS = "settings"
        const val STRINGS = "strings"
        const val INTS = "ints"
        const val TAG = "WalSettings"
        const val DX = "dx"
        const val DY = "dy"
        const val WIDTH = "w"
        const val HEIGHT = "h"

        /** Where a skin's place is kept, apart from the value the engine thread writes whole. */
        fun placeKey(
            id: String,
            sideways: Boolean,
        ) = if (sideways) "$id.place.sideways" else "$id.place"

        /** About 256 KB of JSON per skin. */
        const val MAX_CHARS = 256 * 1024
    }
}
