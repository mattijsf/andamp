// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import nl.mattix.andamp.skin.BundledSkins
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.state.SkinEntry
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.widget.Widget

/**
 * The skin library as a floating skinned window: the listener's skins, with the active one
 * highlighted. Tapping a row applies that skin, and tapping its REM column removes it from
 * the library.
 */
@Composable
fun SkinManagerWindow(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    modifier: Modifier = Modifier,
) {
    val s = vm.state
    val context = LocalContext.current
    val entries = s.skinEntries
    val rasterizer =
        remember {
            SkinListRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
        }
    val frame = frameFor(skin)
    BoxWithConstraints(modifier) {
        // the whole of the app's window, as every window measures it: what the gesture
        // bar takes is the screen's safe bottom. A stored row count is drawn at what
        // fits, and the grip is offered no more than that.
        val screen = windowScreen(scale, s)
        val fits = SkinManagerLayout.rowsThatFit(screen.safeBottom, frame)
        // the width is limited to the screen's
        val maxCols = ((screen.width - SkinManagerLayout.WIDTH) / SkinManagerLayout.WIDTH_STEP).coerceAtLeast(0)
        val cols = s.skinCols.coerceIn(0, maxCols)
        // capped at what fits; with no stored size it uses DEFAULT_ROWS
        val rows =
            (s.skinRows ?: SkinManagerLayout.DEFAULT_ROWS)
                .coerceIn(SkinManagerLayout.MIN_ROWS, fits.coerceAtLeast(SkinManagerLayout.MIN_ROWS))
        val layout = SkinManagerLayout(rows, SkinManagerLayout.widthOfCols(cols))
        val widgets = remember(vm, frame, layout.visibleRows) { skinManagerWidgets(vm, frame, layout) }
        GenSkinWindow(
            skin = skin,
            state = s,
            scale = scale,
            spec =
                GenWindowSpec(
                    title = SkinManagerLayout.TITLE,
                    width = layout.width,
                    height = layout.height(frame),
                    offset = s.skinManagerOffset,
                    onMove = { place -> s.skinManagerOffset = place },
                    onClose = { s.skinManagerOpen = false },
                    id = WindowStore.SKINS,
                    onResizeRaw = { grab ->
                        val resized =
                            WindowSizing.resize(
                                grab,
                                screenW = screen.width,
                                screenH = screen.height,
                                heightAxis =
                                    SizeAxis(
                                        furniture = SkinManagerLayout.furnitureH(frame),
                                        step = SkinManagerLayout.ROW_H,
                                        min = SkinManagerLayout.MIN_ROWS,
                                        max = fits,
                                        current = layout.visibleRows,
                                    ),
                                heightOf = { rows -> SkinManagerLayout(rows).height(frame) },
                                widthAxis =
                                    SizeAxis(
                                        furniture = SkinManagerLayout.WIDTH,
                                        step = SkinManagerLayout.WIDTH_STEP,
                                        min = 0,
                                        max = maxCols,
                                        current = cols,
                                    ),
                                widthOf = SkinManagerLayout::widthOfCols,
                            )
                        s.skinCols = resized.cols
                        s.skinRows = resized.steps
                        s.skinManagerOffset = resized.offset
                    },
                ),
            widgets = widgets,
            modifier = Modifier.matchParentSize(),
        ) {
            drawSkinList(rasterizer, entries, s.skinScroll, vm.skinOps.currentId, skin.pledit, scale, frame, layout)
        }
    }
}

/**
 * One widget for the whole list, with the gesture of [rowListPointer]: a tap applies or
 * removes the row under the finger, and a drag scrolls. The entries and the scroll position
 * are read from the state on each event.
 */
private fun skinManagerWidgets(
    vm: WinampViewModel,
    frame: WindowFrame,
    layout: SkinManagerLayout,
): List<Widget> {
    val s = vm.state
    val listW = layout.listWidth(frame)
    val listH = layout.listHeight()

    var downOnRemove = false
    return listOf(
        Widget(
            "skins.list",
            IntRect(frame.leftW, frame.titleH, frame.leftW + listW, frame.titleH + listH),
            // a background widget, so its hit slop does not reach up into the title bar
            background = true,
            pointer =
                rowListPointer(
                    s,
                    SkinManagerLayout.ROW_H,
                    rowAt = { y -> SkinManagerLayout.rowAt(y, frame.titleH) },
                    rows = { s.skinEntries.size },
                    visibleRows = { layout.visibleRows },
                    scroll = { s.skinScroll },
                    scrollTo = { s.skinScroll = it },
                    onPress = { _, pos ->
                        downOnRemove = pos.x >= frame.leftW + listW - SkinManagerLayout.REMOVE_W
                    },
                    onRelease = { row, tapped ->
                        if (tapped && row in s.skinEntries.indices) {
                            if (downOnRemove) vm.skinOps.removeAt(row) else vm.skinOps.applyAt(row)
                        }
                    },
                ),
        ),
    )
}

@Suppress("LongParameterList") // a draw call fed by everything it renders
internal fun DrawScope.drawSkinList(
    rasterizer: SkinListRasterizer,
    entries: List<SkinEntry>,
    scroll: Int,
    currentId: String,
    style: PleditStyle,
    scale: Int,
    frame: WindowFrame,
    layout: SkinManagerLayout = SkinManagerLayout(),
) {
    val width = layout.listWidth(frame)
    val height = layout.listHeight()
    drawRect(style.normalBg, Offset.Zero, Size(width.toFloat(), height.toFloat()))
    val layer = rasterizer.layer(entries, scroll, currentId, style, width, height, scale, layout.visibleRows)
    drawImage(
        layer,
        dstOffset = IntOffset.Zero,
        dstSize =
            androidx.compose.ui.unit
                .IntSize(width, height),
        filterQuality = FilterQuality.None,
    )
}

/**
 * Draws the skin rows with the playlist's text metrics ([ListText]). The layer is cached and
 * rebuilt only when its inputs change.
 */
internal class SkinListRasterizer(
    private val arialLike: Typeface,
) {
    private var cacheKey: List<Any?>? = null
    private var cache: ImageBitmap? = null

    @Suppress("LongParameterList") // a raster cache keyed on everything it draws
    fun layer(
        entries: List<SkinEntry>,
        scroll: Int,
        currentId: String,
        style: PleditStyle,
        width: Int,
        height: Int,
        scale: Int,
        visibleRows: Int = SkinManagerLayout.DEFAULT_ROWS,
    ): ImageBitmap {
        val key = listOf(entries, scroll, currentId, style, width, height, scale, visibleRows)
        cache?.let { if (key == cacheKey) return it }

        val bitmap = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        val paint = ListText.paint(ListText.typefaceFor(style.fontName, arialLike))
        entries.drop(scroll).take(visibleRows).forEachIndexed { row, entry ->
            val top = SkinManagerLayout.rowY(row).toFloat()
            val baseline = ListText.baseline(paint, top, SkinManagerLayout.ROW_H)
            val active = entry.id == currentId
            if (active) {
                paint.color = style.selectedBg.toArgb()
                canvas.drawRect(0f, top, width.toFloat(), top + SkinManagerLayout.ROW_H, paint)
            }
            paint.color = (if (active) style.current else style.normal).toArgb()

            // REM first; the name is then clipped to what is left of the row
            var nameRight = width - ListText.RIGHT_INSET
            if (BundledSkins.of(entry.id) == null) {
                val rem = paint.measureText(REMOVE_LABEL)
                canvas.drawText(REMOVE_LABEL, width - ListText.RIGHT_INSET - rem, baseline, paint)
                nameRight = (width - SkinManagerLayout.REMOVE_W).toFloat()
            }
            canvas.save()
            canvas.clipRect(0f, top, nameRight, top + SkinManagerLayout.ROW_H)
            canvas.drawText(entry.name, ListText.LEFT_INSET, baseline, paint)
            canvas.restore()
        }
        val image = bitmap.asImageBitmap()
        cacheKey = key
        cache = image
        return image
    }

    private companion object {
        const val REMOVE_LABEL = "REM"
    }
}
