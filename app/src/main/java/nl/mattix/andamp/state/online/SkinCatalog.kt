// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The museum as one long list, addressed by position.
 *
 * The catalog is sparse: every position from zero to [total] exists, the browser says
 * which ones it is showing, and the pages behind those positions are fetched, wherever in
 * the museum they are.
 *
 * The shown positions change with every frame of a scroll, so the catalog samples them: it
 * fetches where the list is, waits [settleMs] before looking again, and cancels a page
 * that has left the window while in flight.
 *
 * A page in flight is not asked for again, and one that failed stays failed until it is
 * retried.
 */
class SkinCatalog(
    private val museum: SkinsSource,
    val scope: CoroutineScope,
    private val io: CoroutineContext = Dispatchers.IO,
    private val pageSize: Int = PAGE_SIZE,
    private val settleMs: Long = SETTLE_MS,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** How many skins the museum has; [UNKNOWN] until the first page lands. */
    var total by mutableIntStateOf(UNKNOWN)
        private set

    /** What is loaded, by position in the museum. */
    val skins = mutableStateMapOf<Int, OnlineSkin>()

    /** Pages whose fetch failed, by page index, with the reason. */
    val failed = mutableStateMapOf<Int, String>()

    /** Fetches under way, by page index. */
    private val loading = mutableStateMapOf<Int, Job>()

    /** When each failure happened, for the automatic retry. */
    private val failedAt = mutableMapOf<Int, Long>()

    /** The delay before failures are retried; it doubles while failures continue. */
    private var retryWait = retryAfterMs

    /** Where the browser says it is; read by the sampler. */
    private val showing = MutableStateFlow<IntRange?>(null)

    /** Incremented by every failure; the retry loop waits on it. */
    private val troubles = MutableStateFlow(0)

    /** The lowest position proven to hold nothing; the list stops there. */
    private var end = UNBOUNDED

    private var findingEnd: Job? = null

    /** Whether the page behind [at] is being fetched. */
    fun isLoading(at: Int) = (at / pageSize) in loading

    /** Why the fetch of the page behind [at] failed, or null. */
    fun failureAt(at: Int): String? = failed[at / pageSize]

    /** True before anything has arrived or failed. */
    val loadingFirstPage: Boolean get() = total == UNKNOWN && failed.isEmpty()

    /** The loops this catalog runs, cancelled by [close]. */
    private val watchers = mutableListOf<Job>()

    init {
        watchers += scope.launch { sample() }
        watchers += scope.launch { nurseFailures() }
    }

    /**
     * Cancels everything this catalog is doing. Its two loops wait on flows it owns and
     * never return on their own, and the browser builds a new catalog for every sorting
     * or search.
     */
    fun close() {
        watchers.forEach { it.cancel() }
        watchers.clear()
        loading.values.forEach { it.cancel() }
        loading.clear()
        findingEnd?.cancel()
    }

    /** Fetches where the list is, then waits [settleMs]. */
    private suspend fun sample() {
        var sampled: IntRange? = null
        while (coroutineContext.isActive) {
            val positions = showing.first { it != null && it != sampled }!!
            sampled = positions
            fetchAround(positions)
            delay(settleMs)
        }
    }

    /**
     * Retries failed pages around the shown positions without waiting for a scroll. The
     * delay doubles up to [RETRY_CEILING_MS] while failures continue.
     */
    private suspend fun nurseFailures() {
        var nursed = troubles.value
        while (coroutineContext.isActive) {
            nursed = troubles.first { it != nursed }
            delay(retryWait)
            retryWait = (retryWait * 2).coerceAtMost(RETRY_CEILING_MS)
            showing.value?.let { fetchAround(it) }
        }
    }

    /** The browser says which positions it is showing. */
    fun show(positions: IntRange) {
        showing.value = positions
    }

    fun retry(position: Int) = load(position / pageSize, retry = true)

    /**
     * Fetches the pages under [positions] and the one after them, and cancels fetches
     * in flight for pages more than [NEARBY] away.
     */
    private fun fetchAround(positions: IntRange) {
        val first = (positions.first / pageSize).coerceAtLeast(0)
        val last = (positions.last / pageSize).coerceAtLeast(first)
        // one page beyond, so reading on does not stall at every page boundary
        val wanted = first..(last + 1)
        // a page just out of view is left to finish, since a small scroll may bring it back
        val kept = (first - NEARBY)..(last + NEARBY)
        loading.keys.filter { it !in kept }.forEach { loading[it]?.cancel() }
        for (page in wanted) load(page, retry = false)
    }

    /**
     * Updates the length of the list from a page.
     *
     * The museum's `count` covers every classic skin it holds, while the list in museum
     * order ends thousands earlier and every position past that is empty. So a page that
     * comes back short is the end of the list, and one that comes back empty starts a
     * search for the end.
     */
    private fun noteEnd(fetched: SkinsPage) {
        if (fetched.items.size < pageSize) {
            end = minOf(end, fetched.offset + fetched.items.size)
            if (fetched.items.isEmpty()) findEnd(below = fetched.offset)
        }
        total = minOf(fetched.total, end)
    }

    /** Binary search for the last position that holds a skin, with one-item requests. */
    private fun findEnd(below: Int) {
        if (findingEnd?.isActive == true) return
        findingEnd =
            scope.launch {
                var low = (skins.keys.maxOrNull() ?: 0) + 1
                var high = below
                while (low < high) {
                    val at = low + (high - low) / 2
                    val there = withContext(io) { runCatching { museum.page(at, 1) }.getOrNull() } ?: return@launch
                    if (there.items.isEmpty()) high = at else low = at + 1
                }
                end = minOf(end, low)
                total = minOf(total, end)
            }
    }

    /**
     * Whether [page] should be requested: it exists, is not in flight, and is not loaded.
     * A failed page is requested again once [retryAfterMs] has passed, or on a retry.
     */
    private fun worthAsking(
        page: Int,
        retry: Boolean,
    ): Boolean {
        val offset = page * pageSize
        val exists = page >= 0 && page !in loading && (total == UNKNOWN || offset < total)
        val failedWhen = failedAt[page]
        return exists &&
            when {
                retry -> true
                skins.containsKey(offset) -> false
                failedWhen != null -> now() - failedWhen >= retryAfterMs
                else -> true
            }
    }

    private fun load(
        page: Int,
        retry: Boolean,
    ) {
        if (!worthAsking(page, retry)) return
        val offset = page * pageSize
        failed.remove(page)
        lateinit var fetch: Job
        fetch =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = withContext(io) { runCatching { museum.page(offset, pageSize) } }
                    result
                        .onSuccess { fetched ->
                            failedAt.remove(page)
                            retryWait = retryAfterMs
                            fetched.items.forEachIndexed { at, skin -> skins[fetched.offset + at] = skin }
                            noteEnd(fetched)
                        }.onFailure { failure ->
                            // a page cancelled because the list moved on has not failed
                            if (failure is CancellationException) throw failure
                            failedAt[page] = now()
                            failed[page] = failure.message ?: "could not reach the museum"
                            troubles.value++
                        }
                } finally {
                    // only if it is still this fetch: the page may have been requested again
                    if (loading[page] === fetch) loading.remove(page)
                }
            }
        loading[page] = fetch
        fetch.start()
    }

    companion object {
        const val UNKNOWN = -1

        /** Until the end of the museum's list is known. */
        private const val UNBOUNDED = Int.MAX_VALUE

        /** Skins per request. */
        const val PAGE_SIZE = 80

        /** How far out of view, in pages, a fetch in flight is left to finish. */
        private const val NEARBY = 2

        /** How long the sampler waits between reads of the shown positions. */
        const val SETTLE_MS = 140L

        /** A shorter wait, for a source that answers without the network. */
        const val LOCAL_SETTLE_MS = 40L

        /** How long a failed page is left alone before it is requested again. */
        const val RETRY_AFTER_MS = 3_000L

        /** The longest delay between automatic retries. */
        const val RETRY_CEILING_MS = 60_000L
    }
}
