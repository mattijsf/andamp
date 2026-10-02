// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.core.model

/**
 * What a backend can do. The player's UI degrades per capability: without [canSeek] the
 * position bar ignores drags, without [canEditQueue] adding and removing do nothing, without
 * [hasDsp] the effect rack is empty.
 *
 * Two things have no flag. The visualizer follows `PlaybackBackend.audioTap` being non-null,
 * and the volume slider always works, because a backend that cannot set its own volume
 * moves the device's.
 */
data class Capabilities(
    val canSeek: Boolean,
    val canEditQueue: Boolean = false,
    /** Backend applies [EqSettings] to the audio; without it the EQ UI is visual only. */
    val hasEqualizer: Boolean = false,
    /** Backend pans the audio; without it the balance slider is visual only. */
    val hasBalance: Boolean = false,
    /** Backend runs the [RackSettings] effects; without it the effect list is empty. */
    val hasDsp: Boolean = false,
    /**
     * Backend can attenuate its own output, so the listener may choose whether the slider
     * moves that or the phone's media volume ([VolumeMode]). The player offers the choice
     * only when this is true.
     */
    val canAttenuate: Boolean = false,
)
