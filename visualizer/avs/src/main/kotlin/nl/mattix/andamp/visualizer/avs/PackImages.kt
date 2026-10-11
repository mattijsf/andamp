// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.visualizer.avs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * The pictures of the preset pack that is playing.
 *
 * AVS presets name a picture by its file name in AVS's own folder. A pack
 * keeps its pictures wherever its author put them, so the name is matched
 * against every file under the pack's folder, whatever folder it is in and
 * whatever its case. A picture is decoded once and kept until the pack
 * changes.
 */
internal class PackImages : AvsImages {
    /** The pack's folder. Setting another one forgets every picture of the last. */
    var directory: File? = null
        set(value) {
            if (field == value) return
            field = value
            files = null
            decoded.clear()
        }

    private var files: Map<String, File>? = null
    private val decoded = HashMap<String, AvsImage?>()

    override fun load(name: String): AvsImage? {
        val key = name.replace('\\', '/').substringAfterLast('/').lowercase()
        if (key.isEmpty()) return null
        return decoded.getOrPut(key) { index()[key]?.let(::decode) }
    }

    private fun index(): Map<String, File> =
        files ?: directory
            ?.walkTopDown()
            ?.maxDepth(MAX_DEPTH)
            ?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            ?.take(MAX_FILES)
            ?.associateBy { it.name.lowercase() }
            .orEmpty()
            .also { files = it }

    /** The file as pixels, scaled down by whole factors until neither side is over [MAX_SIDE]. */
    private fun decode(file: File): AvsImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val bitmap =
            BitmapFactory.decodeFile(file.path, options) ?: run {
                Log.w(TAG, "cannot decode ${file.name}")
                return null
            }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // AVS has no transparency: a picture is drawn as if on black
        for (i in pixels.indices) pixels[i] = pixels[i] or AvsFrame.OPAQUE
        return AvsImage(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }

    private companion object {
        const val TAG = "PackImages"
        const val MAX_DEPTH = 6
        const val MAX_FILES = 2_000

        /** The engine draws at most 196 pixels wide; a picture four times that loses nothing. */
        const val MAX_SIDE = 1_024
        val EXTENSIONS = setOf("bmp", "png", "jpg", "jpeg", "gif")
    }
}
