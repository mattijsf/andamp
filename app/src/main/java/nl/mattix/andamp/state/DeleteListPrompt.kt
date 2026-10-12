// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import nl.mattix.andamp.R

/**
 * The question before a saved list is deleted, the same from the library window and from
 * LIST > LOAD LIST. The file does not come back, so [delete] runs only on the answer. The
 * queue is left alone, so a deleted list that is loaded keeps playing.
 */
fun deleteListPrompt(
    name: String,
    words: Words,
    onDone: () -> Unit,
    delete: () -> Unit,
): AmpPrompt =
    AmpPrompt(
        title = words.get(R.string.prompt_delete_list_title, name),
        body = words.get(R.string.prompt_delete_list_body),
        confirmLabel = words.get(R.string.prompt_delete),
        dismissLabel = words.get(R.string.prompt_cancel),
        onConfirm = {
            onDone()
            delete()
        },
    )
