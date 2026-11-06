// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.walkit.SkinFiles
import nl.mattix.walkit.WithGeneratedImages

/**
 * Andamp's default element set, for use as the base of a skin's files in place of Winamp's: the
 * frames, title bar and widgets skins build on, in plain pictures of our own, and the stand-in
 * fonts. Read from the app's resources under `defaultset/`; its `$solid` pictures are made up
 * ([WithGeneratedImages]).
 */
internal object AndampDefaultSet : SkinFiles by WithGeneratedImages(DefaultSetResources)

/** The default set's files as resources, by name. */
private object DefaultSetResources : SkinFiles {
    private const val ROOT = "/nl/mattix/andamp/modernskin/defaultset/"
    private val FILES =
        listOf(
            "wasabi.xml",
            "elements.xml",
            "aliases.xml",
            "groups.xml",
            "fonts/DejaVuSansCondensed.ttf",
            "fonts/DejaVuSansCondensed-Bold.ttf",
            "fonts/LiberationSans.ttf",
            "fonts/LiberationSans-Bold.ttf",
        )

    // paths reach a SkinFiles normalized to lower case; resources keep their own spelling
    private val byLowerName = FILES.associateBy { it.lowercase() }

    override fun read(path: String): ByteArray? {
        val name = byLowerName[SkinFiles.normalize(path)] ?: return null
        return DefaultSetResources::class.java.getResourceAsStream(ROOT + name)?.use { it.readBytes() }
    }

    override fun names(): List<String> = FILES
}
