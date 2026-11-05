// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.BitmapDef
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.Edge
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Gamma
import nl.mattix.andamp.modernskin.skin.LiveRun
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.Rgb
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinOptions
import nl.mattix.andamp.modernskin.skin.WindowId
import nl.mattix.andamp.modernskin.skin.WindowPlan
import nl.mattix.walkit.BitmapDef as WkBitmapDef
import nl.mattix.walkit.DrawCmd as WkDrawCmd
import nl.mattix.walkit.Edge as WkEdge
import nl.mattix.walkit.FontDef as WkFontDef
import nl.mattix.walkit.Gamma as WkGamma
import nl.mattix.walkit.LiveRun as WkLiveRun
import nl.mattix.walkit.Rect as WkRect
import nl.mattix.walkit.Region as WkRegion
import nl.mattix.walkit.Rgb as WkRgb
import nl.mattix.walkit.SkinMenu as WkSkinMenu
import nl.mattix.walkit.SkinOptions as WkSkinOptions
import nl.mattix.walkit.WindowId as WkWindowId
import nl.mattix.walkit.WindowPlan as WkWindowPlan

/**
 * The native engine's values in the engine module's types, and back where the app hands one in
 * ([WalkitEngine]): ids, rects, fonts, pictures, draw commands and their regions, plans, menus and
 * options. Each is a one-to-one conversion; the fields are the same on both sides.
 */
@Suppress("TooManyFunctions") // one conversion for each type the two engines share
internal object WalkitTypes {
    fun id(id: WkWindowId) = WindowId(id.raw)

    fun wk(id: WindowId) = WkWindowId(id.raw)

    fun rect(r: WkRect) = Rect(r.left, r.top, r.width, r.height)

    fun edge(e: WkEdge) =
        when (e) {
            WkEdge.START -> Edge.START
            WkEdge.END -> Edge.END
        }

    fun font(f: WkFontDef) = FontDef(f.id, f.file)

    fun wkFont(f: FontDef) = WkFontDef(f.id, f.file)

    fun gamma(g: WkGamma) = Gamma(g.r, g.g, g.b, g.boost, g.gray)

    fun bitmap(b: WkBitmapDef) = BitmapDef(b.id, b.file, b.x, b.y, b.w, b.h, b.gammaGroup)

    fun rgb(c: WkRgb) = Rgb(c.value)

    fun image(i: WkDrawCmd.Image) = DrawCmd.Image.drawn(bitmap(i.bitmap), rect(i.dst), i.alpha, i.gamma?.let(::gamma), i.smooth)

    fun region(r: WkRegion): Region =
        when (r) {
            is WkRegion.Pic -> Region.Pic(image(r.image))
            is WkRegion.Box -> Region.Box(rect(r.rect))
            is WkRegion.Op -> Region.Op(r.base?.let(::region), r.add.map(::region), r.keep.map(::region), r.cut.map(::region))
        }

    /** A command of the native frame in the engine module's types; bitmap text never reaches a host. */
    fun cmd(c: WkDrawCmd): DrawCmd =
        when (c) {
            is WkDrawCmd.Image -> {
                image(c)
            }

            is WkDrawCmd.Fill -> {
                DrawCmd.Fill(rect(c.rect), rgb(c.color), c.alpha)
            }

            is WkDrawCmd.WindowStart -> {
                DrawCmd.WindowStart(region(c.region))
            }

            WkDrawCmd.WindowEnd -> {
                DrawCmd.WindowEnd
            }

            is WkDrawCmd.Text -> {
                DrawCmd.Text.placed(
                    c.text,
                    c.font?.let(::font),
                    c.size,
                    rgb(c.color),
                    rect(c.rect),
                    c.alpha,
                    c.bold,
                    c.shadow?.let(::rgb),
                    c.shadowDx,
                    c.shadowDy,
                    c.x,
                    c.baseline,
                )
            }

            is WkDrawCmd.BitmapText -> {
                error("bitmap text is spelled out before the frame reaches a host")
            }

            is WkDrawCmd.Clip -> {
                DrawCmd.Clip(rect(c.rect))
            }

            WkDrawCmd.Unclip -> {
                DrawCmd.Unclip
            }

            is WkDrawCmd.Pixels -> {
                DrawCmd.Pixels(rect(c.rect), c.w, c.h, c.argb, c.alpha, c.stable)
            }
        }

    fun run(r: WkLiveRun) = LiveRun(r.id, r.version, r.from, r.to, rect(r.bounds))

    fun plan(p: WkWindowPlan) = WindowPlan(p.id, p.version, p.runs.map(::run))

    fun menu(m: WkSkinMenu): SkinMenu =
        SkinMenu(
            m.items.map { item ->
                when (item) {
                    is WkSkinMenu.Command -> SkinMenu.Command(item.id, item.text, item.checked, item.disabled)
                    WkSkinMenu.Separator -> SkinMenu.Separator
                    is WkSkinMenu.Submenu -> SkinMenu.Submenu(item.text, menu(item.menu))
                }
            },
        )

    fun wk(m: SkinMenu): WkSkinMenu =
        WkSkinMenu(
            m.items.map { item ->
                when (item) {
                    is SkinMenu.Command -> WkSkinMenu.Command(item.id, item.text, item.checked, item.disabled)
                    SkinMenu.Separator -> WkSkinMenu.Separator
                    is SkinMenu.Submenu -> WkSkinMenu.Submenu(item.text, wk(item.menu))
                }
            },
        )

    fun entry(e: WkSkinOptions.Entry): SkinOptions.Entry =
        when (e) {
            is WkSkinOptions.Toggle -> {
                SkinOptions.Toggle(
                    SkinOptions.Key(e.key.item, e.key.attribute),
                    e.text,
                    e.checked,
                    e.radio,
                )
            }

            WkSkinOptions.Separator -> {
                SkinOptions.Separator
            }

            is WkSkinOptions.Submenu -> {
                SkinOptions.Submenu(e.text, e.entries.map(::entry))
            }
        }
}
