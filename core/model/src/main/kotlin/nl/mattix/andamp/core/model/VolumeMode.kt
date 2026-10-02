// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * Whose volume the player's slider moves.
 *
 * Winamp's slider was its own attenuator, which is [APP]. [DEVICE] makes the phone's volume
 * keys and the slider agree. Android's media stream has about fifteen steps, so under
 * [DEVICE] the slider moves in steps; [APP] is continuous over the slider's travel.
 */
enum class VolumeMode {
    /** The phone's media stream: the volume keys and the slider are the same volume. */
    DEVICE,

    /** The backend's own gain, leaving the phone's volume where the listener left it. */
    APP,
}
