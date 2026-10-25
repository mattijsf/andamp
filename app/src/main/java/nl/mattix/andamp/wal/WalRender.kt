// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
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
import nl.mattix.andamp.modernskin.skin.LiveRun
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.VAlign

/** How skins are drawn; switched over adb ([WalDevSwitch]) to compare ways of drawing. */
internal object WalRender {
    /**
     * Draws a stack at a fractional scale straight onto the screen with hard edges, in place of
     * drawing it at the next whole scale and shrinking the picture (see [drawWalStack]). Off unless
     * switched on; it holds until the app's process ends.
     */
    @Volatile
    var direct = false

    /**
     * Keeps each window's drawing, and the parts of it that change, between frames, and draws
     * again only what changed (see [WalLayers]); off, every frame draws every command. On unless
     * switched off; it holds until the app's process ends. The engine plans frames only while it is
     * on.
     */
    @Volatile
    var layers = true

    /** Moves when a switch changes how frames are drawn, so the skin on screen is drawn again at once; read while drawing. */
    val redraws = mutableIntStateOf(0)
}

/**
 * Draws a modern skin's windows ([stack], as `SkinRuntime.drawWindows` gives them) at [scale], the
 * stack starting [left] px from the left, as one frame of [host]'s drawing in color theme
 * [themeKey] (see [WalDrawing]). Each window's edge clips what sticks out, as its desktop window
 * region did.
 *
 * A scale between whole numbers draws the stack at the next whole scale into [layer], off screen,
 * and shrinks that finished picture to fit, as the classic player does; without a layer it draws
 * piece by piece. Drawn piece by piece at the
 * fractional scale, two pieces that meet inside a screen pixel would each cover it only partly and
 * leave a seam, and a picture could show a row of the one beneath it.
 *
 * [WalRender.direct] draws a fractional scale piece by piece all the same, layer or not, but with
 * hard edges: each screen pixel then shows the one skin pixel its center falls on, whichever piece
 * that pixel belongs to, as if the whole stack were drawn at 1x and enlarged without smoothing.
 * Below 2x, skin pixels come out one or two screen pixels wide.
 */
internal fun DrawScope.drawWalStack(
    stack: List<Pair<Rect, List<DrawCmd>>>,
    host: WalHost,
    scale: Float,
    left: Float,
    layer: GraphicsLayer?,
    themeKey: String? = null,
) {
    host.drawing.begin(themeKey)
    try {
        val whole =
            kotlin.math
                .ceil(scale)
                .toInt()
                .coerceAtLeast(1)
        val fractional = whole.toFloat() != scale && stack.isNotEmpty()
        if (layer == null || !fractional || WalRender.direct) {
            translate(left, 0f) { drawWindowsAt(stack, host, scale, hard = fractional && WalRender.direct) }
            return
        }
        val size = IntSize(stack.maxOf { it.first.right } * whole, stack.maxOf { it.first.bottom } * whole)
        if (size.width <= 0 || size.height <= 0) return
        layer.compositingStrategy = CompositingStrategy.Offscreen
        layer.pivotOffset = Offset.Zero
        layer.scaleX = scale / whole
        layer.scaleY = scale / whole
        layer.record(this, layoutDirection, size) { drawStackLayer(stack, host, whole) }
        translate(left, 0f) { drawLayer(layer) }
    } finally {
        host.drawing.end()
    }
}

/**
 * What the layer of a stack at a fractional scale holds (see [drawWalStack]): the windows of
 * [stack] at the [whole] scale above it. The layer starts clear, so the first window draws straight
 * into it and its shape masks it there, with no further layer.
 */
internal fun DrawScope.drawStackLayer(
    stack: List<Pair<Rect, List<DrawCmd>>>,
    host: WalHost,
    whole: Int,
) = drawWindowsAt(stack, host, whole.toFloat(), overClear = true)

/**
 * The windows of [stack] at [scale], from the stack's corner, with [hard] edges when asked (see
 * [drawWalStack]). With [overClear] the first window is drawn over nothing (a clear layer), so the
 * shape it opens with needs no separate layer (see [drawWalWindow]).
 */
internal fun DrawScope.drawWindowsAt(
    stack: List<Pair<Rect, List<DrawCmd>>>,
    host: WalHost,
    scale: Float,
    overClear: Boolean = false,
    hard: Boolean = false,
) {
    scale(scale, scale, pivot = Offset.Zero) {
        stack.forEachIndexed { i, (r, cmds) ->
            translate(r.left.toFloat(), r.top.toFloat()) {
                clipRect(0f, 0f, r.width.toFloat(), r.height.toFloat()) {
                    drawWalWindow(cmds, host, overClear = overClear && i == 0, hard = hard)
                }
            }
        }
    }
}

/**
 * One window's commands at 1x; with [withText] false the TrueType text is left out. With [hard]
 * the pictures and boxes cover only the screen pixels whose centers they hold, and pictures are
 * enlarged without smoothing, so pieces meet without seams at any scale.
 *
 * With [overClear] nothing is under the window yet (a clear layer): a shape it opens with then
 * masks what the window drew where it lies, in place of drawing the window into a separate layer
 * and masking that. The pixels are the same.
 *
 * Each of [runs] is drawn by [drawRun] (with its index) in place of its commands, where they stand.
 */
internal fun DrawScope.drawWalWindow(
    cmds: List<DrawCmd>,
    host: WalHost,
    withText: Boolean = true,
    overClear: Boolean = false,
    hard: Boolean = false,
    runs: List<LiveRun> = emptyList(),
    drawRun: DrawScope.(Int) -> Unit = {},
) {
    val at = WindowAt(host, if (hard) Paint().apply { isAntiAlias = false } else null)
    at.inPlace = (cmds.firstOrNull() as? DrawCmd.WindowStart)?.takeIf { overClear }
    if (runs.isEmpty()) {
        for (cmd in cmds) if (withText || cmd !is DrawCmd.Text) drawCmd(cmd, at)
        return
    }
    var next = 0
    var i = 0
    while (i < cmds.size) {
        val run = runs.getOrNull(next)
        if (run != null && i == run.from) {
            drawRun(next)
            i = run.to
            next++
            continue
        }
        val cmd = cmds[i++]
        if (withText || cmd !is DrawCmd.Text) drawCmd(cmd, at)
    }
}

/** What drawing one window's commands goes by: the shapes it has open, and how hard edges are drawn. */
private class WindowAt(
    val host: WalHost,
    /** The paint for hard edges, or null to draw as usual. */
    val hard: Paint?,
) {
    /** The shapes of the windows being drawn, each masking its window at WindowEnd. */
    val open = ArrayDeque<DrawCmd.WindowStart>()

    /** The shape opened first in a window drawn over nothing, which masks the window in place. */
    var inPlace: DrawCmd.WindowStart? = null
}

/**
 * [image]'s [srcOffset] x [srcSize] part at [dstOffset] x [dstSize], with nearest pixels unless
 * [smooth]: covering only the screen pixels whose centers it holds when [hard] is given, else as
 * `drawImage` draws.
 */
private fun DrawScope.drawPicture(
    image: ImageBitmap,
    srcOffset: IntOffset,
    srcSize: IntSize,
    dstOffset: IntOffset,
    dstSize: IntSize,
    alpha: Float,
    blend: BlendMode,
    hard: Paint?,
    smooth: Boolean = false,
) {
    val quality = if (smooth) FilterQuality.Low else FilterQuality.None
    if (hard == null) {
        drawImage(
            image,
            srcOffset,
            srcSize,
            dstOffset,
            dstSize,
            alpha,
            blendMode = blend,
            filterQuality = quality,
        )
        return
    }
    hard.alpha = alpha
    hard.blendMode = blend
    hard.filterQuality = quality
    drawContext.canvas.drawImageRect(image, srcOffset, srcSize, dstOffset, dstSize, hard)
}

/** A picture command: its part of the sheet, tinted, cut out once, and stretched as Winamp stretches. */
internal fun DrawScope.drawImageCmd(
    cmd: DrawCmd.Image,
    host: WalHost,
    blend: BlendMode = BlendMode.SrcOver,
    mask: Boolean = false,
    hard: Paint? = null,
) {
    if (cmd.dst.width <= 0 || cmd.dst.height <= 0) return
    // the piece cut out: scaled on the phone, a part of a sheet would take in the pixels next to
    // it at its edges (Winamp3x's sheets keep magenta between their parts). It is as large as
    // what the sheet holds of the part the element claims.
    val piece = host.piece(cmd.bitmap, cmd.gamma, mask) ?: return
    val cw = piece.width
    val ch = piece.height
    val dw = cmd.dst.width * cw / (cmd.bitmap.w ?: cw)
    val dh = cmd.dst.height * ch / (cmd.bitmap.h ?: ch)
    val stretch = !cmd.smooth && (dw != cw || dh != ch) && dw > 0 && dh > 0
    // stretched as Winamp stretches: each pixel from source row/column floor(d * src / dst)
    val img = if (stretch) host.stretched(piece, cmd.bitmap, cmd.gamma, mask, dw, dh) else piece
    val src = IntSize(img.width, img.height)
    drawPicture(
        img,
        IntOffset.Zero,
        src,
        IntOffset(cmd.dst.left, cmd.dst.top),
        IntSize(dw, dh),
        cmd.alpha / 255f,
        blend,
        hard,
        cmd.smooth,
    )
}

// one branch per draw command
private fun DrawScope.drawCmd(
    cmd: DrawCmd,
    at: WindowAt,
) {
    val host = at.host
    when (cmd) {
        is DrawCmd.Image -> {
            drawImageCmd(cmd, host, hard = at.hard)
        }

        is DrawCmd.WindowStart -> {
            // the window's content in a layer as large as its shape, masked by it at WindowEnd;
            // one drawn over nothing is only clipped to that size and masked in place
            val b = cmd.region.bounds() ?: Rect(0, 0, 1, 1)
            val bounds =
                androidx.compose.ui.geometry
                    .Rect(0f, 0f, b.right.toFloat(), b.bottom.toFloat())
            if (cmd === at.inPlace) {
                drawContext.canvas.save()
                drawContext.canvas.clipRect(bounds)
            } else {
                drawContext.canvas.saveLayer(
                    bounds,
                    androidx.compose.ui.graphics
                        .Paint(),
                )
            }
            at.open.addLast(cmd)
        }

        DrawCmd.WindowEnd -> {
            // a shape opened over nothing masks in place: the window's pixels are all there is
            at.open.removeLastOrNull()?.let { start -> host.shapeMask(start.region, this)?.let { drawMask(it, at.hard) } }
            drawContext.canvas.restore()
        }

        is DrawCmd.Fill -> {
            val color = Color(cmd.color.value or OPAQUE)
            val r = cmd.rect
            if (at.hard == null) {
                drawRect(
                    color,
                    Offset(r.left.toFloat(), r.top.toFloat()),
                    Size(r.width.toFloat(), r.height.toFloat()),
                    cmd.alpha / 255f,
                )
            } else {
                at.hard.color = color
                at.hard.alpha = cmd.alpha / 255f
                at.hard.blendMode = BlendMode.SrcOver
                drawContext.canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), at.hard)
            }
        }

        is DrawCmd.Text -> {
            drawText(cmd, host)
        }

        is DrawCmd.BitmapText -> {
            drawBitmapText(cmd, host, at.hard)
        }

        is DrawCmd.Pixels -> {
            drawPicture(
                host.pixels(cmd),
                IntOffset.Zero,
                IntSize(cmd.w, cmd.h),
                IntOffset(cmd.rect.left, cmd.rect.top),
                IntSize(cmd.rect.width, cmd.rect.height),
                cmd.alpha / 255f,
                BlendMode.SrcOver,
                at.hard,
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

/** A window's shape [mask] over what the window drew, keeping only what lies inside it; with hard edges when [hard] is given. */
private fun DrawScope.drawMask(
    mask: ImageBitmap,
    hard: Paint?,
) {
    if (hard == null) {
        drawImage(mask, blendMode = BlendMode.DstIn)
        return
    }
    val size = IntSize(mask.width, mask.height)
    drawPicture(
        mask,
        IntOffset.Zero,
        size,
        IntOffset.Zero,
        size,
        1f,
        BlendMode.DstIn,
        hard,
    )
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

/** Text in a skin's bitmap font: one cell of the font sheet per character, with [hard] edges when given. */
private fun DrawScope.drawBitmapText(
    cmd: DrawCmd.BitmapText,
    host: WalHost,
    hard: Paint?,
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
                drawPicture(
                    img,
                    srcOffset = IntOffset(f.ox + cell.first * f.charWidth, f.oy + cell.second * f.charHeight),
                    srcSize = IntSize(f.charWidth, f.charHeight),
                    dstOffset = IntOffset(x, y),
                    dstSize = IntSize(f.charWidth, f.charHeight),
                    alpha = cmd.alpha / 255f,
                    blend = BlendMode.SrcOver,
                    hard = hard,
                )
            }
            x += if (c == ':') colon else advance
        }
    }
}

private const val OPAQUE = 0xFF000000.toInt()
