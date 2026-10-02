// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A resolver that caches the browser's host names.
 *
 * The browser asks two hosts for thousands of pictures, and Android's resolver can fail or
 * stall when asked for the same name in a burst. So a name is resolved once ([warm] does
 * that when the browser opens) and then answered from memory. One lookup is in flight per
 * host at a time. An answer older than [keepMs] is still returned while a fresh one is
 * fetched in the background. Only a lookup for a host with no cached answer blocks.
 */
class StickyDns(
    private val system: Dns = Dns.SYSTEM,
    private val keepMs: Long = KEEP_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val later: (() -> Unit) -> Unit = { work -> background.execute(work) },
) : Dns {
    private class Known(
        val addresses: List<InetAddress>,
        val at: Long,
    )

    private val known = ConcurrentHashMap<String, Known>()
    private val asking = ConcurrentHashMap<String, Any>()
    private val refreshing = ConcurrentHashMap<String, Boolean>()

    override fun lookup(hostname: String): List<InetAddress> {
        known[hostname]?.let { seen ->
            // a stale answer is returned at once; the refresh runs in the background
            if (now() - seen.at >= keepMs) refresh(hostname)
            return seen.addresses
        }
        return ask(hostname)
    }

    /** Resolves [hostname] off the caller's thread, ahead of the first request. */
    fun warm(hostname: String) {
        later { runCatching { ask(hostname) } }
    }

    private fun ask(hostname: String): List<InetAddress> =
        // one lookup at a time per host. computeIfAbsent is atomic, so two threads arriving
        // together get the same lock
        synchronized(asking.computeIfAbsent(hostname) { Any() }) {
            known[hostname]?.takeIf { now() - it.at < keepMs }?.let { return it.addresses }
            try {
                system.lookup(hostname).also { if (it.isNotEmpty()) known[hostname] = Known(it, now()) }
            } catch (unknown: UnknownHostException) {
                known[hostname]?.addresses ?: throw unknown
            }
        }

    private fun refresh(hostname: String) {
        if (refreshing.putIfAbsent(hostname, true) != null) return
        later {
            try {
                runCatching { ask(hostname) }
            } finally {
                refreshing.remove(hostname)
            }
        }
    }

    private companion object {
        /** How long an answer counts as fresh. */
        const val KEEP_MS = 30 * 60 * 1000L

        val background =
            Executors.newSingleThreadExecutor { work ->
                Thread(work, "sticky-dns").apply { isDaemon = true }
            }
    }
}
