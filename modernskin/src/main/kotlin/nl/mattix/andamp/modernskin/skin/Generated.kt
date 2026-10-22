// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Pictures Winamp makes instead of reading a file. `<bitmap file="$solid" w h color alpha>` is a
 * w x h picture of one color (DeClassified's playlist middle and frame texture).
 * The bitmap element is given a made-up file name that holds everything needed, and [read]
 * answers it with PNG bytes, so every renderer loads it like any skin file.
 */
object GeneratedImages {
    private const val SOLID = "\$solid/"

    /** The file name for a `$solid` element. */
    fun solidName(
        w: Int?,
        h: Int?,
        color: String?,
        alpha: Int?,
    ): String {
        val c = parseRgb(color ?: "")?.value ?: 0
        val a = (alpha ?: 255).coerceIn(0, 255)
        return "$SOLID%06x%02x/%dx%d.png".format(
            c and 0xffffff,
            a,
            (w ?: 1).coerceIn(1, ImageLimits.MAX_SIDE),
            (h ?: 1).coerceIn(1, ImageLimits.MAX_SIDE),
        )
    }

    /** PNG bytes for a made-up name, or null for a real file. */
    fun read(path: String): ByteArray? {
        if (!path.startsWith(SOLID)) return null
        val (spec, size) =
            path
                .removePrefix(SOLID)
                .removeSuffix(".png")
                .split('/')
                .takeIf { it.size == 2 } ?: return null
        val argb = spec.toLongOrNull(16)?.toInt() ?: return null
        val (w, h) = size.split('x').map { (it.toIntOrNull() ?: 1).coerceIn(1, ImageLimits.MAX_SIDE) }
        if (w.toLong() * h > ImageLimits.MAX_PIXELS) return null
        val rgb = argb ushr 8
        val a = argb and 0xff
        return png(w, h, (a shl 24) or rgb)
    }

    private fun png(
        w: Int,
        h: Int,
        argb: Int,
    ): ByteArray {
        val raw = ByteArray((w * 4 + 1) * h)
        for (y in 0 until h) {
            var o = y * (w * 4 + 1) + 1
            repeat(w) {
                raw[o++] = (argb ushr 16).toByte()
                raw[o++] = (argb ushr 8).toByte()
                raw[o++] = argb.toByte()
                raw[o++] = (argb ushr 24).toByte()
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))

        fun chunk(
            type: String,
            data: ByteArray,
        ) {
            fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            out.write(int(data.size))
            val td = type.toByteArray() + data
            out.write(td)
            out.write(int(CRC32().apply { update(td) }.value.toInt()))
        }

        fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        chunk("IHDR", int(w) + int(h) + byteArrayOf(8, 6, 0, 0, 0))
        val d = Deflater()
        d.setInput(raw)
        d.finish()
        val buf = ByteArray(raw.size + 64)
        val z = ByteArrayOutputStream()
        while (!d.finished()) z.write(buf, 0, d.deflate(buf))
        d.end()
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}

/** [files] that also answer [GeneratedImages]' made-up names. */
class WithGeneratedImages(
    private val files: SkinFiles,
) : SkinFiles by files {
    override fun read(path: String): ByteArray? = GeneratedImages.read(path) ?: files.read(path)

    override fun exists(path: String): Boolean = GeneratedImages.read(path) != null || files.exists(path)
}
