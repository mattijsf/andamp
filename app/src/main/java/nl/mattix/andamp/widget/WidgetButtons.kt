// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import nl.mattix.andamp.core.player.TransportCommand
import nl.mattix.andamp.skin.Dest
import nl.mattix.andamp.ui.window.MAIN_H
import nl.mattix.andamp.ui.window.MainShade
import nl.mattix.andamp.ui.window.SHADE_H

/**
 * The controls the widget offers, and what each press means.
 *
 * A button carries the verb it is; [WidgetControl] decides how the press reaches playback.
 *
 * Shuffle and repeat are not transport and carry no verb. They mean something with nothing playing,
 * so [WidgetControl] sets them through the player or, with none, in the stored settings.
 */
enum class WidgetButton(
    /** The transport verb, or null for shuffle and repeat. */
    val command: TransportCommand?,
) {
    PREV(TransportCommand.PREVIOUS),
    PLAY(TransportCommand.PLAY),
    PAUSE(TransportCommand.PAUSE),
    STOP(TransportCommand.STOP),
    NEXT(TransportCommand.NEXT),
    SHUFFLE(null),
    REPEAT(null),
}

/** Where a button sits on the rendered bitmap, in its pixels. */
data class ButtonBox(
    val button: WidgetButton,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * The parts of the drawn player a tap can land on.
 *
 * RemoteViews cannot hit-test a bitmap, so the buttons are transparent views laid over the picture
 * at the art's coordinates, scaled the same way the art is. The coordinates are read from [Dest]
 * and [MainShade], so the sprite map stays the single source (ENGINEERING.md, Pixel rule 1).
 *
 * Sideways a region is as wide as its art, because the buttons sit side by side.
 */
object WidgetButtons {
    /**
     * The touch band of the button row, in virtual pixels: from where the seek bar's band ends down
     * to the window's bottom edge. The targets are invisible, so they are taller than the 18-pixel
     * transport and 15-pixel flags they cover.
     */
    private const val BAND_TOP = 87
    private const val BAND_H = MAIN_H - BAND_TOP

    /** In windowshade the band is the whole 14-pixel window. */
    private const val SHADE_BAND_TOP = 0
    private const val SHADE_BAND_H = SHADE_H

    /**
     * The boxes for [layout], in the bitmap's own pixels. Windowshade has the transport only: it
     * draws no shuffle or repeat.
     */
    fun boxes(layout: WidgetLayout): List<ButtonBox> {
        val virtual = if (layout.shaded) SHADE else FULL
        val top = if (layout.shaded) SHADE_BAND_TOP else BAND_TOP
        val height = if (layout.shaded) SHADE_BAND_H else BAND_H
        return virtual.map { (button, box) ->
            // the band's top and height for every button; only the sideways edges follow the art
            val at = layout.rect(box.left, top, box.width, height)
            ButtonBox(button, at.x, at.y, at.w, at.h)
        }
    }

    /** A button's horizontal slice of the row. The band sets top and height for every button. */
    private data class Box(
        val left: Int,
        val width: Int,
    )

    // the window's own transport row, from the sprite map's destinations
    private val FULL =
        listOf(
            WidgetButton.PREV to Box(Dest.PREV.x, TRANSPORT_W),
            WidgetButton.PLAY to Box(Dest.PLAY.x, TRANSPORT_W),
            WidgetButton.PAUSE to Box(Dest.PAUSE.x, TRANSPORT_W),
            WidgetButton.STOP to Box(Dest.STOP.x, TRANSPORT_W),
            // webamp declares 23 for next and renders 22; the art is what is tapped
            WidgetButton.NEXT to Box(Dest.NEXT.x, NEXT_W),
            WidgetButton.SHUFFLE to Box(Dest.SHUFFLE.x, SHUFFLE_W),
            WidgetButton.REPEAT to Box(Dest.REPEAT.x, REPEAT_W),
        )

    // windowshade's transport, which is its own art at its own coordinates
    private val SHADE =
        listOf(
            // MainShade's own coordinates, which the strip under these is drawn from
            WidgetButton.PREV to Box(MainShade.PREV.x, MainShade.PREV.w),
            WidgetButton.PLAY to Box(MainShade.PLAY.x, MainShade.PLAY.w),
            WidgetButton.PAUSE to Box(MainShade.PAUSE.x, MainShade.PAUSE.w),
            WidgetButton.STOP to Box(MainShade.STOP.x, MainShade.STOP.w),
            WidgetButton.NEXT to Box(MainShade.NEXT.x, MainShade.NEXT.w),
        )
}

private const val TRANSPORT_W = 23
private const val NEXT_W = 22
private const val SHUFFLE_W = 47
private const val REPEAT_W = 28
