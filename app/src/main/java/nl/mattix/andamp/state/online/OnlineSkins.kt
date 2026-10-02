// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state.online

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.SkinOps

/**
 * How the list is arranged.
 *
 * The museum's endpoint offers one order and no sorting. A shuffle is derived from it with
 * arithmetic; see [ShuffledSkins]. An alphabetical order would need every name at once, so
 * it is not offered.
 */
enum class SkinOrder {
    MUSEUM,
    SHUFFLED,
}

/**
 * The online browser's state: which list it is showing, what is installed from it, and
 * where the listener had scrolled to.
 *
 * The list on show follows from what is asked for: the museum in its own order, the
 * museum's search, a shuffle ([ShuffledSkins]), or the installed skins, which the phone
 * answers itself.
 *
 * The scroll position is kept here because closing the browser tears the screen down.
 */
class OnlineSkins(
    val museum: SkinCatalog,
    val ops: SkinInstalls,
    /** Skins fetched for preview; null when previews are not available. */
    val previews: SkinPreviews? = null,
    private val searching: (String) -> SkinCatalog = { SkinCatalog(SearchSkins(it), museum.scope) },
    /** Builds a shuffled catalog from a seed; null when shuffling is not available. */
    private val shuffling: ((Int) -> SkinCatalog)? = null,
) {
    /** What is being searched for, empty when the whole list is on show. */
    var query by mutableStateOf("")
        private set

    /** Whether the search field is open. */
    var searchOpen by mutableStateOf(false)

    /** How the list is arranged. */
    var order by mutableStateOf(SkinOrder.MUSEUM)
        private set

    var onlyInstalled by mutableStateOf(false)
        private set

    /** Whether skins are shown one to a screen instead of as tiles. The list and position are the same. */
    var wide by mutableStateOf(false)

    /** The shuffle's seed; it changes on every new shuffle. */
    private var seed by mutableIntStateOf(1)

    private var shown by mutableStateOf(museum)

    /** The list on show. */
    val catalog: SkinCatalog get() = shown

    fun search(what: String) {
        if (what == query) return
        query = what
        relist()
    }

    fun sortBy(how: SkinOrder) {
        if (how == order && how != SkinOrder.SHUFFLED) return
        // asking to shuffle again gives a different shuffle
        if (how == SkinOrder.SHUFFLED) seed = seed * SEED_STEP % SEED_WRAP
        order = how
        relist()
    }

    fun installedOnly(only: Boolean) {
        onlyInstalled = only
        relist()
    }

    /**
     * Whether this order is available. A search has the endpoint's own order, and the
     * installed list is not sortable either.
     */
    fun canSort(how: SkinOrder): Boolean =
        query.isBlank() &&
            !onlyInstalled &&
            (how == SkinOrder.MUSEUM || (how == SkinOrder.SHUFFLED && shuffling != null))

    /** The installed skins, filtered by the search text. */
    private fun mine(): List<OnlineSkin> {
        val holds = ops.installedSkins()
        return if (query.isBlank()) holds else holds.filter { it.filename.contains(query, ignoreCase = true) }
    }

    /** Builds the list for the current query, order and filter. */
    fun relist() {
        // the museum's catalog is the one handed in and is never closed; only the catalogs
        // built here are
        val letting = shown.takeIf { it !== museum }
        shown =
            when {
                // the installed skins are on the phone, so no request is made and the search
                // filters them here
                onlyInstalled -> SkinCatalog(InstalledOnly(mine()), museum.scope, settleMs = 0)

                query.isNotBlank() -> searching(query)

                order == SkinOrder.SHUFFLED && shuffling != null -> shuffling(seed)

                else -> museum
            }
        if (letting !== shown) letting?.close()
    }

    /** The row the browser was showing, restored when it opens again. */
    var firstVisibleRow by mutableIntStateOf(0)

    var firstVisibleOffset by mutableIntStateOf(0)

    fun remember(
        row: Int,
        offset: Int,
    ) {
        firstVisibleRow = row
        firstVisibleOffset = offset
    }

    companion object {
        private const val SEED_STEP = 31
        private const val SEED_WRAP = 29

        /** The page size of a shuffled list. */
        private const val SHUFFLE_PAGE = 20

        /** The production wiring: the real museum, and the app's own library. */
        fun of(
            skinOps: SkinOps,
            library: SkinLibrary,
            installed: InstalledSkins,
            scope: CoroutineScope,
        ): OnlineSkins {
            val museum = SkinCatalog(WebampSkins(), scope)
            return OnlineSkins(
                museum = museum,
                ops = OnlineSkinOps(skinOps, library, installed, scope),
                previews = SkinPreviews(scope, fallback = { skinOps.baseSkin }),
                // a smaller page than the museum's list uses: skins within a page come from
                // one run of museum order
                shuffling = { seed ->
                    SkinCatalog(
                        ShuffledSkins(WebampSkins(), seed, SHUFFLE_PAGE) { museum.total },
                        scope,
                        pageSize = SHUFFLE_PAGE,
                    )
                },
            )
        }
    }
}
