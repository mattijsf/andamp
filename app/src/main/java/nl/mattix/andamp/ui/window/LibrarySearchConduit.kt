// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import nl.mattix.andamp.state.LibraryOps

/**
 * The keyboard's way into a canvas-drawn search field.
 *
 * The library window is one skinned canvas, and a canvas cannot summon an IME. This
 * invisible one-pixel BasicTextField carries the keystrokes; the visible field is drawn by
 * the window. A change of the ops' `searchEpoch` resyncs the text, refocuses the field and
 * shows the keyboard again.
 */
@Composable
internal fun LibrarySearchConduit(
    ops: LibraryOps,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(ops.query, TextRange(ops.query.length))) }
    LaunchedEffect(ops.searchEpoch) {
        value = TextFieldValue(ops.query, TextRange(ops.query.length))
        focus.requestFocus()
        keyboard?.show()
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            ops.setQuery(next.text, next.selection.start)
        },
        modifier =
            modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focus),
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        // results update as the listener types; Search only hides the keyboard
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}
