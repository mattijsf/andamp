// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import nl.mattix.andamp.state.PresetNotice
import nl.mattix.andamp.state.WinampState

/**
 * One small line over the bottom of the visual, for a few seconds after a preset starts that
 * is not playing whole. It takes no touches, and the visual menu switches it off.
 */
@Composable
internal fun PresetNoticeLine(
    s: WinampState,
    modifier: Modifier = Modifier,
) {
    val text = PresetNotice.textFor(s.presetGaps).takeIf { s.presetNotices }
    var showing by remember { mutableStateOf(false) }
    // keyed on the preset too: the same notice for the next preset is shown again
    LaunchedEffect(text, s.presetName) {
        showing = text != null
        if (text != null) {
            delay(PresetNotice.SHOWN_MS)
            showing = false
        }
    }
    AnimatedVisibility(visible = showing && text != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Text(
            text.orEmpty(),
            color = Color.White.copy(alpha = NOTICE_INK),
            fontSize = NOTICE_SP.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .padding(NOTICE_GAP.dp)
                    .background(Color.Black.copy(alpha = NOTICE_WASH), RoundedCornerShape(NOTICE_GAP.dp))
                    .padding(horizontal = NOTICE_GAP.dp, vertical = (NOTICE_GAP / 2).dp),
        )
    }
}

private const val NOTICE_INK = 0.8f
private const val NOTICE_WASH = 0.45f
private const val NOTICE_SP = 10
private const val NOTICE_GAP = 4
