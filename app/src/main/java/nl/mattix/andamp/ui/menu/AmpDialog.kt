// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

/**
 * A modal that works on both of the app's surfaces.
 *
 * The floating player is a view handed to the window manager and has no window token. Compose's
 * [Dialog] is an `android.app.Dialog` underneath and throws `BadTokenException` without one. So
 * this is a real dialog when there is an activity, and otherwise a scrim over the whole surface
 * with the content centered, drawn inside the composition.
 */
@Composable
fun AmpDialog(
    /**
     * What closing it does, or null for a modal that closes only through its own buttons: no press
     * outside and no back gesture.
     */
    onDismiss: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (LocalContext.current.hostActivity() != null) {
        Dialog(
            onDismissRequest = { onDismiss?.invoke() },
            properties =
                DialogProperties(
                    dismissOnBackPress = onDismiss != null,
                    dismissOnClickOutside = onDismiss != null,
                ),
        ) { content() }
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            // above the windows: each carries a zIndex of its own, so a later sibling without one
            // would draw underneath
            .zIndex(ABOVE_EVERY_WINDOW)
            .background(SCRIM)
            // a press on the scrim closes it, the way tapping outside a dialog does; `selectable`
            // so it is not announced as a button
            .then(
                if (onDismiss == null) {
                    Modifier
                } else {
                    Modifier.selectable(
                        selected = false,
                        interactionSource = remembering(),
                        indication = null,
                        onClick = onDismiss,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // a click of its own that does nothing, so a press on the card does not fall through to the
        // scrim and close it
        Box(
            Modifier.selectable(
                selected = false,
                interactionSource = remembering(),
                indication = null,
                onClick = {},
            ),
        ) { content() }
    }
}

/**
 * The card Material's `AlertDialog` makes, laid out by hand. `AlertDialog` is a [Dialog] inside;
 * this goes through [AmpDialog], so it also works on the floating player.
 */
@Composable
fun AmpAlert(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    /** The button that closes it without doing anything, or null for a card that only tells. */
    dismissLabel: String?,
    confirmEnabled: Boolean = true,
    confirmTag: String? = null,
    body: @Composable () -> Unit,
) {
    AmpDialog(onDismiss) {
        Surface(
            shape = RoundedCornerShape(CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = ELEVATION,
            modifier = Modifier.widthIn(min = MIN_W, max = MAX_W),
        ) {
            Column(Modifier.padding(PAD)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(GAP))
                body()
                Spacer(Modifier.height(GAP))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (dismissLabel != null) TextButton(onClick = onDismiss) { Text(dismissLabel) }
                    TextButton(
                        enabled = confirmEnabled,
                        onClick = {
                            onConfirm()
                            onDismiss()
                        },
                        modifier = confirmTag?.let { Modifier.testTag(it) } ?: Modifier,
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}

/**
 * The activity this composition is drawn in, or null when there is none, as on the floating player.
 * A context is a chain of wrappers and the activity may be several links up.
 */
fun Context.hostActivity(): Activity? {
    var here: Context? = this
    while (here is ContextWrapper) {
        if (here is Activity) return here
        here = here.baseContext
    }
    return null
}

@Composable
private fun remembering() = remember { MutableInteractionSource() }

/** Higher than any window's own stacking index. */
private const val ABOVE_EVERY_WINDOW = 1000f

/** The scrim behind a modal drawn inside the composition. */
private val SCRIM = Color.Black.copy(alpha = 0.6f)

private val CORNER = 28.dp
private val ELEVATION = 6.dp
private val PAD = 24.dp
private val GAP = 16.dp
private val MIN_W = 280.dp
private val MAX_W = 360.dp
