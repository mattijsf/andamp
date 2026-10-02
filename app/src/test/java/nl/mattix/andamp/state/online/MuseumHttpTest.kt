// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client every part of the museum browser fetches through. Without the connect fallback, a
 * host that publishes IPv6 addresses on a network with no route for them costs the connect
 * timeout per address.
 */
class MuseumHttpTest {
    @Test
    fun `the connect fallback is on`() {
        assertTrue("the client uses the connect fallback", MuseumHttp.client.fastFallback)
    }

    @Test
    fun `at least ten requests per host run at once`() {
        assertTrue(MuseumHttp.client.dispatcher.maxRequestsPerHost >= 10)
    }
}
