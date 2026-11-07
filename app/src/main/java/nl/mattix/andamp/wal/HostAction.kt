// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

/**
 * A button action Winamp answered outside the skin, as [nl.mattix.walkit.SkinHost.hostAction]
 * passes it on, sorted by what it asks of the host. Which window a [Show] or [Toggle] can reach,
 * and what the host does for each action, is the host's.
 */
internal sealed interface HostAction {
    /**
     * The main menu: the skin's menu button, whose param names the part of the menu bar it opens
     * ([section], as `wa5:options`), and the system and control menu buttons, which name none.
     */
    data class Menu(
        val section: String?,
    ) : HostAction

    /** The main window's close button. */
    data object Close : HostAction

    /** The main window's minimize button. */
    data object Minimize : HostAction

    /** The visualizer's menu. */
    data object VisualizationMenu : HostAction

    /** The visualizer or the video in full screen. */
    data object Fullscreen : HostAction

    /** The visualizer's next preset. */
    data object NextPreset : HostAction

    /** The visualizer's previous preset. */
    data object PreviousPreset : HostAction

    /** The file info of the song playing. */
    data object TrackInfo : HostAction

    /** Always On Top on or off: a classic skin's clutter bar A. */
    data object AlwaysOnTop : HostAction

    /** Shows the [window] named (`avs`, `video`, `ml`), whatever the skin's own view of it. */
    data class Show(
        val window: String,
    ) : HostAction

    /** Shows the [window] named if hidden, else hides it. */
    data class Toggle(
        val window: String,
    ) : HostAction

    companion object {
        /** The action [action] (uppercase) with [param] stands for, or null for one the host does not answer. */
        fun of(
            action: String,
            param: String?,
        ): HostAction? =
            when (action) {
                "MENU" -> Menu(param)
                "SYSMENU", "CONTROLMENU" -> Menu(null)
                "CLOSE" -> Close
                "MINIMIZE" -> Minimize
                "VIS_MENU" -> VisualizationMenu
                "VIS_FS", "VID_FS" -> Fullscreen
                "VIS_NEXT" -> NextPreset
                "VIS_PREV" -> PreviousPreset
                "TRACKINFO" -> TrackInfo
                "ALWAYS_ON_TOP" -> AlwaysOnTop
                "SHOW" -> param?.let(::Show)
                "TOGGLE" -> param?.let(::Toggle)
                else -> null
            }
    }
}
