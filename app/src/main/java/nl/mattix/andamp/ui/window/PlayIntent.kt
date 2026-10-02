// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import nl.mattix.andamp.core.model.Track

/**
 * Play on an empty queue opens the file browser, as in Winamp; otherwise it plays. Shared by
 * the full player and its shaded form.
 */
fun playOrOpen(
    queue: List<Track>,
    play: () -> Unit,
    openFile: () -> Unit,
) {
    if (queue.isEmpty()) openFile() else play()
}
