// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.prefs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.mattix.andamp.core.playback.PeakReading
import java.util.Locale

/**
 * The peak limiter after the rack: a switch, and one line about what it is doing.
 *
 * It sits in the Output section at the foot of the Effects page, with no drag handle, because its
 * place in the audio is fixed: last. It has no controls for its settings.
 *
 * Off by default. While off, the line reports when a peak past full scale was cut off, so the
 * listener can connect a crackle to the slider they moved.
 */
@Composable
internal fun LimiterCard(
    on: Boolean,
    onChange: (Boolean) -> Unit,
    peaks: () -> PeakReading,
) {
    var reading by remember { mutableStateOf(PeakReading.NONE) }
    var now by remember { mutableStateOf(System.nanoTime()) }
    // asked a few times a second, and only while the rack is on screen
    LaunchedEffect(peaks) {
        while (true) {
            reading = peaks()
            now = System.nanoTime()
            delay(READ_EVERY_MS)
        }
    }
    val status = limiterStatus(on, reading, now)
    Spacer(Modifier.height(8.dp))
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (on) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        modifier = Modifier.fillMaxWidth().testTag("dsp.limiter"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Peak limiter", style = MaterialTheme.typography.titleMedium)
                Text(
                    status.line,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when (status.tone) {
                            LimiterTone.CLIPPING -> MaterialTheme.colorScheme.error
                            LimiterTone.HOLDING -> MaterialTheme.colorScheme.primary
                            LimiterTone.QUIET -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.testTag("dsp.limiter.status"),
                )
            }
            Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.testTag("dsp.limiter.on"))
        }
    }
}

internal enum class LimiterTone { QUIET, HOLDING, CLIPPING }

internal data class LimiterStatus(
    val line: String,
    val tone: LimiterTone,
)

/**
 * The card's one line, from the switch and the last reading. A clip counts as "just now" for
 * [RECENT_NS].
 */
internal fun limiterStatus(
    on: Boolean,
    reading: PeakReading,
    nowNanos: Long,
): LimiterStatus {
    val clippedRecently = reading.clippedAtNanos != 0L && nowNanos - reading.clippedAtNanos < RECENT_NS
    return when {
        clippedRecently -> {
            LimiterStatus(
                if (on) "Peaks cut off just now" else "Peaks cut off just now · turn on to prevent",
                LimiterTone.CLIPPING,
            )
        }

        on && reading.holdingDb < -HOLDING_SHOWN_DB -> {
            // a typographic minus, as a readout should show one
            val db = String.format(Locale.ROOT, "%.1f", reading.holdingDb).replace('-', '−')
            LimiterStatus("Holding peaks · $db dB", LimiterTone.HOLDING)
        }

        on -> {
            LimiterStatus("Keeps loud peaks under −1 dB", LimiterTone.QUIET)
        }

        else -> {
            LimiterStatus("Off · loud peaks are cut off", LimiterTone.QUIET)
        }
    }
}

private const val READ_EVERY_MS = 150L
private const val RECENT_NS = 2_000_000_000L

/** Gain reduction below this many dB is not shown. */
private const val HOLDING_SHOWN_DB = 0.1f
