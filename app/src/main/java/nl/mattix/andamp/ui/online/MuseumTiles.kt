// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.online

/**
 * The blurred stand-ins for the tiles the first launch shows.
 *
 * Those three are the only screenshots the app knows before it has asked the museum: the first two
 * of the museum's front row, and Garfield, which [firstRow] pins third. The welcome card is a
 * first-launch screen, so nothing is cached yet.
 *
 * These are hashes, not pictures: the app ships neither other people's skins nor their screenshots.
 * Encoded by `tools/blurhash.py` from the museum's screenshots. A tile the museum sends that is not
 * in here has no stand-in.
 *
 * [firstRow]: nl.mattix.andamp.ui.welcome.firstRow
 */
internal object MuseumTiles {
    /** A tile known before the museum has answered. */
    data class Tile(
        val md5: String,
        val blurHash: String,
    )

    /**
     * The three, in the order the card shows them. The strip shows their blurs until the museum
     * answers.
     */
    val front =
        listOf(
            // base-2.91.wsz - the original, the museum's first tile
            Tile("5e4f10275dcb1fb211d4a8b4f1bda236", BASE_291),
            // Winamp3_Classified_v5.5.wsz - the museum's second
            Tile("cd251187a5e6ff54ce938d26f1f2de02", CLASSIFIED),
            // Garfield.zip - the museum's fifth, moved into the first row
            Tile("47597ab8e5ffcd39686d455c10c3b436", GARFIELD),
        )

    // Four by three: at a finer grid the bands of a screenshot (title bar, player, equalizer, list)
    // separate and start to read as the skin.
    private const val BASE_291 =
        "L3A16Rt654R%~eITIn%M5,x[xGQ."
    private const val CLASSIFIED =
        "L6CsggoxIUWB?GM{WAoz4,RjRjjZ"
    private const val GARFIELD =
        "L8K]cPXS}qJ7~E5mNHxaOuEj9wwy"

    private val byMd5 = front.associate { it.md5 to it.blurHash }

    /** The stand-in for the skin with this md5, or null when there is none. */
    fun blurHashOf(md5: String): String? = byMd5[md5]
}
