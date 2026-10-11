// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController

/**
 * Puts the stack in the shape's form after a turn of the phone changed the shape. Beside the list
 * the stack holds the page alone, and one page at a time the list is under it; the page stays, and
 * the list goes under it or away.
 */
@Composable
internal fun KeepStackInShape(
    pages: NavHostController,
    beside: Boolean,
    here: String?,
    /** The list's route. */
    list: String,
    open: (String) -> Unit,
) {
    LaunchedEffect(beside, here) {
        val page = here?.takeIf { it != list } ?: return@LaunchedEffect
        val listUnder = runCatching { pages.getBackStackEntry(list) }.isSuccess
        if (beside && listUnder) open(page)
        if (!beside && !listUnder) {
            pages.navigate(list) { popUpTo(pages.graph.id) { inclusive = true } }
            pages.navigate(page)
        }
    }
}
