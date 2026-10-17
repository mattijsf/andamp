// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.mattix.andamp.R
import nl.mattix.andamp.state.DspOps
import nl.mattix.andamp.state.PluginEntry
import nl.mattix.andamp.state.PluginOps

/**
 * The plug-ins the listener added, with a way to add another. Transient results go through the
 * shared snackbar; a refusal, a replacement or a removal is a dialog.
 */
@Composable
internal fun InstalledPlugins(
    plugins: PluginOps,
    dsp: DspOps,
    snackbars: SnackbarHostState,
    scope: CoroutineScope,
    onPick: () -> Unit,
) {
    if (plugins.waiting || plugins.fetching) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.prefs_plugins_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onPick,
        enabled = !plugins.waiting,
        modifier = Modifier.testTag("prefs.plugins.add"),
    ) {
        if (plugins.waiting) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.prefs_plugins_adding))
        } else {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.prefs_plugins_add))
        }
    }
    MoreEffectsLink()

    RefusalDialog(plugins)
    plugins.replacement?.let { ReplaceDialog(it, plugins) }
    plugins.added?.let { AddedDialog(it, plugins) }
    plugins.offer?.let { OfferDialog(it, plugins) }

    if (plugins.entries.isEmpty()) {
        EmptyState()
        return
    }

    plugins.entries.forEach { entry ->
        Spacer(Modifier.height(12.dp))
        PluginCard(entry, plugins, dsp, snackbars, scope)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.prefs_plugins_builtin_always),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.prefs_plugins_none), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.prefs_plugins_none_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PluginCard(
    entry: PluginEntry,
    plugins: PluginOps,
    dsp: DspOps,
    snackbars: SnackbarHostState,
    scope: CoroutineScope,
) {
    var confirming by remember { mutableStateOf(false) }
    val removeNamed = stringResource(R.string.prefs_plugins_remove_named, entry.name)
    // the plug-in just added is brought into view and lit up for a moment, so the
    // listener sees which row is new
    val glow = remember { Animatable(0f) }
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(plugins.justAdded == entry.pluginId) {
        if (plugins.justAdded != entry.pluginId) return@LaunchedEffect
        inView.bringIntoView()
        glow.animateTo(1f, tween(GLOW_IN_MS))
        glow.animateTo(0f, tween(GLOW_OUT_MS))
        plugins.pointedOut()
    }
    val lit = lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, glow.value * GLOW_STRENGTH)
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(inView)
            .testTag("plugin.${entry.pluginId}.card"),
        colors = CardDefaults.outlinedCardColors(containerColor = lit),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("plugin.${entry.pluginId}.name"),
                    )
                    Text(
                        entry.credit(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // the card's one action, shown directly instead of in a one-item menu
                IconButton(
                    onClick = { confirming = true },
                    modifier =
                        Modifier
                            .semantics { contentDescription = removeNamed }
                            .testTag("plugin.${entry.pluginId}.remove"),
                ) { Icon(Icons.Outlined.Delete, contentDescription = null) }
            }
            if (entry.about.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // a plug-in can install and still not run: the graph is built again
            // for whatever a stream turns out to be, and can fail there
            if (dsp.available && !plugins.waiting && dsp.catalogue.none { it.id == entry.pluginId }) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.prefs_plugins_cannot_run),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("plugin.${entry.pluginId}.missing"),
                    )
                }
            }
        }
    }
    if (confirming) {
        val removed = stringResource(R.string.prefs_plugins_removed, entry.name)
        // a confirmation, not an undo: removal deletes the stored copy
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.prefs_plugins_remove_question, entry.name)) },
            text = { Text(stringResource(R.string.prefs_plugins_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        plugins.remove(entry.id)
                        scope.launch { snackbars.showSnackbar(removed) }
                    },
                    modifier = Modifier.testTag("plugin.${entry.pluginId}.remove.confirm"),
                ) { Text(stringResource(R.string.prefs_plugins_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.prefs_plugins_cancel)) }
            },
        )
    }
}

/** Why a file was refused, as a dialog. Dismissing it marks the problem as seen. */
@Composable
private fun RefusalDialog(plugins: PluginOps) {
    val problem = plugins.problem
    if (problem != null) {
        AlertDialog(
            onDismissRequest = { plugins.seen() },
            icon = { Icon(Icons.Filled.ErrorOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.prefs_plugins_refused_title)) },
            text = {
                Text(problem, modifier = Modifier.testTag("prefs.plugins.problem"))
            },
            confirmButton = {
                TextButton(onClick = { plugins.seen() }) { Text(stringResource(R.string.prefs_plugins_ok)) }
            },
        )
    }
}

/**
 * What an install did, with the plug-in's name, version and author, and where the result is: at the
 * end of the rack and switched off, or in the place of the one it updated.
 */
@Composable
private fun AddedDialog(
    added: PluginOps.Added,
    plugins: PluginOps,
) {
    val entry = added.entry
    val (title, body) =
        when (added) {
            is PluginOps.Added.New -> {
                stringResource(R.string.prefs_plugins_added_title) to stringResource(R.string.prefs_plugins_added_body)
            }

            is PluginOps.Added.Updated -> {
                val replaced = added.previous.version.takeIf { it.isNotBlank() && it != entry.version }
                val kept =
                    if (replaced == null) {
                        stringResource(R.string.prefs_plugins_updated_body)
                    } else {
                        stringResource(R.string.prefs_plugins_updated_body_replacing, replaced)
                    }
                stringResource(R.string.prefs_plugins_updated_title) to kept
            }

            is PluginOps.Added.Unchanged -> {
                stringResource(R.string.prefs_plugins_unchanged_title) to stringResource(R.string.prefs_plugins_unchanged_body)
            }
        }
    AlertDialog(
        onDismissRequest = { plugins.seen() },
        icon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("prefs.plugins.added.name"),
                )
                Text(
                    entry.credit(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("prefs.plugins.added.credit"),
                )
                Spacer(Modifier.height(12.dp))
                Text(body)
            }
        },
        confirmButton = {
            TextButton(onClick = { plugins.seen() }, modifier = Modifier.testTag("prefs.plugins.added.ok")) {
                Text(stringResource(R.string.prefs_plugins_ok))
            }
        },
        modifier = Modifier.testTag("prefs.plugins.added"),
    )
}

/**
 * A plug-in a link offered: its name, credit and description, and where it came from, before
 * anything is installed.
 */
@Composable
private fun OfferDialog(
    offer: PluginOps.Offer,
    plugins: PluginOps,
) {
    val entry = offer.entry
    AlertDialog(
        onDismissRequest = { plugins.declineOffer() },
        icon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
        title = { Text(stringResource(R.string.prefs_plugins_offer_title)) },
        text = {
            Column {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("prefs.plugins.offer.name"),
                )
                Text(
                    entry.credit(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("prefs.plugins.offer.credit"),
                )
                if (entry.about.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(entry.about)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.prefs_plugins_offer_from, offer.from),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { plugins.acceptOffer() },
                modifier = Modifier.testTag("prefs.plugins.offer.add"),
            ) { Text(stringResource(R.string.prefs_plugins_offer_add)) }
        },
        dismissButton = {
            TextButton(
                onClick = { plugins.declineOffer() },
                modifier = Modifier.testTag("prefs.plugins.offer.cancel"),
            ) { Text(stringResource(R.string.prefs_plugins_cancel)) }
        },
        modifier = Modifier.testTag("prefs.plugins.offer"),
    )
}

/**
 * Asked before a plug-in already installed is replaced by another file
 * claiming to be it: the new file takes the old one's place in the rack,
 * settings included.
 */
@Composable
private fun ReplaceDialog(
    replacement: PluginOps.Replacement,
    plugins: PluginOps,
) {
    AlertDialog(
        onDismissRequest = { plugins.keep() },
        title = { Text(stringResource(R.string.prefs_plugins_replace_question, replacement.installed.name)) },
        text = {
            Text(
                stringResource(
                    R.string.prefs_plugins_replace_body,
                    replacement.installed.name,
                    replacement.installed.versionOrNothing(),
                    replacement.incoming.name,
                    replacement.incoming.versionOrNothing(),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { plugins.replace() },
                modifier = Modifier.testTag("prefs.plugins.replace"),
            ) { Text(stringResource(R.string.prefs_plugins_replace)) }
        },
        dismissButton = {
            TextButton(
                onClick = { plugins.keep() },
                modifier = Modifier.testTag("prefs.plugins.keep"),
            ) { Text(stringResource(R.string.prefs_plugins_cancel)) }
        },
        modifier = Modifier.testTag("prefs.plugins.replacing"),
    )
}

/** The version in brackets, as it follows a plug-in's name, or nothing for a plug-in that declared none. */
private fun PluginEntry.versionOrNothing() = version.takeIf { it.isNotBlank() }?.let { "($it)" } ?: ""

/** Version and author, whichever of them the plug-in declared. */
@Composable
private fun PluginEntry.credit(): String {
    val declared =
        listOfNotNull(
            if (version.isNotBlank()) stringResource(R.string.prefs_plugins_credit_version, version) else null,
            if (author.isNotBlank()) stringResource(R.string.prefs_plugins_credit_by, author) else null,
        )
    return if (declared.isEmpty()) stringResource(R.string.prefs_plugins_credit_none) else declared.joinToString(", ")
}

/** The site's page of effect plug-ins, each with an Install link that opens Andamp. */
internal const val MORE_EFFECTS_URL = "https://andamp.nl/extensions/plugins"

/**
 * A link to more effects, shown on the rack and on the list of added plug-ins. What is offered is
 * listed on the site, not in this build.
 */
@Composable
internal fun MoreEffectsLink() {
    val links = androidx.compose.ui.platform.LocalUriHandler.current
    Spacer(Modifier.height(4.dp))
    NavRow(
        title = stringResource(R.string.prefs_plugins_more_title),
        summary = stringResource(R.string.prefs_plugins_more_summary),
        tag = "prefs.plugins.more",
        icon = Icons.AutoMirrored.Filled.OpenInNew,
        onClick = { links.openUri(MORE_EFFECTS_URL) },
    )
}

/** How long the just-added plug-in's row takes to light up, and to fade again. */
private const val GLOW_IN_MS = 250
private const val GLOW_OUT_MS = 1200

/** How far the row's color goes toward the theme's accent at its brightest. */
private const val GLOW_STRENGTH = 0.6f
