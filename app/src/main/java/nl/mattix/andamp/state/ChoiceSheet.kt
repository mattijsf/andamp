// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

/**
 * A short question with a few answers, each picked with one tap.
 *
 * The playlist's SAVE and LOAD use it to ask where: Andamp's own library, or an `.m3u` file
 * other players can read. [PresetPicker] is for selecting rows and then confirming.
 */
data class ChoiceSheet(
    val title: String,
    val options: List<Choice>,
) {
    data class Choice(
        val label: String,
        /** The line under the label, describing the option. */
        val detail: String,
        val onPick: () -> Unit,
    )
}
