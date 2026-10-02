// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * [StickyDns] keeps the last answer for a name: the system resolver is asked once per host, a
 * stale answer is handed out while it is refreshed, and the last good answer stands in when a
 * lookup fails.
 */
class StickyDnsTest {
    private val address = InetAddress.getByAddress("r2.example", byteArrayOf(1, 2, 3, 4))

    private class Flaky(
        var answering: Boolean = true,
    ) : Dns {
        var asked = 0

        override fun lookup(hostname: String): List<InetAddress> {
            asked++
            if (!answering) throw UnknownHostException("No address associated with hostname")
            return listOf(InetAddress.getByAddress(hostname, byteArrayOf(1, 2, 3, 4)))
        }
    }

    @Test
    fun `an address that worked once carries a host through a hiccup`() {
        // the clock has to move: an answer still fresh is handed back without
        // asking the system, and the failing lookup would never run
        val system = Flaky()
        var clock = 0L
        val dns = StickyDns(system, keepMs = 1_000, now = { clock }, later = { it() })
        assertEquals(listOf(address), dns.lookup("r2.example"))

        system.answering = false
        clock = 5_000

        assertEquals(listOf(address), dns.lookup("r2.example"))
        assertEquals("the system is asked again once the answer is stale", 2, system.asked)
    }

    @Test
    fun `repeated lookups of one host ask the system once`() {
        val system = Flaky()
        val dns = StickyDns(system)

        repeat(50) { dns.lookup("r2.example") }

        assertEquals(1, system.asked)
    }

    @Test
    fun `a stale answer is handed over now and refreshed behind it`() {
        // the stale answer is returned at once; the refresh runs on `later`
        val system = Flaky()
        var clock = 0L
        val errands = mutableListOf<() -> Unit>()
        val dns = StickyDns(system, keepMs = 1_000, now = { clock }, later = { errands += it })

        dns.lookup("r2.example")
        clock = 1_001

        assertEquals(listOf(address), dns.lookup("r2.example"))
        assertEquals(1, system.asked)

        errands.forEach { it() }
        assertEquals(2, system.asked)
    }

    @Test
    fun `a name asked for up front is not asked for again`() {
        // warming resolves the name ahead of the first request
        val system = Flaky()
        val dns = StickyDns(system, later = { it() })

        dns.warm("r2.example")
        repeat(20) { dns.lookup("r2.example") }

        assertEquals(1, system.asked)
    }

    @Test
    fun `warming a name that cannot be resolved is not an error`() {
        StickyDns(Flaky(answering = false), later = { it() }).warm("nowhere.example")
    }

    @Test
    fun `threads that ask at the same time share one lookup`() {
        // one lookup is in flight per host; the other threads wait for its answer
        val system = Flaky()
        val slow =
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    Thread.sleep(50)
                    return system.lookup(hostname)
                }
            }
        val dns = StickyDns(slow)

        val threads = (1..10).map { Thread { dns.lookup("r2.example") } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1, system.asked)
    }

    @Test
    fun `a host that never resolved still fails`() {
        val dns = StickyDns(Flaky(answering = false))

        assertThrows(UnknownHostException::class.java) { dns.lookup("nowhere.example") }
    }
}
