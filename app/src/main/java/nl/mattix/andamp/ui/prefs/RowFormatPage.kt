// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import nl.mattix.andamp.R
import nl.mattix.andamp.core.model.Track
import nl.mattix.andamp.state.RowFormat
import nl.mattix.andamp.state.TrackField
import nl.mattix.andamp.state.TrackInfo

/** The route of Preferences > Player > Song rows. */
internal const val ROWS_PAGE = "player/rows"

/** The longest text a listener can type between two blocks. */
private const val MAX_TEXT = 24

/** The playlist's colors in Winamp's own skin, for the preview: green rows, the current one white. */
private val PREVIEW_BACK = Color(0xFF000000)
private val PREVIEW_TEXT = Color(0xFF00FF00)
private val PREVIEW_CURRENT = Color(0xFFFFFFFF)

/** The Lists group's row that opens the page of how a list writes a song; it says the format in the fields' names. */
@Composable
internal fun RowFormatRow(
    prefs: ListPrefs,
    onOpen: () -> Unit,
) {
    NavRow(
        title = stringResource(R.string.prefs_page_rows),
        summary = spelled(prefs.rowFormat),
        tag = "prefs.lists.format",
        onClick = onOpen,
    )
}

/**
 * Preferences > Player > Song rows: the blocks a row of the playlist and the library writes a song
 * with, in order, and the text before, between and after them. A block is moved by holding and
 * dragging it. Only the fields the library has for at least one song can be added.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RowFormatPage(prefs: ListPrefs) {
    val format = prefs.rowFormat
    var known by remember { mutableStateOf(TrackField.entries.filter { it.onTrack }.toSet()) }
    var playing by remember { mutableStateOf<Pair<Track, TrackInfo?>?>(null) }
    LaunchedEffect(Unit) {
        known = prefs.knownFields()
        playing = prefs.playing()
    }
    Section(stringResource(R.string.prefs_rows_preview_section))
    Preview(listOfNotNull(spelled(format), playing?.let { (track, info) -> format.write(track, info) }))

    Section(stringResource(R.string.prefs_rows_blocks_section))
    Blocks(format, prefs.onRowFormat)
    About(stringResource(R.string.prefs_rows_blocks_about))

    val addable = TrackField.entries.filter { it in known && it !in format.fields }
    if (addable.isNotEmpty()) {
        Section(stringResource(R.string.prefs_rows_add_section))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
            addable.forEach { field ->
                AssistChip(
                    onClick = { prefs.onRowFormat(format.added(field)) },
                    label = { Text(stringResource(nameOf(field))) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier.testTag("prefs.rows.add.${field.code}"),
                )
            }
        }
        About(stringResource(R.string.prefs_rows_add_about))
    }
    TextButton(
        onClick = { prefs.onRowFormat(RowFormat.DEFAULT) },
        enabled = format != RowFormat.DEFAULT,
        modifier = Modifier.padding(top = 16.dp).testTag("prefs.rows.reset"),
    ) { Text(stringResource(R.string.prefs_rows_reset)) }
}

/** The format as playlist rows in Winamp's colors: in the fields' names, then the song playing, as the current row. */
@Composable
private fun Preview(lines: List<String>) {
    Surface(
        color = PREVIEW_BACK,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag("prefs.rows.preview"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            lines.forEachIndexed { at, line ->
                Text(
                    "${at + 1}. $line",
                    color = if (at == 0) PREVIEW_TEXT else PREVIEW_CURRENT,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The blocks in order, with a text field before the first, between each two and after the last.
 * Dragging a block moves only the fields: the text between them keeps its place.
 */
@Composable
private fun Blocks(
    format: RowFormat,
    onFormat: (RowFormat) -> Unit,
) {
    val drag = remember { RackDrag() }
    // a drag outlives several reorders, so the gesture reads the format as it is now
    val now by rememberUpdatedState(format)
    val change by rememberUpdatedState(onFormat)
    Column(Modifier.fillMaxWidth()) {
        Piece(format.lead, R.string.prefs_rows_before, "prefs.rows.before") { change(now.copy(lead = it)) }
        format.fields.forEachIndexed { at, field ->
            key(field) {
                val carried = drag.carried == field.code
                // measured with the text after it, so a drag passes a whole block and its text
                Column(
                    Modifier
                        .onSizeChanged { drag.measured(field.code, it.height) }
                        .zIndex(if (carried) 1f else 0f),
                ) {
                    Block(
                        field = field,
                        carried = carried,
                        offset = { if (carried) drag.offset else 0f },
                        removable = format.fields.size > 1,
                        onRemove = { change(now.removed(now.fields.indexOf(field))) },
                        onDrag = { delta ->
                            val order = now.fields.map { it.code }
                            drag.drag(delta, order)?.also { change(now.moved(now.fields.indexOf(field), it)) } != null
                        },
                        onStart = { drag.start(field.code) },
                        onStop = drag::stop,
                    )
                    if (at < format.gaps.size) {
                        Piece(format.gaps[at], R.string.prefs_rows_between, "prefs.rows.gap.$at", indent = true) {
                            change(now.withGap(at, it))
                        }
                    }
                }
            }
        }
        Piece(format.trail, R.string.prefs_rows_after, "prefs.rows.after") { change(now.copy(trail = it)) }
    }
}

/** One field's block: held and dragged to move it, with a button that takes it out. */
@Suppress("LongParameterList") // the block, how it moves, and what its button does
@Composable
private fun Block(
    field: TrackField,
    carried: Boolean,
    offset: () -> Float,
    removable: Boolean,
    onRemove: () -> Unit,
    /** Moves the block by a drag's step; true when it took another place. */
    onDrag: (Float) -> Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val name = stringResource(nameOf(field))
    val dragToMove = stringResource(R.string.prefs_rack_drag_to_move, name)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = if (carried) 6.dp else 0.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .graphicsLayer { translationY = offset() }
                .semantics { contentDescription = dragToMove }
                .testTag("prefs.rows.block.${field.code}")
                .pointerInput(field) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStart()
                        },
                        onDragEnd = onStop,
                        onDragCancel = onStop,
                        onDrag = { change, dragged ->
                            change.consume()
                            if (onDrag(dragged.y)) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                    )
                },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Icon(Icons.Filled.DragHandle, contentDescription = null)
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(12.dp))
            if (removable) {
                val remove = stringResource(R.string.prefs_rows_remove, name)
                IconButton(onClick = onRemove, modifier = Modifier.testTag("prefs.rows.remove.${field.code}")) {
                    Icon(Icons.Filled.Close, contentDescription = remove)
                }
            }
        }
    }
}

/** A text field for the text at one place of the format; [indent] sets the text between two blocks in from them. */
@Composable
private fun Piece(
    text: String,
    @StringRes label: Int,
    tag: String,
    indent: Boolean = false,
    onText: (String) -> Unit,
) {
    OutlinedTextField(
        value = text,
        onValueChange = { onText(it.replace("\n", "").take(MAX_TEXT)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = if (indent) 32.dp else 0.dp, top = 2.dp, bottom = 2.dp)
                .testTag(tag),
    )
}

@Composable
private fun About(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The format in the fields' names, as the preview's first row and the Lists row's line say it. */
@Composable
private fun spelled(format: RowFormat): String {
    val names = TrackField.entries.associateWith { stringResource(nameOf(it)) }
    return format.spelled(names::getValue)
}

@StringRes
private fun nameOf(field: TrackField): Int =
    when (field) {
        TrackField.ARTIST -> R.string.prefs_rows_field_artist
        TrackField.TITLE -> R.string.prefs_rows_field_title
        TrackField.ALBUM -> R.string.prefs_rows_field_album
        TrackField.NUMBER -> R.string.prefs_rows_field_number
        TrackField.YEAR -> R.string.prefs_rows_field_year
        TrackField.GENRE -> R.string.prefs_rows_field_genre
    }
