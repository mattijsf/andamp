// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * Where on the phone a document of the storage provider is, as a file path.
 *
 * A file or a folder picked from the phone's storage or from a card is named by an id
 * that is its place: `primary:Music/Road/one.mp3`, `1A2B-3C4D:Music/x.mp3`. The path says
 * which file a row stands for when the row's own uri no longer opens, and the phone's
 * library lists its files by path.
 */
object DocumentFilePath {
    /** Whether [uri] is a document of the storage provider, the kind whose id is a place. */
    fun covers(uri: String): Boolean = uri.startsWith(STORAGE_PROVIDER)

    /**
     * The path of the document [uri] names, with [primary] as the folder the phone's own
     * storage is mounted at. Null for a document of another provider, whose ids are not
     * places, and for an id that names a whole storage.
     */
    fun of(
        uri: String,
        primary: String,
    ): String? {
        if (!covers(uri)) return null
        val id = java.net.URLDecoder.decode(uri.substringAfterLast(DOCUMENT, missingDelimiterValue = ""), "UTF-8")
        val storage = id.substringBefore(':', missingDelimiterValue = "")
        val inside = id.substringAfter(':', missingDelimiterValue = "")
        if (storage.isEmpty() || inside.isEmpty()) return null
        val root =
            when (storage) {
                PRIMARY -> primary

                // the picker's Documents shortcut
                HOME -> "$primary/Documents"

                else -> "/storage/$storage"
            }
        return "$root/$inside"
    }

    private const val STORAGE_PROVIDER = "content://com.android.externalstorage.documents/"
    private const val DOCUMENT = "/document/"
    private const val PRIMARY = "primary"
    private const val HOME = "home"
}
