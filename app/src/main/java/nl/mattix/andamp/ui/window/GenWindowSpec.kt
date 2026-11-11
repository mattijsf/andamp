// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.unit.IntOffset

/** What a generic-frame window needs: a title, a size, and where it floats. */
data class GenWindowSpec(
    val title: String,
    val width: Int,
    val height: Int,
    val offset: IntOffset,
    val onMove: (IntOffset) -> Unit,
    val onClose: () -> Unit,
    /** The store's name for this window, and its key in the stack. */
    val id: String = title.lowercase(),
    /**
     * Receives the size the resize grip is asking for, unquantized; the caller quantizes it
     * to the window's own step. A window without one has no grip and cannot be resized.
     */
    val onResizeRaw: ((WindowGrab) -> Unit)? = null,
)
