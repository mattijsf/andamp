// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Loads a classic Winamp skin (.wsz = plain zip). Mirrors Winamp/webamp
 * lookup rules: case-insensitive base names, any directory prefix, last
 * matching zip entry wins, every member optional with per-sheet fallback
 * to the bundled base skin.
 */
object SkinLoader {
    private const val TAG = "SkinLoader"

    /** Loads the bundled base skin. */
    fun loadBase(context: Context): Skin = loadBundled(context, BundledSkins.BASE)

    /**
     * Loads one of the skins that ship inside the app. No fallback: a bundled skin that does not
     * decode is a broken build, and this throws.
     */
    fun loadBundled(
        context: Context,
        bundled: BundledSkin,
    ): Skin = context.assets.open(bundled.asset).use { load(it, fallback = null, name = bundled.name) }

    /**
     * Loads a skin from a .wsz stream. Missing or corrupt members fall back to [fallback] (the base
     * skin); with no fallback all required sheets must decode or this throws.
     *
     * This reads the zip and decodes what it finds; [SkinAssembly] applies the rules about what a
     * skin may leave out.
     */
    fun load(
        input: InputStream,
        fallback: Skin?,
        name: String,
    ): Skin {
        val members = readZip(input)
        // an empty or non-skin zip would otherwise load as a copy of the fallback skin
        require(members.isNotEmpty()) { "'$name' contains no skin members" }

        fun member(
            baseName: String,
            vararg exts: String,
        ): ByteArray? {
            for (ext in exts) members["$baseName.$ext"]?.let { return it }
            return null
        }

        fun text(baseName: String) = member(baseName, "txt")?.let { String(it, Charsets.ISO_8859_1) }

        val sheets =
            Sheet.entries
                .mapNotNull { sheet -> member(sheet.baseName, "bmp", "png")?.let(::decode)?.let { sheet to it } }
                .toMap()
        return SkinAssembly.assemble(
            SkinAssembly.Parts(sheets, text("pledit"), text("viscolor"), text("region"), readmeIn(members)),
            fallback,
            name,
        )
    }

    /**
     * The readme the artist packed with the skin: `readme.txt`, or else the first .txt that is not
     * one of the skin format's own files. DOS line endings are normalized.
     */
    private fun readmeIn(members: Map<String, ByteArray>): String? {
        val spent = setOf("pledit.txt", "viscolor.txt", "region.txt")
        val bytes =
            members["readme.txt"]
                ?: members.entries.firstOrNull { it.key.endsWith(".txt") && it.key !in spent }?.value
                ?: return null
        // ISO-8859-1, like every other text file here
        return String(bytes, Charsets.ISO_8859_1).replace("\r\n", "\n").trim().ifBlank { null }
    }

    /** Zip members keyed by lowercased base name (directories stripped); last entry wins. */
    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val members = mutableMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            generateSequence { zip.nextEntry }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val key =
                        entry.name
                            .substringAfterLast('/')
                            .substringAfterLast('\\')
                            .lowercase()
                    if (key.isNotEmpty()) members[key] = zip.readBytes()
                }
        }
        return members
    }

    // any decode failure degrades to the fallback sheet
    @Suppress("TooGenericExceptionCaught")
    private fun decode(bytes: ByteArray): ImageBitmap? =
        try {
            val opts =
                BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode sheet", e)
            null
        }
}
