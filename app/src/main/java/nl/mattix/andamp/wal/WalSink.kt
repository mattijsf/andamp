// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

/**
 * Where a worn modern skin sends what the app answers outside the skin: its buttons' host actions
 * and its requests for the file picker. Both calls come on the main thread. While no player surface
 * has installed a sink ([WalSurface.sink] is null), they are dropped.
 */
internal interface WalSink {
    /**
     * A button action the app answers, as the skin host's `hostAction` hears it: [action] in
     * uppercase, with the button's [param].
     *
     * - MINIMIZE, SYSMENU, CONTROLMENU and MENU.
     * - TRACKINFO, file info for the song playing: a clutter bar's I (DeClassified, Winamp2000SP4),
     *   or a double tap on a song ticker whose double click action it is (Winamp Modern, Bento).
     * - CLOSE, from the main window only; a skin window's close button closes it inside the skin.
     * - VIS_NEXT, VIS_PREV, VIS_MENU and VIS_FS.
     * - TOGGLE, with the window as [param]. Always for "ml" (the library), "avs" (the visualizer)
     *   and "video", which the app shows, by that short name however the skin names the window
     *   ("library", "musiclibrary" and the library's GUID arrive as "ml"). For any other window only
     *   when the skin does not have it: "pl" for the playlist (also "playlist" or its GUID), else
     *   its id or GUID, lowercased, without braces or "guid:".
     */
    fun hostAction(
        action: String,
        param: String?,
    )

    /** Opens the file picker: [andPlay] false for the eject button, true for Play on an empty playlist. */
    fun openFile(andPlay: Boolean)
}
