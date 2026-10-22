// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.MakiFormatException
import nl.mattix.andamp.modernskin.maki.MakiParser
import nl.mattix.andamp.modernskin.skin.AndampDefaultSet
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.FontDef
import nl.mattix.andamp.modernskin.skin.ImageLimits
import nl.mattix.andamp.modernskin.skin.LayeredSkinFiles
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinHost
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.WithGeneratedImages
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Skins built to break the engine, and the test skins with random damage: loading may refuse a
 * skin, but nothing may crash, run out of memory or hang, and a loaded skin must tick and draw.
 * `-Dfuzz.count=n` multiplies the number of damaged copies (default 1: a quick round).
 */
class FuzzTest {
    private class Host(
        private val files: SkinFiles,
    ) : SkinHost {
        override fun imageSize(file: String): Pair<Int, Int>? =
            files.read(file)?.takeUnless { ImageLimits.tooLarge(it) }?.let { ImageLimits.size(it) }

        override fun textWidth(
            text: String,
            font: FontDef?,
            size: Int,
        ): Int = text.length * maxOf(1, size / 2)

        override fun log(message: String) = Unit

        override fun invalidate() = Unit
    }

    private class MapFiles(
        entries: Map<String, ByteArray>,
    ) : SkinFiles {
        private val m = entries.mapKeys { SkinFiles.normalize(it.key) }

        override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]

        override fun names(): List<String> = m.keys.toList()
    }

    /** Loads, ticks and draws a skin on a clock of its own; the failure, or null when it held. */
    private fun exercise(
        name: String,
        files: SkinFiles,
    ): String? {
        var failure: String? = null
        val worker =
            Thread {
                val all = WithGeneratedImages(LayeredSkinFiles(files, AndampDefaultSet))
                var t = 0L
                val player = FakePlayer().apply { clock = { t } }
                val rt = SkinRuntime(all, Host(all), player).apply { clock = { t } }
                try {
                    rt.load()
                } catch (e: Exception) {
                    // refusing a broken skin is fine
                    return@Thread
                } catch (e: Throwable) {
                    failure = "$name: load: $e"
                    return@Thread
                }
                try {
                    player.play()
                    repeat(TICKS) {
                        rt.tick()
                        rt.drawWindows()
                        t += FRAME_MS
                    }
                } catch (e: Throwable) {
                    failure = "$name: running: $e"
                }
            }
        worker.isDaemon = true
        worker.start()
        worker.join(TIMEOUT_MS)
        if (worker.isAlive) {
            worker.interrupt()
            return "$name: still running after $TIMEOUT_MS ms"
        }
        return failure
    }

    private fun skin(
        playerXml: String,
        extra: Map<String, ByteArray> = emptyMap(),
    ): SkinFiles =
        MapFiles(
            mapOf(
                "skin.xml" to SKIN_XML.toByteArray(),
                "player.xml" to playerXml.toByteArray(),
            ) + extra,
        )

    private fun window(content: String) =
        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">$content</layout></container>"""

    @Test
    fun hostileSkins() {
        val cases = LinkedHashMap<String, SkinFiles>()
        cases["includes itself"] = skin("""<include file="player.xml"/>${window("")}""")
        // ten files of ten includes each, six deep: a million copies if nothing stops it
        val bomb =
            (0..6).associate { level ->
                val body =
                    if (level == 6) {
                        window("<layer x=\"0\" y=\"0\"/>")
                    } else {
                        (1..10).joinToString("") { "<include file=\"l${level + 1}.xml\"/>" }
                    }
                "l$level.xml" to body.toByteArray()
            }
        cases["include bomb"] = skin("""<include file="l0.xml"/>""", bomb)
        cases["deep nesting"] = skin(window("<group x=\"0\" y=\"0\">".repeat(100_000) + "</group>".repeat(100_000)))
        cases["huge sizes"] =
            skin(
                """<elements><bitmap id="b" file="${'$'}solid" w="1000000000" h="1000000000" color="1,2,3"/></elements>
                <container id="main" name="t" default_visible="1"><layout id="normal" w="2000000000" h="2000000000">
                <layer image="b" x="0" y="0"/><gradient x="0" y="0" w="1000000" h="1000000" points="0=1,2,3,255;1=4,5,6,255"/>
                <vis x="0" y="0" w="1000000" h="1000000"/></layout></container>""",
            )
        cases["many objects"] = skin(window("<layer x=\"0\" y=\"0\" w=\"1\" h=\"1\"/>".repeat(300_000)))
        cases["broken xml"] = skin("<<<container id=\"main\"><layout id=\"normal\" w=\"x\"><group")
        cases["huge picture header"] =
            skin(
                """<elements><bitmap id="p" file="p.png"/></elements>${window("<layer image=\"p\" x=\"0\" y=\"0\"/>")}""",
                mapOf("p.png" to pngHeader(60_000, 60_000)),
            )
        val failures = cases.mapNotNull { (name, files) -> exercise(name, files) }
        assertTrue("hostile skins broke the engine: $failures", failures.isEmpty())
    }

    @Test
    fun zipBomb() {
        // 300 MB of zeros that compress to a few hundred kB, claiming to be skin.xml
        val file = File.createTempFile("bomb", ".wal").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("skin.xml"))
            val zeros = ByteArray(1 shl 20)
            repeat(300) { zip.write(zeros) }
            zip.closeEntry()
        }
        val files = ZipSkinFiles(file)
        assertTrue("the bomb's entry was kept", files.read("skin.xml") == null)
    }

    @Test
    fun damagedTestSkins() {
        val dir = File(localResource("/CornerAmp_Redux.wal").toURI()).parentFile
        val skins = dir.listFiles { f -> f.name.endsWith(".wal") }.orEmpty().sortedBy { it.name }
        val random = Random(SEED)
        val failures = ArrayList<String>()
        repeat(SKIN_ROUNDS * count()) { round ->
            val wal = skins[random.nextInt(skins.size)]
            val original = ZipSkinFiles(wal)
            val names = original.names().filter { it.endsWith(".xml") || it.endsWith(".maki") || it.endsWith(".png") }
            if (names.isEmpty()) return@repeat
            val target = names[random.nextInt(names.size)]
            val entries = original.names().associateWith { original.read(it)!! }.toMutableMap()
            entries[target] = damage(entries.getValue(target), random)
            exercise("round $round ${wal.name}:$target", MapFiles(entries))?.let { failures.add(it) }
        }
        assertTrue("damaged skins broke the engine: $failures", failures.isEmpty())
    }

    @Test
    fun damagedScripts() {
        val dir = File(localResource("/CornerAmp_Redux.wal").toURI()).parentFile
        val scripts =
            dir.listFiles { f -> f.name.endsWith(".wal") }.orEmpty().flatMap { wal ->
                val z = ZipSkinFiles(wal)
                z.names().filter { it.endsWith(".maki") }.map { "${wal.name}:$it" to z.read(it)!! }
            }
        val random = Random(SEED)
        val failures = ArrayList<String>()
        repeat(SCRIPT_ROUNDS * count()) {
            val (name, bytes) = scripts[random.nextInt(scripts.size)]
            try {
                MakiParser.parse(damage(bytes, random), name)
            } catch (e: MakiFormatException) {
                // refused cleanly
            } catch (e: Throwable) {
                failures.add("$name: $e")
            }
        }
        assertTrue("damaged scripts broke the parser: ${failures.take(10)}", failures.isEmpty())
    }

    /** A copy with a few kinds of damage: flipped bytes, a cut-off end, a repeated or dropped slice. */
    private fun damage(
        bytes: ByteArray,
        random: Random,
    ): ByteArray {
        if (bytes.isEmpty()) return bytes
        return when (random.nextInt(4)) {
            0 -> {
                bytes.copyOf().also { b ->
                    repeat(1 + random.nextInt(16)) {
                        b[random.nextInt(b.size)] =
                            random.nextInt(256).toByte()
                    }
                }
            }

            1 -> {
                bytes.copyOf(random.nextInt(bytes.size))
            }

            2 -> {
                val at = random.nextInt(bytes.size)
                val len = minOf(bytes.size - at, 1 + random.nextInt(256))
                bytes.copyOfRange(0, at + len) + bytes.copyOfRange(at, bytes.size)
            }

            else -> {
                val at = random.nextInt(bytes.size)
                val len = minOf(bytes.size - at, 1 + random.nextInt(256))
                bytes.copyOfRange(0, at) + bytes.copyOfRange(at + len, bytes.size)
            }
        }
    }

    private fun pngHeader(
        w: Int,
        h: Int,
    ): ByteArray {
        fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            int(13) + "IHDR".toByteArray() + int(w) + int(h) + byteArrayOf(8, 6, 0, 0, 0) + int(0)
    }

    private fun count(): Int = System.getProperty("fuzz.count")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    private companion object {
        const val SEED = 20261022
        const val SKIN_XML = """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>"""
        const val TICKS = 40
        const val FRAME_MS = 50L
        const val TIMEOUT_MS = 30_000L
        const val SKIN_ROUNDS = 40
        const val SCRIPT_ROUNDS = 400
    }
}
