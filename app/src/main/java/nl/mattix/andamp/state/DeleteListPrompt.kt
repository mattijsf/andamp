// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * The question before a saved list is deleted, the same from the library window and from
 * LIST > LOAD LIST. The file does not come back, so [delete] runs only on the answer. The
 * queue is left alone, so a deleted list that is loaded keeps playing.
 */
fun deleteListPrompt(
    name: String,
    onDone: () -> Unit,
    delete: () -> Unit,
): AmpPrompt =
    AmpPrompt(
        title = "Delete $name?",
        body = "This deletes the saved list. The music in it is not deleted.",
        confirmLabel = "Delete",
        dismissLabel = "Cancel",
        onConfirm = {
            onDone()
            delete()
        },
    )
