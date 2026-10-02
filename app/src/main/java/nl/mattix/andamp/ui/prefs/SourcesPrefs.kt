// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.ExtraSource
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.MusicPermission
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.PhoneStats
import nl.mattix.andamp.state.ScanProgress
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SourceUpdates
import nl.mattix.andamp.state.sourceRoute
import java.util.Locale

/**
 * Preferences > Music sources: where the music comes from, one page each.
 *
 * The phone first, because every install has it; then the sources the listener installed; then a
 * link to more. Each source has its own page because each has its own kind of settings: the phone
 * has a permission and an index, an account-based source has an account.
 */
data class SourcesPrefs(
    val phone: PhonePrefs = PhonePrefs(),
    /** The sources beyond the phone, in the order they were found. */
    val extras: List<ExtraSource> = emptyList(),
    /** Which of [extras] have an account on this device. */
    val signedIn: Set<MusicSource> = emptySet(),
    val onSignedIn: (MusicSource) -> Unit = {},
    val onSignedOut: (MusicSource) -> Unit = {},
    /**
     * Opens a screen a source owns; see [ExtraSource.settings]. The host does it, because starting
     * an activity needs a context and this page is drawn in tests that have no window.
     */
    val openSettings: (Intent) -> Unit = {},
    /** How a source's update file is read; a test answers without a network. */
    val checkUpdate: suspend (url: String, installed: String?) -> SourceUpdates.Check = SourceUpdates::check,
    /** The installed skins, which a source can ask to wear; see [nl.mattix.andamp.state.SourceSkins]. */
    val skins: List<SkinEntry> = emptyList(),
    /** The skin a source asks for, or null for Default. */
    val skinOf: (MusicSource) -> String? = { null },
    val onSkin: (MusicSource, String?) -> Unit = { _, _ -> },
)

/** What the phone's page shows and can do; see [nl.mattix.andamp.state.PhoneLibrary]. */
data class PhonePrefs(
    val stats: PhoneStats? = null,
    val scanning: ScanProgress? = null,
    val lastScan: String? = null,
    val onScan: () -> Unit = {},
    /**
     * Count again. Called when Preferences opens with access to the music, and again when access is
     * given, which on a first visit happens while this page is open.
     */
    val onCount: () -> Unit = {},
)

/** The route of the phone's page. */
internal const val PHONE_PAGE = "source/phone"

/** The page that lists the sources that can be added. */
internal const val MORE_SOURCES_URL = "https://mattix.nl/andamp/extensions/source"

@Composable
internal fun SourcesSection(
    access: LibraryAccess,
    sources: SourcesPrefs,
    onPhone: () -> Unit,
    onSource: (MusicSource) -> Unit,
) {
    val links = LocalUriHandler.current
    Section("Music sources")
    NavRow(
        title = MusicSource.LOCAL.label,
        summary = phoneSummary(access, sources.phone.stats),
        tag = "prefs.source.phone",
        onClick = onPhone,
        route = PHONE_PAGE,
    )
    sources.extras.forEach { extra ->
        NavRow(
            title = extra.source.label,
            summary = extra.Summary(signedIn = extra.source in sources.signedIn),
            tag = "prefs.${sourceRoute(extra.source).replace('/', '.')}",
            onClick = { onSource(extra.source) },
            route = sourceRoute(extra.source),
        )
    }
    // always last, and a link: the available sources are listed on the site, not in this build
    NavRow(
        title = "More sources",
        summary = "Other places Andamp can play music from, and how to add them",
        tag = "prefs.source.more",
        icon = Icons.AutoMirrored.Filled.OpenInNew,
        onClick = { links.openUri(MORE_SOURCES_URL) },
    )
}

/** The phone row's summary: the permission when it is missing, otherwise the count. */
internal fun phoneSummary(
    access: LibraryAccess,
    stats: PhoneStats?,
): String =
    when {
        access == LibraryAccess.BLOCKED -> "${MusicPermission.current} permission is off"
        access != LibraryAccess.GRANTED -> "Allow ${MusicPermission.current} to show the music on this phone"
        stats == null -> "Counting…"
        stats.tracks == 0 -> "No music found on this phone yet"
        else -> stats.summary
    }

/**
 * Preferences > Music sources > This Phone. The screen around it does the counting, keyed on
 * [access]; see [PhonePrefs.onCount].
 */
@Composable
internal fun PhonePage(
    access: LibraryAccess,
    onRequestAccess: () -> Unit,
    phone: PhonePrefs,
) {
    val context = LocalContext.current

    Section("Access")
    Text(access.summary(MusicPermission.current), style = MaterialTheme.typography.bodyMedium)
    access.action?.let { action ->
        Spacer(Modifier.height(12.dp))
        Button(
            // when access is blocked the button opens the app's system settings directly
            onClick = { if (access == LibraryAccess.BLOCKED) context.openAppSettings() else onRequestAccess() },
            modifier = Modifier.testTag("prefs.library.action"),
        ) { Text(action) }
    }
    // counting and scanning read the index the permission guards, so they are not shown without it
    if (access != LibraryAccess.GRANTED) return

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Section("Library")
    val stats = phone.stats
    if (stats == null) {
        Text("Counting…", style = MaterialTheme.typography.bodyMedium)
    } else {
        StatRow("Tracks", String.format(Locale.US, "%,d", stats.tracks))
        StatRow("Artists", String.format(Locale.US, "%,d", stats.artists))
        StatRow("Albums", String.format(Locale.US, "%,d", stats.albums))
        StatRow("Playing time", stats.listening)
        StatRow("On storage", stats.size)
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Section("Scan")
    Text(
        "Android keeps track of the music on this phone by itself, but not always straight away. " +
            "A scan asks it to look through the music folders now, so music you have just copied over shows up.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    val scanning = phone.scanning
    Button(
        onClick = phone.onScan,
        enabled = scanning == null,
        modifier = Modifier.testTag("prefs.phone.scan"),
    ) { Text(if (scanning == null) "Scan now" else "Scanning…") }
    if (scanning != null) {
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { scanning.fraction },
            modifier = Modifier.fillMaxWidth().testTag("prefs.phone.progress"),
        )
        Spacer(Modifier.height(4.dp))
        Text(scanning.words, style = MaterialTheme.typography.bodySmall)
    } else {
        phone.lastScan?.let { said ->
            Spacer(Modifier.height(8.dp))
            Text(
                said,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("prefs.phone.outcome"),
            )
        }
    }
}

/**
 * Preferences > Music sources > a source beyond the phone: its own settings, the skin its tracks
 * wear, then whether there is a newer version of it.
 *
 * The settings are the source's, drawn here or opened as a screen it owns through an intent. The
 * skin and the update check are the app's.
 */
@Composable
internal fun ExtraSourcePage(
    extra: ExtraSource,
    sources: SourcesPrefs,
) {
    val context = LocalContext.current
    val settings = extra.settings(context)
    if (settings == null) {
        extra.Page(onSignedIn = { sources.onSignedIn(extra.source) }, onSignedOut = { sources.onSignedOut(extra.source) })
    } else {
        Section("Settings")
        NavRow(
            title = "${extra.source.label} settings",
            summary = "Its account and whatever else it offers, on its own screen",
            tag = "prefs.source.settings",
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { sources.openSettings(settings) },
        )
        // who made the source: it plays the listener's music in its own app, so the page shows what
        // [ExtraSource.provenance] reports about it
        extra.provenance(context)?.let { whose ->
            Text(whose, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("prefs.source.whose"))
        }
    }
    if (extra.skinnable) {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Section("Skin")
        SourceSkinRow(extra.source, sources)
    }
    val updates = extra.updates ?: return
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Section("Updates")
    UpdatesSection(updates, extra.version, sources.checkUpdate)
}

/**
 * The skin a source wears while its tracks play: Default, or one installed. A choice whose skin is
 * no longer installed reads as Default here, as it plays.
 */
@Composable
internal fun SourceSkinRow(
    source: MusicSource,
    sources: SourcesPrefs,
) {
    var choosing by remember { mutableStateOf(false) }
    val chosen = sources.skinOf(source)?.let { id -> sources.skins.firstOrNull { it.id == id } }
    Text(
        "While a track from ${source.label} is playing, the player wears this skin. " +
            "Default keeps the one you picked for the player.",
        style = MaterialTheme.typography.bodyMedium,
    )
    NavRow(
        title = chosen?.name ?: DEFAULT_SKIN,
        summary = if (chosen == null) "The player's own skin" else "Instead of the player's own skin",
        tag = "prefs.skin.row",
        onClick = { choosing = true },
    )
    if (!choosing) return
    AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Skin for ${source.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SkinChoice(DEFAULT_SKIN, selected = chosen == null, tag = "prefs.skin.default") {
                    sources.onSkin(source, null)
                    choosing = false
                }
                sources.skins.forEach { entry ->
                    SkinChoice(entry.name, selected = entry.id == chosen?.id, tag = "prefs.skin.${entry.id}") {
                        sources.onSkin(source, entry.id)
                        choosing = false
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
    )
}

/**
 * One skin in the list. The whole row is the radio, so a screen reader stops once and hears whether
 * it is selected.
 */
@Composable
private fun SkinChoice(
    name: String,
    selected: Boolean,
    tag: String,
    onPick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onPick)
            .testTag(tag)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The label for following the player's own skin. */
internal const val DEFAULT_SKIN = "Default"

/**
 * Whether the source is the newest there is, checked each time the page opens. It offers the
 * source's download page, opened in the browser, and fetches no file here; see [SourceUpdates].
 */
@Composable
internal fun UpdatesSection(
    url: String,
    installed: String?,
    check: suspend (String, String?) -> SourceUpdates.Check,
) {
    val links = LocalUriHandler.current
    var attempt by remember { mutableIntStateOf(0) }
    val answer by produceState<SourceUpdates.Check>(SourceUpdates.Check.Checking, url, attempt) {
        value = SourceUpdates.Check.Checking
        value = check(url, installed)
    }
    when (val now = answer) {
        SourceUpdates.Check.Checking -> {
            Text("Looking for a newer version…", style = MaterialTheme.typography.bodyMedium)
        }

        is SourceUpdates.Check.UpToDate -> {
            Text(
                "Version ${now.version}, the newest there is.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("prefs.updates.current"),
            )
        }

        is SourceUpdates.Check.Available -> {
            Text(
                "Version ${now.latest.version} is out" + (installed?.let { " (this is $it)" } ?: "") + ".",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("prefs.updates.available"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Its download page opens in your browser; install it the way you installed this one.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { links.openUri(now.latest.page) },
                modifier = Modifier.testTag("prefs.updates.download"),
            ) { Text("Open download page") }
        }

        SourceUpdates.Check.Failed -> {
            Text(
                "Could not check for a newer version.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("prefs.updates.failed"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { attempt++ }) { Text("Try again") }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A row that goes somewhere: a page of this screen, or out of the app. The whole row is the target.
 *
 * [route] is the page it opens, when it opens one; the row is marked as selected while that page
 * shows beside the list.
 */
@Composable
internal fun NavRow(
    title: String,
    summary: String,
    tag: String,
    onClick: () -> Unit,
    icon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    route: String? = null,
) {
    val showing = LocalShowingPage.current
    val selected = route != null && route == showing
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                },
            ).clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .testTag(tag)
            // beside a page the rows carry a highlight, which needs padding either side of the text
            .padding(horizontal = if (showing != null) 12.dp else 0.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Icon(icon, contentDescription = null)
    }
}
