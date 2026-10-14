// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.mattix.andamp.R
import nl.mattix.andamp.state.PlaylistFont
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.Words
import nl.mattix.andamp.ui.remote.CompanionCheck

/**
 * Preferences > Android Auto: whether the window can be expected to show in the car, and what
 * the main window and the list look like there.
 *
 * The page is offered on a phone that has the app Android Auto talks to; see
 * [nl.mattix.andamp.state.AndroidAutoStore.companionInstalled].
 */
data class AndroidAutoPrefs(
    val offered: Boolean = false,
    /** The installed skins. */
    val skins: List<SkinEntry> = emptyList(),
    /** The skin chosen for Android Auto, or null for the player's own. */
    val skin: String? = null,
    val onSkin: (String?) -> Unit = {},
    /** The font of the list in Android Auto; see [nl.mattix.andamp.state.AndroidAutoStore.listFont]. */
    val listFont: PlaylistFont = PlaylistFont.REGULAR,
    val onListFont: (PlaylistFont) -> Unit = {},
    /** Checks that app, each time the page is shown; null when it is not on the phone. */
    val check: () -> CompanionCheck? = { null },
)

/** The Android Auto settings, read off the view model. [offered] is whether this phone has the app Android Auto talks to. */
fun androidAutoPrefsOf(
    vm: WinampViewModel,
    offered: Boolean,
) = AndroidAutoPrefs(
    offered = offered,
    skins = vm.state.skinEntries,
    skin = vm.androidAutoStore.skin,
    onSkin = { vm.androidAutoStore.skin = it },
    listFont = vm.androidAutoStore.listFont,
    onListFont = { vm.androidAutoStore.listFont = it },
    check = { CompanionCheck.of(vm.getApplication()) },
)

/** The summary on the row that opens the page. */
fun AndroidAutoPrefs.summary(words: Words): String =
    skins.firstOrNull { it.id == skin }?.let { words.get(R.string.prefs_android_auto_wears_skin, it.name) }
        ?: words.get(R.string.prefs_android_auto_wears_own_skin)

@Composable
internal fun AndroidAutoPage(prefs: AndroidAutoPrefs) {
    // checked again on coming back to the page, after an install or an update elsewhere
    var checked by remember { mutableStateOf(prefs.check()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checked = prefs.check() }
    checked?.let { CompanionStatus(it) }
    Section(stringResource(R.string.prefs_android_auto_skin_section))
    Note(stringResource(R.string.prefs_android_auto_skin_note))
    Group {
        SkinPickRow(
            skins = prefs.skins,
            chosen = prefs.skin,
            heading = stringResource(R.string.prefs_android_auto_skin_heading),
            tag = "prefs.androidauto.skin",
            onPick = prefs.onSkin,
        )
    }
    Section(stringResource(R.string.prefs_android_auto_list_section))
    Group { PlaylistFontChoice(prefs.listFont, prefs.onListFont, tag = "prefs.androidauto.font") }
}

/** Whether the window can be expected to show in the car: one row for each thing that has to be right. */
@Composable
private fun CompanionStatus(check: CompanionCheck) {
    Section(stringResource(R.string.prefs_android_auto_status_section))
    Note(stringResource(R.string.prefs_android_auto_installed, check.version))
    Group {
        val play = check.fromPlay
        CheckRow(
            ok = play,
            title = if (play) R.string.prefs_android_auto_from_play else R.string.prefs_android_auto_not_from_play,
            about =
                stringResource(
                    if (play) R.string.prefs_android_auto_from_play_about else R.string.prefs_android_auto_not_from_play_about,
                ),
            what = "play",
        )
        val match = check.match
        CheckRow(
            ok = match == CompanionCheck.Match.SAME,
            title =
                when (match) {
                    CompanionCheck.Match.SAME -> R.string.prefs_android_auto_matches
                    CompanionCheck.Match.BEHIND -> R.string.prefs_android_auto_older
                    CompanionCheck.Match.AHEAD -> R.string.prefs_android_auto_newer
                },
            about =
                stringResource(
                    when (match) {
                        CompanionCheck.Match.SAME -> R.string.prefs_android_auto_matches_about
                        CompanionCheck.Match.BEHIND -> R.string.prefs_android_auto_older_about
                        CompanionCheck.Match.AHEAD -> R.string.prefs_android_auto_newer_about
                    },
                ),
            what = "version",
        )
    }
}

/** One thing checked: a tick when it is right, a warning when it is not, and what that means. */
@Composable
private fun CheckRow(
    ok: Boolean,
    title: Int,
    about: String,
    what: String,
) {
    ListItem(
        leadingContent = {
            Icon(
                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(about) },
        colors = rowColors(),
        modifier = Modifier.prefsRow().testTag("prefs.androidauto.check.$what.${if (ok) "ok" else "warning"}"),
    )
}
