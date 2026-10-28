// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.R
import nl.mattix.andamp.state.Tip
import nl.mattix.andamp.state.TipNote
import nl.mattix.andamp.state.TipShelf

/** Preferences > Support Andamp, as the page needs it: what [nl.mattix.andamp.state.TipJar] holds. */
data class SupportPrefs(
    val shelf: TipShelf = TipShelf.Closed,
    val note: TipNote? = null,
    /** Whether this listener ever gave a tip. */
    val given: Boolean = false,
    /** The page is on screen, so the store is asked what it sells. */
    val onShown: () -> Unit = {},
    val onGive: (Tip) -> Unit = {},
    /**
     * The page outside the app that takes support, in a build no store hands out; null where a
     * store sells the tips.
     */
    val outside: String? = null,
    val onOutside: (String) -> Unit = {},
) {
    /** What the page holds, for the line under its row in the list: the tips, or the way to the site. */
    @StringRes
    fun summary(): Int = if (outside != null) R.string.prefs_support_summary_outside else R.string.prefs_support_summary
}

/** The route of the page, which the main menu's entry opens Preferences on. */
internal const val SUPPORT_PAGE = "support"

/** Preferences > Support Andamp: what a tip is for, and the tips. */
@Composable
internal fun SupportPage(prefs: SupportPrefs) {
    if (prefs.outside != null) {
        OutsideSupport(prefs.outside) { prefs.onOutside(prefs.outside) }
        return
    }
    LaunchedEffect(Unit) { prefs.onShown() }
    Spacer(Modifier.height(8.dp))
    Note(stringResource(R.string.prefs_support_intro))
    Spacer(Modifier.height(8.dp))
    when (val shelf = prefs.shelf) {
        TipShelf.Loading -> {
            CircularProgressIndicator(
                Modifier.padding(horizontal = 16.dp).size(24.dp).testTag("prefs.support.loading"),
                strokeWidth = 2.dp,
            )
        }

        TipShelf.Closed -> {
            Note(stringResource(R.string.prefs_support_closed), Modifier.testTag("prefs.support.closed"))
        }

        is TipShelf.Open -> {
            shelf.tips.forEach { tip -> TipButton(tip) { prefs.onGive(tip) } }
            Note(stringResource(R.string.prefs_support_play_note))
        }
    }
    val said =
        prefs.note?.let { stringResource(wordsFor(it)) }
            ?: if (prefs.given) stringResource(R.string.prefs_support_given) else null
    if (said != null) {
        Spacer(Modifier.height(20.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("prefs.support.note"),
        ) {
            if (prefs.note != TipNote.FAILED) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(said, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** The page of a build no store hands out: one button, to the page that takes support. */
@Composable
private fun OutsideSupport(
    page: String,
    onOpen: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Note(stringResource(R.string.prefs_support_outside_intro))
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("prefs.support.outside"),
    ) {
        Text(stringResource(R.string.prefs_support_open_site, page.substringAfter("://").substringBefore('/')))
    }
}

@Composable
private fun TipButton(
    tip: Tip,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("prefs.support.tip.${tip.id}"),
    ) {
        Text(tip.name, modifier = Modifier.weight(1f))
        Text(tip.price)
    }
}

/** The string resource that says what became of a tip. */
private fun wordsFor(note: TipNote): Int =
    when (note) {
        TipNote.THANKS -> R.string.prefs_support_note_thanks
        TipNote.WAITING -> R.string.prefs_support_note_waiting
        TipNote.FAILED -> R.string.prefs_support_note_failed
    }
