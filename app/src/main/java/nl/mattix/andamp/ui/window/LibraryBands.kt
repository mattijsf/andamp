// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.window

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import nl.mattix.andamp.skin.PleditStyle
import nl.mattix.andamp.state.LibraryOps
import nl.mattix.andamp.state.WinampState

/**
 * Everything under the words: the background, the rules between the parts, the active tab, the
 * selected row, and whatever the finger is on.
 */
internal fun DrawScope.drawLibraryBands(
    ops: LibraryOps,
    s: WinampState,
    style: PleditStyle,
    width: Int,
    layout: LibraryLayout,
    hasSource: Boolean,
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
    if (hasSource && s.pressedWidget == "library.source") {
        fill(band, width - LibraryLayout.SOURCE_CELL_W, layout.headerTop, width, layout.barH)
    }

    drawRowBands(ops, s, band, width, layout)
    if (ops.canPlayPage) drawBarBands(ops, s, band, width, layout)
    if (ops.canOrder && s.pressedWidget == "library.order") {
        fill(band, width - LibraryLayout.ORDER_CELL_W, layout.barTop, width, layout.barH)
    }
}

/** The band under the selected, pressed and picked rows, and the rule under a section's heading. */
private fun DrawScope.drawRowBands(
    ops: LibraryOps,
    s: WinampState,
    band: Color,
    width: Int,
    layout: LibraryLayout,
) {
    val rowsW = width - LibraryLayout.SCROLLBAR_W
    for (at in 0 until layout.visibleRows) {
        val index = s.libraryScroll + at
        if (index >= ops.rows.size) break
        val top = layout.rowsTop + at * layout.rowH
        val banded = index == s.librarySelected || index == s.libraryPressedRow || index in ops.chosen
        if (banded) fill(band, 0, top, rowsW, layout.rowH)
        // a section's heading stands on a rule, as the header does
        if (ops.headingAt(index)) fill(band, 0, top + layout.rowH - LibraryLayout.RULE_H, rowsW, LibraryLayout.RULE_H)
    }
}

/** The band under a pressed cell of the bar; the cells are there only where PLAY and ADD act. */
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
internal fun DrawScope.fill(
    color: Color,
    left: Int,
    top: Int,
    right: Int,
    height: Int,
) = drawRect(color, Offset(left.toFloat(), top.toFloat()), Size((right - left).toFloat(), height.toFloat()))
