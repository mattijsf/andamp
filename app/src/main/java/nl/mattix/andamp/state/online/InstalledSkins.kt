// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import android.content.Context

/**
 * Which museum skins are on this device, by the museum's md5.
 *
 * The library files a skin by a hash of the bytes it stored and the museum knows it by md5,
 * so the pairing is recorded at install. A row can then offer "Uninstall" without a
 * download, and an uninstall finds the file.
 */
class InstalledSkins(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The library id for [md5], or null when the museum's copy is not here. */
    fun libraryId(md5: String): String? = prefs.getString(md5, null)

    fun remember(
        md5: String,
        libraryId: String,
    ) {
        prefs.edit().putString(md5, libraryId).apply()
    }

    fun forget(md5: String) {
        prefs.edit().remove(md5).apply()
    }

    /** Everything installed from the museum: md5 to library id. */
    fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private companion object {
        const val PREFS = "online_skins"
    }
}
