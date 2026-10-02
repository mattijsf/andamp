// SPDX-License-Identifier: Apache-2.0

package nl.mattix.andamp.pack.common.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Which server answers mean a lost connection ([Ending.Dropped]) and which mean the track
 * ([Ending.Broke]), when the extractor would not open a stream.
 */
class ServerAnswerTest {
    private val refused = IOException("Failed to instantiate extractor.")

    @Test
    fun `a passing server trouble is a dropped connection`() {
        listOf(500, 502, 503, 504, 408, 429).forEach { status ->
            assertTrue("$status", ServerAnswer.meaning(status, refused) is Ending.Dropped)
        }
    }

    @Test
    fun `an answer about the track is a broken track`() {
        listOf(400, 401, 403, 404, 410, 416).forEach { status ->
            assertTrue("$status", ServerAnswer.meaning(status, refused) is Ending.Broke)
        }
    }

    /** A 2xx whose body the extractor still could not read is the track. */
    @Test
    fun `a stream the server sent that still would not open is the track`() {
        assertTrue(ServerAnswer.meaning(200, refused) is Ending.Broke)
        assertTrue(ServerAnswer.meaning(206, refused) is Ending.Broke)
    }

    /** Nothing listening at the address is the connection. */
    @Test
    fun `a server that cannot be reached at all is the connection`() {
        assertTrue(ServerAnswer.about("http://127.0.0.1:1/rest/stream", emptyMap(), refused) is Ending.Dropped)
    }
}
