// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The button that was just pressed, shown pressed for a moment afterwards.
 *
 * A launcher keeps the touch for its own scrolling and long-press and hands over one finished
 * click, so a widget cannot show a button down while the finger is. The press is shown just after
 * instead, with the skin's own depressed sprites, which the draw code picks from `pressedWidget`.
 */
object WidgetFlash {
    /** Long enough to see, short enough not to look like a stuck button. */
    const val MILLIS = 180L

    @Volatile
    var pressed: String? = null
        private set

    /**
     * Its own clock, so the widget's single render executor never sleeps and redraws that arrive
     * during a flash are not stalled.
     *
     * A press cancels the clear the press before it scheduled, so an older timer cannot end a newer
     * flash early.
     */
    private val clock =
        Executors.newSingleThreadScheduledExecutor { work ->
            Thread(work, "widget-flash").apply { isDaemon = true }
        }

    @Volatile
    private var clearing: ScheduledFuture<*>? = null

    fun flash(
        widget: String,
        redraw: () -> Unit,
    ) {
        clearing?.cancel(false)
        pressed = widget
        redraw()
        clearing =
            clock.schedule({
                pressed = null
                redraw()
            }, MILLIS, TimeUnit.MILLISECONDS)
    }

    /** The draw code's own name for a control, which keys its pressed art. */
    fun idOf(button: WidgetButton) =
        when (button) {
            WidgetButton.PREV -> "main.prev"
            WidgetButton.PLAY -> "main.play"
            WidgetButton.PAUSE -> "main.pause"
            WidgetButton.STOP -> "main.stop"
            WidgetButton.NEXT -> "main.next"
            WidgetButton.SHUFFLE -> "main.shuffle"
            WidgetButton.REPEAT -> "main.repeat"
        }

    fun idOf(slider: WidgetSlider) =
        when (slider) {
            WidgetSlider.SEEK -> "main.posbar"
            WidgetSlider.VOLUME -> "main.volume"
        }
}
