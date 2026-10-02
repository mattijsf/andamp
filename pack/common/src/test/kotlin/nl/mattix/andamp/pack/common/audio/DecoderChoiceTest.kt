// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which decoder a track gets, over lists shaped like the ones phones give: a vendor
 * decoder listed ahead of the platform's own, OMX names as aliases of Codec2 ones, and no
 * software flag below API 29.
 */
class DecoderChoiceTest {
    @Test
    fun `a decoder the platform says is software only is preferred over a hardware one listed first`() {
        val pick =
            DecoderChoice.pick(
                listOf(
                    DecoderCandidate("c2.vendor.mp3.decoder", softwareOnly = false),
                    DecoderCandidate("c2.android.mp3.decoder", softwareOnly = true),
                ),
            )

        assertEquals("c2.android.mp3.decoder", pick)
    }

    @Test
    fun `the platform's word wins over a name`() {
        val pick =
            DecoderChoice.pick(
                listOf(
                    DecoderCandidate("c2.android.flac.decoder", softwareOnly = false),
                    DecoderCandidate("c2.somebody.flac.decoder", softwareOnly = true),
                ),
            )

        assertEquals("c2.somebody.flac.decoder", pick)
    }

    @Test
    fun `aliases are passed over for the decoder they name`() {
        val pick =
            DecoderChoice.pick(
                listOf(
                    DecoderCandidate("OMX.google.aac.decoder", softwareOnly = true, alias = true),
                    DecoderCandidate("c2.android.aac.decoder", softwareOnly = true),
                ),
            )

        assertEquals("c2.android.aac.decoder", pick)
    }

    @Test
    fun `below API 29 a software decoder is known by its name`() {
        val omx =
            DecoderChoice.pick(
                listOf(
                    DecoderCandidate("OMX.qcom.audio.decoder.mp3", softwareOnly = null),
                    DecoderCandidate("OMX.google.mp3.decoder", softwareOnly = null),
                ),
            )
        val codec2 =
            DecoderChoice.pick(
                listOf(
                    DecoderCandidate("c2.vendor.opus.decoder", softwareOnly = null),
                    DecoderCandidate("c2.android.opus.decoder", softwareOnly = null),
                ),
            )

        assertEquals("OMX.google.mp3.decoder", omx)
        assertEquals("c2.android.opus.decoder", codec2)
    }

    @Test
    fun `among software decoders the first listed is taken`() {
        val pick =
            DecoderChoice.pick(
                listOf(
                    DecoderCandidate("c2.android.aac.decoder", softwareOnly = true),
                    DecoderCandidate("c2.android.inproc.aac.decoder", softwareOnly = true),
                ),
            )

        assertEquals("c2.android.aac.decoder", pick)
    }

    @Test
    fun `a format only the vendor decodes is left to the platform`() {
        assertNull(DecoderChoice.pick(listOf(DecoderCandidate("c2.dolby.eac3.decoder", softwareOnly = false))))
        assertNull(DecoderChoice.pick(listOf(DecoderCandidate("OMX.dolby.ac3.decoder", softwareOnly = null))))
        assertNull(DecoderChoice.pick(emptyList()))
    }
}
