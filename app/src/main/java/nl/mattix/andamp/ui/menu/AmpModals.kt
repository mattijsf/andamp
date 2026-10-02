// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.AmpMenu
import nl.mattix.andamp.state.AmpMenuItem
import nl.mattix.andamp.state.AmpPrompt
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.NamePrompt
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.ui.about.AboutDialog
import nl.mattix.andamp.ui.theme.rememberSkinColorScheme

/**
 * Renders whatever modal the state asks for (context menu, pickers, prompts) in a Material theme.
 *
 * [anchorBounds] maps a menu's [MenuAnchor] to device-pixel bounds inside the composable hosting
 * AmpModals; the popup opens from that rectangle.
 */
@Composable
fun AmpModals(
    s: WinampState,
    anchorBounds: (MenuAnchor?) -> IntRect,
    modifier: Modifier = Modifier,
    /** Colors the Material theme after the skin on screen; null uses Material's dark scheme. */
    skin: nl.mattix.andamp.skin.Skin? = null,
) {
    val scheme = skin?.let { rememberSkinColorScheme(it) } ?: darkColorScheme()
    MaterialTheme(colorScheme = scheme) {
        s.activeMenu?.let { menu ->
            AmpContextMenu(menu, anchorBounds(menu.anchor), modifier) { s.activeMenu = null }
        }
        s.presetPicker?.let { picker ->
            PresetPickerDialog(picker) { if (s.presetPicker === picker) s.presetPicker = null }
        }
        s.trackInfo?.let { info ->
            TrackInfoDialog(info) { if (s.trackInfo === info) s.trackInfo = null }
        }
        s.choiceSheet?.let { sheet ->
            ChoiceSheetDialog(sheet) { if (s.choiceSheet === sheet) s.choiceSheet = null }
        }
        // drawn here so it also shows on the floating window, which has no screens
        if (s.aboutOpen) AboutDialog { s.aboutOpen = false }
        s.bookmarkSheet?.let { sheet ->
            BookmarkSheetDialog(sheet) { if (s.bookmarkSheet === sheet) s.bookmarkSheet = null }
        }
        // a permission prompt raised from the player (the clutter bar's A) and a backend's notice
        // are drawn here
        AmpPromptHost(s)
        s.namePrompt?.let { prompt ->
            // clear only the prompt this dialog showed: an onSubmit may chain a
            // follow-up prompt (radio's url-then-name), and the dialog's own
            // dismissal must not tear that one down
            NamePromptDialog(prompt) { if (s.namePrompt === prompt) s.namePrompt = null }
        }
    }
}

@Composable
internal fun AmpContextMenu(
    menu: AmpMenu,
    anchor: IntRect,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    // submenu navigation happens in place, like Winamp's cascades collapsed into one panel
    var path by remember(menu) { mutableStateOf(listOf<AmpMenuItem.Submenu>()) }
    val density = LocalDensity.current
    // an empty box standing in for the opening widget; DropdownMenu anchors to
    // it and handles screen-edge flipping
    Box(
        modifier
            .offset { IntOffset(anchor.left, anchor.top) }
            .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() })
            .testTag("menu.anchor"),
    ) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
            val open = path.lastOrNull()
            if (open != null) {
                MenuRow(open.label, leading = "‹", emphasized = true) { path = path.dropLast(1) }
                HorizontalDivider()
            }
            (open?.items ?: menu.items).forEach { item ->
                when (item) {
                    is AmpMenuItem.Action -> {
                        MenuRow(item.label, enabled = item.enabled, leading = if (item.checked) "\u2713" else null) {
                            item.onClick()
                            onDismiss()
                        }
                    }

                    is AmpMenuItem.Submenu -> {
                        MenuRow(item.label, enabled = item.enabled, trailing = "›") { path = path + item }
                    }

                    AmpMenuItem.Divider -> {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    enabled: Boolean = true,
    leading: String? = null,
    trailing: String? = null,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .widthIn(min = MIN_WIDTH.dp)
            .heightIn(min = ROW_HEIGHT.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color =
            if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        val style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
        if (leading != null) {
            Text(leading, color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
        }
        Text(label, color = color, style = style, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = color, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A titled message; see [AmpPrompt].
 *
 * Its own composable because it has two hosts: the player draws it through [AmpModals], and the
 * modern screens draw it themselves. Only one of those is on screen at a time, so the message is
 * drawn once.
 */
@Composable
fun AmpPromptHost(state: WinampState) {
    state.prompt?.let { prompt ->
        AmpPromptDialog(prompt) { if (state.prompt === prompt) state.prompt = null }
    }
}

@Composable
private fun AmpPromptDialog(
    prompt: AmpPrompt,
    onDismiss: () -> Unit,
) {
    AmpAlert(
        title = prompt.title,
        onDismiss = onDismiss,
        confirmLabel = prompt.confirmLabel,
        onConfirm = prompt.onConfirm,
        dismissLabel = prompt.dismissLabel,
        confirmTag = "prompt.confirm",
    ) { Text(prompt.body) }
}

@Composable
private fun NamePromptDialog(
    prompt: NamePrompt,
    onDismiss: () -> Unit,
) {
    var text by remember(prompt) { mutableStateOf(prompt.initial) }
    AmpAlert(
        title = prompt.title,
        onDismiss = onDismiss,
        confirmLabel = prompt.confirmLabel,
        onConfirm = { prompt.onSubmit(text.trim()) },
        dismissLabel = "Cancel",
        confirmEnabled = text.isNotBlank(),
    ) {
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
    }
}

private const val ROW_HEIGHT = 42
private const val MIN_WIDTH = 190
