// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import nl.mattix.andamp.state.Words
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
    /** The rectangle the layout holds it in, or null while it floats. */
    pinned: IntRect? = null,
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
        val rowH = s.rowHeight.px
        val minRows = SkinManagerLayout.minRows(rowH)
        val fits = SkinManagerLayout.rowsThatFit(screen.safeBottom, frame, rowH)
        // the width is limited to the screen's
        val cols = SkinManagerLayout.WIDTH_STEPS.clamp(s.skinCols, screen.width)
        // capped at what fits; with no stored size it uses DEFAULT_ROWS
        val rows =
            (s.skinRows ?: SkinManagerLayout.DEFAULT_ROWS)
                .coerceIn(minRows, fits.coerceAtLeast(minRows))
        val layout =
            pinned?.let { SkinManagerLayout.filling(it.height, it.width, frame, rowH) }
                ?: SkinManagerLayout(rows, SkinManagerLayout.widthOfCols(cols), rowH = rowH)
        val widgets =
            remember(vm, frame, layout.visibleRows, layout.width, layout.slack, rowH) { skinManagerWidgets(vm, frame, layout) }
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
                    pinned = pinned,
                    onResizeRaw = { grab ->
                        val resized =
                            WindowSizing.resize(
                                grab,
                                screen,
                                heightAxis =
                                    SizeAxis(
                                        furniture = SkinManagerLayout.furnitureH(frame),
                                        step = rowH,
                                        min = minRows,
                                        max = fits,
                                        current = layout.visibleRows,
                                    ),
                                heightOf = { rows -> SkinManagerLayout(rows, rowH = rowH).height(frame) },
                                widthAxis = SkinManagerLayout.WIDTH_STEPS.axis(cols, screen.width),
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
            drawSkinList(
                rasterizer,
                entries,
                s.skinScroll,
                vm.skinOps.currentId,
                skin.pledit,
                scale,
                frame,
                layout,
                labels = entries.map { it.rowLabel(vm.words) },
            )
        }
    }
}

/**
 * What a Skin Browser row says: the skin's [name], with the words a modern skin's row puts
 * [before] and [after] it (see [SkinEntry.label]). Only the name is clipped when the row is too
 * narrow: [before] starts the row and [after] ends it, right-aligned before the REM column, both
 * drawn whole.
 */
internal data class SkinRowLabel(
    val name: String,
    val before: String = "",
    val after: String = "",
)

/** This entry's [SkinRowLabel]: its [SkinEntry.label] in the listener's language, split around its name. */
internal fun SkinEntry.rowLabel(words: Words): SkinRowLabel {
    val marked = copy(name = NAME_MARK).label(words)
    val at = marked.indexOf(NAME_MARK)
    if (at < 0) return SkinRowLabel(label(words))
    return SkinRowLabel(name, before = marked.substring(0, at), after = marked.substring(at + NAME_MARK.length))
}

/** Stands in for the skin's name in [SkinEntry.label], to find where the name goes in a language's words. */
private const val NAME_MARK = "\u0000"

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
                    layout.rowH,
                    rowAt = { y -> layout.rowAt(y, frame.titleH) },
                    rows = { s.skinEntries.size },
                    visibleRows = { layout.visibleRows },
                    scroll = { s.skinScroll },
                    scrollTo = { s.skinScroll = it },
                    onPress = { _, pos ->
                        downOnRemove = pos.x >= frame.leftW + listW - SkinManagerLayout.REMOVE_W
                    },
                    onRelease = { row, tapped ->
                        val id = s.skinEntries.getOrNull(row)?.id
                        if (tapped && id != null) {
                            if (downOnRemove) vm.skinOps.remove(id) else vm.skinOps.apply(id)
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
    /** What each row says, in the order of [entries]. */
    labels: List<SkinRowLabel> = entries.map { SkinRowLabel(it.name) },
) {
    val width = layout.listWidth(frame)
    val height = layout.listHeight()
    drawRect(style.normalBg, Offset.Zero, Size(width.toFloat(), height.toFloat()))
    val layer = rasterizer.layer(entries, scroll, currentId, style, width, height, scale, layout.visibleRows, layout.rowH, labels)
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
        rowH: Int = SkinManagerLayout.ROW_H,
        labels: List<SkinRowLabel> = entries.map { SkinRowLabel(it.name) },
    ): ImageBitmap {
        val key = listOf(entries, scroll, currentId, style, width, height, scale, visibleRows, rowH, labels)
        cache?.let { if (key == cacheKey) return it }

        val bitmap = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        val paint = ListText.paint(ListText.typefaceFor(style.fontName, arialLike))
        entries.withIndex().drop(scroll).take(visibleRows).forEachIndexed { row, (index, entry) ->
            val top = SkinManagerLayout.rowY(row, rowH).toFloat()
            val baseline = ListText.baseline(paint, top, rowH)
            val active = entry.id == currentId
            if (active) {
                paint.color = style.selectedBg.toArgb()
                canvas.drawRect(0f, top, width.toFloat(), top + rowH, paint)
            }
            paint.color = (if (active) style.current else style.normal).toArgb()

            // REM first; the label then takes what is left of the row
            var labelRight = width - ListText.RIGHT_INSET
            if (BundledSkins.of(entry.id) == null) {
                val rem = paint.measureText(REMOVE_LABEL)
                canvas.drawText(REMOVE_LABEL, width - ListText.RIGHT_INSET - rem, baseline, paint)
                labelRight = (width - SkinManagerLayout.REMOVE_W).toFloat()
            }
            drawLabel(canvas, paint, labels.getOrElse(index) { SkinRowLabel(entry.name) }, labelRight, top, rowH, baseline)
        }
        val image = bitmap.asImageBitmap()
        cacheKey = key
        cache = image
        return image
    }

    /**
     * Draws [label] in its row, between the row's left inset and [right]: the words before the name
     * from the left, the words after it right-aligned against [right], and the name between them,
     * clipped to the room they leave. Words wider than the whole row are clipped to it too.
     */
    @Suppress("LongParameterList") // the row's place and the paint it is drawn with
    private fun drawLabel(
        canvas: Canvas,
        paint: Paint,
        label: SkinRowLabel,
        right: Float,
        top: Float,
        rowH: Int,
        baseline: Float,
    ) {
        val bottom = top + rowH
        val left = ListText.LEFT_INSET
        canvas.save()
        canvas.clipRect(0f, top, right, bottom)
        if (label.before.isNotEmpty()) canvas.drawText(label.before, left, baseline, paint)
        val nameLeft = (left + paint.measureText(label.before)).coerceAtMost(right)
        val afterW = paint.measureText(label.after)
        val nameRight = (right - afterW).coerceAtLeast(nameLeft)
        if (label.after.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(nameLeft, top, right, bottom)
            canvas.drawText(label.after, right - afterW, baseline, paint)
            canvas.restore()
        }
        canvas.clipRect(0f, top, nameRight, bottom)
        canvas.drawText(label.name, nameLeft, baseline, paint)
        canvas.restore()
    }

    private companion object {
        const val REMOVE_LABEL = "REM"
    }
}
