// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin.fixtures

import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Gamma
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinMenu
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SkinSetup
import nl.mattix.andamp.modernskin.skin.Track
import nl.mattix.andamp.modernskin.skin.WindowId
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * One run of a skin for [EngineTrace]: the skin, the songs the player shows, and the [steps] taken
 * in order on a clock that moves only when a step says so. The run is written into the trace, so a
 * trace says what made it.
 */
class TraceScenario(
    val name: String,
    val files: SkinFiles,
    /** Winamp's getSkinName(): the skin's folder name, which scripts key their private settings by. */
    val skinName: String,
    val playlist: List<String> = listOf("Andamp"),
    val steps: List<Step>,
    /** Whether the host has a media library, which skins read as @HAVE_LIBRARY@. */
    val haveLibrary: Boolean = true,
) {
    /** One thing the run does. */
    sealed interface Step {
        /** The clock runs [ms] on, a display frame at a time, with the engine ticked on each. */
        data class Settle(
            val ms: Long,
        ) : Step

        /** The player starts playing the current song. */
        data object Play : Step

        /** The player stops. */
        data object Stop : Step

        /** The color theme [name] is put on. */
        data class Theme(
            val name: String,
        ) : Step

        /** The window [container] opens, as a skin opens one by itself at start. */
        data class Show(
            val container: String,
        ) : Step

        /** The windows stack in a view [w] x [h], as a phone stacks them. */
        data class View(
            val w: Int,
            val h: Int,
        ) : Step

        /** The main window is resized to [w] x [h], as a host fills its box with a resizable one. */
        data class ResizeMain(
            val w: Int,
            val h: Int,
        ) : Step

        /** A tap in the middle of the object [id], in the window opened last that has it. */
        data class Tap(
            val id: String,
        ) : Step
    }

    companion object {
        /**
         * The run the Winamp reference screenshots were taken with: start-up timers run out, the
         * music plays two seconds and stops, then each tap with three seconds after it.
         */
        @Suppress("LongParameterList") // one for each thing a reference scenario sets
        fun winampReference(
            name: String,
            files: SkinFiles,
            skinName: String,
            playlist: List<String> = listOf("Andamp"),
            theme: String? = null,
            view: Pair<Int, Int>? = null,
            show: List<String> = emptyList(),
            mainSize: Pair<Int, Int>? = null,
            taps: List<String> = emptyList(),
            haveLibrary: Boolean = true,
        ): TraceScenario =
            TraceScenario(
                name,
                files,
                skinName,
                playlist,
                buildList {
                    theme?.let { add(Step.Theme(it)) }
                    mainSize?.let { (w, h) -> add(Step.ResizeMain(w, h)) }
                    view?.let { (w, h) -> add(Step.View(w, h)) }
                    show.forEach { add(Step.Show(it)) }
                    add(Step.Settle(3_000))
                    add(Step.Play)
                    add(Step.Settle(2_000))
                    add(Step.Stop)
                    add(Step.Settle(3_000))
                    for (id in taps) {
                        add(Step.Tap(id))
                        add(Step.Settle(3_000))
                    }
                },
                haveLibrary,
            )
    }
}

/**
 * A record of everything the engine does in a [TraceScenario], as text: every line the skin logs,
 * every menu, host action and window change, the script work of each tick, and the whole draw
 * list of every frame that differs from the one before. Two engines fed the same scenario should
 * write the same trace, line for line; the first line that differs is where they part.
 *
 * The record depends on nothing a platform measures: the host measures text by a fixed rule
 * ([TraceHost]) and takes image sizes and pixels from the skin's files, so any engine can be run
 * against it anywhere. The clock starts at [START] and moves [FRAME_MS] at a time.
 *
 * Format, one event per line, `#` lines being comments:
 * - `trace v1`, `skin <name>`, `song <title>`..., `clock start=<ms> frame=<ms>`
 * - `step <what>` before each step runs
 * - `log <text>`, `menu <items>`, `hostaction <ACTION> <param>`, `windows <ids>` as the engine
 *   calls its host
 * - `tick t=<ms> steps=<n> timers=<n> layouts=<n> shapes=<n> invalidates=<n>` for each tick that
 *   did any script or layout work (t counts from the start)
 * - `frame t=<ms>` ... `endframe` for each frame that draws differently from the one before:
 *   `window <id> <container>/<layout> <rect>` then one line per draw command
 * - `tap <id> <x>,<y>` before a tap's press and release
 *
 * Rects are `left,top,width,height`; colors `#rrggbb`; fractions have two decimals.
 */
object EngineTrace {
    /** 2026-10-20 12:00:00 UTC, as the reference tests start. */
    const val START = 1_792_324_800_000L
    const val FRAME_MS = 16L
    const val VERSION = 1

    /** The trace of [scenario], as text. */
    fun record(
        scenario: TraceScenario,
        viewport: Pair<Int, Int> = 1080 to 1920,
    ): String {
        val out = StringBuilder()
        val host = TraceHost(scenario.files, out)
        var now = START
        val player =
            FakePlayer().apply {
                clock = { now }
                title = scenario.playlist.firstOrNull() ?: "Andamp"
                lengthMs = SONG_MS
                playlist = scenario.playlist.map { Track(it, SONG_MS) }
                decoderName = "Nullsoft Waveform Decoder v3.25"
                bitrateKbps = 64
                sampleRateKhz = 8
                channels = 1
            }
        val rt =
            SkinRuntime(
                scenario.files,
                host,
                player,
                SkinSetup(skinName = scenario.skinName, haveLibrary = scenario.haveLibrary),
                clock = { now },
            )
        rt.viewportW = viewport.first
        rt.viewportH = viewport.second
        out.append("trace v$VERSION\n")
        out.append("skin ${scenario.skinName}\n")
        for (t in scenario.playlist) out.append("song $t\n")
        out.append("clock start=$START frame=$FRAME_MS\n")
        out.append("step load\n")
        rt.load()
        val frames = Frames(rt, host, out)
        frames.tick(now)
        for (step in scenario.steps) {
            out.append("step ${describe(step)}\n")
            when (step) {
                is TraceScenario.Step.Settle -> {
                    val until = now + step.ms
                    while (now < until) {
                        now = minOf(now + FRAME_MS, until)
                        frames.tick(now)
                    }
                }

                TraceScenario.Step.Play -> {
                    player.play()
                }

                TraceScenario.Step.Stop -> {
                    player.stop()
                }

                is TraceScenario.Step.Theme -> {
                    rt.selectColorTheme(step.name)
                }

                is TraceScenario.Step.Show -> {
                    rt.openWindow(step.container)
                }

                is TraceScenario.Step.View -> {
                    rt.viewSize = step.w to step.h
                }

                is TraceScenario.Step.ResizeMain -> {
                    rt.mainWindow?.let { rt.resizeWindow(it, step.w, step.h) }
                }

                is TraceScenario.Step.Tap -> {
                    val target = rt.objectInfo(step.id)
                    if (target == null) {
                        out.append("tap ${step.id} missing\n")
                    } else {
                        val r = target.stackRect
                        val x = r.left + r.width / 2
                        val y = r.top + r.height / 2
                        out.append("tap ${step.id} $x,$y\n")
                        rt.pointerDown(x, y)
                        rt.pointerUp(x, y)
                    }
                }
            }
        }
        out.append("end t=${now - START}\n")
        return out.toString()
    }

    private fun describe(step: TraceScenario.Step): String =
        when (step) {
            is TraceScenario.Step.Settle -> "settle ${step.ms}"
            TraceScenario.Step.Play -> "play"
            TraceScenario.Step.Stop -> "stop"
            is TraceScenario.Step.Theme -> "theme ${step.name}"
            is TraceScenario.Step.Show -> "show ${step.container}"
            is TraceScenario.Step.View -> "view ${step.w}x${step.h}"
            is TraceScenario.Step.ResizeMain -> "resizemain ${step.w}x${step.h}"
            is TraceScenario.Step.Tap -> "tap ${step.id}"
        }

    /** Ticks the engine and writes what each tick did, and each frame that changed. */
    private class Frames(
        private val rt: SkinRuntime,
        private val host: TraceHost,
        private val out: StringBuilder,
    ) {
        private var stats = rt.stats
        private var invalidates = 0
        private var last = ""

        fun tick(now: Long) {
            rt.tick()
            val s = rt.stats
            val t = now - START
            val frame = StringBuilder()
            for ((id, cmds) in rt.windowsDrawn()) {
                val info = rt.windowInfo(id)
                frame.append("window ${id.raw} ${info.container}/${info.layout} ${rect(rt.windowRect(id))}\n")
                for (cmd in cmds) frame.append(line(cmd)).append('\n')
            }
            val drawn = frame.toString()
            val changed = drawn != last
            val worked = s != stats || host.invalidates != invalidates
            if (worked || changed) {
                out.append(
                    "tick t=$t steps=${s.scriptSteps - stats.scriptSteps} timers=${s.timerFires - stats.timerFires} " +
                        "layouts=${s.layoutPasses - stats.layoutPasses} shapes=${s.shapesWorkedOut - stats.shapesWorkedOut} " +
                        "invalidates=${host.invalidates - invalidates}\n",
                )
            }
            if (changed) {
                out.append("frame t=$t\n").append(drawn).append("endframe\n")
                last = drawn
            }
            stats = s
            invalidates = host.invalidates
        }

        /** Each window's id with its drawing, as [SkinRuntime.drawWindows] gives them in stack order. */
        private fun SkinRuntime.windowsDrawn(): List<Pair<WindowId, List<DrawCmd>>> {
            val ids = windowIds
            val drawn = drawWindows()
            check(ids.size == drawn.size) { "${ids.size} windows, ${drawn.size} drawn" }
            return ids.zip(drawn) { id, (_, cmds) -> id to cmds }
        }
    }

    /** A draw command as one line of the trace. */
    fun line(cmd: DrawCmd): String =
        when (cmd) {
            is DrawCmd.Image -> {
                image(cmd)
            }

            is DrawCmd.Fill -> {
                "fill ${rect(cmd.rect)} ${color(cmd.color.value)} a=${cmd.alpha}"
            }

            is DrawCmd.WindowStart -> {
                "shape ${region(cmd.region)}"
            }

            DrawCmd.WindowEnd -> {
                "endshape"
            }

            is DrawCmd.Text -> {
                text(cmd)
            }

            is DrawCmd.BitmapText -> {
                "bitmaptext ${quote(cmd.text)} ${rect(cmd.rect)}"
            }

            is DrawCmd.Clip -> {
                "clip ${rect(cmd.rect)}"
            }

            DrawCmd.Unclip -> {
                "unclip"
            }

            is DrawCmd.Pixels -> {
                val crc = CRC32()
                for (p in cmd.argb) {
                    crc.update(p ushr 24)
                    crc.update(p ushr 16)
                    crc.update(p ushr 8)
                    crc.update(p)
                }
                "pixels ${rect(cmd.rect)} ${cmd.w}x${cmd.h} a=${cmd.alpha} crc=${"%08x".format(crc.value)}" +
                    if (cmd.stable) " stable" else ""
            }
        }

    private fun image(cmd: DrawCmd.Image): String {
        val b = cmd.bitmap
        val sb = StringBuilder("image ${b.file} ${b.x},${b.y},${b.w ?: "-"},${b.h ?: "-"} -> ${rect(cmd.dst)} a=${cmd.alpha}")
        cmd.gamma?.takeUnless { it.isIdentity }?.let { sb.append(" g=").append(gamma(it)) }
        if (cmd.smooth) sb.append(" smooth")
        return sb.toString()
    }

    private fun text(cmd: DrawCmd.Text): String {
        val sb =
            StringBuilder(
                "text ${quote(cmd.text)} font=${cmd.font?.file ?: "-"} size=${cmd.size} ${color(cmd.color.value)} " +
                    "a=${cmd.alpha} ${rect(cmd.rect)} x=${num(cmd.x)} base=${num(cmd.baseline)}",
            )
        if (cmd.bold) sb.append(" bold")
        cmd.shadow?.let { sb.append(" shadow=${color(it.value)} ${cmd.shadowDx},${cmd.shadowDy}") }
        return sb.toString()
    }

    private fun region(r: Region): String =
        when (r) {
            is Region.Pic -> {
                "pic(${image(r.image).removePrefix("image ")})"
            }

            is Region.Box -> {
                "box(${rect(r.rect)})"
            }

            is Region.Op -> {
                val parts = ArrayList<String>()
                r.base?.let { parts.add("base=${region(it)}") }
                if (r.add.isNotEmpty()) parts.add("add=[${r.add.joinToString(" ") { region(it) }}]")
                if (r.keep.isNotEmpty()) parts.add("keep=[${r.keep.joinToString(" ") { region(it) }}]")
                if (r.cut.isNotEmpty()) parts.add("cut=[${r.cut.joinToString(" ") { region(it) }}]")
                "op(${parts.joinToString(" ")})"
            }
        }

    private fun gamma(g: Gamma) = "${g.r},${g.g},${g.b},${g.boost},${g.gray}"

    private fun rect(r: Rect) = "${r.left},${r.top},${r.width},${r.height}"

    private fun color(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)

    private fun num(f: Float) = String.format(Locale.ROOT, "%.2f", f)

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private const val SONG_MS = 600_000
}

/**
 * The host a trace is recorded with: image sizes and pixels from the skin's files, text measured by
 * a fixed rule so no platform's fonts enter the trace, and every call the engine makes written
 * into the trace. The playlist window counts as open and the library as closed, as the reference
 * Winamp had them; a menu is dismissed at once.
 */
class TraceHost(
    private val files: SkinFiles,
    private val out: StringBuilder,
) : SkinHost {
    /** How often the engine asked for a redraw. */
    var invalidates = 0
        private set

    private val images = HashMap<String, java.awt.image.BufferedImage?>()

    private fun image(file: String) =
        images.getOrPut(file.lowercase()) {
            files.read(file)?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
        }

    override fun imageSize(file: String): Pair<Int, Int>? = image(file)?.let { it.width to it.height }

    override fun pixel(
        file: String,
        x: Int,
        y: Int,
    ): Int? = image(file)?.takeIf { x < it.width && y < it.height }?.getRGB(x, y)

    /** Six tenths of the size per character, a tenth more in bold; a fixed rule, no font. */
    override fun textWidth(
        text: String,
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Int = text.length * size * (if (bold) 7 else 6) / 10

    override fun textWidth(
        text: String,
        font: FontDef?,
        size: Int,
    ): Int = textWidth(text, font, size, bold = false)

    /** Eight tenths of the size above the baseline, two below. */
    override fun lineMetrics(
        font: FontDef?,
        size: Int,
        bold: Boolean,
    ): Pair<Float, Float> = size * 0.8f to size * 0.2f

    override fun log(message: String) {
        out.append("log ").append(message.replace("\n", "\\n")).append('\n')
    }

    override fun invalidate() {
        invalidates++
    }

    /** Not in the trace: the engine asks every frame, and the answer is the host's, fixed. */
    override fun windowOpen(kind: String): Boolean? =
        when (kind) {
            "pl" -> true
            "ml" -> false
            else -> null
        }

    override fun chooseFromMenu(menu: SkinMenu): Int {
        out.append("menu ${items(menu)}\n")
        return 0
    }

    private fun items(menu: SkinMenu): String =
        menu.items.joinToString(" ", "[", "]") {
            when (it) {
                is SkinMenu.Command -> "${it.id}:${it.text}${if (it.checked) "*" else ""}${if (it.disabled) "!" else ""}"
                SkinMenu.Separator -> "-"
                is SkinMenu.Submenu -> "${it.text}>${items(it.menu)}"
            }
        }

    override fun windowsChanged(windows: List<WindowId>) {
        out.append("windows ${windows.joinToString(",") { it.raw.toString() }}\n")
    }

    override fun hostAction(
        action: String,
        param: String?,
    ) {
        out.append("hostaction $action ${param ?: "-"}\n")
    }
}
