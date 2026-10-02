// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.overlay

/**
 * Acts on [OverlayGate]: shows the floating player, hides it, and finishes the activity it
 * was handed over from.
 *
 * - [settle]: the gate changed; show or hide to match it. Idempotent, so every lifecycle
 *   callback can call it.
 * - [handOver]: the app gives the player to the overlay: settle, and if the overlay is then
 *   shown, finish the activity. Nothing else finishes the activity, so leaving by the home
 *   button does not.
 * - [stepAside]: another activity is arriving; hide the window without touching the gate,
 *   because the arriving activity settles it.
 */
class OverlayCoordinator(
    private val show: () -> Unit,
    private val hide: () -> Unit,
    private val finish: () -> Unit,
    /**
     * Whether the floating window is on screen. Asked each time, because the window can
     * also take itself down.
     */
    private val shown: () -> Boolean,
) {
    fun settle(gate: OverlayGate) {
        if (gate.showing == shown()) return
        if (gate.showing) show() else hide()
    }

    /** The app handing the player over: the activity finishes when the overlay is shown. */
    fun handOver(gate: OverlayGate) {
        settle(gate)
        if (shown()) finish()
    }

    /**
     * An activity is coming forward for something the floating player cannot do (a picker,
     * Preferences, the museum). The gate is left alone; the activity's arrival settles it.
     */
    fun stepAside() {
        if (shown()) hide()
    }
}
