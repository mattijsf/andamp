// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Reports whether this composition is on screen, and again every time that changes.
 *
 * The default is STARTED: a window behind a dialog is still visible. It uses a lifecycle
 * observer because a stopped activity cannot be relied on to apply another recomposition.
 * [onChange] fires once with the current answer as soon as it is registered, and with false
 * when the composition leaves.
 */
@Composable
internal fun WhileOnScreen(
    minimum: Lifecycle.State = Lifecycle.State.STARTED,
    onChange: (onScreen: Boolean) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listener = rememberUpdatedState(onChange)
    DisposableEffect(lifecycle, minimum) {
        // several lifecycle events arrive on the way up; report only a changed answer
        var reported: Boolean? = null
        val observer =
            LifecycleEventObserver { owner, _ ->
                val now = owner.lifecycle.currentState.isAtLeast(minimum)
                if (now != reported) {
                    reported = now
                    listener.value(now)
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (reported != false) listener.value(false)
        }
    }
}
