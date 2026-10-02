// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.graphics.drawscope.DrawScope
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.ui.window.GenWindow.drawGenFrame
import nl.mattix.andamp.ui.window.PleditFrame.drawPleditFrame

/**
 * The frame of a floating window that is not the player, the equalizer or the playlist.
 *
 * Winamp drew these windows with GEN.BMP, which many classic skins do not have. A skin with
 * its own GEN.BMP gets [GenFrame]; any other gets [PleditWindowFrame], built from the
 * playlist art every skin has, so the frame always matches the skin on screen.
 */
interface WindowFrame {
    val titleH: Int
    val leftW: Int
    val chromeW: Int
    val chromeH: Int

    fun closeX(windowW: Int): Int

    fun closeY(): Int

    fun DrawScope.draw(
        skin: Skin,
        windowW: Int,
        windowH: Int,
        title: String,
        closePressed: Boolean,
    )
}

/** Winamp's generic window frame, for skins that ship GEN.BMP. */
object GenFrame : WindowFrame {
    override val titleH get() = GenWindow.TITLE_H
    override val leftW get() = GenWindow.LEFT_W
    override val chromeW get() = GenWindow.CHROME_W
    override val chromeH get() = GenWindow.CHROME_H

    override fun closeX(windowW: Int) = GenWindow.closeX(windowW)

    override fun closeY() = GenWindow.CLOSE_INSET_TOP

    override fun DrawScope.draw(
        skin: Skin,
        windowW: Int,
        windowH: Int,
        title: String,
        closePressed: Boolean,
    ) = drawGenFrame(skin, windowW, windowH, title, closePressed = closePressed)
}

/** The playlist-art frame, for skins with no GEN.BMP of their own. */
object PleditWindowFrame : WindowFrame {
    override val titleH get() = PleditFrame.TITLE_H
    override val leftW get() = PleditFrame.LEFT_W
    override val chromeW get() = PleditFrame.CHROME_W
    override val chromeH get() = PleditFrame.CHROME_H

    override fun closeX(windowW: Int) = PleditFrame.closeX(windowW)

    override fun closeY() = PleditFrame.CLOSE_INSET_TOP

    override fun DrawScope.draw(
        skin: Skin,
        windowW: Int,
        windowH: Int,
        title: String,
        closePressed: Boolean,
    ) = drawPleditFrame(skin, windowW, windowH, title, closePressed = closePressed)
}

/** The frame that matches [skin]: its own generic art when it has any. */
fun frameFor(skin: Skin): WindowFrame = if (skin.ownsGenArt) GenFrame else PleditWindowFrame
