// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

/**
 * Where a worn modern skin sends what the app answers outside the skin: its buttons' host actions
 * and its requests for the file picker. Both calls come on the main thread. While no player surface
 * has installed a sink ([WalSurface.sink] is null), they are dropped.
 */
internal interface WalSink {
    /**
     * A button action the app answers, as `SkinRuntime.onHostAction` sends it: [action] in
     * uppercase, with the button's [param].
     *
     * - MINIMIZE, SYSMENU, CONTROLMENU and MENU.
     * - CLOSE, from the main window only; a skin window's close button closes it inside the skin.
     * - VIS_NEXT, VIS_PREV, VIS_MENU and VIS_FS.
     * - TOGGLE of a window the app shows, with the window's short name as [param]: "ml" (the
     *   library), "avs" (the visualizer) or "video"; or of a window the skin does not have, with
     *   its lowercased id or GUID.
     */
    fun hostAction(
        action: String,
        param: String?,
    )

    /** Opens the file picker: [andPlay] false for the eject button, true for Play on an empty playlist. */
    fun openFile(andPlay: Boolean)
}
