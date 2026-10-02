// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import nl.mattix.andamp.MainActivity

/**
 * Every intent a widget press can fire, and how a received one is read back. Builder and parser
 * share the names of the extras, so they live in one file.
 */
internal object WidgetIntents {
    const val ACTION_PRESS = "nl.mattix.andamp.widget.PRESS"

    /** Past every slider step and every button ordinal, so it collides with none. */
    private const val MENU_CODE = 9_000
    const val ACTION_SLIDE = "nl.mattix.andamp.widget.SLIDE"
    private const val EXTRA_BUTTON = "button"
    private const val EXTRA_TARGET = "target"
    private const val EXTRA_SLIDER = "slider"
    private const val EXTRA_FRACTION = "fraction"

    /** Well clear of the transport's request codes, which are its ordinals. */
    private const val SLIDE_CODES = 1_000
    private const val SLIDE_STRIDE = 1_000

    /**
     * One request code per tap target. They must all be distinct, because two PendingIntents with
     * the same code and action are one, and FLAG_UPDATE_CURRENT makes the last one placed win. The
     * fraction scales strictly below the stride, so the two sliders' codes cannot meet;
     * WidgetDiffTest holds every code apart.
     */
    fun slideRequestCode(
        slider: WidgetSlider,
        fraction: Float,
    ) = SLIDE_CODES + slider.ordinal * SLIDE_STRIDE + (fraction * (SLIDE_STRIDE - 1)).toInt()

    fun press(
        context: Context,
        button: WidgetButton,
        target: Boolean,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            button.ordinal,
            Intent(context, MainWindowWidget::class.java)
                .setAction(ACTION_PRESS)
                .putExtra(EXTRA_BUTTON, button.name)
                .putExtra(EXTRA_TARGET, target),
            // the flags' intents differ only in an extra, which does not identify a PendingIntent:
            // without FLAG_UPDATE_CURRENT a press would set what the first one asked for
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun slide(
        context: Context,
        step: SliderStep,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            slideRequestCode(step.slider, step.fraction),
            Intent(context, MainWindowWidget::class.java)
                .setAction(ACTION_SLIDE)
                .putExtra(EXTRA_SLIDER, step.slider.name)
                .putExtra(EXTRA_FRACTION, step.fraction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The menu behind the player's own top-left button. It is an activity because RemoteViews has
     * no menu or dialog; the button gets its own invisible target, as the transport and the sliders
     * do.
     */
    fun menu(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            MENU_CODE,
            Intent(context, WidgetMenuActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** A press, read back; null when the intent is not one or names nothing real. */
    fun readPress(intent: Intent): Pair<WidgetButton, Boolean>? {
        if (intent.action != ACTION_PRESS) return null
        val name = intent.getStringExtra(EXTRA_BUTTON) ?: return null
        val button = runCatching { WidgetButton.valueOf(name) }.getOrNull() ?: return null
        return button to intent.getBooleanExtra(EXTRA_TARGET, false)
    }

    fun readSlide(intent: Intent): Pair<WidgetSlider, Float>? {
        if (intent.action != ACTION_SLIDE) return null
        val name = intent.getStringExtra(EXTRA_SLIDER) ?: return null
        val fraction = intent.getFloatExtra(EXTRA_FRACTION, -1f)
        if (fraction < 0f) return null
        val slider = runCatching { WidgetSlider.valueOf(name) }.getOrNull() ?: return null
        return slider to fraction
    }
}
