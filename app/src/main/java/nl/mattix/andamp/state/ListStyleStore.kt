// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The font the playlist and the media library are written in. */
enum class ListFont {
    /** The skin's own pixel letters, the ones the main window writes the song title in. */
    SKIN,

    /** The outline font PLEDIT.TXT names, drawn smooth. */
    REGULAR,
}

/**
 * Where a playlist row shows the mark of its source ([SourceMark]), when more than one source is
 * set up: nowhere, between its number and its title, or in front of its length.
 */
enum class MarkPlace { OFF, BEFORE, AFTER }

/** The order of the library's ALBUMS page: by title, by artist, or by year, the oldest first. */
enum class AlbumOrder { TITLE, ARTIST, YEAR }

/** How tall a row of a list is, in virtual pixels. The text keeps its size; a taller row is a larger thing to tap. */
enum class RowHeight(
    val px: Int,
) {
    /** Winamp's own. */
    CLASSIC(13),
    ROOMY(19),
    LARGE(25),
}

/**
 * How the lists look: the font of the playlist and the media library, the height of a row in
 * the playlist, the media library and the skin list, and how their rows write a song.
 *
 * It has its own preferences file because it is read before a window is drawn and written
 * from a settings screen.
 */
class ListStyleStore(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Snapshot state, so the choices and the windows recompose on a change. */
    private var currentFont by mutableStateOf(named(prefs.getString(FONT, null), DEFAULT_FONT))
    private var currentRowHeight by mutableStateOf(named(prefs.getString(ROW_HEIGHT, null), DEFAULT_ROW_HEIGHT))
    private var currentMarks by mutableStateOf(named(prefs.getString(MARKS, null), MarkPlace.OFF))
    private var currentOrder by mutableStateOf(named(prefs.getString(ALBUM_ORDER, null), AlbumOrder.TITLE))
    private var currentRowFormat by mutableStateOf(prefs.getString(ROW_FORMAT, null)?.let(RowFormat::parse) ?: RowFormat.DEFAULT)

    var font: ListFont
        get() = currentFont
        set(value) {
            currentFont = value
            prefs.edit().putString(FONT, value.name).apply()
        }

    var rowHeight: RowHeight
        get() = currentRowHeight
        set(value) {
            currentRowHeight = value
            prefs.edit().putString(ROW_HEIGHT, value.name).apply()
        }

    /** The order of the library's ALBUMS page; by title until the listener picks another. */
    var albumOrder: AlbumOrder
        get() = currentOrder
        set(value) {
            currentOrder = value
            prefs.edit().putString(ALBUM_ORDER, value.name).apply()
        }

    /** Where the playlist's rows show their source's mark; off unless the listener asks. */
    var marks: MarkPlace
        get() = currentMarks
        set(value) {
            currentMarks = value
            prefs.edit().putString(MARKS, value.name).apply()
        }

    /** How a list writes a song; artist and title, as Winamp's playlist, until the listener sets another. */
    var rowFormat: RowFormat
        get() = currentRowFormat
        set(value) {
            currentRowFormat = value
            prefs.edit().putString(ROW_FORMAT, value.encode()).apply()
        }

    companion object {
        private const val PREFS = "liststyle"
        private const val FONT = "font"
        private const val ROW_HEIGHT = "rowheight"
        private const val MARKS = "marks"
        private const val ALBUM_ORDER = "albumorder"
        private const val ROW_FORMAT = "rowformat"

        /** The skin's letters, so a skin dresses the lists as it does the main window. */
        val DEFAULT_FONT = ListFont.SKIN

        /** As in Winamp. */
        val DEFAULT_ROW_HEIGHT = RowHeight.CLASSIC

        /** The choice stored under [name], or [fallback] for none and for one this version does not have. */
        private inline fun <reified E : Enum<E>> named(
            name: String?,
            fallback: E,
        ): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
