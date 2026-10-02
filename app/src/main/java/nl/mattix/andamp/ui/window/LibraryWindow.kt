// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.LibraryOps
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.MusicPermission
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.ui.LibraryAccessHandle
import nl.mattix.andamp.ui.menu.libraryRowMenu
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The media library as a floating skinned window: Winamp's gen_ml at phone width.
 *
 * The categories are a tab strip and their contents one drill-down list. The header bar is
 * the search entry at a category's root and the way back up inside a level. It is drawn in
 * the generic frame with the skin's PLEDIT.TXT colors.
 */
@Composable
fun LibraryWindow(
    vm: WinampViewModel,
    skin: Skin,
    scale: Int,
    access: LibraryAccessHandle,
    modifier: Modifier = Modifier,
) {
    val s = vm.state
    val context = LocalContext.current
    val rasterizer =
        remember {
            LibraryRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH))
        }
    val frame = frameFor(skin)
    // a permission granted while the window shows its tap-to-allow row reloads the list
    LaunchedEffect(access.status) {
        if (vm.libraryOps.needsAccess && access.status == LibraryAccess.GRANTED) vm.libraryOps.open()
    }
    val requestNow by rememberUpdatedState(access.request)
    BoxWithConstraints(modifier) {
        // the whole of the app's window, as every window measures it: what the gesture
        // bar and the keyboard take is the screen's safe bottom (safeDrawing includes
        // the IME)
        val screen = windowScreen(scale, s)
        // the smaller of what fits and what the listener asked for; the ask itself is
        // not overwritten
        val fits = LibraryLayout.rowsThatFit(screen.safeBottom, frame)
        val rows = WindowSizing.effectiveSteps(s.libraryRows, fits, LibraryLayout.MIN_ROWS)
        // the width is limited to the screen's
        val maxCols = ((screen.width - LibraryLayout.WIDTH) / LibraryLayout.WIDTH_STEP).coerceAtLeast(0)
        val cols = s.libraryCols.coerceIn(0, maxCols)
        val layout = remember(rows, cols) { LibraryLayout(rows, LibraryLayout.widthOfCols(cols)) }
        val widgets = remember(vm, frame, layout) { libraryWidgets(vm, frame, layout) { requestNow() } }
        GenSkinWindow(
            skin = skin,
            state = s,
            scale = scale,
            spec =
                GenWindowSpec(
                    title = LibraryLayout.TITLE,
                    width = layout.width,
                    height = layout.height(frame),
                    offset = s.libraryOffset,
                    onMove = { place -> s.libraryOffset = place },
                    onClose = { s.libraryOpen = false },
                    id = WindowStore.LIBRARY,
                    onResizeRaw = { grab ->
                        val resized =
                            WindowSizing.resize(
                                grab,
                                screenW = screen.width,
                                screenH = screen.height,
                                heightAxis =
                                    SizeAxis(
                                        furniture = frame.chromeH + LibraryLayout.FURNITURE_H,
                                        step = LibraryLayout.ROW_H,
                                        min = LibraryLayout.MIN_ROWS,
                                        max = fits,
                                        current = rows,
                                    ),
                                heightOf = { asked -> LibraryLayout(asked, layout.width).height(frame) },
                                widthAxis =
                                    SizeAxis(
                                        furniture = LibraryLayout.WIDTH,
                                        step = LibraryLayout.WIDTH_STEP,
                                        min = 0,
                                        max = maxCols,
                                        current = cols,
                                    ),
                                widthOf = LibraryLayout::widthOfCols,
                            )
                        s.libraryCols = resized.cols
                        s.libraryOffset = resized.offset
                        // null once it fills the height, so it goes back to the default
                        s.libraryRows = WindowSizing.rememberedSize(resized.steps, fits)
                    },
                ),
            widgets = widgets,
            // the window centers and clamps in the whole container
            modifier = Modifier.matchParentSize(),
        ) {
            drawLibraryContent(rasterizer, vm.libraryOps, s, skin, scale, frame, layout)
        }
        // carries the keystrokes for the canvas-drawn search field
        if (vm.libraryOps.searching) {
            LibrarySearchConduit(vm.libraryOps)
        }
    }
}

/**
 * The library's widgets: the tab strip, a header that searches or goes up, one widget for
 * all the rows, the scrollbar beside them, and the bottom bar's cells.
 *
 * The list is a [rowListWidget], as in the playlist. The row list is captured at the press,
 * so a tap that straddles an async load does not act on a different list.
 */
private fun libraryWidgets(
    vm: WinampViewModel,
    frame: WindowFrame,
    layout: LibraryLayout,
    onRequestAccess: () -> Unit,
): List<Widget> {
    val s = vm.state
    val ops = vm.libraryOps
    val left = frame.leftW
    val top = frame.titleH
    val listW = layout.listWidth(frame)
    val headerTop = top + LibraryLayout.HEADER_TOP
    val rowsTop = top + LibraryLayout.ROWS_TOP
    val rowsBottom = top + layout.rowsBottom
    val barTop = top + layout.barTop
    val addLeft = left + listW - LibraryLayout.ADD_CELL_W
    val playLeft = addLeft - LibraryLayout.PLAY_CELL_W
    val newLeft = playLeft - LibraryLayout.NEW_CELL_W

    var downRows: List<LibraryOps.Row> = emptyList()

    fun scrollTo(y: Float) {
        val maxScroll = (ops.rows.size - layout.visibleRows).coerceAtLeast(0)
        if (maxScroll == 0) return
        val travel = (layout.rowsH - LibraryLayout.HANDLE_H).coerceAtLeast(1)
        val frac = ((y - rowsTop - LibraryLayout.HANDLE_H / 2f) / travel).coerceIn(0f, 1f)
        s.libraryScroll = (frac * maxScroll).roundToInt()
    }

    val tabs =
        LibraryLayout.tabCells(listW, ops.visibleCategories.size).zip(ops.visibleCategories) { cell, category ->
            Widget(
                "library.tab.${category.name.lowercase()}",
                IntRect(left + cell.start, top, left + cell.end, top + LibraryLayout.BAR_H),
                taps = Widget.Taps(onTap = { ops.switchCategory(category) }),
            )
        }

    return tabs +
        listOf(
            Widget(
                "library.header",
                IntRect(left, headerTop, left + listW, headerTop + LibraryLayout.BAR_H),
                taps = Widget.Taps(onTap = { ops.headerTap() }),
            ),
            // after the header: the last widget wins the hit test
            Widget(
                "library.searchclear",
                IntRect(left + listW - LibraryLayout.BAR_H, headerTop, left + listW, headerTop + LibraryLayout.BAR_H),
                enabled = { ops.searching },
                taps = Widget.Taps(onTap = { ops.searchClear() }),
            ),
            rowListWidget(
                "library.list",
                IntRect(left, rowsTop, left + layout.rowsWidth(frame), rowsBottom),
                s,
                LibraryLayout.ROW_H,
                rowAt = { y -> layout.rowAt(y, frame) },
                rows = { ops.rows.size },
                visibleRows = { layout.visibleRows },
                scroll = { s.libraryScroll },
                scrollTo = { s.libraryScroll = it },
                onPress = { row, _ ->
                    // the list as it was when the finger went down
                    downRows = ops.rows
                    if (row in downRows.indices) s.libraryPressedRow = row
                },
                onScrollStarted = { s.libraryPressedRow = -1 },
                onRelease = { row, tapped ->
                    s.libraryPressedRow = -1
                    val sameList = !ops.loading && ops.rows === downRows
                    if (tapped && sameList) {
                        if (ops.needsAccess) onRequestAccess() else ops.tapRow(row)
                    }
                },
                onLongPress = { row ->
                    s.libraryPressedRow = -1
                    val sameList = !ops.loading && ops.rows === downRows
                    if (sameList && row in ops.rows.indices && !ops.needsAccess) {
                        val top = rowsTop + (row - s.libraryScroll) * LibraryLayout.ROW_H
                        s.activeMenu =
                            libraryRowMenu(
                                ops,
                                row,
                                ops.rows[row].label,
                                MenuAnchor(WindowStore.LIBRARY, left, top, layout.rowsWidth(frame), LibraryLayout.ROW_H),
                            )
                    }
                },
                background = true,
            ),
            // after the list: the last widget wins the hit test
            Widget(
                "library.scroll",
                IntRect(left + listW - LibraryLayout.SCROLL_HIT_W, rowsTop, left + listW, rowsBottom),
                pointer =
                    Widget.Pointer(
                        onDown = { pos -> scrollTo(pos.y) },
                        onDrag = { pos -> scrollTo(pos.y) },
                    ),
            ),
            Widget(
                "library.new",
                IntRect(newLeft, barTop, playLeft, barTop + LibraryLayout.BAR_H),
                enabled = { ops.category == LibraryOps.Category.RADIO && ops.atTracks && !ops.searching },
                taps = Widget.Taps(onTap = { ops.promptNewStation() }),
            ),
            Widget(
                "library.play",
                IntRect(playLeft, barTop, addLeft, barTop + LibraryLayout.BAR_H),
                enabled = { ops.atTracks },
                taps = Widget.Taps(onTap = { ops.playSelection() }),
            ),
            Widget(
                "library.add",
                IntRect(addLeft, barTop, left + listW, barTop + LibraryLayout.BAR_H),
                enabled = { ops.atTracks },
                taps = Widget.Taps(onTap = { ops.enqueueSelection() }),
            ),
        )
}

/** Text and bands are one rasterized layer; the scroll handle is skin art on top. */
@Suppress("LongParameterList") // a draw call fed by everything it renders
internal fun DrawScope.drawLibraryContent(
    rasterizer: LibraryRasterizer,
    ops: LibraryOps,
    s: WinampState,
    skin: Skin,
    scale: Int,
    frame: WindowFrame,
    layout: LibraryLayout,
) {
    val width = layout.listWidth(frame)
    val scene =
        LibraryRasterizer.Scene(
            revision = ops.revision,
            scroll = s.libraryScroll,
            selection = s.librarySelected,
            pressedRow = s.libraryPressedRow,
            tabs = ops.visibleCategories.map { it.label },
            activeTab = ops.visibleCategories.indexOf(ops.category),
            pressedTab =
                ops.visibleCategories.indexOfFirst {
                    s.pressedWidget == "library.tab.${it.name.lowercase()}"
                },
            headerPressed = s.pressedWidget == "library.header",
            newPressed = s.pressedWidget == "library.new",
            playPressed = s.pressedWidget == "library.play",
            addPressed = s.pressedWidget == "library.add",
            playingRow = ops.rowOfTrack(s.currentTrack?.id),
            flash = ops.flash,
            searching = ops.searching,
            query = ops.query,
            caret = ops.caret,
            // the caret blinks on the marquee's clock; blinkOn only ticks while paused
            caretOn = (s.marqueeStep / 2) % 2 == 0,
            canSearch = ops.canSearch,
            newCell = ops.category == LibraryOps.Category.RADIO && ops.atTracks && !ops.searching,
            width = width,
            visibleRows = layout.visibleRows,
            scale = scale,
            style = skin.pledit,
        )
    drawImage(
        rasterizer.layer(ops, scene),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, layout.contentH),
        filterQuality = FilterQuality.None,
    )

    // the playlist's scroll handle over plain background: the playlist's rail art is
    // part of its frame tile and cannot be drawn here
    val maxScroll = (ops.rows.size - layout.visibleRows).coerceAtLeast(0)
    val frac = if (maxScroll == 0) 0f else s.libraryScroll.toFloat() / maxScroll
    val handleY = LibraryLayout.ROWS_TOP + (frac * (layout.rowsH - LibraryLayout.HANDLE_H)).roundToInt()
    val handle =
        if (s.pressedWidget == "library.scroll") {
            SpriteMap.PLAYLIST_SCROLL_HANDLE_SELECTED
        } else {
            SpriteMap.PLAYLIST_SCROLL_HANDLE
        }
    sprite(skin[Sheet.PLEDIT], handle, width - LibraryLayout.SCROLLBAR_W, handleY)
}

/**
 * Draws the library's content area with the playlist's text metrics ([ListText]) in
 * PLEDIT.TXT colors. The bitmap is cached and redrawn only when the [Scene] changes.
 */
internal class LibraryRasterizer(
    private val arialLike: Typeface,
) {
    /** Everything the layer depends on, as values; the cache key. */
    @Suppress("LongParameterList") // the scene is the list of everything that gets drawn
    data class Scene(
        val revision: Int,
        val scroll: Int,
        val selection: Int,
        val pressedRow: Int,
        val tabs: List<String>,
        val activeTab: Int,
        val pressedTab: Int,
        val headerPressed: Boolean,
        val newPressed: Boolean,
        val playPressed: Boolean,
        val addPressed: Boolean,
        val playingRow: Int,
        val flash: String,
        val searching: Boolean,
        val query: String,
        val caret: Int,
        val caretOn: Boolean,
        val canSearch: Boolean,
        val newCell: Boolean,
        val width: Int,
        val visibleRows: Int,
        val scale: Int,
        val style: PleditStyle,
    )

    private var sceneKey: Scene? = null
    private var image: ImageBitmap? = null

    fun layer(
        ops: LibraryOps,
        scene: Scene,
    ): ImageBitmap {
        image?.let { if (scene == sceneKey) return it }

        val layout = LibraryLayout(scene.visibleRows)
        val w = scene.width * scene.scale
        val h = layout.contentH * scene.scale
        // a new bitmap per scene: the hardware renderer caches a bitmap it has drawn,
        // and pixels changed in it afterwards do not reach the screen
        val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.save()
        canvas.scale(scene.scale.toFloat(), scene.scale.toFloat())
        val paint = ListText.paint(ListText.typefaceFor(scene.style.fontName, arialLike))

        paint.color = scene.style.normalBg.toArgb()
        canvas.drawRect(0f, 0f, scene.width.toFloat(), layout.contentH.toFloat(), paint)

        drawTabs(canvas, paint, scene)
        drawRule(canvas, paint, scene, LibraryLayout.BAR_H)
        drawHeader(canvas, paint, ops, scene)
        drawRule(canvas, paint, scene, LibraryLayout.HEADER_TOP + LibraryLayout.BAR_H)
        drawRows(canvas, paint, ops, scene)
        drawRule(canvas, paint, scene, layout.rowsBottom)
        drawBar(canvas, paint, ops, scene, layout)

        canvas.restore()
        val wrapped = target.asImageBitmap()
        image = wrapped
        sceneKey = scene
        return wrapped
    }

    /** The tab strip; the active tab has the selection band. */
    private fun drawTabs(
        canvas: Canvas,
        paint: android.graphics.Paint,
        scene: Scene,
    ) {
        val cells = LibraryLayout.tabCells(scene.width, scene.tabs.size)
        val baseline = ListText.baseline(paint, 0f, LibraryLayout.BAR_H)
        cells.forEachIndexed { at, cell ->
            val active = at == scene.activeTab
            if (active || at == scene.pressedTab) {
                paint.color = scene.style.selectedBg.toArgb()
                canvas.drawRect(cell.start.toFloat(), 0f, cell.end.toFloat(), LibraryLayout.BAR_H.toFloat(), paint)
            }
            paint.color = (if (active) scene.style.current else scene.style.normal).toArgb()
            val label = scene.tabs[at]
            val textW = paint.measureText(label)
            val x = cell.start + ((cell.end - cell.start) - textW) / 2f
            canvas.save()
            canvas.clipRect(cell.start.toFloat(), 0f, cell.end.toFloat(), LibraryLayout.BAR_H.toFloat())
            canvas.drawText(label, x, baseline, paint)
            canvas.restore()
        }
    }

    /**
     * The bar under the tabs: the way up while inside a level, the search field while
     * searching, the search entry at a searchable root.
     */
    private fun drawHeader(
        canvas: Canvas,
        paint: android.graphics.Paint,
        ops: LibraryOps,
        scene: Scene,
    ) {
        val top = LibraryLayout.HEADER_TOP.toFloat()
        val baseline = ListText.baseline(paint, top, LibraryLayout.BAR_H)
        val w = scene.width.toFloat()
        // pressed art only where the bar does something: up, or open the search
        val tappable = ops.depth > 0 || (scene.canSearch && !scene.searching)
        if (scene.headerPressed && tappable) {
            paint.color = scene.style.selectedBg.toArgb()
            canvas.drawRect(0f, top, w, top + LibraryLayout.BAR_H, paint)
        }
        when {
            scene.searching -> {
                drawSearchField(canvas, paint, scene, top, baseline)
            }

            ops.depth > 0 -> {
                paint.color = scene.style.current.toArgb()
                canvas.save()
                canvas.clipRect(0f, top, w - ListText.RIGHT_INSET, top + LibraryLayout.BAR_H)
                canvas.drawText(".. ${ops.header.uppercase()}", ListText.LEFT_INSET, baseline, paint)
                canvas.restore()
            }

            scene.canSearch -> {
                paint.color = scene.style.normal.toArgb()
                canvas.drawText("SEARCH", ListText.LEFT_INSET, baseline, paint)
            }

            else -> {
                paint.color = scene.style.current.toArgb()
                canvas.drawText(ops.header, ListText.LEFT_INSET, baseline, paint)
            }
        }
    }

    private fun drawSearchField(
        canvas: Canvas,
        paint: android.graphics.Paint,
        scene: Scene,
        top: Float,
        baseline: Float,
    ) {
        val fieldRight = scene.width - LibraryLayout.BAR_H - ListText.RIGHT_INSET
        paint.color = scene.style.current.toArgb()
        // when the query outgrows the bar, its end stays visible
        val textW = paint.measureText(scene.query)
        val x = min(ListText.LEFT_INSET, fieldRight - textW - CARET_W)
        canvas.save()
        canvas.clipRect(0f, top, fieldRight, top + LibraryLayout.BAR_H)
        canvas.drawText(scene.query, x, baseline, paint)
        if (scene.caretOn) {
            val caretX = x + paint.measureText(scene.query.substring(0, scene.caret))
            canvas.drawRect(caretX, top + 2f, caretX + CARET_W, top + LibraryLayout.BAR_H - 2f, paint)
        }
        canvas.restore()

        // the clear cell: an x drawn as two lines
        paint.color = scene.style.normal.toArgb()
        val cellLeft = scene.width - LibraryLayout.BAR_H
        val inset = 4f
        canvas.drawLine(cellLeft + inset, top + inset, scene.width - inset, top + LibraryLayout.BAR_H - inset, paint)
        canvas.drawLine(cellLeft + inset, top + LibraryLayout.BAR_H - inset, scene.width - inset, top + inset, paint)
    }

    private fun drawRule(
        canvas: Canvas,
        paint: android.graphics.Paint,
        scene: Scene,
        y: Int,
    ) {
        paint.color = scene.style.selectedBg.toArgb()
        canvas.drawRect(0f, y.toFloat(), scene.width.toFloat(), (y + LibraryLayout.RULE_H).toFloat(), paint)
    }

    private fun drawRows(
        canvas: Canvas,
        paint: android.graphics.Paint,
        ops: LibraryOps,
        scene: Scene,
    ) {
        val rowsW = (scene.width - LibraryLayout.SCROLLBAR_W).toFloat()
        val rows = ops.rows
        if (rows.isEmpty()) {
            drawEmptyNote(canvas, paint, ops, scene)
            return
        }
        rows.drop(scene.scroll).take(scene.visibleRows).forEachIndexed { at, row ->
            val index = scene.scroll + at
            val top = (LibraryLayout.ROWS_TOP + at * LibraryLayout.ROW_H).toFloat()
            val baseline = ListText.baseline(paint, top, LibraryLayout.ROW_H)
            val banded = index == scene.selection || index == scene.pressedRow
            if (banded) {
                paint.color = scene.style.selectedBg.toArgb()
                canvas.drawRect(0f, top, rowsW, top + LibraryLayout.ROW_H, paint)
            }
            paint.color = (if (index == scene.playingRow) scene.style.current else scene.style.normal).toArgb()

            var labelRight = rowsW - ListText.RIGHT_INSET
            if (row.detail.isNotBlank()) {
                val detail = paint.measureText(row.detail)
                canvas.drawText(row.detail, labelRight - detail, baseline, paint)
                labelRight -= detail + DETAIL_GAP
            }
            canvas.save()
            canvas.clipRect(0f, top, labelRight, top + LibraryLayout.ROW_H)
            canvas.drawText(row.label, ListText.LEFT_INSET, baseline, paint)
            canvas.restore()
        }
    }

    /** The note an empty list shows in its first row. */
    private fun drawEmptyNote(
        canvas: Canvas,
        paint: android.graphics.Paint,
        ops: LibraryOps,
        scene: Scene,
    ) {
        if (ops.loading) return
        val (text, colour) =
            when {
                ops.needsAccess -> "TAP TO ALLOW ${MusicPermission.current.uppercase()}" to scene.style.current

                // a tap retries, so it is drawn in the same color as the prompt above
                ops.unreachable -> ops.emptyNote to scene.style.current

                else -> ops.emptyNote to scene.style.normal
            }
        paint.color = colour.toArgb()
        val baseline = ListText.baseline(paint, LibraryLayout.ROWS_TOP.toFloat(), LibraryLayout.ROW_H)
        canvas.drawText(text, ListText.LEFT_INSET, baseline, paint)
    }

    private fun drawBar(
        canvas: Canvas,
        paint: android.graphics.Paint,
        ops: LibraryOps,
        scene: Scene,
        layout: LibraryLayout,
    ) {
        val top = layout.barTop.toFloat()
        val baseline = ListText.baseline(paint, top, LibraryLayout.BAR_H)
        val w = scene.width.toFloat()

        val addLeft = w - LibraryLayout.ADD_CELL_W
        val playLeft = addLeft - LibraryLayout.PLAY_CELL_W
        val newLeft = playLeft - LibraryLayout.NEW_CELL_W

        // status on the left; the enqueue flash replaces it, in the current-track color
        val statusText = scene.flash.ifEmpty { ops.status }
        paint.color = (if (scene.flash.isEmpty()) scene.style.normal else scene.style.current).toArgb()
        val statusRight = (if (scene.newCell) newLeft else playLeft) - DETAIL_GAP
        canvas.save()
        canvas.clipRect(0f, top, statusRight, top + LibraryLayout.BAR_H)
        canvas.drawText(statusText, ListText.LEFT_INSET, baseline, paint)
        canvas.restore()

        if (!ops.atTracks) return
        if (scene.newCell) {
            drawBarButton(canvas, paint, scene, top, baseline, newLeft, playLeft, "NEW", scene.newPressed)
        }
        drawBarButton(canvas, paint, scene, top, baseline, playLeft, addLeft, "PLAY", scene.playPressed)
        drawBarButton(canvas, paint, scene, top, baseline, addLeft, w, "ADD", scene.addPressed)
    }

    @Suppress("LongParameterList") // one button cell: geometry, label, pressed
    private fun drawBarButton(
        canvas: Canvas,
        paint: android.graphics.Paint,
        scene: Scene,
        top: Float,
        baseline: Float,
        cellLeft: Float,
        cellRight: Float,
        label: String,
        pressed: Boolean,
    ) {
        if (pressed) {
            paint.color = scene.style.selectedBg.toArgb()
            canvas.drawRect(cellLeft, top, cellRight, top + LibraryLayout.BAR_H, paint)
        }
        paint.color = scene.style.normal.toArgb()
        canvas.drawText(label, cellRight - ListText.RIGHT_INSET - paint.measureText(label), baseline, paint)
    }

    private companion object {
        const val DETAIL_GAP = 6f
        const val CARET_W = 1f
    }
}

/**
 * The library window's geometry: a tab strip, a header, the rows beside a scrollbar, and a
 * status and action bar, in virtual px inside the frame's content area.
 */
class LibraryLayout(
    val visibleRows: Int,
    /** The window's width, which grows in steps of [WIDTH_STEP]. */
    val width: Int = WIDTH,
) {
    /** How many steps wider than its narrowest this window is drawn. */
    val cols = (width - WIDTH) / WIDTH_STEP

    /** The width of the content area inside the frame. */
    fun listWidth(frame: WindowFrame) = width - frame.chromeW

    fun rowsWidth(frame: WindowFrame) = listWidth(frame) - SCROLLBAR_W

    /** One tab's slice of the strip; [end] exclusive. */
    data class TabCell(
        val start: Int,
        val end: Int,
    )

    val rowsH = visibleRows * ROW_H
    val contentH = FURNITURE_H + rowsH
    val rowsBottom = ROWS_TOP + rowsH
    val barTop = rowsBottom + RULE_H

    fun height(frame: WindowFrame) = frame.chromeH + contentH

    /** The row under a window-space y, clamped so the top edge is row zero. */
    fun rowAt(
        y: Float,
        frame: WindowFrame,
    ) = ((y - frame.titleH - ROWS_TOP) / ROW_H).toInt().coerceAtLeast(0)

    companion object {
        const val WIDTH = 275
        const val ROW_H = 13
        const val BAR_H = 13
        const val RULE_H = 1
        const val HEADER_TOP = BAR_H + RULE_H
        const val ROWS_TOP = HEADER_TOP + BAR_H + RULE_H
        const val FURNITURE_H = ROWS_TOP + RULE_H + BAR_H
        const val SCROLLBAR_W = 8
        const val SCROLL_HIT_W = 15
        const val HANDLE_H = 18
        const val NEW_CELL_W = 30
        const val PLAY_CELL_W = 34
        const val ADD_CELL_W = 30
        const val MIN_ROWS = 8
        const val TITLE = "Library"

        /** As many rows as the screen holds, never fewer than [MIN_ROWS]. */
        fun forAvailableHeight(
            availVirtual: Int,
            frame: WindowFrame,
        ): LibraryLayout = LibraryLayout(rowsThatFit(availVirtual, frame))

        fun rowsThatFit(
            availVirtual: Int,
            frame: WindowFrame,
        ): Int = ((availVirtual - frame.chromeH - FURNITURE_H) / ROW_H).coerceAtLeast(MIN_ROWS)

        /** webamp's WINDOW_RESIZE_SEGMENT_WIDTH. */
        const val WIDTH_STEP = 25

        fun widthOfCols(cols: Int) = WIDTH + cols.coerceAtLeast(0) * WIDTH_STEP

        /**
         * The strip divided over [n] tabs; the last cell absorbs the division
         * remainder so the strip always spans the full width.
         */
        fun tabCells(
            listW: Int,
            n: Int,
        ): List<TabCell> {
            val base = listW / n
            return (0 until n).map { at ->
                val start = at * base
                TabCell(start, if (at == n - 1) listW else start + base)
            }
        }
    }
}
