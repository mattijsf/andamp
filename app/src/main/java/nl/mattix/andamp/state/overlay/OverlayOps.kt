// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nl.mattix.andamp.state.AmpPrompt

/**
 * The floating player's state for the rest of the app: [OverlayGate] held as Compose state.
 * The settings screen, the lifecycle and the overlay's close button each write one flag.
 * The permission is read each time, because the listener can revoke it in system settings
 * while Andamp is running.
 */
class OverlayOps(
    private val store: OverlayStore,
    /** Whether Android lets the app draw over other apps, asked each time. */
    private val permitted: () -> Boolean,
    /** Receives the preference on every change; the clutter bar's A is lit from it. */
    private val mirror: (Boolean) -> Unit = {},
    /** Called when the preference is switched on, and not when it is read as on. */
    private val onSwitchedOn: () -> Unit = {},
) {
    var gate by mutableStateOf(
        OverlayGate(wanted = store.wanted, permitted = permitted()),
    )
        private set

    init {
        mirror(gate.wanted)
    }

    /** Stores the preference. Without the permission the gate stays closed until it is granted. */
    fun want(on: Boolean) {
        store.wanted = on
        gate = gate.copy(wanted = on, permitted = permitted())
        mirror(on)
        if (on) onSwitchedOn()
    }

    /**
     * Sets Winamp's Always On Top to [on]; used by the Preferences switch and by [toggle].
     * Turning it on without the permission shows an [AmpPrompt] whose action is
     * [openSettings], and leaves the preference unchanged.
     */
    fun askFor(
        on: Boolean,
        prompt: (AmpPrompt?) -> Unit,
        openSettings: () -> Unit,
    ) {
        recheck()
        if (on && !gate.permitted) {
            prompt(
                AmpPrompt(
                    title = "Always on top",
                    body =
                        "Andamp needs permission to draw over other apps before it can " +
                            "float on top of them.",
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
     * granted, and calls [onChanged] only if the preference changed.
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
        when {
            !gate.wanted -> "Off"
            !gate.permitted -> "Waiting for permission to draw over other apps"
            else -> "The player floats on top of other apps"
        }
}
