// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import android.media.MediaCodecList
import android.os.Build

/**
 * One decoder this phone lists for a format.
 *
 * @param softwareOnly what the platform says, from API 29; null below that, where only the
 *   name is known
 * @param alias another name for a decoder that is also listed under its own name
 */
internal data class DecoderCandidate(
    val name: String,
    val softwareOnly: Boolean?,
    val alias: Boolean = false,
)

/**
 * Which decoder a track is decoded with: a software one, whenever the phone has one for
 * the format.
 *
 * Decoding music costs a software decoder little, and hardware decoder instances are
 * limited in number and shared with other apps. [DecoderTurn] makes sure a backend holds
 * one decoder at a time; this makes it a software one.
 *
 * [pick] works on plain values and [installed] lists the phone's decoders, so the choice
 * can be tested on a JVM.
 */
internal object DecoderChoice {
    /** Name prefixes of the platform's own software decoders: Codec2's, and OMX's. */
    private val SOFTWARE_PREFIXES = listOf("c2.android.", "OMX.google.")

    /**
     * The name of the decoder to create, or null to leave the choice to
     * `MediaCodec.createDecoderByType`.
     *
     * First a decoder the platform says is software only; then, where the platform does
     * not say (below API 29), one whose name has a platform prefix. Among equals the first
     * listed is taken. Aliases are passed over, because the decoder they name is listed
     * under its real name too. Null when there is no software decoder.
     */
    fun pick(candidates: List<DecoderCandidate>): String? {
        val real = candidates.filterNot { it.alias }
        return real.firstOrNull { it.softwareOnly == true }?.name
            ?: real.firstOrNull { it.softwareOnly == null && SOFTWARE_PREFIXES.any(it.name::startsWith) }?.name
    }

    /** Every decoder this phone lists for [mime], in the platform's order. */
    fun installed(mime: String): List<DecoderCandidate> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
            .map { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DecoderCandidate(info.name, softwareOnly = info.isSoftwareOnly, alias = info.isAlias)
                } else {
                    DecoderCandidate(info.name, softwareOnly = null)
                }
            }
}
