// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.SkinSetup

/**
 * What a modern skin the listener uses is given before it loads, the same in the player, in the adb
 * check ([WalCheck]) and in the debug activity, so the debug activity opens a skin as the player
 * shows it.
 */
internal object WalPrepare {
    /**
     * What a skin is told before it loads. [skinName] is the skin's name as the listener knows it,
     * which its scripts read as its folder name (Bento keys what it remembers by it); never a
     * stored file's hash. Winamp's library is a separate Andamp screen, so the skin has none inside
     * it (`@HAVE_LIBRARY@` reads 0: Bento leaves its library tab out, and skins with a library
     * window keep it shut at the start). The app answers the buttons it handles outside the skin
     * (the host's `hostAction`), which also keeps the frames a skin has for the library, the
     * visualizer and video shut, since the app shows those.
     */
    fun setup(skinName: String): SkinSetup = SkinSetup(skinName = skinName, haveLibrary = false, hostActions = true)

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
