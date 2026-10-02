// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.graphics.drawscope.DrawScope
import nl.mattix.andamp.core.model.Transport
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.bitmapText
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import nl.mattix.andamp.ui.widget.button

/** The collapsed playlist's geometry: the current track's title and the clock. */
private object PlaylistShade {
    const val TITLE_X = 5
    const val TITLE_Y = 4

    /** The clock ends this far from the right edge and grows leftwards. */
    const val TIME_FROM_RIGHT = 30
    const val GLYPH_W = 5

    /**
     * The left edge of each of the bar's controls, measured from the bar's right edge as
     * webamp's `playlist-window.css` anchors them: the close button, the un-shade button
     * at right 12, and the resize grip at right 20.
     */
    const val CLOSE_FROM_RIGHT = 11
    const val SHADE_FROM_RIGHT = 21
    const val GRIP_FROM_RIGHT = 29
    const val BUTTON = 9
}

/** Where the shade bar's resize grip is, for a bar this wide. */
fun playlistShadeGrip(width: Int) =
    androidx.compose.ui.unit.IntRect(
        width - PlaylistShade.GRIP_FROM_RIGHT,
        3,
        width - PlaylistShade.GRIP_FROM_RIGHT + PlaylistShade.BUTTON,
        3 + PlaylistShade.BUTTON,
    )

fun playlistShadeWidgets(
    vm: WindowControls,
    /** The height the playlist expands back to. */
    expandedH: Int,
    /** The bar's width; its controls are anchored to the right edge. */
    width: Int = PL_W,
): List<Widget> {
    val s = vm.state
    return listOf(
        button("pl.shade", width - PlaylistShade.SHADE_FROM_RIGHT, 3, PlaylistShade.BUTTON, PlaylistShade.BUTTON) {
            s.setShaded(WindowStore.PLAYLIST, false, expandedH)
        },
        button("pl.close", width - PlaylistShade.CLOSE_FROM_RIGHT, 3, PlaylistShade.BUTTON, PlaylistShade.BUTTON) {
            s.plVisible = false
        },
    )
}

fun DrawScope.drawPlaylistShade(
    skin: Skin,
    s: WinampState,
    width: Int = PL_W,
) {
    val sheet = skin[Sheet.PLEDIT]
    sprite(sheet, SpriteMap.PLAYLIST_SHADE_LEFT, 0, 0)
    // filler tiled between the caps
    var x = SpriteMap.PLAYLIST_SHADE_LEFT.w
    val fillTo = width - SpriteMap.PLAYLIST_SHADE_RIGHT_SELECTED.w
    while (x < fillTo) {
        sprite(sheet, SpriteMap.PLAYLIST_SHADE_FILL, x, 0)
        x += SpriteMap.PLAYLIST_SHADE_FILL.w
    }
    sprite(sheet, SpriteMap.PLAYLIST_SHADE_RIGHT_SELECTED, fillTo, 0)
    if (s.pressedWidget == "pl.close") {
        sprite(sheet, SpriteMap.PLAYLIST_CLOSE_SELECTED, width - PlaylistShade.CLOSE_FROM_RIGHT, 3)
    }
    if (s.pressedWidget == "pl.shade") {
        sprite(sheet, SpriteMap.PLAYLIST_COLLAPSE_SELECTED, width - PlaylistShade.SHADE_FROM_RIGHT, 3)
    }

    val text = skin[Sheet.TEXT]
    // with nothing queued the bar shows NO_FILE
    val title = s.currentTrack?.let { "${s.currentIndex + 1}. ${it.title}" } ?: NO_FILE
    bitmapText(text, title.take(TITLE_GLYPHS), PlaylistShade.TITLE_X, PlaylistShade.TITLE_Y)

    if (s.transport == Transport.Stopped) return
    val clock = shadeTime(s)
    val x0 = width - PlaylistShade.TIME_FROM_RIGHT - clock.length * PlaylistShade.GLYPH_W
    bitmapText(text, clock, x0, PlaylistShade.TITLE_Y)
}

/** The most glyphs of the title that are drawn, at 5px each. */
private const val TITLE_GLYPHS = 39

/** What the shaded playlist shows when no track is current. */
private const val NO_FILE = "[NO FILE]"
