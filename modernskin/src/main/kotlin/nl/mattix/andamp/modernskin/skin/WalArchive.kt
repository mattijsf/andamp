// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * How a zip is told to be a modern skin: it holds a skin.xml. Classic skins (.wsz) never do.
 * The answer needs only the names in the zip's directory, so nothing is unpacked to give it.
 */
object WalArchive {
    /**
     * The folder the skin sits in, as a normalized prefix ending in "/", or "" when skin.xml sits
     * at the root. Null when the zip holds no skin.xml, so it is not a modern skin. Some archives
     * hold the whole skin in one folder; the first skin.xml found decides which.
     */
    fun skinFolder(names: Iterable<String>): String? {
        val normalized = names.map { SkinFiles.normalize(it) }
        if ("skin.xml" in normalized) return ""
        return normalized.firstOrNull { it.endsWith("/skin.xml") }?.removeSuffix("skin.xml")
    }

    /** Whether a zip with these entry names is a modern skin. */
    fun isModern(names: Iterable<String>): Boolean = skinFolder(names) != null
}
