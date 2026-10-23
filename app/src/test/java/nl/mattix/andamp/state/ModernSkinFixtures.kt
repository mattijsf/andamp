// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A zip holding [names], each with a few bytes. With a skin.xml among them it reads as a modern skin. */
internal fun zipOf(vararg names: String): ByteArray = zipWith(*names.map { it to it }.toTypedArray())

/** A zip holding each path with its text. */
internal fun zipWith(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * A modern skin that loads: one window with a play button. [seed] sets the window's width, so the
 * bytes, and so the id, differ from another one's.
 */
internal fun loadingModernSkin(seed: Int = 0): ByteArray =
    zipWith(
        "skin.xml" to """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
        "player.xml" to
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="${200 + seed}" h="100">""" +
            """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/></layout></container>""",
    )

/**
 * Stores a modern skin in the library as an import does, streamed to a temporary file first,
 * and returns its id. [seed] makes the bytes, and so the id, differ from another one's. The skin
 * [loads] when asked; otherwise its files are not a skin the engine can start.
 */
internal fun SkinLibrary.addModern(
    context: Context,
    name: String,
    seed: Int = 0,
    loads: Boolean = false,
): String {
    val bytes = if (loads) loadingModernSkin(seed) else zipOf("skin.xml", "xml/player-$seed.xml")
    val id = idFor(bytes)
    val tmp = File.createTempFile("modern", ".tmp", context.cacheDir).apply { writeBytes(bytes) }
    storeModern(tmp, id)
    setName(id, name)
    return id
}
