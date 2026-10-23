// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A zip holding [names], each with a few bytes. With a skin.xml among them it reads as a modern skin. */
internal fun zipOf(vararg names: String): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        names.forEach { name ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(name.toByteArray())
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/**
 * Stores a modern skin in the library as an import does, streamed to a temporary file first,
 * and returns its id. [seed] makes the bytes, and so the id, differ from another one's.
 */
internal fun SkinLibrary.addModern(
    context: Context,
    name: String,
    seed: Int = 0,
): String {
    val bytes = zipOf("skin.xml", "xml/player-$seed.xml")
    val id = idFor(bytes)
    val tmp = File.createTempFile("modern", ".tmp", context.cacheDir).apply { writeBytes(bytes) }
    storeModern(tmp, id)
    setName(id, name)
    return id
}
