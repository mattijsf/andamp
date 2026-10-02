// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import nl.mattix.andamp.BuildConfig
import nl.mattix.andamp.R
import nl.mattix.andamp.ui.menu.AmpDialog

/**
 * The about box. Like Winamp's, it warps its logo: the warp is [AboutAnimation], the picture is
 * drawn here, and touching it starts a burst.
 *
 * A dialog over the player, not a screen, and not skinned: the panel uses the mark's own colors.
 */
@Composable
fun AboutDialog(onClose: () -> Unit) {
    AmpDialog(onClose) { AboutBody(onClose) }
}

/** The dialog's contents, separate so a test can drive them. */
@Composable
fun AboutBody(onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(CORNER),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = ELEVATION,
        modifier = Modifier.widthIn(max = WIDTH),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoPanel()
            Spacer(Modifier.height(20.dp))
            Text("Andamp", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "A Winamp 2.8 replica, rebuilt in Kotlin. Not affiliated with " +
                    "Winamp, Nullsoft or Llama Group.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Stamp("mattix.nl", "https://mattix.nl")
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onClose, modifier = Modifier.testTag("about.close")) { Text("Close") }
        }
    }
}

/** A link drawn as a plate with a hard offset shadow, in the style of mattix.nl. */
@Composable
private fun Stamp(
    label: String,
    url: String,
) {
    val context = LocalContext.current
    Box {
        // the shadow takes the plate's size and has no text, so the label is in the tree once
        Box(
            Modifier
                .matchParentSize()
                .offset(SHADOW, SHADOW)
                .background(INK),
        )
        Box(
            Modifier
                .background(LIME)
                .border(BORDER, INK)
                .clickable {
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(view) }.recoverCatching { error ->
                        if (error !is ActivityNotFoundException) throw error
                    }
                }.testTag("about.link")
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                label,
                color = INK,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * The warping picture. The buffer is small and drawn with `FilterQuality.None`, so each pixel stays
 * a whole block on screen and the comb of the feedback is not smoothed by the upscale.
 */
@Composable
private fun LogoPanel() {
    val context = LocalContext.current
    val animation = remember(context) { AboutAnimation(PANEL_W, PANEL_H, pictures(context)) }
    val bitmap = remember { Bitmap.createBitmap(PANEL_W, PANEL_H, Bitmap.Config.ARGB_8888) }
    val image = remember { bitmap.asImageBitmap() }
    // one counter for the whole panel: the pixels live in an array the composition does not diff
    var frame by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animation) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    val delta = if (last == 0L) 0 else (now - last).coerceAtMost(SLOWEST)
                    last = now
                    animation.advance(delta)
                    bitmap.setPixels(animation.pixels, 0, PANEL_W, 0, 0, PANEL_W, PANEL_H)
                    frame++
                }
            }
        }
    }
    Box {
        Box(
            Modifier
                .offset(SHADOW, SHADOW)
                .fillMaxWidth()
                .aspectRatio(PANEL_W.toFloat() / PANEL_H)
                .background(INK),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(PANEL_W.toFloat() / PANEL_H)
                .border(BORDER, INK)
                .clickable { animation.poke() }
                .testTag("about.logo"),
        ) {
            frame // read, so a new frame is what redraws this
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(PANEL_W, PANEL_H),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/**
 * The two still pictures the warp travels between: Andamp's mark, then the maker's. The app's is
 * the launcher icon's two layers, rendered from the same vectors the icon ships as. mattix's is
 * `logo.svg` from mattix.nl, transcribed here so it needs no network.
 */
private fun pictures(context: android.content.Context): List<IntArray> = listOf(appMark(context), makerMark())

/** The launcher icon, drawn into the panel: plate behind, mark on top. */
private fun appMark(context: android.content.Context): IntArray {
    val bitmap = Bitmap.createBitmap(PANEL_W, PANEL_H, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val plate = ContextCompat.getDrawable(context, R.drawable.ic_launcher_background)
    val mark = ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
    // the icon is square and the panel is not: the plate is stretched to fill, and the mark keeps
    // its shape
    plate?.setBounds(0, 0, PANEL_W, PANEL_H)
    plate?.draw(canvas)
    val side = (PANEL_H * ICON).toInt()
    val left = (PANEL_W - side) / 2
    val top = (PANEL_H - side) / 2
    mark?.setBounds(left, top, left + side, top + side)
    mark?.draw(canvas)
    return IntArray(PANEL_W * PANEL_H).also { bitmap.getPixels(it, 0, PANEL_W, 0, 0, PANEL_W, PANEL_H) }
}

/** mattix's mark on its own colors. */
private fun makerMark(): IntArray {
    val image = ImageBitmap(PANEL_W, PANEL_H)
    val mark = PathParser().parsePathString(LOGO).toPath()
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(image),
        Size(PANEL_W.toFloat(), PANEL_H.toFloat()),
    ) {
        drawRect(PINK)
        // a band of color behind the mark, the way mattix.nl highlights a word
        drawRect(LIME, topLeft = Offset(0f, PANEL_H * BAND_TOP), size = Size(PANEL_W.toFloat(), PANEL_H * BAND))
        val scale = PANEL_H * MARK / LOGO_SIDE
        translate(PANEL_W / 2f - LOGO_SIDE * scale / 2f, PANEL_H / 2f - LOGO_SIDE * scale / 2f) {
            scale(scale, scale, pivot = Offset.Zero) { drawPath(mark, INK) }
        }
    }
    return image.toPixelMap().buffer
}

/** mattix.nl/logo.svg, `viewBox="0 0 96 96"`. */
private const val LOGO = "M6 12 H30 L48 48 L66 12 H90 V84 H80 L65 54 H51 L66 84 H30 L45 54 H31 L16 84 H6 Z"

/**
 * How much of the panel's height the app's square mark takes. Past 1, because the icon carries the
 * margin a launcher's mask needs and nothing masks it here.
 */
private const val ICON = 1.24f

private const val LOGO_SIDE = 96f

/** How much of the panel's height the mark takes. */
private const val MARK = 0.62f

private const val BAND_TOP = 0.60f
private const val BAND = 0.12f

/** The buffer's size; small, so every pixel is several on screen. */
private const val PANEL_W = 220
private const val PANEL_H = 143

/** The longest step one frame may advance, in milliseconds, so a pause is not replayed. */
private const val SLOWEST = 50L

private val INK = Color(0xFF17130F)
private val PINK = Color(0xFFFF5FA2)
private val LIME = Color(0xFFCCFF00)

private val CORNER = 16.dp
private val ELEVATION = 6.dp
private val WIDTH = 360.dp

private val SHADOW = 6.dp
private val BORDER = 3.dp
