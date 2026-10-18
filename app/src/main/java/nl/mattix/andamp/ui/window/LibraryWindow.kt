// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.R
import nl.mattix.andamp.skin.GlyphMask
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.skin.Sheet
import nl.mattix.andamp.skin.Skin
import nl.mattix.andamp.skin.SpriteMap
import nl.mattix.andamp.state.LibraryAccess
import nl.mattix.andamp.state.LibraryOps
import nl.mattix.andamp.state.ListFont
import nl.mattix.andamp.state.MenuAnchor
import nl.mattix.andamp.state.MusicPermission
import nl.mattix.andamp.state.WinampState
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.WindowStore
import nl.mattix.andamp.state.Words
import nl.mattix.andamp.ui.LibraryAccessHandle
import nl.mattix.andamp.ui.menu.libraryRowMenu
import nl.mattix.andamp.ui.sprite
import nl.mattix.andamp.ui.widget.Widget
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
    /** The rectangle the layout holds it in, or null while it floats. */
    pinned: IntRect? = null,
) {
    val s = vm.state
    val context = LocalContext.current
    val rasterizer =
        remember {
            LibraryRasterizer(Typeface.createFromAsset(context.assets, PlaylistTextRasterizer.ASSET_PATH), context.resources)
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
        val rowH = s.rowHeight.px
        val minRows = LibraryLayout.minRows(rowH)
        val fits = LibraryLayout.rowsThatFit(screen.safeBottom, frame, rowH)
        val rows = WindowSizing.effectiveSteps(s.libraryRows, fits, minRows)
        // the width is limited to the screen's
        val cols = LibraryLayout.WIDTH_STEPS.clamp(s.libraryCols, screen.width)
        val layout =
            remember(rows, cols, pinned, frame, rowH) {
                pinned?.let { LibraryLayout.filling(it.height, it.width, frame, rowH) }
                    ?: LibraryLayout(rows, LibraryLayout.widthOfCols(cols), rowH = rowH)
            }
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
                    pinned = pinned,
                    onResizeRaw = { grab ->
                        val resized =
                            WindowSizing.resize(
                                grab,
                                screen,
                                heightAxis =
                                    SizeAxis(
                                        furniture = frame.chromeH + LibraryLayout.furnitureH(rowH),
                                        step = rowH,
                                        min = minRows,
                                        max = fits,
                                        current = rows,
                                    ),
                                heightOf = { asked -> LibraryLayout(asked, layout.width, rowH = rowH).height(frame) },
                                widthAxis = LibraryLayout.WIDTH_STEPS.axis(cols, screen.width),
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
    val barH = layout.barH
    val headerTop = top + layout.headerTop
    val rowsTop = top + layout.rowsTop
    val rowsBottom = top + layout.rowsBottom
    val barTop = top + layout.barTop
    val cells = LibraryLayout.BarCells(listW)
    val addLeft = left + cells.addLeft
    val playLeft = left + cells.playLeft
    val newLeft = left + cells.newLeft
    val deleteLeft = left + cells.deleteLeft

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
                IntRect(left + cell.start, top, left + cell.end, top + barH),
                taps = Widget.Taps(onTap = { ops.switchCategory(category) }),
            )
        }

    return tabs +
        listOf(
            Widget(
                "library.header",
                IntRect(left, headerTop, left + listW, headerTop + barH),
                taps = Widget.Taps(onTap = { ops.headerTap() }),
            ),
            // after the header: the last widget wins the hit test
            Widget(
                "library.searchclear",
                IntRect(left + listW - barH, headerTop, left + listW, headerTop + barH),
                enabled = { ops.searching },
                taps = Widget.Taps(onTap = { ops.searchClear() }),
            ),
            rowListWidget(
                "library.list",
                IntRect(left, rowsTop, left + layout.rowsWidth(frame), rowsBottom),
                s,
                layout.rowH,
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
                        val top = rowsTop + (row - s.libraryScroll) * layout.rowH
                        s.activeMenu =
                            libraryRowMenu(
                                ops,
                                row,
                                ops.rows[row].label,
                                MenuAnchor(WindowStore.LIBRARY, left, top, layout.rowsWidth(frame), layout.rowH),
                                vm.words,
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
                IntRect(newLeft, barTop, playLeft, barTop + barH),
                enabled = { ops.category == LibraryOps.Category.RADIO && ops.atTracks && !ops.searching },
                taps = Widget.Taps(onTap = { ops.promptNewStation() }),
            ),
            // NEW's place: a saved list is never the radio page
            Widget(
                "library.delete",
                IntRect(deleteLeft, barTop, playLeft, barTop + barH),
                enabled = { ops.canDeletePage },
                taps = Widget.Taps(onTap = { ops.promptDeletePage() }),
            ),
            Widget(
                "library.play",
                IntRect(playLeft, barTop, addLeft, barTop + barH),
                enabled = { ops.atTracks },
                taps = Widget.Taps(onTap = { ops.playSelection() }),
            ),
            Widget(
                "library.add",
                IntRect(addLeft, barTop, left + listW, barTop + barH),
                enabled = { ops.atTracks },
                taps = Widget.Taps(onTap = { ops.enqueueSelection() }),
            ),
        )
}

/**
 * The library's content area. The backgrounds and the bands are drawn here; the words are three
 * cached layers over them (the tabs and the header, the rows, the bar); the scroll handle is skin
 * art on top. Scrolling makes only the rows' layer again.
 */
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
    drawLibraryBands(ops, s, skin.pledit, width, layout)
    val scene =
        LibraryRasterizer.Scene(
            revision = ops.revision,
            scroll = s.libraryScroll,
            playingRow = ops.rowOfTrack(s.currentTrack?.id),
            tabs = ops.visibleCategories.map(ops::label),
            activeTab = ops.visibleCategories.indexOf(ops.category),
            flash = ops.flash,
            searching = ops.searching,
            query = ops.query,
            caret = ops.caret,
            // the caret blinks on the marquee's clock; blinkOn only ticks while paused
            caretOn = (s.marqueeStep / 2) % 2 == 0,
            canSearch = ops.canSearch,
            newCell = ops.category == LibraryOps.Category.RADIO && ops.atTracks && !ops.searching,
            deleteCell = ops.canDeletePage,
            width = width,
            visibleRows = layout.visibleRows,
            slack = layout.slack,
            scale = scale,
            style = skin.pledit,
            rowH = layout.rowH,
            skinFont = skin[Sheet.TEXT].takeIf { s.listFont == ListFont.SKIN },
        )
    drawLayer(rasterizer.top(ops, scene), 0, width, layout.rowsTop)
    drawLayer(rasterizer.rows(ops, scene), layout.rowsTop, width, layout.rowsH)
    drawLayer(rasterizer.bar(ops, scene), layout.barTop, width, layout.barH)

    // the playlist's scroll handle over plain background: the playlist's rail art is
    // part of its frame tile and cannot be drawn here
    val maxScroll = (ops.rows.size - layout.visibleRows).coerceAtLeast(0)
    val frac = if (maxScroll == 0) 0f else s.libraryScroll.toFloat() / maxScroll
    val handleY = layout.rowsTop + (frac * (layout.rowsH - LibraryLayout.HANDLE_H)).roundToInt()
    val handle =
        if (s.pressedWidget == "library.scroll") {
            SpriteMap.PLAYLIST_SCROLL_HANDLE_SELECTED
        } else {
            SpriteMap.PLAYLIST_SCROLL_HANDLE
        }
    sprite(skin[Sheet.PLEDIT], handle, width - LibraryLayout.SCROLLBAR_W, handleY)
}

/**
 * A layer [height] virtual pixels tall at [top], at whatever resolution it was made: the outline
 * font's device pixels map one to one, and the skin font's pixels become whole squares.
 */
private fun DrawScope.drawLayer(
    layer: ImageBitmap,
    top: Int,
    width: Int,
    height: Int,
) {
    drawImage(
        layer,
        dstOffset = IntOffset(0, top),
        dstSize = IntSize(width, height),
        filterQuality = FilterQuality.None,
    )
}

/**
 * Everything under the words: the background, the rules between the parts, the active tab, the
 * selected row, and whatever the finger is on.
 */
private fun DrawScope.drawLibraryBands(
    ops: LibraryOps,
    s: WinampState,
    style: PleditStyle,
    width: Int,
    layout: LibraryLayout,
) {
    val band = style.selectedBg
    fill(style.normalBg, 0, 0, width, layout.contentH)
    val categories = ops.visibleCategories
    LibraryLayout.tabCells(width, categories.size).zip(categories) { cell, category ->
        if (category == ops.category || s.pressedWidget == "library.tab.${category.name.lowercase()}") {
            fill(band, cell.start, 0, cell.end, layout.barH)
        }
    }
    for (rule in listOf(layout.barH, layout.headerTop + layout.barH, layout.rowsBottom)) {
        fill(band, 0, rule, width, LibraryLayout.RULE_H)
    }
    // pressed art only where the header does something: up, or open the search
    val tappable = ops.depth > 0 || (ops.canSearch && !ops.searching)
    if (s.pressedWidget == "library.header" && tappable) fill(band, 0, layout.headerTop, width, layout.barH)

    val rowsW = width - LibraryLayout.SCROLLBAR_W
    for (at in 0 until layout.visibleRows) {
        val index = s.libraryScroll + at
        if (index >= ops.rows.size) break
        if (index == s.librarySelected || index == s.libraryPressedRow) {
            fill(band, 0, layout.rowsTop + at * layout.rowH, rowsW, layout.rowH)
        }
    }
    if (ops.atTracks) drawBarBands(ops, s, band, width, layout)
}

/** The band under a pressed cell of the bar; the cells are there only on a page of tracks. */
private fun DrawScope.drawBarBands(
    ops: LibraryOps,
    s: WinampState,
    band: Color,
    width: Int,
    layout: LibraryLayout,
) {
    val cells = LibraryLayout.BarCells(width)
    val top = layout.barTop
    when (s.pressedWidget) {
        "library.new" -> {
            if (ops.category == LibraryOps.Category.RADIO &&
                !ops.searching
            ) {
                fill(band, cells.newLeft, top, cells.playLeft, layout.barH)
            }
        }

        "library.delete" -> {
            if (ops.canDeletePage) fill(band, cells.deleteLeft, top, cells.playLeft, layout.barH)
        }

        "library.play" -> {
            fill(band, cells.playLeft, top, cells.addLeft, layout.barH)
        }

        "library.add" -> {
            fill(band, cells.addLeft, top, width, layout.barH)
        }
    }
}

/** A rectangle of [color] from [left] to [right], [height] tall from [top], in virtual px. */
private fun DrawScope.fill(
    color: Color,
    left: Int,
    top: Int,
    right: Int,
    height: Int,
) = drawRect(color, Offset(left.toFloat(), top.toFloat()), Size((right - left).toFloat(), height.toFloat()))

/**
 * Writes the library's words in the playlist's text metrics ([ListText]) and PLEDIT.TXT colors,
 * in the skin's font when the lists are set to it ([TextLayer]). Each of the three layers is
 * cached and made again only when what it shows changes.
 */
internal class LibraryRasterizer(
    private val arialLike: Typeface,
    /** Where the bar's words come from, in the phone's language. */
    private val resources: Resources,
) {
    /** Everything the layers depend on, as values; the cache keys are made from it. */
    @Suppress("LongParameterList") // the scene is the list of everything that gets drawn
    data class Scene(
        val revision: Int,
        val scroll: Int,
        val playingRow: Int,
        val tabs: List<String>,
        val activeTab: Int,
        val flash: String,
        val searching: Boolean,
        val query: String,
        val caret: Int,
        val caretOn: Boolean,
        val canSearch: Boolean,
        val newCell: Boolean,
        val deleteCell: Boolean,
        val width: Int,
        val visibleRows: Int,
        val slack: Int,
        val scale: Int,
        val style: PleditStyle,
        val rowH: Int = LibraryLayout.ROW_H,
        /** The skin's TEXT.BMP while the lists are in its font; null for the outline font. */
        val skinFont: ImageBitmap? = null,
    ) {
        val layout: LibraryLayout get() = LibraryLayout(visibleRows, slack = slack, rowH = rowH)
    }

    /** One layer and the key it was made for. */
    private class Cached {
        val text = TextLayer()
        private var key: List<Any?>? = null
        private var image: ImageBitmap? = null

        fun get(
            key: List<Any?>,
            make: TextLayer.() -> Bitmap,
        ): ImageBitmap {
            image?.let { if (key == this.key) return it }
            // a new bitmap per key: the hardware renderer caches a bitmap it has drawn, and
            // pixels changed in it afterwards do not reach the screen
            return text.make().asImageBitmap().also {
                image = it
                this.key = key
            }
        }
    }

    private val topLayer = Cached()
    private val rowsLayer = Cached()
    private val barLayer = Cached()

    private var maskSheet: ImageBitmap? = null
    private var mask: GlyphMask? = null

    /** The ink of [sheet], made once for as long as the skin is worn; null for the outline font. */
    private fun glyphsOf(sheet: ImageBitmap?): GlyphMask? {
        if (sheet == null) return null
        if (sheet !== maskSheet) {
            mask = PlaylistTextRasterizer.glyphMaskOf(sheet)
            maskSheet = sheet
        }
        return mask
    }

    private fun paintFor(scene: Scene) = ListText.paint(ListText.typefaceFor(scene.style.fontName, arialLike))

    /** The tab strip and the header bar under it, as tall as [LibraryLayout.rowsTop]. */
    fun top(
        ops: LibraryOps,
        scene: Scene,
    ): ImageBitmap {
        val key =
            listOf(
                scene.revision,
                ops.depth,
                ops.header,
                scene.tabs,
                scene.activeTab,
                scene.searching,
                scene.query,
                scene.caret,
                scene.caretOn,
                scene.canSearch,
                scene.width,
                scene.scale,
                scene.style,
                scene.rowH,
                scene.skinFont,
            )
        val layout = scene.layout
        return topLayer.get(key) {
            draw(scene.width, layout.rowsTop, scene.scale, glyphsOf(scene.skinFont), paintFor(scene)) {
                writeTabs(scene, layout)
                writeHeader(ops, scene, layout)
            }
        }
    }

    /** The rows in view, or the note an empty page shows, as tall as [LibraryLayout.rowsH]. */
    fun rows(
        ops: LibraryOps,
        scene: Scene,
    ): ImageBitmap {
        val key =
            listOf(
                scene.revision,
                scene.scroll,
                scene.playingRow,
                ops.loading,
                ops.needsAccess,
                ops.unreachable,
                scene.visibleRows,
                scene.slack,
                scene.width,
                scene.scale,
                scene.style,
                scene.rowH,
                scene.skinFont,
            )
        val layout = scene.layout
        return rowsLayer.get(key) {
            draw(scene.width, layout.rowsH, scene.scale, glyphsOf(scene.skinFont), paintFor(scene)) {
                writeRows(ops, scene)
            }
        }
    }

    /** The bar under the rows: the status, or a flash in its place, and the cells. */
    fun bar(
        ops: LibraryOps,
        scene: Scene,
    ): ImageBitmap {
        val key =
            listOf(
                scene.revision,
                ops.status,
                ops.atTracks,
                scene.flash,
                scene.newCell,
                scene.deleteCell,
                scene.width,
                scene.scale,
                scene.style,
                scene.rowH,
                scene.skinFont,
            )
        val layout = scene.layout
        return barLayer.get(key) {
            draw(scene.width, layout.barH, scene.scale, glyphsOf(scene.skinFont), paintFor(scene)) {
                writeBar(ops, scene, layout)
            }
        }
    }

    /** The tab strip; the active tab is in the current-track color. */
    private fun TextLayer.Words.writeTabs(
        scene: Scene,
        layout: LibraryLayout,
    ) {
        LibraryLayout.tabCells(scene.width, scene.tabs.size).forEachIndexed { at, cell ->
            label(
                scene.tabs[at],
                (cell.start + CELL_PAD).toFloat(),
                (cell.end - CELL_PAD).toFloat(),
                0,
                layout.barH,
                (if (at == scene.activeTab) scene.style.current else scene.style.normal).toArgb(),
                TextLayer.Align.CENTER,
                TextLayer.Fit.SQUEEZE,
            )
        }
    }

    /**
     * The bar under the tabs: the search field while searching, the way up while inside a
     * level, the search entry at a searchable root.
     */
    private fun TextLayer.Words.writeHeader(
        ops: LibraryOps,
        scene: Scene,
        layout: LibraryLayout,
    ) {
        val top = layout.headerTop
        val right = scene.width - ListText.RIGHT_INSET
        val current = scene.style.current.toArgb()
        when {
            scene.searching -> {
                val clearLeft = scene.width - layout.barH
                field(
                    scene.query,
                    scene.caret,
                    scene.caretOn,
                    ListText.LEFT_INSET,
                    clearLeft - ListText.RIGHT_INSET,
                    top,
                    layout.barH,
                    current,
                )
                cross(clearLeft, top, layout.barH, scene.style.normal.toArgb())
            }

            ops.depth > 0 -> {
                label(".. ${ops.header.uppercase()}", ListText.LEFT_INSET, right, top, layout.barH, current)
            }

            scene.canSearch -> {
                val search = resources.getString(R.string.library_search_header)
                label(search, ListText.LEFT_INSET, right, top, layout.barH, scene.style.normal.toArgb())
            }

            else -> {
                label(ops.header, ListText.LEFT_INSET, right, top, layout.barH, current)
            }
        }
    }

    private fun TextLayer.Words.writeRows(
        ops: LibraryOps,
        scene: Scene,
    ) {
        val rows = ops.rows
        if (rows.isEmpty()) {
            writeEmptyNote(ops, scene)
            return
        }
        val right = scene.width - LibraryLayout.SCROLLBAR_W - ListText.RIGHT_INSET
        for (at in 0 until scene.visibleRows) {
            val index = scene.scroll + at
            val row = rows.getOrNull(index) ?: break
            val color = if (index == scene.playingRow) scene.style.current else scene.style.normal
            row(row.label, row.detail, ListText.LEFT_INSET, right, at * scene.rowH, scene.rowH, color.toArgb())
        }
    }

    /** The note an empty list shows in its first row. */
    private fun TextLayer.Words.writeEmptyNote(
        ops: LibraryOps,
        scene: Scene,
    ) {
        if (ops.loading) return
        val (text, colour) =
            when {
                ops.needsAccess -> {
                    resources.getString(
                        R.string.library_tap_to_allow,
                        MusicPermission.current(Words.of(resources)).uppercase(),
                    ) to
                        scene.style.current
                }

                // a tap retries, so it is drawn in the same color as the prompt above
                ops.unreachable -> {
                    ops.emptyNote to scene.style.current
                }

                else -> {
                    ops.emptyNote to scene.style.normal
                }
            }
        val right = scene.width - LibraryLayout.SCROLLBAR_W - ListText.RIGHT_INSET
        label(text, ListText.LEFT_INSET, right, 0, scene.rowH, colour.toArgb(), fit = TextLayer.Fit.SQUEEZE)
    }

    private fun TextLayer.Words.writeBar(
        ops: LibraryOps,
        scene: Scene,
        layout: LibraryLayout,
    ) {
        val cells = LibraryLayout.BarCells(scene.width)
        val barH = layout.barH

        // status on the left; the enqueue flash replaces it, in the current-track color
        val statusText = scene.flash.ifEmpty { ops.status }
        val statusColor = if (scene.flash.isEmpty()) scene.style.normal else scene.style.current
        val statusRight =
            when {
                scene.newCell -> cells.newLeft
                scene.deleteCell -> cells.deleteLeft
                else -> cells.playLeft
            } - DETAIL_GAP
        label(statusText, ListText.LEFT_INSET, statusRight, 0, barH, statusColor.toArgb(), fit = TextLayer.Fit.SQUEEZE)

        if (!ops.atTracks) return
        val normal = scene.style.normal.toArgb()

        fun cell(
            left: Int,
            right: Int,
            word: Int,
        ) = label(
            resources.getString(word),
            (left + CELL_PAD).toFloat(),
            right - ListText.RIGHT_INSET,
            0,
            barH,
            normal,
            TextLayer.Align.RIGHT,
            TextLayer.Fit.SQUEEZE,
        )

        if (scene.newCell) cell(cells.newLeft, cells.playLeft, R.string.library_bar_new)
        if (scene.deleteCell) cell(cells.deleteLeft, cells.playLeft, R.string.library_bar_del_list)
        cell(cells.playLeft, cells.addLeft, R.string.library_bar_play)
        cell(cells.addLeft, scene.width, R.string.library_bar_add)
    }

    private companion object {
        /** The status stops this far short of the first cell. */
        const val DETAIL_GAP = 6f

        /** The least room left between a label and the edge of its cell. */
        const val CELL_PAD = 1
    }
}

/**
 * The library window's geometry: a tab strip, a header, the rows beside a scrollbar, and a
 * status and action bar, in virtual px inside the frame's content area.
 *
 * The strip, the header and the bar are as tall as a row ([barH]), so a taller row makes them
 * larger things to tap as well.
 */
class LibraryLayout(
    val visibleRows: Int,
    /** The window's width, which grows in steps of [WIDTH_STEP]. */
    val width: Int = WIDTH,
    /**
     * What the rows' area is taller than its rows: a window held at a height that is not a
     * whole number of rows leaves this much empty under the last one.
     */
    val slack: Int = 0,
    /** How tall a row of the list is: Winamp's 13 px, or what the listener picked. */
    val rowH: Int = ROW_H,
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

    /** Where the bar's cells start, counted from the right of a content area [width] wide. */
    class BarCells(
        width: Int,
    ) {
        val addLeft = width - ADD_CELL_W
        val playLeft = addLeft - PLAY_CELL_W

        /** RADIO's NEW, in the place a saved list's DEL LIST takes. */
        val newLeft = playLeft - NEW_CELL_W
        val deleteLeft = playLeft - DELETE_CELL_W
    }

    /** How tall the tab strip, the header and the bar are. */
    val barH = rowH
    val headerTop = headerTop(rowH)
    val rowsTop = rowsTop(rowH)
    val rowsH = visibleRows * rowH + slack
    val contentH = furnitureH(rowH) + rowsH
    val rowsBottom = rowsTop + rowsH
    val barTop = rowsBottom + RULE_H

    fun height(frame: WindowFrame) = frame.chromeH + contentH

    /** The row under a window-space y, clamped so the top edge is row zero. */
    fun rowAt(
        y: Float,
        frame: WindowFrame,
    ) = ((y - frame.titleH - rowsTop) / rowH).toInt().coerceAtLeast(0)

    companion object {
        const val WIDTH = 275
        const val ROW_H = 13
        const val RULE_H = 1
        const val SCROLLBAR_W = 8
        const val SCROLL_HIT_W = 15
        const val HANDLE_H = 18
        const val NEW_CELL_W = 30
        const val DELETE_CELL_W = 52
        const val PLAY_CELL_W = 34
        const val ADD_CELL_W = 30
        const val MIN_ROWS = 8
        const val TITLE = "Library"

        /** Where the header starts, under the tab strip and its rule, for rows [rowH] tall. */
        fun headerTop(rowH: Int) = rowH + RULE_H

        /** Where the rows start, under the header and its rule. */
        fun rowsTop(rowH: Int) = headerTop(rowH) + rowH + RULE_H

        /** All of the content area that is not rows: the strip, the header, the bar and their rules. */
        fun furnitureH(rowH: Int) = rowsTop(rowH) + RULE_H + rowH

        /**
         * The fewest rows of [rowH] the window is drawn with: as many as fit in the content area
         * of the smallest classic window, [MIN_ROWS] of Winamp's rows, once the strip, the
         * header and the bar have taken their height. Taller rows do not make the smallest
         * window any taller.
         */
        fun minRows(rowH: Int) = ((MIN_ROWS * ROW_H + furnitureH(ROW_H) - furnitureH(rowH)) / rowH).coerceAtLeast(1)

        /** As many rows as the screen holds, never fewer than [minRows]. */
        fun forAvailableHeight(
            availVirtual: Int,
            frame: WindowFrame,
            rowH: Int = ROW_H,
        ): LibraryLayout = LibraryLayout(rowsThatFit(availVirtual, frame, rowH), rowH = rowH)

        fun rowsThatFit(
            availVirtual: Int,
            frame: WindowFrame,
            rowH: Int = ROW_H,
        ): Int = ((availVirtual - frame.chromeH - furnitureH(rowH)) / rowH).coerceAtLeast(minRows(rowH))

        /** The layout of a window exactly [height] tall and [width] wide; see [WindowSizing.rowsAndSlack]. */
        fun filling(
            height: Int,
            width: Int,
            frame: WindowFrame,
            rowH: Int = ROW_H,
        ): LibraryLayout {
            val (rows, slack) = WindowSizing.rowsAndSlack(height, frame.chromeH + furnitureH(rowH), rowH, minRows(rowH))
            return LibraryLayout(rows, width, slack, rowH)
        }

        /** webamp's WINDOW_RESIZE_SEGMENT_WIDTH. */
        const val WIDTH_STEP = WidthSteps.WIDTH_STEP

        /** How the library widens. */
        val WIDTH_STEPS = WidthSteps(WIDTH, WIDTH_STEP)

        fun widthOfCols(cols: Int) = WIDTH_STEPS.widthOf(cols)

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
