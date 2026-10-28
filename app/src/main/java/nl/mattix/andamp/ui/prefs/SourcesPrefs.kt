// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import nl.mattix.andamp.R
import nl.mattix.andamp.state.ExtraSource
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.MusicPermission
import nl.mattix.andamp.state.MusicSource
import nl.mattix.andamp.state.PhoneStats
import nl.mattix.andamp.state.ScanProgress
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.SourceUpdates
import nl.mattix.andamp.state.Words
import nl.mattix.andamp.state.shown
import nl.mattix.andamp.state.sourceRoute
import nl.mattix.andamp.ui.rememberWords
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
internal const val MORE_SOURCES_URL = "https://andamp.nl/extensions/source"

@Composable
internal fun SourcesSection(
    access: LibraryAccess,
    sources: SourcesPrefs,
    onPhone: () -> Unit,
    onSource: (MusicSource) -> Unit,
) {
    val links = LocalUriHandler.current
    val words = rememberWords()
    Section(stringResource(R.string.prefs_sources_section))
    Group {
        NavRow(
            title = MusicSource.LOCAL.shown(words),
            summary = phoneSummary(access, words),
            tag = "prefs.source.phone",
            onClick = onPhone,
            leading = Icons.Outlined.PhoneAndroid,
            route = PHONE_PAGE,
        )
        sources.extras.forEach { extra ->
            NavRow(
                title = extra.source.label,
                summary = extra.Warning(signedIn = extra.source in sources.signedIn) ?: stringResource(extra.summary()),
                tag = "prefs.${sourceRoute(extra.source).replace('/', '.')}",
                onClick = { onSource(extra.source) },
                leading = Icons.Outlined.LibraryMusic,
                leadingContent = sourceIcon(extra),
                route = sourceRoute(extra.source),
            )
        }
        // always last, and a link: the available sources are listed on the site, not in this build
        NavRow(
            title = stringResource(R.string.prefs_sources_more_title),
            summary = stringResource(R.string.prefs_sources_more_summary),
            tag = "prefs.source.more",
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            leading = Icons.Outlined.AddCircleOutline,
            onClick = { links.openUri(MORE_SOURCES_URL) },
        )
    }
}

/** The icon of the source's app, drawn like the glyphs beside it; null falls back to the generic one. */
@Composable
private fun sourceIcon(extra: ExtraSource): (@Composable () -> Unit)? {
    val context = LocalContext.current
    val glyph = remember(extra) { extra.icon(context)?.glyph() } ?: return null
    return {
        Image(
            glyph.image,
            contentDescription = null,
            colorFilter = if (glyph.tinted) ColorFilter.tint(LocalContentColor.current) else null,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** An app icon reduced to a row's glyph; [tinted] says it is a shape to draw in the row's color. */
private class Glyph(
    val image: ImageBitmap,
    val tinted: Boolean,
)

/**
 * An adaptive icon's monochrome layer, where it has one, is a shape and takes the row's color like
 * the glyphs beside it. Without one the icon's foreground is shown as drawn, in color; a flat icon
 * is taken whole. Either layer is cropped to the zone an adaptive icon keeps its art in.
 */
private fun Drawable.glyph(): Glyph {
    val adaptive = this as? AdaptiveIconDrawable ?: return Glyph(toBitmap().asImageBitmap(), tinted = false)
    val monochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) adaptive.monochrome else null
    val layer = monochrome ?: adaptive.foreground ?: return Glyph(toBitmap().asImageBitmap(), tinted = false)
    val whole = layer.toBitmap(ADAPTIVE_CANVAS, ADAPTIVE_CANVAS)
    val inset = (ADAPTIVE_CANVAS - ADAPTIVE_SAFE) / 2
    val art = Bitmap.createBitmap(whole, inset, inset, ADAPTIVE_SAFE, ADAPTIVE_SAFE)
    return Glyph(art.asImageBitmap(), tinted = monochrome != null)
}

/** An adaptive icon layer's canvas, in pixels here, and the zone in its middle the art is kept to: 72 of 108. */
private const val ADAPTIVE_CANVAS = 216
private const val ADAPTIVE_SAFE = 144

/**
 * The phone row's line: what the page holds, or, while the music cannot be read, what to allow,
 * since nothing from the phone plays until it is.
 */
internal fun phoneSummary(
    access: LibraryAccess,
    words: Words,
): String =
    when (access) {
        LibraryAccess.BLOCKED -> words.get(R.string.prefs_phone_permission_off, MusicPermission.current(words))
        LibraryAccess.ASKABLE -> words.get(R.string.prefs_phone_allow, MusicPermission.current(words))
        LibraryAccess.GRANTED -> words.get(R.string.prefs_phone_summary)
    }

/**
 * What a source's page holds, for its row's line: its account and settings, then the skin and the
 * update check where the page shows them.
 */
@StringRes
internal fun ExtraSource.summary(): Int =
    when {
        skinnable && updates != null -> R.string.prefs_source_summary_skin_updates
        skinnable -> R.string.prefs_source_summary_skin
        updates != null -> R.string.prefs_source_summary_updates
        else -> R.string.prefs_source_summary
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
    val words = rememberWords()

    Section(stringResource(R.string.prefs_phone_access_section))
    Note(access.summary(MusicPermission.current(words), words))
    access.action(words)?.let { action ->
        Button(
            // when access is blocked the button opens the app's system settings directly
            onClick = { if (access == LibraryAccess.BLOCKED) context.openAppSettings() else onRequestAccess() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("prefs.library.action"),
        ) { Text(action) }
    }
    // counting and scanning read the index the permission guards, so they are not shown without it
    if (access != LibraryAccess.GRANTED) return

    Section(stringResource(R.string.prefs_phone_library_section))
    val stats = phone.stats
    if (stats == null) {
        Note(stringResource(R.string.prefs_phone_counting))
    } else {
        Group {
            StatRow(stringResource(R.string.prefs_phone_tracks), String.format(Locale.US, "%,d", stats.tracks))
            StatRow(stringResource(R.string.prefs_phone_artists), String.format(Locale.US, "%,d", stats.artists))
            StatRow(stringResource(R.string.prefs_phone_albums), String.format(Locale.US, "%,d", stats.albums))
            StatRow(stringResource(R.string.prefs_phone_playing_time), stats.listening(words))
            StatRow(stringResource(R.string.prefs_phone_on_storage), stats.size)
        }
    }

    Section(stringResource(R.string.prefs_phone_scan_section))
    Note(stringResource(R.string.prefs_phone_scan_note))
    val scanning = phone.scanning
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Button(
            onClick = phone.onScan,
            enabled = scanning == null,
            modifier = Modifier.testTag("prefs.phone.scan"),
        ) { Text(stringResource(if (scanning == null) R.string.prefs_phone_scan_now else R.string.prefs_phone_scanning)) }
        if (scanning != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { scanning.fraction },
                modifier = Modifier.fillMaxWidth().testTag("prefs.phone.progress"),
            )
            Spacer(Modifier.height(4.dp))
            Text(scanning.words(words), style = MaterialTheme.typography.bodySmall)
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
        Section(stringResource(R.string.prefs_source_settings_section))
        Group {
            NavRow(
                title = stringResource(R.string.prefs_source_settings_title, extra.source.label),
                summary = stringResource(R.string.prefs_source_settings_summary),
                tag = "prefs.source.settings",
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = { sources.openSettings(settings) },
            )
        }
        // who made the source: it plays the listener's music in its own app, so the page shows what
        // [ExtraSource.provenance] reports about it
        extra.provenance(context)?.let { whose ->
            Text(
                whose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("prefs.source.whose"),
            )
        }
    }
    if (extra.skinnable) {
        Section(stringResource(R.string.prefs_source_skin_section))
        SourceSkinRow(extra.source, sources)
    }
    val updates = extra.updates ?: return
    Section(stringResource(R.string.prefs_source_updates_section))
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
    Text(
        stringResource(R.string.prefs_source_skin_note, source.label),
        style = MaterialTheme.typography.bodyMedium,
    )
    SkinPickRow(
        skins = sources.skins,
        chosen = sources.skinOf(source),
        heading = stringResource(R.string.prefs_source_skin_heading, source.label),
        tag = "prefs.skin",
        onPick = { sources.onSkin(source, it) },
    )
}

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
            Text(stringResource(R.string.prefs_updates_checking), style = MaterialTheme.typography.bodyMedium)
        }

        is SourceUpdates.Check.UpToDate -> {
            Text(
                stringResource(R.string.prefs_updates_current, now.version),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("prefs.updates.current"),
            )
        }

        is SourceUpdates.Check.Available -> {
            Text(
                if (installed == null) {
                    stringResource(R.string.prefs_updates_available, now.latest.version)
                } else {
                    stringResource(R.string.prefs_updates_available_over, now.latest.version, installed)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("prefs.updates.available"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.prefs_updates_how),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { links.openUri(now.latest.page) },
                modifier = Modifier.testTag("prefs.updates.download"),
            ) { Text(stringResource(R.string.prefs_updates_open)) }
        }

        SourceUpdates.Check.Failed -> {
            Text(
                stringResource(R.string.prefs_updates_failed),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("prefs.updates.failed"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.prefs_updates_retry)) }
        }
    }
}
