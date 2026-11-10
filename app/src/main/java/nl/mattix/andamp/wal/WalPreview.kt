// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import nl.mattix.andamp.ui.online.SkinDemo
import nl.mattix.walkit.FakePlayer
import nl.mattix.walkit.Player
import nl.mattix.walkit.SkinRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A classic skin in [file] on a running demo player, drawn by the engine the player draws skins
 * with: the museum's composition, main over equalizer over playlist, at [scale] whole pixels per
 * skin pixel. The demo is the museum's ([SkinDemo]): its songs, its equalizer and its song looping
 * over the stretch it shows, with walkit's own demo signal in the visualizer. Every step of the
 * demo runs on the engine's thread, as the skin reads it there.
 *
 * With [interactive], a finger presses the skin's controls; otherwise the preview takes no touch,
 * so a press reaches what holds it.
 */
@Composable
internal fun WalPreview(
    file: File,
    scale: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val context = LocalContext.current
    val frames = remember(file) { mutableIntStateOf(0) }
    val preview = remember(file) { Preview(file, context.cacheDir) { frames.intValue++ } }
    DisposableEffect(preview) { onDispose { preview.close() } }
    LaunchedEffect(preview) {
        preview.start()
        val began = withInfiniteAnimationFrame()
        while (true) {
            val now = withInfiniteAnimationFrame()
            preview.frame(now - began)
        }
    }
    val density = LocalDensity.current
    val w = STACK_W * scale
    val h = STACK_H * scale
    val touch =
        if (!interactive) {
            Modifier
        } else {
            Modifier.pointerInput(preview, scale) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    preview.engine.pointerDown((down.position.x / scale).toInt(), (down.position.y / scale).toInt())
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        last = change.position
                        if (!change.pressed) break
                        preview.engine.pointerMove((last.x / scale).toInt(), (last.y / scale).toInt())
                        change.consume()
                    }
                    preview.engine.pointerUp((last.x / scale).toInt(), (last.y / scale).toInt())
                }
            }
        }
    Canvas(modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).then(touch)) {
        frames.intValue
        val stack = preview.engine.frame?.stack ?: return@Canvas
        drawWalStack(stack, preview.host, scale.toFloat(), 0f, layer = null)
    }
}

/** The stack's size at 1x: three of Winamp 2's windows, 116 tall each. */
internal const val STACK_W = 275
internal const val STACK_H = 348

/** One preview's engine and demo. */
private class Preview(
    file: File,
    cacheDir: File,
    onFrame: () -> Unit,
) {
    private val opened = WalFiles.open(file, emptyMap<String, File>())
    val host: WalHost = WalHost(opened, cacheDir).apply { crispText = WalCrispText(::fontBytes) }
    val engine = WalEngine("preview-${count.incrementAndGet()}", host, classic = true, onFrame = onFrame)

    /** The demo, touched only on the engine thread. */
    private val demo = SkinDemo()

    /** The demo's clock, the millis of the last frame; the skin, its signal and the demo all read it. */
    @Volatile
    private var demoAt = 0L

    private val signal =
        FakePlayer().apply {
            clock = { demoAt }
            play()
        }

    private fun fontBytes(
        font: nl.mattix.walkit.FontDef?,
        bold: Boolean,
    ): ByteArray? = host.crispFontBytes(font, bold)

    fun start() {
        engine.start(box = IntSize(STACK_W, STACK_H)) { build() }
        // the stack keeps its natural heights, one window under the other
        engine.fit(WalFit(STACK_W, STACK_H, 1f, sideways = false, compact = true))
    }

    private fun build(): SkinRuntime =
        SkinRuntime(
            opened,
            host,
            DemoPlayer(WalPlayer(demo.player) { demoAt }, signal),
            WalPrepare.setup(""),
            clock = { demoAt },
        ).apply {
            load()
            openWindow(EQUALIZER)
            openWindow(PLAYLIST)
            setClassicPlaylistSelection(demo.state.selectedRows)
        }

    /** The demo moves on to [millis] since it began, and the skin with it. */
    fun frame(millis: Long) {
        engine.post(publish = false) {
            demoAt = millis
            demo.frame(millis)
        }
        engine.requestTick()
    }

    fun close() {
        // the files close once the work before is done, and the engine thread then ends
        engine.postAlways { opened.close() }
        engine.close()
    }

    private companion object {
        val count = AtomicInteger()
        const val EQUALIZER = "equalizer"
        const val PLAYLIST = "pledit"
    }
}

/** The demo's player, with [signal]'s demo spectrum and wave in its visualizer while it plays. */
private class DemoPlayer(
    private val player: Player,
    private val signal: Player,
) : Player by player {
    override fun spectrum(): FloatArray = if (status == Player.Status.PLAYING) signal.spectrum() else FloatArray(0)

    override fun waveform(): FloatArray = if (status == Player.Status.PLAYING) signal.waveform() else FloatArray(0)
}

/** Waits for the next display frame on the infinite-animation clock, and gives its time in ms. */
private suspend fun withInfiniteAnimationFrame(): Long =
    androidx.compose.animation.core
        .withInfiniteAnimationFrameMillis { it }
