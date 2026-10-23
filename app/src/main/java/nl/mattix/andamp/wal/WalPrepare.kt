// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.SkinRuntime

/**
 * What a modern skin the listener uses is given before it loads, the same in the player and in the
 * debug activity, so the debug activity opens a skin as the player shows it.
 */
internal object WalPrepare {
    /**
     * Readies [rt] before it loads. [skinName] is the skin's name as the listener knows it, which
     * its scripts read as its folder name (Bento keys what it remembers by it); never a stored
     * file's hash. Winamp's library is a separate Andamp screen, so the skin has none inside it
     * (Bento shows its playlist tab in place of an empty library tab). [onHostAction] answers the
     * buttons the app handles outside the skin; set, it also keeps the frames a skin has for the
     * library, the visualizer and video shut, since the app shows those.
     */
    fun prepare(
        rt: SkinRuntime,
        skinName: String,
        onHostAction: (action: String, param: String?) -> Unit = { _, _ -> },
    ) {
        rt.skinName = skinName
        rt.haveLibrary = false
        rt.onHostAction = onHostAction
    }

    /**
     * How the skin's host answers whether a window the app shows in place of the skin's is open:
     * the library ("ml") as [libraryOpen] says and the visualizer ("avs") as [visualizerOn] says;
     * every other window is the skin's.
     */
    fun windowOpen(
        libraryOpen: () -> Boolean = { false },
        visualizerOn: () -> Boolean = { false },
    ): (String) -> Boolean? =
        { kind ->
            when (kind) {
                "ml" -> libraryOpen()
                "avs" -> visualizerOn()
                else -> null
            }
        }
}
