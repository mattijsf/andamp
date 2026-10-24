// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.modernskin.skin.Align
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.VAlign

/**
 * Draws a modern skin's windows ([stack], as `SkinRuntime.drawWindows` gives them) at [scale], the
 * stack starting [left] px from the left. Each window's edge clips what sticks out, as its desktop
 * window region did.
 *
 * A scale between whole numbers draws the stack at the next whole scale into [layer], off screen,
 * and shrinks that finished picture to fit, as the classic player does; without a layer it draws
 * piece by piece. Drawn piece by piece at the
 * fractional scale, two pieces that meet inside a screen pixel would each cover it only partly and
 * leave a seam, and a picture could show a row of the one beneath it.
 */
internal fun DrawScope.drawWalStack(
    stack: List<Pair<Rect, List<DrawCmd>>>,
    host: WalHost,
    scale: Float,
    left: Float,
    layer: GraphicsLayer?,
) {
    val whole =
        kotlin.math
            .ceil(scale)
            .toInt()
            .coerceAtLeast(1)
    if (layer == null || whole.toFloat() == scale || stack.isEmpty()) {
        translate(left, 0f) { drawWindowsAt(stack, host, scale) }
        return
    }
    val size = IntSize(stack.maxOf { it.first.right } * whole, stack.maxOf { it.first.bottom } * whole)
    if (size.width <= 0 || size.height <= 0) return
    layer.compositingStrategy = CompositingStrategy.Offscreen
    layer.pivotOffset = Offset.Zero
    layer.scaleX = scale / whole
    layer.scaleY = scale / whole
    layer.record(this, layoutDirection, size) { drawWindowsAt(stack, host, whole.toFloat()) }
    translate(left, 0f) { drawLayer(layer) }
}

/** The windows of [stack] at [scale], from the stack's corner. */
private fun DrawScope.drawWindowsAt(
    stack: List<Pair<Rect, List<DrawCmd>>>,
    host: WalHost,
    scale: Float,
) {
    scale(scale, scale, pivot = Offset.Zero) {
        for ((r, cmds) in stack) {
            translate(r.left.toFloat(), r.top.toFloat()) {
                clipRect(0f, 0f, r.width.toFloat(), r.height.toFloat()) { drawWalWindow(cmds, host) }
            }
        }
    }
}

/** One window's commands at 1x; with [withText] false the TrueType text is left out. */
internal fun DrawScope.drawWalWindow(
    cmds: List<DrawCmd>,
    host: WalHost,
    withText: Boolean = true,
) {
    // the shapes of the windows being drawn, each masking its window at WindowEnd
    val open = ArrayDeque<DrawCmd.WindowStart>()
    for (cmd in cmds) if (withText || cmd !is DrawCmd.Text) drawCmd(cmd, host, open)
}

/** A picture command: its part of the sheet, tinted, cut out once, and stretched as Winamp stretches. */
internal fun DrawScope.drawImageCmd(
    cmd: DrawCmd.Image,
    host: WalHost,
    blend: BlendMode = BlendMode.SrcOver,
    mask: Boolean = false,
) {
    val img = (if (mask) host.cutMask(cmd.bitmap.file) else host.image(cmd.bitmap.file, cmd.gamma)) ?: return
    val full = host.imageSize(cmd.bitmap.file) ?: return
    val part = sheetPart(cmd, full) ?: return
    val (sw, sh) = part.claimed
    val (cw, ch) = part.held
    // the piece cut out: scaled on the phone, a part of a sheet would take in the pixels next to
    // it at its edges (Winamp3x's sheets keep magenta between their parts)
    val piece = host.piece(img, "${cmd.bitmap.file}|${cmd.gamma}|$mask", cmd.bitmap.x, cmd.bitmap.y, cw, ch)
    val dw = cmd.dst.width * cw / sw
    val dh = cmd.dst.height * ch / sh
    val stretch = !cmd.smooth && (dw != cw || dh != ch)
    if (stretch && dw > 0 && dh > 0) {
        // stretched as Winamp stretches: each pixel from source row/column floor(d * src / dst)
        val key = "${cmd.bitmap.file}|${cmd.gamma}|$mask|${cmd.bitmap.x},${cmd.bitmap.y},$cw,$ch"
        drawImage(
            host.stretched(piece, key, dw, dh),
            dstOffset = IntOffset(cmd.dst.left, cmd.dst.top),
            alpha = cmd.alpha / 255f,
            blendMode = blend,
            filterQuality = FilterQuality.None,
        )
        return
    }
    drawImage(
        piece,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(cw, ch),
        dstOffset = IntOffset(cmd.dst.left, cmd.dst.top),
        dstSize = IntSize(dw, dh),
        alpha = cmd.alpha / 255f,
        blendMode = blend,
        filterQuality = if (cmd.smooth) FilterQuality.Low else FilterQuality.None,
    )
}

/** The size of the sheet part a picture command names, and the size of what the sheet holds of it. */
private class SheetPart(
    val claimed: Pair<Int, Int>,
    val held: Pair<Int, Int>,
)

/**
 * The part of its sheet [cmd] draws, or null when there is nothing to draw. A bitmap element may
 * claim more than its sheet holds: the part that exists is drawn, unstretched; a part placed before
 * the sheet's corner has nothing.
 */
private fun sheetPart(
    cmd: DrawCmd.Image,
    full: Pair<Int, Int>,
): SheetPart? {
    if (cmd.bitmap.x < 0 || cmd.bitmap.y < 0) return null
    val sw = cmd.bitmap.w ?: (full.first - cmd.bitmap.x)
    val sh = cmd.bitmap.h ?: (full.second - cmd.bitmap.y)
    val noSource = sw <= 0 || sh <= 0
    if (noSource || cmd.dst.width <= 0 || cmd.dst.height <= 0) return null
    val cw = minOf(sw, full.first - cmd.bitmap.x)
    val ch = minOf(sh, full.second - cmd.bitmap.y)
    return if (cw <= 0 || ch <= 0) null else SheetPart(sw to sh, cw to ch)
}

// one branch per draw command
private fun DrawScope.drawCmd(
    cmd: DrawCmd,
    host: WalHost,
    open: ArrayDeque<DrawCmd.WindowStart>,
) {
    when (cmd) {
        is DrawCmd.Image -> {
            drawImageCmd(cmd, host)
        }

        is DrawCmd.WindowStart -> {
            // the window's content in a layer as large as its shape, masked by it at WindowEnd
            val b = cmd.region.bounds() ?: Rect(0, 0, 1, 1)
            drawContext.canvas.saveLayer(
                androidx.compose.ui.geometry
                    .Rect(0f, 0f, b.right.toFloat(), b.bottom.toFloat()),
                androidx.compose.ui.graphics
                    .Paint(),
            )
            open.addLast(cmd)
        }

        DrawCmd.WindowEnd -> {
            open.removeLastOrNull()?.let { start ->
                host.shapeMask(start.region, this)?.let { drawImage(it, blendMode = BlendMode.DstIn) }
            }
            drawContext.canvas.restore()
        }

        is DrawCmd.Fill -> {
            drawRect(
                Color(cmd.color.value or OPAQUE),
                Offset(cmd.rect.left.toFloat(), cmd.rect.top.toFloat()),
                Size(cmd.rect.width.toFloat(), cmd.rect.height.toFloat()),
                alpha = cmd.alpha / 255f,
            )
        }

        is DrawCmd.Text -> {
            drawText(cmd, host)
        }

        is DrawCmd.BitmapText -> {
            drawBitmapText(cmd, host)
        }

        is DrawCmd.Pixels -> {
            drawImage(
                host.pixels(cmd),
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(cmd.w, cmd.h),
                dstOffset = IntOffset(cmd.rect.left, cmd.rect.top),
                dstSize = IntSize(cmd.rect.width, cmd.rect.height),
                alpha = cmd.alpha / 255f,
                filterQuality = FilterQuality.None,
            )
        }

        is DrawCmd.Clip -> {
            drawContext.canvas.save()
            drawContext.canvas.clipRect(
                cmd.rect.left.toFloat(),
                cmd.rect.top.toFloat(),
                cmd.rect.right.toFloat(),
                cmd.rect.bottom.toFloat(),
            )
        }

        DrawCmd.Unclip -> {
            drawContext.canvas.restore()
        }
    }
}

/** TrueType text on Windows' line where the font file gives it (whole pixels), else Android's. */
private fun DrawScope.drawText(
    cmd: DrawCmd.Text,
    host: WalHost,
) {
    val r = cmd.rect
    clipRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()) {
        drawIntoCanvas { canvas ->
            val paint = host.drawingPaint(cmd.font, cmd.size, cmd.bold)
            val width = paint.measureText(cmd.text)
            val x =
                when (cmd.align) {
                    Align.LEFT -> r.left.toFloat()
                    Align.CENTER -> r.left + (r.width - width) / 2f
                    Align.RIGHT -> r.right - width
                } - cmd.scroll
            val fm = paint.fontMetrics
            val (asc, desc) =
                host.gdiLine(cmd.font, cmd.size, cmd.bold)?.let { (a, d) -> a.toFloat() to d.toFloat() }
                    ?: (-fm.ascent to fm.descent)
            val y =
                when (cmd.valign) {
                    VAlign.TOP -> r.top + asc
                    VAlign.CENTER -> r.top + ((r.height - (asc + desc)) / 2f).toInt() + asc
                    VAlign.BOTTOM -> r.bottom - desc
                }
            cmd.shadow?.let {
                paint.color = it.value or OPAQUE
                paint.alpha = cmd.alpha
                canvas.nativeCanvas.drawText(cmd.text, x + cmd.shadowDx, y + cmd.shadowDy, paint)
            }
            paint.color = cmd.color.value or OPAQUE
            paint.alpha = cmd.alpha
            canvas.nativeCanvas.drawText(cmd.text, x, y, paint)
        }
    }
}

/** Text in a skin's bitmap font: one cell of the font sheet per character. */
private fun DrawScope.drawBitmapText(
    cmd: DrawCmd.BitmapText,
    host: WalHost,
) {
    val img = host.image(cmd.font.file, cmd.gamma) ?: return
    val f = cmd.font
    val advance = f.charWidth + f.hSpacing
    val colon = cmd.colonWidth ?: advance
    val total = cmd.text.sumOf { if (it == ':') colon else advance }
    var x =
        when (cmd.align) {
            Align.LEFT -> cmd.rect.left
            Align.CENTER -> cmd.rect.left + (cmd.rect.width - total) / 2
            Align.RIGHT -> cmd.rect.right - total
        } - cmd.scroll
    val y =
        when (cmd.valign) {
            VAlign.TOP -> cmd.rect.top
            VAlign.CENTER -> cmd.rect.top + (cmd.rect.height - f.charHeight) / 2
            VAlign.BOTTOM -> cmd.rect.bottom - f.charHeight
        }
    val r = cmd.rect
    clipRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()) {
        for (c in cmd.text) {
            f.cell(c)?.let { cell ->
                drawImage(
                    img,
                    srcOffset = IntOffset(f.ox + cell.first * f.charWidth, f.oy + cell.second * f.charHeight),
                    srcSize = IntSize(f.charWidth, f.charHeight),
                    dstOffset = IntOffset(x, y),
                    dstSize = IntSize(f.charWidth, f.charHeight),
                    alpha = cmd.alpha / 255f,
                    filterQuality = FilterQuality.None,
                )
            }
            x += if (c == ':') colon else advance
        }
    }
}

private const val OPAQUE = 0xFF000000.toInt()
