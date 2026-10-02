// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * The touch replacement for Winamp's Windows context menus (EQ presets, the titlebar main
 * menu, ...). A menu is plain data: builders assemble it from ops, and the ui layer renders
 * whatever [WinampState.activeMenu] holds as a popup anchored at [anchor] with in-place
 * submenu navigation.
 */
data class AmpMenu(
    val title: String,
    val items: List<AmpMenuItem>,
    val anchor: MenuAnchor? = null,
)

/**
 * Where a menu pops from: the opening widget's bounds in virtual window
 * coordinates plus which docked window ("main", "eq", "pl") they live in.
 * The ui layer translates this to screen position.
 */
data class MenuAnchor(
    val window: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

sealed interface AmpMenuItem {
    /**
     * Whether this entry needs the app's own windows: a picker, a dialog, a window to tick,
     * or a setting whose only effect is something the player draws. The player's menu
     * ignores it; the widget's menu greys these entries out.
     */
    val needsTheApp: Boolean

    data class Action(
        val label: String,
        val enabled: Boolean = true,
        /** Ticked, as Winamp ticks its open windows and the current skin. */
        val checked: Boolean = false,
        override val needsTheApp: Boolean = false,
        val onClick: () -> Unit,
    ) : AmpMenuItem

    data class Submenu(
        val label: String,
        val items: List<AmpMenuItem>,
        val enabled: Boolean = true,
        override val needsTheApp: Boolean = false,
    ) : AmpMenuItem

    data object Divider : AmpMenuItem {
        override val needsTheApp = false
    }
}

/** A modal text prompt (save-preset naming, ...); rendered by the ui layer. */
data class NamePrompt(
    val title: String,
    val initial: String = "",
    val confirmLabel: String = "Save",
    val onSubmit: (String) -> Unit,
)

/**
 * A titled message with one action: a permission the system must grant, or a notice from a
 * backend. The clutter bar's A and the Preferences switch both ask for the overlay
 * permission through this.
 */
data class AmpPrompt(
    val title: String,
    val body: String,
    val confirmLabel: String = "Open settings",
    /** The label for declining, or null for a notice, which has only its confirm button. */
    val dismissLabel: String? = "Not now",
    val onConfirm: () -> Unit,
)
