// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.mattix.andamp.R
import nl.mattix.andamp.state.AmpPrompt
import nl.mattix.andamp.state.Words

/**
 * The floating player's state for the rest of the app: [OverlayGate] held as Compose state.
 * The settings screen, the lifecycle and the overlay's close button each write one flag.
 * The permission is read each time, because the listener can revoke it in system settings
 * while Andamp is running.
 *
 * Where the phone cannot show a floating player the preference reads as off and stays off,
 * whatever is stored, and asking for it explains why.
 */
class OverlayOps(
    private val store: OverlayStore,
    /** Whether Android lets the app draw over other apps, asked each time. */
    private val permitted: () -> Boolean,
    /** Receives the preference on every change; the clutter bar's A is lit from it. */
    private val mirror: (Boolean) -> Unit = {},
    /** Called when the preference is switched on, and not when it is read as on. */
    private val onSwitchedOn: () -> Unit = {},
    /** Whether this phone can show a floating player at all; see [OverlaySupport]. */
    val supported: Boolean = true,
    private val words: Words,
) {
    var gate by mutableStateOf(
        OverlayGate(wanted = supported && store.wanted, permitted = permitted()),
    )
        private set

    init {
        mirror(gate.wanted)
    }

    /**
     * Stores the preference. Without the permission the gate stays closed until it is granted.
     * Switching it on does nothing on a phone that cannot show a floating player.
     */
    fun want(on: Boolean) {
        if (on && !supported) return
        store.wanted = on
        gate = gate.copy(wanted = on, permitted = permitted())
        mirror(on)
        if (on) onSwitchedOn()
    }

    /**
     * Sets Winamp's Always On Top to [on]; used by the Preferences switch and by [toggle].
     * Turning it on without the permission shows an [AmpPrompt] whose action is
     * [openSettings], and leaves the preference unchanged. On a phone that cannot show a
     * floating player, turning it on shows a notice that says so.
     */
    fun askFor(
        on: Boolean,
        prompt: (AmpPrompt?) -> Unit,
        openSettings: () -> Unit,
    ) {
        if (on && !supported) {
            prompt(
                AmpPrompt(
                    title = words.get(R.string.overlay_title),
                    body = words.get(R.string.overlay_needs_android_11),
                    confirmLabel = words.get(R.string.prompt_ok),
                    dismissLabel = null,
                    onConfirm = {},
                ),
            )
            return
        }
        recheck()
        if (on && !gate.permitted) {
            prompt(
                AmpPrompt(
                    title = words.get(R.string.overlay_title),
                    body = words.get(R.string.overlay_needs_permission),
                    confirmLabel = words.get(R.string.prompt_open_settings),
                    dismissLabel = words.get(R.string.prompt_not_now),
                    onConfirm = openSettings,
                ),
            )
            return
        }
        want(on)
    }

    /**
     * Toggles Winamp's Always On Top, for the clutter bar's A and the main menu's entry:
     * asks for the permission if it is missing, changes the preference only if it is
     * granted and the phone can show a floating player, and calls [onChanged] only if the
     * preference changed.
     */
    fun toggle(
        prompt: (AmpPrompt?) -> Unit,
        openSettings: () -> Unit,
        onChanged: (Boolean) -> Unit,
    ) {
        val turningOn = !gate.wanted
        askFor(turningOn, prompt, openSettings)
        // askFor may have shown the prompt and left the preference as it was
        if (turningOn == gate.wanted) onChanged(gate.wanted)
    }

    /** Re-reads the permission: on return from system settings, and whenever the app comes forward. */
    fun recheck() {
        gate = gate.copy(permitted = permitted())
    }

    fun enteredApp() {
        gate = gate.returnedToApp().copy(permitted = permitted())
    }

    fun leftApp() {
        gate = gate.leftApp()
    }

    /** The X on the floating window. */
    fun dismiss() {
        gate = gate.dismiss()
    }

    /** What the settings row says under its title. */
    fun summary(): String =
        words.get(
            when {
                !gate.wanted -> R.string.overlay_summary_off
                !gate.permitted -> R.string.overlay_summary_waiting
                else -> R.string.overlay_summary_on
            },
        )
}
