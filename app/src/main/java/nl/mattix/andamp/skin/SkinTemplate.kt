// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Builds a skin from Android's live color scheme.
 *
 * The template is the Python build in `skin/` with its colors factored out: one byte per pixel
 * naming the role that drew it, plus a table saying where each role comes from. This file holds no
 * geometry; the shapes exist only in the Python build.
 *
 * Generate the template with `python3 skin/build.py all`. Binding it to the shipped palette
 * reproduces the shipped `.wsz` byte for byte, which `SkinTemplateBitmapsTest` checks.
 */
class SkinTemplate private constructor(
    private val roles: List<RoleExpr>,
    private val order: List<String>,
    private val sheets: List<Sheet>,
    private val text: Map<String, String>,
    private val pairs: List<ContrastPair>,
    private val indices: ByteArray,
) {
    private data class Sheet(
        val name: String,
        val w: Int,
        val h: Int,
        val offset: Int,
    )

    private data class ContrastPair(
        val name: String,
        val fg: String,
        val bg: String,
        val min: Double,
    )

    sealed interface RoleExpr {
        val name: String

        data class M3(
            override val name: String,
            val role: String,
        ) : RoleExpr

        data class Alias(
            override val name: String,
            val of: String,
        ) : RoleExpr

        data class Blend(
            override val name: String,
            val a: String,
            val b: String,
            val t: Double,
        ) : RoleExpr

        data class Tone(
            override val name: String,
            val of: String,
            val t: Double,
        ) : RoleExpr

        data class Literal(
            override val name: String,
            val value: Int,
        ) : RoleExpr
    }

    /** Every role this template names, in pixel-index order. */
    val roleNames: List<String> get() = order

    /** The declared contrast pairs, as (foreground role, background role, required ratio). */
    val contrastPairs: List<Triple<String, String, Double>> get() = pairs.map { Triple(it.fg, it.bg, it.min) }

    /**
     * Resolves every role against [scheme], as packed 0xRRGGBB in [order].
     *
     * One pass suffices: the table is emitted in dependency order, so an entry refers only to
     * entries already resolved.
     *
     * With [repair] on, a declared pair that comes out under its required ratio has its foreground
     * walked along its own tone axis until it passes. Only the foreground moves, because a
     * background is shared by several pairs.
     */
    fun colors(
        scheme: ColorScheme,
        repair: Boolean = true,
    ): IntArray {
        val byName = HashMap<String, Int>(roles.size)
        for (expr in roles) {
            byName[expr.name] =
                when (expr) {
                    is RoleExpr.M3 -> m3(scheme, expr.role)
                    is RoleExpr.Alias -> byName.getValue(expr.of)
                    is RoleExpr.Blend -> Tonal.blend(byName.getValue(expr.a), byName.getValue(expr.b), expr.t)
                    is RoleExpr.Tone -> Tonal.tone(byName.getValue(expr.of), expr.t)
                    is RoleExpr.Literal -> expr.value
                }
        }
        if (repair) repair(byName)
        return IntArray(order.size) { byName.getValue(order[it]) }
    }

    /**
     * The Material roles this template reads, as [scheme] has them. The output depends on nothing
     * else.
     */
    fun inputs(scheme: ColorScheme): IntArray = IntArray(M3_ROLES.size) { m3(scheme, M3_ROLES[it]) }

    private fun repair(byName: MutableMap<String, Int>) {
        for (pair in pairs) {
            val bg = byName[pair.bg] ?: continue
            var fg = byName[pair.fg] ?: continue
            if (Tonal.contrast(fg, bg) >= pair.min) continue
            val lab = Tonal.toLab(fg)
            var l = lab[0]
            val chroma = hypot(lab[1], lab[2])
            val hue = Math.toDegrees(atan2(lab[2], lab[1]))
            val step = if (Tonal.toLab(bg)[0] < HALF_LIGHTNESS) 1.0 else -1.0
            var budget = REPAIR_BUDGET
            while (Tonal.contrast(fg, bg) < pair.min && budget-- > 0) {
                l = (l + step).coerceIn(0.0, 100.0)
                fg = Tonal.fromLch(l, chroma, hue)
            }
            byName[pair.fg] = fg
        }
    }

    /** One [Bitmap] per sheet, keyed by its `.wsz` file name, ready for the skin loader. */
    fun bitmaps(colors: IntArray): Map<String, Bitmap> {
        val lut = IntArray(colors.size) { argb(colors[it]) }
        val out = LinkedHashMap<String, Bitmap>(sheets.size)
        for (sheet in sheets) {
            val pixels = IntArray(sheet.w * sheet.h) { lut[indices[sheet.offset + it].toInt() and 0xFF] }
            out[sheet.name] = Bitmap.createBitmap(pixels, sheet.w, sheet.h, Bitmap.Config.ARGB_8888)
        }
        return out
    }

    /**
     * `PLEDIT.TXT`, `VISCOLOR.TXT` and `REGION.TXT`. A placeholder names the format it is replaced
     * with: `#RRGGBB` (`hex`) or `r,g,b` (`rgb`).
     */
    fun textFiles(colors: IntArray): Map<String, String> {
        val out = LinkedHashMap<String, String>(text.size)
        for ((name, template) in text) {
            var s = template
            for ((i, role) in order.withIndex()) {
                val c = colors[i]
                if (s.contains("{{$role|")) {
                    s = s.replace("{{$role|hex}}", "#%06X".format(c))
                    s = s.replace("{{$role|rgb}}", "${red(c)},${green(c)},${blue(c)}")
                }
            }
            out[name] = s
        }
        return out
    }

    companion object {
        private const val FORMAT = 1
        private const val HALF_LIGHTNESS = 50.0
        private const val REPAIR_BUDGET = 40

        /** Material 3's roles, by the snake-case name the template and the Python side use. */
        private val M3: Map<String, (ColorScheme) -> Color> =
            linkedMapOf(
                "primary" to { it.primary },
                "on_primary" to { it.onPrimary },
                "primary_container" to { it.primaryContainer },
                "on_primary_container" to { it.onPrimaryContainer },
                "inverse_primary" to { it.inversePrimary },
                "primary_fixed" to { it.primaryFixed },
                "primary_fixed_dim" to { it.primaryFixedDim },
                "on_primary_fixed" to { it.onPrimaryFixed },
                "on_primary_fixed_variant" to { it.onPrimaryFixedVariant },
                "secondary" to { it.secondary },
                "on_secondary" to { it.onSecondary },
                "secondary_container" to { it.secondaryContainer },
                "on_secondary_container" to { it.onSecondaryContainer },
                "tertiary" to { it.tertiary },
                "on_tertiary" to { it.onTertiary },
                "tertiary_container" to { it.tertiaryContainer },
                "on_tertiary_container" to { it.onTertiaryContainer },
                "error" to { it.error },
                "on_error" to { it.onError },
                "error_container" to { it.errorContainer },
                "on_error_container" to { it.onErrorContainer },
                "surface" to { it.surface },
                "on_surface" to { it.onSurface },
                "surface_variant" to { it.surfaceVariant },
                "on_surface_variant" to { it.onSurfaceVariant },
                "surface_dim" to { it.surfaceDim },
                "surface_bright" to { it.surfaceBright },
                "surface_container_lowest" to { it.surfaceContainerLowest },
                "surface_container_low" to { it.surfaceContainerLow },
                "surface_container" to { it.surfaceContainer },
                "surface_container_high" to { it.surfaceContainerHigh },
                "surface_container_highest" to { it.surfaceContainerHighest },
                "outline" to { it.outline },
                "outline_variant" to { it.outlineVariant },
                "inverse_surface" to { it.inverseSurface },
                "inverse_on_surface" to { it.inverseOnSurface },
            )

        /** The same roles as a list, which is the order [inputs] reports them in. */
        val M3_ROLES: List<String> = M3.keys.toList()

        private fun m3(
            s: ColorScheme,
            role: String,
        ): Int = (M3[role] ?: error("template names an unknown Material 3 role: $role"))(s).toArgb() and 0xFFFFFF

        fun load(zip: InputStream): SkinTemplate {
            var manifest: JSONObject? = null
            var indices: ByteArray? = null
            ZipInputStream(zip).use { z ->
                while (true) {
                    val entry = z.nextEntry ?: break
                    when (entry.name) {
                        "manifest.json" -> manifest = JSONObject(z.readBytes().decodeToString())
                        "sheets.idx" -> indices = z.readBytes()
                    }
                }
            }
            val m = requireNotNull(manifest) { "template has no manifest.json" }
            val idx = requireNotNull(indices) { "template has no sheets.idx" }
            require(m.getInt("format") == FORMAT) { "unsupported template format ${m.getInt("format")}" }
            return SkinTemplate(
                roles = m.getJSONArray("roles").objects().map(::roleExpr),
                order = m.getJSONArray("role_order").let { arr -> (0 until arr.length()).map { arr.getString(it) } },
                sheets =
                    m.getJSONArray("sheets").objects().map {
                        Sheet(it.getString("name"), it.getInt("w"), it.getInt("h"), it.getInt("offset"))
                    },
                text = m.getJSONObject("text").let { o -> o.keys().asSequence().associateWith { o.getString(it) } },
                pairs =
                    m.getJSONArray("contrast").objects().mapNotNull { o ->
                        // a pair without both names cannot be checked
                        if (o.isNull("fg") || o.isNull("bg")) {
                            null
                        } else {
                            ContrastPair(o.getString("name"), o.getString("fg"), o.getString("bg"), o.getDouble("min"))
                        }
                    },
                indices = idx,
            )
        }

        private fun roleExpr(o: JSONObject): RoleExpr {
            val name = o.getString("name")
            return when (val kind = o.getString("kind")) {
                "m3" -> RoleExpr.M3(name, o.getString("role"))
                "alias" -> RoleExpr.Alias(name, o.getString("of"))
                "blend" -> RoleExpr.Blend(name, o.getString("a"), o.getString("b"), o.getDouble("t"))
                "tone" -> RoleExpr.Tone(name, o.getString("of"), o.getDouble("t"))
                "literal" -> RoleExpr.Literal(name, o.getInt("value"))
                else -> error("unknown role kind: $kind")
            }
        }

        private fun org.json.JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}
