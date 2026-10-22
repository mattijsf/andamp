// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.Align
import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.Gamma
import nl.mattix.andamp.modernskin.skin.GdiLineMetrics
import nl.mattix.andamp.modernskin.skin.ImageLimits
import nl.mattix.andamp.modernskin.skin.LayeredSkinFiles
import nl.mattix.andamp.modernskin.skin.Rect
import nl.mattix.andamp.modernskin.skin.Region
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SkinsFolderFiles
import nl.mattix.andamp.modernskin.skin.Track
import nl.mattix.andamp.modernskin.skin.VAlign
import nl.mattix.andamp.modernskin.skin.WithGeneratedImages
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.AlphaComposite
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders each skin's main layout to a PNG next to the reference screenshots from Winamp 5.666,
 * in the state the reference shows: Andamp.wav played for 2 s, then stopped.
 *
 * Runs only when ~/Development/winamp-vm/shots exists (the reference VM setup).
 */
class ReferenceRenderTest {
    private val out = File(System.getProperty("user.home"), "Development/winamp-vm/shots")

    /** The other .wal files in the resources as sibling skins, by folder name ("BigBento.wal" is "Big Bento"). */
    private fun withSiblings(
        skin: SkinFiles,
        res: File,
    ): SkinFiles {
        val siblings =
            res
                .listFiles { f -> f.name.endsWith(".wal") }
                .orEmpty()
                .associate { f -> f.nameWithoutExtension.replace(" ", "").lowercase() to ZipSkinFiles(f) }
        return SkinsFolderFiles(skin, siblings)
    }

    /**
     * Winamp's default element set (its Plugins\freeform\xml\wasabi folder, zipped from inside;
     * Nullsoft's files, never in the repo) behind a skin, as Winamp has it. Without the zip a skin
     * uses only the elements it brings.
     */
    private fun withDefaults(skin: SkinFiles): SkinFiles {
        // -Ddefaults=andamp: Andamp's own default element set instead of Winamp's
        if (System.getProperty("defaults") == "andamp") return LayeredSkinFiles(skin, AndampDefaultSet)
        val zip = File(out.parentFile, "wasabi-defaults.zip")
        return if (zip.exists()) LayeredSkinFiles(skin, ZipSkinFiles(zip)) else skin
    }

    private class Java2dHost(
        private val files: SkinFiles,
    ) : SkinHost {
        val log = ArrayList<String>()
        private val images = HashMap<String, BufferedImage?>()
        private val fonts = HashMap<String, Font?>()

        fun image(file: String): BufferedImage? =
            images.getOrPut(file.lowercase()) {
                files
                    .read(file)
                    ?.takeUnless { ImageLimits.tooLarge(it) }
                    ?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
                    ?.let { srgb(it) }
            }

        /**
         * ImageIO hands grayscale PNGs over in a linear gray space, and getRGB then brightens
         * them. Skin images mean their gray values as sRGB, so copy the samples over as they are.
         */
        private fun srgb(src: BufferedImage): BufferedImage {
            if (src.colorModel.colorSpace.type != java.awt.color.ColorSpace.TYPE_GRAY) return src
            val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
            val raster = src.raster
            val hasAlpha = src.colorModel.hasAlpha()
            val maxGray = (1 shl src.colorModel.getComponentSize(0)) - 1
            for (y in 0 until src.height) {
                for (x in 0 until src.width) {
                    val gray = raster.getSample(x, y, 0) * 255 / maxGray
                    val alpha =
                        if (hasAlpha) {
                            raster.getSample(
                                x,
                                y,
                                1,
                            ) * 255 / ((1 shl src.colorModel.getComponentSize(1)) - 1)
                        } else {
                            255
                        }
                    out.setRGB(x, y, (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray)
                }
            }
            return out
        }

        private val tinted = HashMap<Pair<String, Gamma>, BufferedImage?>()
        private val masks = HashMap<String, BufferedImage?>()

        /** A region picture as a mask: opaque where it cuts, clear elsewhere. */
        fun cutMask(file: String): BufferedImage? =
            masks.getOrPut(file.lowercase()) {
                val src = image(file) ?: return@getOrPut null
                val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until src.height) {
                    for (x in 0 until src.width) {
                        out.setRGB(x, y, if (DrawCmd.isCutPixel(src.getRGB(x, y))) 0xFF000000.toInt() else 0)
                    }
                }
                out
            }

        /** The image with a color theme applied, made once per file and tint. */
        fun image(
            file: String,
            gamma: Gamma?,
        ): BufferedImage? {
            if (gamma == null) return image(file)
            return tinted.getOrPut(file.lowercase() to gamma) {
                val src = image(file) ?: return@getOrPut null
                val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until src.height) for (x in 0 until src.width) out.setRGB(x, y, gamma.apply(src.getRGB(x, y)))
                out
            }
        }

        fun font(
            def: FontDef?,
            size: Int,
            bold: Boolean = false,
        ): Font {
            val em =
                nl.mattix.andamp.modernskin.skin.Text
                    .emSize(size)
            if (def != null && def.file.isEmpty()) {
                // a system font by name (Bento's Tahoma): the free stand-in the app carries, as the
                // phone draws it; -Dsysfonts=<dir> takes <name>.ttf / <name>bd.ttf from there instead
                // (the reference VM's own fonts, ~/Development/winamp-vm/fonts, to compare against)
                val key = def.id + if (bold) "/bold" else ""
                val sys =
                    fonts.getOrPut(key) {
                        val file =
                            SYS_FONTS?.let { File(it, def.id.replace(" ", "") + (if (bold) "bd" else "") + ".ttf") }
                                ?: STAND_INS[def.id]?.let { File(APP_FONTS, it + (if (bold) "-Bold" else "") + ".ttf") }
                        file?.takeIf { it.exists() }?.let { f ->
                            GdiLineMetrics.read(f.readBytes())?.let { gdi[key] = it }
                            runCatching { Font.createFont(Font.TRUETYPE_FONT, f) }.getOrNull()
                        }
                    }
                if (sys != null) return sys.deriveFont(Font.PLAIN, em).also { lineOf[it] = gdi[key] }
            }
            val base =
                def?.let {
                    fonts.getOrPut(it.id) {
                        files.read(it.file)?.let { b ->
                            GdiLineMetrics.read(b)?.let { m -> gdi[it.id] = m }
                            runCatching { Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(b)) }.getOrNull()
                        }
                    }
                } ?: Font("Arial", Font.PLAIN, size)
            return base.deriveFont(if (bold) Font.BOLD else Font.PLAIN, em).also { f -> def?.let { lineOf[f] = gdi[it.id] } }
        }

        private val gdi = HashMap<String, GdiLineMetrics>()
        private val lineOf = HashMap<Font, GdiLineMetrics?>()

        /** Ascent and descent as GDI lays the font out, or Java's when the file gave none. */
        fun line(
            f: Font,
            fm: java.awt.FontMetrics,
        ): Pair<Int, Int> = lineOf[f]?.at(f.size2D) ?: (fm.ascent to fm.descent)

        private val scratch = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()

        override fun imageSize(file: String): Pair<Int, Int>? = image(file)?.let { it.width to it.height }

        override fun textWidth(
            text: String,
            font: FontDef?,
            size: Int,
        ): Int = scratch.getFontMetrics(font(font, size)).stringWidth(text)

        override fun textWidth(
            text: String,
            font: FontDef?,
            size: Int,
            bold: Boolean,
        ): Int = scratch.getFontMetrics(font(font, size, bold)).stringWidth(text)

        override fun log(message: String) {
            log.add(message)
        }

        override fun invalidate() = Unit

        // the reference Winamp (magenta desktop shots) has its playlist open, its library and album art closed
        override fun windowOpen(kind: String): Boolean? =
            when (kind) {
                "pl" -> true
                "ml" -> false
                else -> null
            }

        override fun pixel(
            file: String,
            x: Int,
            y: Int,
        ): Int? = image(file)?.takeIf { x < it.width && y < it.height }?.getRGB(x, y)
    }

    /**
     * The test's clock: starts at a fixed moment and moves only when stepped, so every timer and
     * animation lands on the same frame in every run, and nothing waits for real time.
     */
    class TestClock(
        var now: Long = START,
    ) {
        /** Advances [ms] in display frames of 16 ms, ticking the runtime on each. */
        fun step(
            rt: SkinRuntime,
            ms: Long,
        ) {
            val until = now + ms
            while (now < until) {
                now = minOf(now + FRAME, until)
                rt.tick()
            }
        }

        companion object {
            /** 2026-10-20 12:00:00 UTC */
            const val START = 1_792_324_800_000L
            const val FRAME = 16L
        }
    }

    private val clocks = java.util.WeakHashMap<SkinRuntime, TestClock>()

    private fun clockOf(rt: SkinRuntime): TestClock = clocks.getOrPut(rt) { TestClock() }

    private fun settle(
        rt: SkinRuntime,
        ms: Long,
    ) = clockOf(rt).step(rt, ms)

    /** Puts a runtime and its demo player on a stepped clock. */
    private fun onTestClock(
        rt: SkinRuntime,
        player: FakePlayer,
    ): TestClock {
        val c = clockOf(rt)
        rt.clock = { c.now }
        player.clock = { c.now }
        return c
    }

    /** A window's shape as an alpha mask the size of the window. */
    private fun regionMask(
        region: Region,
        host: Java2dHost,
        w: Int,
        h: Int,
    ): BufferedImage {
        val stack = ArrayDeque<Pair<BufferedImage, java.awt.Graphics2D>>()

        fun fresh() = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).let { it to it.createGraphics() }
        stack.addLast(fresh())
        region.eval(
            object : Region.Canvas {
                override fun picture(
                    image: DrawCmd.Image,
                    hard: Boolean,
                ) = drawImageCmd(stack.last().second, host, image, AlphaComposite.SRC_OVER, 0, 0, mask = hard)

                override fun box(rect: Rect) {
                    val g = stack.last().second
                    g.composite = AlphaComposite.SrcOver
                    g.color = java.awt.Color.BLACK
                    g.fillRect(rect.left, rect.top, rect.width, rect.height)
                }

                override fun layer(
                    blend: Region.Blend,
                    body: () -> Unit,
                ) {
                    stack.addLast(fresh())
                    body()
                    val (img, g) = stack.removeLast()
                    g.dispose()
                    val outer = stack.last().second
                    outer.composite =
                        when (blend) {
                            Region.Blend.ADD -> AlphaComposite.SrcOver
                            Region.Blend.KEEP -> AlphaComposite.DstIn
                            Region.Blend.CUT -> AlphaComposite.DstOut
                        }
                    outer.drawImage(img, 0, 0, null)
                }
            },
        )
        val (img, g) = stack.removeLast()
        g.dispose()
        return img
    }

    private fun drawImageCmd(
        g: java.awt.Graphics2D,
        host: Java2dHost,
        cmd: DrawCmd.Image,
        rule: Int,
        ox: Int,
        oy: Int,
        mask: Boolean = false,
    ) {
        val src = (if (mask) host.cutMask(cmd.bitmap.file) else host.image(cmd.bitmap.file, cmd.gamma)) ?: return
        val sw = cmd.bitmap.w ?: (src.width - cmd.bitmap.x)
        val sh = cmd.bitmap.h ?: (src.height - cmd.bitmap.y)
        if (sw <= 0 || sh <= 0 || cmd.dst.width <= 0 || cmd.dst.height <= 0) return
        g.composite = AlphaComposite.getInstance(rule, cmd.alpha / 255f)
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            if (cmd.smooth) RenderingHints.VALUE_INTERPOLATION_BILINEAR else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        if (!cmd.smooth && (sw != cmd.dst.width || sh != cmd.dst.height)) {
            // stretched as Winamp stretches: each pixel from source row/column floor(d * src / dst)
            val out = BufferedImage(cmd.dst.width, cmd.dst.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until cmd.dst.height) {
                val sy = cmd.bitmap.y + (y.toLong() * sh / cmd.dst.height).toInt()
                for (x in 0 until cmd.dst.width) {
                    val sx = cmd.bitmap.x + (x.toLong() * sw / cmd.dst.width).toInt()
                    if (sx < src.width && sy < src.height) out.setRGB(x, y, src.getRGB(sx, sy))
                }
            }
            g.drawImage(out, cmd.dst.left - ox, cmd.dst.top - oy, null)
            return
        }
        g.drawImage(
            src,
            cmd.dst.left - ox,
            cmd.dst.top - oy,
            cmd.dst.right - ox,
            cmd.dst.bottom - oy,
            cmd.bitmap.x,
            cmd.bitmap.y,
            cmd.bitmap.x + sw,
            cmd.bitmap.y + sh,
            null,
        )
    }

    /**
     * One entry of scenarios.json: what Winamp, this test and the Android check all set up. The
     * Winamp side plays the playlist's first title (`<title>.wav` in C:\winamp-vm) for 2 s and
     * stops; [view] is the phone's screen in layout pixels, which docks and sizes other windows.
     */
    data class Scenario(
        val name: String,
        val wal: String,
        /** The skin's name in the VM's Skins folder (what getSkinName() answers there). */
        val skin: String,
        val theme: String?,
        val playlist: List<String>,
        val taps: List<String>,
        val view: Pair<Int, Int>?,
        /** The most that may differ in total, and in shape pixels (everything but TrueType text), in percent. */
        val limit: Double,
        val shapeLimit: Double,
        /** "mainsize": [w, h] for a resizable main window, else its own size. */
        val mainSize: Pair<Int, Int>? = null,
        /** "show": containers open at start without a tap, as the skin opens them in Winamp. */
        val show: List<String> = emptyList(),
    ) {
        companion object {
            fun load(file: File): List<Scenario> {
                val arr = JSONArray(file.readText())
                return (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Scenario(
                        o.getString("name"),
                        o.getString("wal"),
                        o.getString("skin"),
                        o.optString("theme", null).takeIf { !o.isNull("theme") },
                        o.optJSONArray("playlist")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf("Andamp"),
                        o.optJSONArray("taps")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                        o.optJSONArray("view")?.takeIf { it.length() == 2 }?.let { it.getInt(0) to it.getInt(1) },
                        o.optDouble("limit", 100.0),
                        o.optDouble("shape", 100.0),
                        o.optJSONArray("mainsize")?.takeIf { it.length() == 2 }?.let { it.getInt(0) to it.getInt(1) },
                        o.optJSONArray("show")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    )
                }
            }
        }
    }

    /** Renders, compares and returns the share of pixels that differ from the reference, in percent (null without one). */
    private fun render(sc: Scenario): Double? {
        val res = File(localResource("/CornerAmp_Redux.wal").toURI()).parentFile
        val name = sc.name
        val files = withDefaults(withSiblings(ZipSkinFiles(File(res, sc.wal)), res))
        val host = Java2dHost(WithGeneratedImages(files))
        val player =
            FakePlayer().apply {
                title = sc.playlist.firstOrNull() ?: "Andamp"
                lengthMs = 600_000
                playlist = sc.playlist.map { Track(it, 600_000) }
                // the reference plays an 8 kHz 8-bit mono WAV: Winamp names its decoder, and Bento's file info shows it
                decoderName = "Nullsoft Waveform Decoder v3.25"
                bitrateKbps = 64
                sampleRateKhz = 8
                channels = 1
            }
        val rt = SkinRuntime(files, host, player)
        onTestClock(rt, player)
        // the reference VM's screen: scripts that size a window from the viewport (Bento) then agree
        // Winamp's getSkinName(): the skin's folder name in the VM (scripts key their private settings by it)
        rt.skinName = sc.skin.removeSuffix(".wal")
        // probes for the phone's start: -Dhave.library=0, -Dprivate=section/item=value
        System.getProperty("have.library")?.let { rt.haveLibrary = it == "1" }
        System.getProperty("private")?.let { p -> rt.privateStrings[p.substringBefore('=').lowercase()] = p.substringAfter('=') }
        System.getProperty("setting")?.let { p -> rt.storedSettings[p.substringBefore('=')] = p.substringAfter('=') }
        rt.viewportW = VM_SCREEN_W
        rt.viewportH = VM_SCREEN_H
        val t0 = System.nanoTime()
        rt.load()
        if (System.getProperty("timing") != null) {
            val t1 = System.nanoTime()
            rt.mainLayout?.let { l -> repeat(50) { rt.draw(l) } }
            val t2 = System.nanoTime()
            rt.mainLayout?.let { l ->
                repeat(50) {
                    l.makiCall("getwidth", emptyList())
                    rt.tick()
                }
            }
            println(
                "TIMING ${sc.name} load=${(t1 - t0) / 1_000_000} ms draw=${(t2 - t1) / 50_000} us/frame " +
                    "tick=${(System.nanoTime() - t2) / 50_000} us",
            )
        }
        sc.theme?.let { rt.selectGammaSet(it) }
        // -Dprobe.color=a,b: those colors as the skin resolves them
        System.getProperty("probe.color")?.split(",")?.forEach {
            println(
                "COLOR ${sc.name} $it = ${rt.color(it)?.value?.let { v ->
                    "%06x".format(v)
                }} group=${rt.gamma("text")}",
            )
        }
        if (System.getProperty("trace.content") != null) {
            rt.mainLayout?.let {
                println(
                    "CONTENT ${sc.name} size=${it.size} right=${it.contentRight()} bottom=${it.contentBottom()}",
                )
            }
        }
        // a scenario may size a resizable main window, as the phone does (Bento filling its screen)
        sc.mainSize?.let { (w, h) ->
            rt.mainLayout?.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(w), Value.Int(h)))
        }
        // a screen size stacks windows as the phone does: docked ones at the main window's width
        rt.viewSize = sc.view
        // windows the skin opens by itself at start in Winamp (Winamp3's playlist and Thinger)
        for (id in sc.show) rt.container(id)?.let { rt.openWindow(it) }
        // let start-up timers run out, as the reference waited a few seconds before its screenshot
        settle(rt, 3_000)
        // the reference played Andamp.wav (10 min of silence) for 2 s and stopped it
        player.play()
        settle(rt, 2_000)
        player.stop()
        settle(rt, 3_000)
        // what the VM script needs to click the same spots: each window's size at the start (to find
        // it on the Windows desktop), each tap relative to its window, and the window to compare
        val main = rt.mainLayout ?: return null
        val tapsOut = JSONObject()
        val sizes = JSONObject()
        sizes.put(main.container?.id ?: "main", JSONArray(listOf(main.size.first, main.size.second)))
        // the playlist editor's size on the VM: as the phone would dock it (arrange() does the same),
        // else the skin's own, so one scenario's size never leaks into the next
        val plSize =
            sc.view?.let { (_, vh) -> main.size.first to vh - main.contentBottom() }
                ?: rt.containers
                    .firstOrNull { it.id.equals("pledit", ignoreCase = true) }
                    ?.layout("normal")
                    ?.size
        plSize?.let { (pw, ph) -> tapsOut.put("plrect", JSONArray(listOf(pw, ph))) }
        val tapList = JSONArray()
        // taps one object after another, as a user would
        for (id in sc.taps) {
            // the window opened last first: its buttons share ids with the main window's
            val target = rt.windows.asReversed().firstNotNullOfOrNull { it.findObject(id) }
            if (target == null) {
                println("$name: no object $id")
                continue
            }
            // pointer events take points of the window stack
            val window = target.layoutObject
            val origin = window?.let { rt.windowRect(it) }
            val lx = target.rect.left + target.rect.width / 2
            val ly = target.rect.top + target.rect.height / 2
            val cx = lx + (origin?.left ?: 0)
            val cy = ly + (origin?.top ?: 0)
            println("$name: tap $id at $cx,$cy hits ${rt.hitTest(cx, cy)}")
            tapList.put(
                JSONObject()
                    .put("id", id)
                    .put("window", window?.container?.id ?: "main")
                    .put("x", lx)
                    .put("y", ly),
            )
            rt.pointerDown(cx, cy)
            rt.pointerUp(cx, cy)
            settle(rt, 3_000)
        }
        println("$name: unhandled attributes ${rt.unhandledParams}")
        // -Dprobe.dump=id,id prints those objects' subtrees
        System.getProperty("probe.dump")?.split(',')?.forEach { id ->
            val o = rt.windows.asReversed().firstNotNullOfOrNull { it.findObject(id.trim()) }
            println("DUMP $id:\n" + (o?.let { dump(it, rt, max = 6) } ?: "  (not found)"))
        }
        // the window opened last (the main one unless a tap opened another)
        val layout = rt.windows.lastOrNull() ?: return null
        val (w, h) = layout.size
        tapsOut.put("windows", sizes).put("taps", tapList)
        tapsOut.put("compare", JSONObject().put("window", layout.container?.id ?: "main").put("w", w).put("h", h))
        File(out, "taps-$name.json").writeText(tapsOut.toString(1))
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        var g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // which object last changed each pixel, to trace a wrong pixel back to its object, and
        // whether that was TrueType text (fonts are scored apart from shapes)
        val owner = arrayOfNulls<String>(w * h)
        val isText = BooleanArray(w * h)
        val fromBase = BooleanArray(w * h)
        var target = img
        val targets = ArrayDeque<BufferedImage>()
        val clips = ArrayDeque<java.awt.Shape?>()
        val windows = ArrayDeque<Triple<BufferedImage, java.awt.Graphics2D, DrawCmd.WindowStart>>()
        if (System.getProperty("probe.rect") != null) {
            val (px0, py0, px1, py1) = System.getProperty("probe.rect").split(",").map { it.toInt() }
            for (c in rt.draw(layout)) {
                if (c is DrawCmd.WindowStart) {
                    println("PROBE $name WindowStart ${c.owner} pics=${c.region.pics().map { it.bitmap.id + "@" + it.dst }}")
                } else if (System.getProperty("probe.all") != null && c !is DrawCmd.Image) {
                    println(
                        "PROBE $name ${c.javaClass.simpleName} ${c.owner}" +
                            if (c is DrawCmd.Text) " '${c.text}' ${c.rect} ${c.align} size=${c.size}" else "",
                    )
                } else if (c is DrawCmd.Image && c.dst.left < px1 && c.dst.right > px0 && c.dst.top < py1 && c.dst.bottom > py0) {
                    println(
                        "PROBE $name ${c.owner} ${c.bitmap.id} ${c.bitmap.file} src=${c.bitmap.x},${c.bitmap.y} " +
                            "${c.bitmap.w}x${c.bitmap.h} dst=${c.dst} a=${c.alpha} sheet=${host.imageSize(c.bitmap.file)}",
                    )
                }
            }
        }
        for (cmd in rt.draw(layout)) {
            val before = target.getRGB(0, 0, w, h, null, 0, w)
            val drawnOn = target
            when (cmd) {
                is DrawCmd.Image -> {
                    drawImageCmd(g, host, cmd, AlphaComposite.SRC_OVER, 0, 0)
                }

                is DrawCmd.Text -> {
                    val r = cmd.rect
                    val f = host.font(cmd.font, cmd.size, cmd.bold)
                    val fm = g.getFontMetrics(f)
                    val tw = fm.stringWidth(cmd.text)
                    val x =
                        when (cmd.align) {
                            Align.LEFT -> r.left
                            Align.CENTER -> r.left + (r.width - tw) / 2
                            Align.RIGHT -> r.right - tw
                        } - cmd.scroll
                    val (asc, desc) = host.line(f, fm)
                    val y =
                        when (cmd.valign) {
                            VAlign.TOP -> r.top + asc
                            VAlign.CENTER -> r.top + (r.height - (asc + desc)) / 2 + asc
                            VAlign.BOTTOM -> r.bottom - desc
                        }
                    val clip = g.clip
                    g.clipRect(r.left, r.top, r.width, r.height)
                    g.font = f
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, cmd.alpha / 255f)
                    cmd.shadow?.let {
                        g.color = java.awt.Color(it.value)
                        g.drawString(cmd.text, x + cmd.shadowDx, y + cmd.shadowDy)
                    }
                    g.color = java.awt.Color(cmd.color.value)
                    g.drawString(cmd.text, x, y)
                    g.clip = clip
                }

                is DrawCmd.BitmapText -> {
                    val src = host.image(cmd.font.file, cmd.gamma) ?: continue
                    val f = cmd.font
                    val adv = f.charWidth + f.hSpacing
                    val colon = cmd.colonWidth ?: adv
                    val total = cmd.text.sumOf { if (it == ':') colon else adv }
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
                    val clip = g.clip
                    g.clipRect(cmd.rect.left, cmd.rect.top, cmd.rect.width, cmd.rect.height)
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, cmd.alpha / 255f)
                    for (c in cmd.text) {
                        f.cell(c)?.let { (cx, cy) ->
                            val sx = f.ox + cx * f.charWidth
                            val sy = f.oy + cy * f.charHeight
                            g.drawImage(
                                src,
                                x,
                                y,
                                x + f.charWidth,
                                y + f.charHeight,
                                sx,
                                sy,
                                sx + f.charWidth,
                                sy + f.charHeight,
                                null,
                            )
                        }
                        x += if (c == ':') colon else adv
                    }
                    g.clip = clip
                }

                is DrawCmd.Fill -> {
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, cmd.alpha / 255f)
                    g.color = java.awt.Color(cmd.color.value)
                    g.fillRect(cmd.rect.left, cmd.rect.top, cmd.rect.width, cmd.rect.height)
                }

                is DrawCmd.Pixels -> {
                    val pic = BufferedImage(cmd.w, cmd.h, BufferedImage.TYPE_INT_ARGB)
                    pic.setRGB(0, 0, cmd.w, cmd.h, cmd.argb, 0, cmd.w)
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, cmd.alpha / 255f)
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    g.drawImage(pic, cmd.rect.left, cmd.rect.top, cmd.rect.width, cmd.rect.height, null)
                }

                is DrawCmd.WindowStart -> {
                    // the window's content in a layer of its own, masked by the union at WindowEnd
                    val layer = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                    val lg = layer.createGraphics()
                    lg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    windows.addLast(Triple(layer, g, cmd))
                    g = lg
                    targets.addLast(target)
                    target = layer
                }

                DrawCmd.WindowEnd -> {
                    val (layer, outer, start) = windows.removeLast()
                    val mask = regionMask(start.region, host, w, h)
                    g.composite = AlphaComposite.DstIn
                    g.drawImage(mask, 0, 0, null)
                    g.dispose()
                    g = outer
                    target = targets.removeLast()
                    g.composite = AlphaComposite.SrcOver
                    g.drawImage(layer, 0, 0, null)
                }

                is DrawCmd.Clip -> {
                    clips.addLast(g.clip)
                    g.clipRect(cmd.rect.left, cmd.rect.top, cmd.rect.width, cmd.rect.height)
                }

                DrawCmd.Unclip -> {
                    g.clip = clips.removeLastOrNull()
                }
            }
            if (cmd !is DrawCmd.WindowStart && cmd !is DrawCmd.WindowEnd) {
                val after = drawnOn.getRGB(0, 0, w, h, null, 0, w)
                // a faint overlay (alpha 13 over the whole display) changes every pixel a little; it is not the cause
                for (i in after.indices) {
                    if (after[i] == before[i]) continue
                    // text owns every pixel it touched, fringes included: a faint anti-aliased edge
                    // is still a font difference, not a shape one
                    if (cmd is DrawCmd.Text) isText[i] = true
                    if (colorDistance(after[i], before[i]) > OWNER_DELTA) {
                        owner[i] = cmd.owner ?: cmd.javaClass.simpleName
                        fromBase[i] = cmd is DrawCmd.Image && cmd.bitmap.file.startsWith(LayeredSkinFiles.BASE_PREFIX)
                        if (cmd !is DrawCmd.Text) isText[i] = false
                    }
                }
            }
        }
        g.dispose()
        ImageIO.write(img, "png", File(out, "ours-$name.png"))
        // -Dtrace.base: what of Winamp's default element set the scenario used, and the share of the
        // window's pixels that show its pictures
        if (System.getProperty("trace.base") != null) {
            rt.baseUsage.forEach { println("BASE $name $it") }
            val visible = (0 until w * h).count { (img.getRGB(it % w, it / w) ushr 24) > 0 }
            println("BASEPX $name ${fromBase.count { it }} of $visible")
        }
        println("$name: ${w}x$h, log ${host.log.size} lines")
        host.log.forEach { println("  log: $it") }
        return triage(name, img, owner, isText)
    }

    /** Scores: total, shape (not text) and text, in percent of the reference's pixels. */
    data class Score(
        val total: Double,
        val shape: Double,
        val text: Double,
    )

    private val shapeScores = HashMap<String, Double>()

    /**
     * Compares with the reference and lists which objects own the differing pixels, worst first,
     * with the box they span. Pixels nothing drew are listed as "(nothing drawn)".
     */
    private fun triage(
        name: String,
        ours: BufferedImage,
        owner: Array<String?>,
        isText: BooleanArray,
    ): Double? {
        val refFile = File(out, "ref-$name.png")
        if (!refFile.exists()) return null
        val ref = ImageIO.read(refFile)
        val w = minOf(ref.width, ours.width)
        val h = minOf(ref.height, ours.height)

        class Box(
            var n: Int = 0,
            var x0: Int = Int.MAX_VALUE,
            var y0: Int = Int.MAX_VALUE,
            var x1: Int = -1,
            var y1: Int = -1,
        )
        val boxes = LinkedHashMap<String, Box>()
        var total = 0
        var textPixels = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = ref.getRGB(x, y)
                val o = ours.getRGB(x, y)
                // ours over the same magenta as the reference desktop
                val oa = (o ushr 24) / 255.0

                fun ch(
                    v: Int,
                    s: Int,
                ) = (v shr s) and 0xff

                fun over(
                    s: Int,
                    bg: Int,
                ) = (ch(o, s) * oa + bg * (1 - oa)).toInt()
                val dr = ch(a, 16) - over(16, 255)
                val dg = ch(a, 8) - over(8, 0)
                val db = ch(a, 0) - over(0, 255)
                // the same 12 % colour distance as `magick compare -fuzz 12%`
                if (Math.sqrt((dr * dr + dg * dg + db * db) / 3.0) <= 0.12 * 255) continue
                total++
                if (isText[y * ours.width + x]) textPixels++
                val b = boxes.getOrPut(owner[y * ours.width + x] ?: "(nothing drawn)") { Box() }
                b.n++
                b.x0 = minOf(b.x0, x)
                b.y0 = minOf(b.y0, y)
                b.x1 = maxOf(b.x1, x)
                b.y1 = maxOf(b.y1, y)
            }
        }
        val n = w * h
        shapeScores[name] = 100.0 * (total - textPixels) / n

        fun pct(v: Int) = "%.2f".format(100.0 * v / n)
        println(
            "$name: $total pixels differ (${pct(
                total,
            )} %), shape ${total - textPixels} (${pct(total - textPixels)} %), text $textPixels (${pct(textPixels)} %)",
        )
        boxes.entries.sortedByDescending { it.value.n }.take(15).forEach { (k, b) ->
            println("  ${b.n.toString().padStart(5)}  $k  [${b.x0},${b.y0} - ${b.x1},${b.y1}]")
        }
        return 100.0 * total / (w * h)
    }

    /**
     * Every scenario in scenarios.json against its reference; a scenario may differ by at most its
     * limit (what is left is known: fonts, Winamp's own component bucket art; see
     * internal/wasabi-feasibility.md). `-Dscenario=<name>[,<name>...]` runs those.
     */
    @Test
    fun scenarios() {
        assumeTrue(out.isDirectory)
        val only = System.getProperty("scenario")
        val all = Scenario.load(File(out.parentFile, "scenarios.json")).filter { only == null || it.name in only.split(",") }
        val scores = all.associate { it.name to render(it) }
        println("scenarios: " + scores.entries.joinToString { (k, v) -> "$k ${"%.2f".format(v)} %" })
        val limits = all.associate { it.name to it.limit }
        val worse = scores.filter { (k, v) -> v != null && v > limits.getValue(k) }
        assertTrue("more pixels differ than before: $worse (limits ${limits.filterKeys { it in worse }})", worse.isEmpty())
        // shapes (everything but TrueType text) have their own, tighter limit: a wrong asset, offset
        // or corner must not hide under font differences
        val shapeLimits = all.associate { it.name to it.shapeLimit }
        val worseShape = shapeScores.filter { (k, v) -> k in shapeLimits && v > shapeLimits.getValue(k) }
        val shapeLimitsHit = shapeLimits.filterKeys { it in worseShape }
        assertTrue("more shape pixels differ than before: $worseShape (limits $shapeLimitsHit)", worseShape.isEmpty())
    }

    /**
     * The config drawer's slide with "Animate Config Drawer" on, beside Winamp 5.666's (bottom edge
     * of the window in ms after the click, recorded at 60 frames a second in the VM).
     */
    @Test
    fun drawerSlide() {
        assumeTrue(out.isDirectory)
        val res = File(localResource("/CornerAmp_Redux.wal").toURI()).parentFile
        val files = ZipSkinFiles(File(res, "WinampModern566.wal"))
        val player = FakePlayer()
        val rt = SkinRuntime(files, Java2dHost(WithGeneratedImages(files)), player)
        val clock = onTestClock(rt, player)
        rt.storedSettings["Animate Config Drawer"] = "1"
        rt.load()
        settle(rt, 2_000)
        val drawer = checkNotNull(rt.mainLayout?.findObject("player.normal.drawer"))
        val button = checkNotNull(rt.mainLayout?.findObject("drawer.button.open"))
        val cx = button.rect.left + button.rect.width / 2
        val cy = button.rect.top + button.rect.height / 2
        rt.pointerDown(cx, cy)
        rt.pointerUp(cx, cy)
        val start = clock.now
        val ours = StringBuilder()
        var last = -1
        while (clock.now - start < 1_500) {
            clock.step(rt, TestClock.FRAME)
            rt.draw()
            val bottom = drawer.rect.bottom
            if (bottom != last) ours.append("${clock.now - start}:$bottom ")
            last = bottom
        }
        println("drawer slide, ours:   $ours")
        println("drawer slide, Winamp: $WINAMP_DRAWER_SLIDE")
    }

    private companion object {
        const val WINAMP_DRAWER_SLIDE =
            "25:164 184:165 200:168 233:170 265:174 332:182 364:188 431:195 481:203 510:211 559:220 609:229 " +
                "641:237 690:245 740:253 790:260 841:266 891:271 924:275 971:278 1023:280"

        /** The reference VM's work area in physical pixels: 1024x768 at 122 % scaling, less the taskbar. */
        const val VM_SCREEN_W = 1248
        const val VM_SCREEN_H = 853

        /** -Dsysfonts=<dir>: Windows fonts by file name, e.g. the reference VM's (Microsoft's; local only, never committed). */
        val SYS_FONTS: File? = System.getProperty("sysfonts")?.let { File(it) }

        /** The app's stand-ins for Windows fonts a skin names without a file (ModernSkinActivity's STAND_INS). */
        val APP_FONTS = File("../app/src/debug/assets/fonts")
        val STAND_INS = mapOf("tahoma" to "DejaVuSansCondensed")

        /** A command owns a pixel only when it changed it by more than this (0-255 per channel, root mean square). */
        const val OWNER_DELTA = 12.0

        fun colorDistance(
            a: Int,
            b: Int,
        ): Double {
            var sum = 0.0
            for (shift in intArrayOf(24, 16, 8, 0)) {
                val d = ((a shr shift) and 0xff) - ((b shr shift) and 0xff)
                sum += d * d
            }
            return Math.sqrt(sum / 4)
        }
    }
}
