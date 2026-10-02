// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

/**
 * A list of skins in an order that depends on [seed].
 *
 * It wraps the source the browser is paging through and maps each requested page to
 * another page with modular arithmetic, so the whole list is never needed. Pages are
 * permuted because a page is what the endpoint hands out: a shuffled list costs the same
 * requests as an unshuffled one. The skins within a page are reordered too.
 *
 * The short page at the end stays at the end, because the catalog reads a short page as
 * the end of the list.
 *
 * [howMany] is read at each request, because the length is learned from the pages that
 * arrive.
 */
class ShuffledSkins(
    private val skins: SkinsSource,
    private val seed: Int,
    private val pageSize: Int,
    private val howMany: () -> Int,
) : SkinsSource {
    override suspend fun page(
        offset: Int,
        count: Int,
    ): SkinsPage {
        val pages = fullPages()
        // a request that is not a whole page on a page boundary (the search for the end makes
        // these) is passed through in the real order
        val dealt = if (count == pageSize && offset % pageSize == 0) deal(offset / pageSize, pages) else null
        if (dealt == null) return skins.page(offset, count)
        val fetched = skins.page(dealt * pageSize, count)
        // back into the caller's numbering: the catalog files a page by its offset
        return fetched.copy(offset = offset, items = shuffleWithin(fetched.items, dealt))
    }

    /** Which page of the real list the [wanted] page of the shuffled one is. */
    private fun deal(
        wanted: Int,
        pages: Int,
    ): Int? {
        if (pages < 2) return null
        // the short last page keeps its place
        if (wanted >= pages) return wanted
        return (wanted.toLong() * strideFor(pages) + shiftFor(pages)).mod(pages.toLong()).toInt()
    }

    private fun shuffleWithin(
        items: List<OnlineSkin>,
        page: Int,
    ): List<OnlineSkin> {
        val size = items.size
        if (size < 2) return items
        val stride = strideFor(size)
        val shift = (page + seed).mod(size)
        return List(size) { items[(it.toLong() * stride + shift).mod(size.toLong()).toInt()] }
    }

    /** Whole pages only; the last one is short unless the length divides. */
    private fun fullPages(): Int {
        val total = howMany()
        return if (total <= 0) 0 else total / pageSize
    }

    /**
     * A step that visits every position before repeating: any step coprime with the
     * length does. It starts near the golden section of the length, which spreads
     * consecutive pages apart, and increases until the two share no factor.
     */
    private fun strideFor(length: Int): Int {
        if (length < 3) return 1
        var step = (length * GOLDEN).toInt().coerceAtLeast(1) or 1
        step += seed.mod(length)
        while (gcd(step, length) != 1) step++
        return step.mod(length).coerceAtLeast(1)
    }

    private fun shiftFor(length: Int) = (seed.toLong() * SHIFT_SPREAD).mod(length.toLong()).toInt()

    private tailrec fun gcd(
        a: Int,
        b: Int,
    ): Int = if (b == 0) a else gcd(b, a % b)

    private companion object {
        const val GOLDEN = 0.6180339887

        const val SHIFT_SPREAD = 2_654_435_761L
    }
}
