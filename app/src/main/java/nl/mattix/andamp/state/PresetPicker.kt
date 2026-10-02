// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/** One row in a preset picker: what it is called, and what it is filed under. */
data class PresetEntry(
    val key: String,
    val label: String,
    /** A second line, where a name alone is not enough (an artist, a count). */
    val detail: String? = null,
)

/**
 * Winamp's preset dialogs (Load, Delete), as data. They differ in whether one row or
 * several can be picked and in the confirm button's label. A long list gets a search field.
 */
data class PresetPicker(
    val title: String,
    val entries: List<PresetEntry>,
    val confirmLabel: String,
    /** Delete takes any number of rows; Load takes one. */
    val multiSelect: Boolean = false,
    /** Paints the confirm button as a warning, for an action that deletes. */
    val destructive: Boolean = false,
    val emptyMessage: String = "Nothing saved yet",
    val onConfirm: (keys: List<String>) -> Unit,
) {
    /** The rows matching [query]; every row while it is blank. */
    fun matching(query: String): List<PresetEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return entries
        return entries.filter { entry ->
            entry.label.lowercase().contains(needle) || entry.detail?.lowercase()?.contains(needle) == true
        }
    }

    /** Whether a list this long is worth a search field. */
    val searchable: Boolean get() = entries.size > SEARCH_FROM

    /** The line under the title: how much is here, and what is picked. */
    fun countLabel(selected: Int): String =
        when {
            entries.isEmpty() -> "Nothing saved"
            selected > 0 -> "$selected of ${entries.size} selected"
            entries.size == 1 -> "1 saved"
            else -> "${entries.size} saved"
        }

    /** What the confirm button says when [selected] rows are picked. */
    fun confirmLabelFor(selected: Int): String = if (multiSelect && selected > 1) "$confirmLabel ($selected)" else confirmLabel

    private companion object {
        const val SEARCH_FROM = 8
    }
}
