// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * The options a skin's scripts list for Winamp's Options menu (Config item [OPTIONS_MENU]), as of
 * [version] ([SkinRuntime.configVersion] when the list was read). Made on the engine thread; it
 * never changes after, so any thread may read it. Two are equal when they list the same entries,
 * whatever their versions.
 */
class SkinOptions(
    val version: Int,
    val entries: List<Entry>,
) {
    override fun equals(other: Any?): Boolean = other is SkinOptions && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "SkinOptions(version=$version, entries=$entries)"

    sealed interface Entry

    /** A checkable option; [checked] as Winamp ticks it: the value read as a number is not 0. */
    data class Toggle(
        val key: Key,
        val text: String,
        val checked: Boolean,
    ) : Entry

    data object Separator : Entry

    /** An attribute whose value is another item's GUID; [text] is the attribute's name. */
    data class Submenu(
        val text: String,
        val entries: List<Entry>,
    ) : Entry

    /** An option by item GUID and lowercased attribute name; stays valid across snapshots. */
    data class Key(
        val item: String,
        val attribute: String,
    )

    companion object {
        /** The Config item whose attributes Winamp lists in its Options menu (CUSTOM_OPTIONSMENU_ITEMS). */
        const val OPTIONS_MENU = "{1828D28F-78DD-4647-8532-EBA504B8FC04}"

        val EMPTY = SkinOptions(0, emptyList())
    }
}
