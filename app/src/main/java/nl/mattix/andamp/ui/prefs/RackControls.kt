// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import nl.mattix.andamp.core.model.ParamSpec
import nl.mattix.andamp.core.model.RackSlot
import nl.mattix.andamp.state.DspOps
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One control, drawn from what its effect declared about it. The same renderer serves an effect
 * written in Kotlin and a plug-in read from a file: a slider, a switch and a row of choices are all
 * a number, and the declaration says which to draw.
 */
@Composable
internal fun Control(
    param: ParamSpec,
    slot: RackSlot,
    dsp: DspOps,
    // false grays the control instead of hiding it (docs/dsp-plugin-spec.md section 3)
    enabled: Boolean = true,
) {
    val value = slot.params[param.id, param.default]
    val set = { value: Float -> dsp.setValue(slot.pluginId, param.id, value) }
    when {
        param.isChoice -> ChoiceRow(param, value, slot.pluginId, enabled, set)
        param.toggle -> ToggleRow(param, value, slot.pluginId, enabled, set)
        else -> Knob(param, value, slot.pluginId, enabled, set)
    }
}

/**
 * A switch inside an effect, for a control that is on or off. This is one of the effect's own
 * values; the switch on the card decides whether the effect runs at all.
 */
@Composable
private fun ToggleRow(
    param: ParamSpec,
    value: Float,
    pluginId: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(param.name, style = MaterialTheme.typography.bodyMedium, color = textColour(enabled))
        Help(param, pluginId)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = value >= HALF,
            enabled = enabled,
            onCheckedChange = { onChange(if (it) 1f else 0f) },
            modifier = Modifier.testTag("dsp.$pluginId.toggle.${param.id}"),
        )
    }
}

/**
 * The value at or above which a toggle reads as on; a smoothed toggle passes through the values in
 * between.
 */
internal const val HALF = 0.5f

/** At most this many options fit as segments; past it, radios. */
private const val SEGMENTS = 4

/** Options with a label longer than this are drawn as radios. */
private const val SEGMENT_CHARS = 12

/**
 * A control's info mark and the popup it opens, showing [ParamSpec.help]. Nothing is drawn for a
 * control with no help text.
 */
@Composable
internal fun Help(
    param: ParamSpec,
    pluginId: String,
) {
    if (param.help.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Box {
        // lit while its popup is open, since the popup floats free of the row it belongs to. The
        // lit disc is smaller than the touch target around it, and the transparent ring between the
        // two is also the spacing.
        Box(
            Modifier
                .size(TARGET)
                .clip(CircleShape)
                .clickable { open = !open }
                .semantics { contentDescription = "About ${param.name}" }
                .testTag("dsp.$pluginId.help.${param.id}"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(DISC)
                    .clip(CircleShape)
                    .background(if (open) MaterialTheme.colorScheme.primary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (open) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (open) {
            Popup(
                popupPositionProvider = AboveAnchor,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.widthIn(max = 260.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        // the popup names its control, because one pushed below or sideways to stay
                        // on screen no longer points at it
                        Text(
                            param.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.testTag("dsp.$pluginId.help.${param.id}.title"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            param.help,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Above the mark, centered on it, and clear of every window edge. The window is edge to edge, so
 * its y = 0 is behind the status bar; the margin keeps the popup out from under it.
 */
internal object AboveAnchor : PopupPositionProvider {
    /** Enough to clear a status bar, in pixels, since a position provider is handed no density. */
    const val MARGIN = 96

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // held off both side edges by the same margin
        val rightmost = maxOf(MARGIN, windowSize.width - popupContentSize.width - MARGIN)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(minOf(MARGIN, rightmost), rightmost)
        val above = anchorBounds.top - popupContentSize.height
        val below = anchorBounds.bottom
        // Above the mark needs no further clamping: it ends at the mark, which is on screen.
        // windowSize is the visible display frame while anchorBounds is window-relative, so the two
        // differ by the insets.
        val y =
            when {
                above >= MARGIN -> above
                below + popupContentSize.height + MARGIN <= windowSize.height -> below
                else -> MARGIN
            }
        return IntOffset(x, y)
    }
}

@Composable
private fun ChoiceRow(
    param: ParamSpec,
    value: Float,
    pluginId: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(param.name, style = MaterialTheme.typography.bodyMedium, color = textColour(enabled))
            Help(param, pluginId)
        }
        val choices =
            param.choices
                .orEmpty()
                .entries
                .toList()
        if (choices.size <= SEGMENTS && choices.all { it.key.length <= SEGMENT_CHARS }) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { at, (label, choiceValue) ->
                    SegmentedButton(
                        selected = value == choiceValue,
                        enabled = enabled,
                        onClick = { onChange(choiceValue) },
                        shape = SegmentedButtonDefaults.itemShape(index = at, count = choices.size),
                        modifier = Modifier.testTag("dsp.$pluginId.choice.${label.lowercase()}"),
                    ) { Text(label) }
                }
            }
        } else {
            // the fallback for a plug-in with many or long options
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { (label, choiceValue) ->
                    Row(
                        Modifier
                            .selectable(
                                selected = value == choiceValue,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onChange(choiceValue) },
                            ).testTag("dsp.$pluginId.choice.${label.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == choiceValue, onClick = null, enabled = enabled)
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = textColour(enabled))
                    }
                }
            }
        }
    }
}

@Composable
private fun Knob(
    param: ParamSpec,
    value: Float,
    pluginId: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    // The detent applies only to a finger: an arrow key steps 1% of the range and the detent
    // reaches 3%, so snapping every value would pin the slider to the snap point under key presses.
    //
    // Tracked from the pointer stream, not from an InteractionSource, whose state arrives through a
    // flow a frame late. A plain holder, not snapshot state: it is read inside the same event
    // dispatch that writes it, before any recomposition.
    val touching = remember { BooleanArray(1) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(param.name, style = MaterialTheme.typography.bodyMedium, color = textColour(enabled))
            Help(param, pluginId)
            Spacer(Modifier.weight(1f))
            Text(
                display(param, value),
                // tabular digits, so a moving value does not wobble sideways
                style =
                    MaterialTheme.typography.labelLarge.copy(
                        fontFeatureSettings = "tnum",
                    ),
                color = textColour(enabled),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                // on the parent and on the Initial pass, so a press is known to
                // be a press before the slider turns it into a value
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // latched, not mirrored: a tap emits its value after the finger is
                            // already up, so clearing on release would miss it
                            if (event.changes.any { it.pressed }) touching[0] = true
                        }
                    }
                },
        ) {
            Slider(
                value = value,
                // snapped while the finger is down, so the value does not change on release
                onValueChange = { onChange(if (touching[0]) param.snapped(it) else it) },
                onValueChangeFinished = { touching[0] = false },
                enabled = enabled,
                valueRange = param.min..param.max,
                modifier =
                    Modifier // scoped by plug-in: two effects can both call a control "level"
                        .testTag("dsp.$pluginId.knob.${param.id}"),
            )
        }
    }
}

/**
 * What a control reads as. A control with a 0..1 range and no unit, or the percent unit, reads as a
 * percentage, which is what Winamp showed. One that declares a range past 1, another unit or a
 * display scale reads in its own units, with the unit's symbol spelled as it is written
 * (docs/dsp-plugin-spec.md section 2).
 */
internal fun display(
    param: ParamSpec,
    value: Float,
): String {
    // a control may name its bottom value, such as "off"
    if (param.displayZero.isNotBlank() && value <= param.min) return param.displayZero
    val scaled = value * param.displayScale
    // a declared scale means the plug-in has already said what the number reads as, so it is not
    // also turned into a percentage
    val fraction =
        param.displayScale == 1f && (param.unit == "pc" || (param.unit.isEmpty() && param.max <= 1f))
    if (fraction) return "${(value * PERCENT).roundToInt()}"
    val decimals =
        if (param.displayDecimals >= 0) {
            param.displayDecimals
        } else if ((param.max - param.min) * param.displayScale <= FINE_RANGE) {
            1
        } else {
            0
        }
    val number = String.format(Locale.US, "%.${decimals}f", scaled)
    return if (param.unit.isEmpty()) number else "$number ${unitLabel(param.unit)}"
}

private fun unitLabel(unit: String): String =
    when (unit) {
        "hz" -> "Hz"
        "khz" -> "kHz"
        "ms" -> "ms"
        "s" -> "s"
        "db" -> "dB"
        "pc" -> "%"
        "semitone12TET" -> "st"
        "degree" -> "\u00b0"
        "coef" -> ""
        else -> unit
    }

private const val PERCENT = 100

/**
 * A displayed range at or below this gets one decimal: 4.5 Hz and 4 Hz are audibly different, 1000
 * Hz and 1001 Hz are not.
 */
private const val FINE_RANGE = 50f

/** The lit disc around the info glyph, smaller than the touch target. */
private val DISC = 20.dp
