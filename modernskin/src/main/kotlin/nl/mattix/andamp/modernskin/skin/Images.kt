// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.skin

/**
 * `<images>`: the classic skins' volume and balance bars. `images` names a strip of frames stacked
 * `imagesspacing` px apart; `source` picks the frame from the volume, the balance (its distance
 * from the middle) or the seek position (frame 0 while stopped). The frame is drawn unscaled,
 * from the strip's left edge.
 */
class Images(
    rt: SkinRuntime,
) : GuiObject(rt, "images") {
    private var image: String? = null
    private var spacing = 0
    private var source = ""

    override fun setParam(
        key: String,
        value: String,
    ): Boolean {
        if (super.setParam(key, value)) return true
        when (key.lowercase()) {
            "images" -> image = value
            "imagesspacing" -> spacing = value.toIntLenient() ?: 0
            "source" -> source = value.lowercase()
            else -> return false
        }
        return true
    }

    /** The source as Winamp's 16-bit position: 0..65535, the balance's middle at 32768. */
    private fun position(): Int? {
        val p = rt.player
        return when (source) {
            "volume" -> {
                p.volume.coerceIn(0, 255) * 65535 / 255
            }

            "balance" -> {
                CENTER + p.pan.coerceIn(-PAN_REACH, PAN_REACH) * (CENTER - 1) / PAN_REACH
            }

            "seek" -> {
                if (p.status == Player.Status.STOPPED ||
                    p.lengthMs <= 0
                ) {
                    null
                } else {
                    (p.positionMs.toLong() * 65535 / p.lengthMs).toInt()
                }
            }

            else -> {
                null
            }
        }
    }

    override fun draw(out: MutableList<DrawCmd>) {
        if (!visible || rect.width <= 0 || rect.height <= 0) return
        if (spacing <= 0) return
        val id = image ?: return
        val def = rt.bitmap(id) ?: return
        val sheetH = def.h ?: rt.bitmapSize(id)?.second ?: return
        val last = sheetH / spacing - 1
        val frame =
            when (source) {
                "balance" -> {
                    val off = kotlin.math.abs((position() ?: CENTER) - CENTER)
                    off * last shr 15
                }

                else -> {
                    (position() ?: 0) * last shr 16
                }
            }
        out.add(
            DrawCmd.Image(
                BitmapDef(def.id, def.file, def.x, def.y + frame * spacing, rect.width, rect.height, def.gammaGroup),
                rect,
                effectiveAlpha,
            ),
        )
    }

    private companion object {
        const val CENTER = 0x8000

        /** How far the player's balance reaches either way from the middle. */
        const val PAN_REACH = 127
    }
}
