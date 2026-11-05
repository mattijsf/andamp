// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.modernskin.host.TouchPoint
import nl.mattix.andamp.modernskin.skin.Rect

// the skin host kit's plain values as Compose has them, and back

/** This point as the skin host kit takes it. */
internal fun Offset.toTouch(): TouchPoint = TouchPoint(x, y)

/** This finger's place as a Compose point. */
internal fun TouchPoint.toOffset(): Offset = Offset(x, y)

/** This size as the skin host kit takes it: width to height. */
internal fun IntSize.toPair(): Pair<Int, Int> = width to height

/** This rect as Compose has it. */
internal fun Rect.toIntRect(): IntRect = IntRect(left, top, right, bottom)
