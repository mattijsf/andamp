// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.mattix.andamp.core.model.EffectSpec
import nl.mattix.andamp.core.model.RackSlot
import nl.mattix.andamp.state.DspOps

/**
 * The effect rack: one card per plug-in, in signal order.
 *
 * Each card has two independent facts: whether the effect is heard (the switch) and whether its
 * controls are shown (tapping the header). An effect that is off keeps live controls, so it can be
 * set up before it is switched in, and switching one on opens it.
 */
@Composable
fun RackSection(
    dsp: DspOps,
    snackbars: SnackbarHostState,
    scope: CoroutineScope,
    /**
     * True draws the pinned input stage instead of the effects: the same card, in its own section
     * above the rack because its place does not move. In the audio it is still among the rack's
     * slots.
     */
    input: Boolean = false,
) {
    val rack = dsp.rack
    val drag = remember { RackDrag() }
    // view state, not audio state: which cards are open is keyed by plug-in, so it survives a
    // reorder
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    val shown = rack.slots.filter { dsp.pinned(it.pluginId) == input }
    shown.forEachIndexed { index, slot ->
        val spec = dsp.specFor(slot.pluginId) ?: return@forEachIndexed
        // keyed by the plug-in, not its position, so moving one moves its card
        key(slot.pluginId) {
            EffectCard(
                spec = spec,
                slot = slot,
                position = if (input) "In" else "${index + 1}",
                first = index == 0,
                last = index == shown.lastIndex,
                open = opened[slot.pluginId] == true,
                onOpen = { opened[slot.pluginId] = it },
                dsp = dsp,
                drag = drag,
                snackbars = snackbars,
                scope = scope,
            )
        }
    }
}

@Suppress("LongParameterList") // a card is its effect, its place, its state and its plumbing
@Composable
private fun EffectCard(
    spec: EffectSpec,
    slot: RackSlot,
    position: String,
    first: Boolean,
    last: Boolean,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    dsp: DspOps,
    drag: RackDrag,
    snackbars: SnackbarHostState,
    scope: CoroutineScope,
) {
    val carried = drag.carried == spec.id
    // tone says heard or not; the controls stay live either way
    val tone by animateColorAsState(
        if (slot.enabled) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "cardTone",
    )
    Column(
        Modifier
            // measured with the gap above the card, so a drag counts whole slots
            .onSizeChanged { drag.measured(spec.id, it.height) }
            .zIndex(if (carried) 1f else 0f)
            .graphicsLayer { translationY = if (carried) drag.offset else 0f },
    ) {
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = tone),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (carried) CARRIED_LIFT else 0.dp,
                        shape = CardDefaults.shape,
                        clip = false,
                    ),
        ) {
            // the whole header is the tap target; the switch, menu and handle sit on top and take
            // their own presses first
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .clickable(onClickLabel = if (open) "Hide controls" else "Show controls") { onOpen(!open) }
                        .testTag("dsp.${spec.id}.expand")
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                NodeBadge(position, slot.enabled)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        spec.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("dsp.${spec.id}.title"),
                    )
                    StatusLine(spec, slot)
                }
                if (dsp.pinned(spec.id)) {
                    // pinned: nothing to drag, and the space is kept so the switches still line up
                    // down the page
                    Spacer(Modifier.size(TARGET))
                } else {
                    DragHandle(spec, dsp, drag)
                }
                CardMenu(spec, slot, dsp, first || dsp.pinned(spec.id), last || dsp.pinned(spec.id), snackbars, scope)
                Switch(
                    checked = slot.enabled,
                    onCheckedChange = {
                        dsp.setEnabled(spec.id, it)
                        // switching on opens the card; switching off does not close it
                        if (it) onOpen(true)
                    },
                    modifier = Modifier.testTag("dsp.${spec.id}.on"),
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (spec.description.isNotBlank()) {
                        Text(
                            spec.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Layout(spec, slot, dsp)
                }
            }
        }
    }
}

/** The effect's position in the chain: filled when the effect is heard, hollow when it is off. */
@Composable
private fun NodeBadge(
    position: String,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .then(
                    if (enabled) {
                        Modifier.background(colors.primary, CircleShape)
                    } else {
                        Modifier.border(1.5.dp, colors.outline, CircleShape)
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            position,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

/**
 * One line under the card's title. Off: the first sentence of the effect's description. On: what it
 * is set to, from [RackDigest].
 */
@Composable
private fun StatusLine(
    spec: EffectSpec,
    slot: RackSlot,
) {
    val line =
        if (slot.enabled) {
            RackDigest.statusOf(spec, slot)
        } else {
            spec.description.substringBefore(". ").ifBlank { spec.description }
        }
    if (line.isNotBlank()) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Hold this, then drag, to move an effect through the chain. The long press comes first so that a
 * thumb scrolling the page does not reorder the chain, and it is confirmed with a haptic tick. The
 * menu keeps Move up and Move down as the path that needs no drag, including for a screen reader.
 */
@Composable
private fun DragHandle(
    spec: EffectSpec,
    dsp: DspOps,
    drag: RackDrag,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(TARGET)
                .semantics { contentDescription = "Drag to move ${spec.name}" }
                .testTag("dsp.${spec.id}.drag")
                .pointerInput(spec.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            drag.start(spec.id)
                        },
                        onDragEnd = { drag.stop() },
                        onDragCancel = { drag.stop() },
                        onDrag = { change, dragged ->
                            change.consume()
                            // the order is read here, not captured when the handle was composed:
                            // this gesture outlives several reorders
                            val order = dsp.rack.slots.map { it.pluginId }
                            drag.drag(dragged.y, order)?.let {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                dsp.moveTo(spec.id, it)
                            }
                        },
                    )
                },
    ) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The card's menu. Move up and down are the accessible reorder path. Restore and reset offer an
 * undo, because they replace the card's values.
 */
@Suppress("LongParameterList", "LongMethod") // a menu is its items, and each carries its plumbing
@Composable
private fun CardMenu(
    spec: EffectSpec,
    slot: RackSlot,
    dsp: DspOps,
    first: Boolean,
    last: Boolean,
    snackbars: SnackbarHostState,
    scope: CoroutineScope,
) {
    var open by remember { mutableStateOf(false) }

    /** Runs [act], which replaces the card's values, and offers an undo. */
    fun replacing(
        said: String,
        act: () -> Unit,
    ) {
        val before = slot.params.values
        act()
        open = false
        scope.launch {
            val answer = snackbars.showSnackbar(said, actionLabel = "Undo")
            if (answer == SnackbarResult.ActionPerformed) dsp.apply(spec.id, before)
        }
    }
    Box {
        IconButton(
            onClick = { open = true },
            modifier =
                Modifier
                    .semantics { contentDescription = "More for ${spec.name}" }
                    .testTag("dsp.${spec.id}.menu"),
        ) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Move up") },
                enabled = !first,
                onClick = {
                    dsp.moveUp(spec.id)
                    open = false
                },
                modifier = Modifier.testTag("dsp.${spec.id}.up"),
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                enabled = !last,
                onClick = {
                    dsp.moveDown(spec.id)
                    open = false
                },
                modifier = Modifier.testTag("dsp.${spec.id}.down"),
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Save settings") },
                onClick = {
                    dsp.remember(spec.id)
                    open = false
                    scope.launch { snackbars.showSnackbar("${spec.name} settings saved") }
                },
                modifier = Modifier.testTag("dsp.${spec.id}.menu.keep"),
            )
            DropdownMenuItem(
                text = { Text("Restore saved settings") },
                enabled = dsp.hasRemembered(spec.id),
                onClick = { replacing("${spec.name} settings restored") { dsp.restore(spec.id) } },
                modifier = Modifier.testTag("dsp.${spec.id}.menu.restore"),
            )
            DropdownMenuItem(
                text = { Text("Reset to defaults") },
                onClick = { replacing("${spec.name} reset to defaults") { dsp.reset(spec.id) } },
                modifier = Modifier.testTag("dsp.${spec.id}.menu.reset"),
            )
        }
    }
}

internal val TARGET = 36.dp

/** How far the carried card lifts off the others: M3's dragged level. */
private val CARRIED_LIFT = 8.dp
