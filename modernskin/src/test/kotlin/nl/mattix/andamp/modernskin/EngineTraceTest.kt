// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.fixtures.EngineTrace
import nl.mattix.andamp.modernskin.fixtures.TraceScenario
import nl.mattix.andamp.modernskin.skin.LayeredSkinFiles
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinsFolderFiles
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The engine's trace: the same run gives the same text, a change in what the skin shows changes
 * it, and every reference scenario of the local corpus records cleanly. The corpus traces land in
 * `build/traces`, where a second engine's are diffed against them.
 */
class EngineTraceTest {
    /** A main window with the song's title and a play button, and nothing a file would draw. */
    private val small =
        object : SkinFiles {
            private val m =
                mapOf(
                    "skin.xml" to
                        """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
                    "player.xml" to
                        """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
                        """<text id="title" display="songtitle" x="10" y="10" w="180" h="20" color="255,255,255"/>""" +
                        """<text id="time" display="time" x="10" y="40" w="80" h="20" color="0,255,0"/>""" +
                        """<button id="play" action="PLAY" x="100" y="40" w="40" h="20"/>""" +
                        """</layout></container>""",
                ).mapKeys { SkinFiles.normalize(it.key) }

            override fun read(path: String): ByteArray? = m[SkinFiles.normalize(path)]?.toByteArray()

            override fun names(): List<String> = m.keys.toList()
        }

    private fun scenario(
        title: String = "Andamp",
        taps: List<String> = emptyList(),
    ) = TraceScenario.winampReference("small", small, "Small", listOf(title), taps = taps)

    @Test
    fun `the same run gives the same trace, and it says what it did`() {
        val trace = EngineTrace.record(scenario())
        assertEquals(trace, EngineTrace.record(scenario()))
        val lines = trace.trimEnd().lines()
        assertEquals("trace v1", lines[0])
        assertEquals("skin Small", lines[1])
        assertEquals("song Andamp", lines[2])
        assertTrue(lines.contains("step load"))
        assertTrue(lines.contains("frame t=0"))
        assertTrue(lines.any { it.startsWith("window ") && it.contains(" main/normal ") })
        assertTrue(lines.any { it.startsWith("text \"Andamp\"") })
        assertTrue(lines.contains("step play"))
        assertTrue("the time display changes while the song plays", lines.count { it.startsWith("frame t=") } > 2)
        assertTrue(lines.last().startsWith("end t="))
    }

    @Test
    fun `what the skin shows is in the trace, and a tap too`() {
        val plain = EngineTrace.record(scenario())
        val other = EngineTrace.record(scenario(title = "Second Song"))
        assertNotEquals(plain, other)
        assertTrue(other.lines().any { it.startsWith("text \"Second Song\"") })

        val tapped = EngineTrace.record(scenario(taps = listOf("play")))
        assertTrue(tapped.lines().contains("tap play 120,50"))
        assertTrue(EngineTrace.record(scenario(taps = listOf("nothing"))).lines().contains("tap nothing missing"))
    }

    /**
     * Every scenario of the reference set (`~/Development/winamp-vm/scenarios.json`, with the local
     * skins) recorded twice, the two the same, into `build/traces/<name>.trace`. Skipped without
     * the local set.
     */
    @Test
    fun corpus() {
        val res = File(localResource("/CornerAmp_Redux.wal").toURI()).parentFile
        val vm = File(System.getProperty("user.home"), "Development/winamp-vm")
        val scenarios = File(vm, "scenarios.json")
        assumeTrue("$scenarios is the local reference set", scenarios.exists())
        val out = File("build/traces").apply { mkdirs() }
        val arr = JSONArray(scenarios.readText())
        var frames = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            val wal = File(res, o.getString("wal"))
            if (!wal.exists()) {
                println("$name: no ${wal.name}")
                continue
            }

            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }

            fun pair(key: String) = o.optJSONArray(key)?.takeIf { it.length() == 2 }?.let { it.getInt(0) to it.getInt(1) }
            val sc =
                TraceScenario.winampReference(
                    name,
                    withDefaults(withSiblings(ZipSkinFiles(wal), res), vm),
                    o.getString("skin").removeSuffix(".wal"),
                    strings("playlist") ?: listOf("Andamp"),
                    theme = o.optString("theme", null).takeIf { !o.isNull("theme") },
                    view = pair("view"),
                    show = strings("show").orEmpty(),
                    mainSize = pair("mainsize"),
                    taps = strings("taps").orEmpty(),
                    haveLibrary = !o.optBoolean("noLibrary", false),
                )
            val trace = EngineTrace.record(sc)
            assertEquals("$name records the same twice", trace, EngineTrace.record(sc))
            File(out, "$name.trace").writeText(trace)
            val n = trace.lines().count { it.startsWith("frame t=") }
            frames += n
            println("$name: ${trace.lines().size} lines, $n frames")
        }
        assertTrue(frames > 0)
    }

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

    /** Winamp's default element set behind a skin, as the reference test layers it; without the zip, the skin alone. */
    private fun withDefaults(
        skin: SkinFiles,
        vm: File,
    ): SkinFiles {
        val zip = File(vm, "wasabi-defaults.zip")
        return if (zip.exists()) LayeredSkinFiles(skin, ZipSkinFiles(zip)) else skin
    }
}
