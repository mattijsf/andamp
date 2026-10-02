// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.skin

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The bundled skins that follow the phone, built from their templates.
 *
 * A live skin is a function of its template and the 36 Material roles the phone provides, so it is
 * built once per palette and reused while the palette is unchanged. [stamp] resolves the palette
 * through the template and is recomputed on every call; [build] writes bitmaps only when the stamp
 * has changed.
 *
 * [palette] decides whether an entry is live: null means the phone has no palette to give, and the
 * entry wears the file it ships. It is a function because the widget asks from a broadcast with a
 * different context each time.
 */
class LiveSkins(
    private val palette: (Context, Boolean) -> ColorScheme?,
) {
    private class Built(
        val colors: IntArray,
        val skin: Skin,
    )

    /** Parsed once per asset and kept for the life of the process. */
    private val templates = HashMap<String, SkinTemplate>()

    /** One built skin per live entry, the last palette it was built for beside it. */
    private val built = HashMap<String, Built>()

    /**
     * The colors [bundled] resolves to under the current palette, or null when it is not live. The
     * widget keys its picture by it.
     */
    fun stamp(
        context: Context,
        bundled: BundledSkin,
    ): IntArray? {
        val live = bundled.live ?: return null
        val scheme = palette(context, live.dark) ?: return null
        return synchronized(this) { template(context, live).colors(scheme) }
    }

    /**
     * The live skin for [bundled], or null when it is not live now. The same palette gives back the
     * same instance, so caches keyed on identity keep working.
     */
    fun build(
        context: Context,
        bundled: BundledSkin,
    ): Skin? {
        val live = bundled.live ?: return null
        val scheme = palette(context, live.dark) ?: return null
        return synchronized(this) {
            val template = template(context, live)
            val colors = template.colors(scheme)
            val kept = built[bundled.id]?.takeIf { it.colors.contentEquals(colors) }
            kept?.skin ?: assemble(template, colors, bundled, scheme).also { built[bundled.id] = Built(colors, it) }
        }
    }

    private fun template(
        context: Context,
        live: LiveTemplate,
    ): SkinTemplate = templates.getOrPut(live.asset) { context.assets.open(live.asset).use(SkinTemplate::load) }

    /**
     * No fallback: a template missing a sheet is a broken build, and this throws as
     * [SkinLoader.loadBundled] does.
     */
    private fun assemble(
        template: SkinTemplate,
        colors: IntArray,
        bundled: BundledSkin,
        scheme: ColorScheme,
    ): Skin {
        val bitmaps = template.bitmaps(colors)
        // the template may hold sheets this player does not draw; the Sheet enum says which a Skin
        // is made of
        val sheets =
            Sheet.entries
                .mapNotNull { sheet -> bitmaps["${sheet.baseName.uppercase()}.BMP"]?.let { sheet to it.asImageBitmap() } }
                .toMap()
        val text = template.textFiles(colors)
        return SkinAssembly.assemble(
            SkinAssembly.Parts(sheets, text["PLEDIT.TXT"], text["VISCOLOR.TXT"], text["REGION.TXT"], readme = null),
            fallback = null,
            name = bundled.name,
            liveScheme = scheme,
        )
    }
}
