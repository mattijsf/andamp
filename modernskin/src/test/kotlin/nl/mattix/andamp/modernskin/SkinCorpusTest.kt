// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Test
import java.io.File

/** Loads every .wal in the test resources and prints how far each gets; nothing is asserted. */
class SkinCorpusTest {
    @Test
    fun survey() {
        val dir = File(localResource("/CornerAmp_Redux.wal").toURI()).parentFile
        for (wal in dir.listFiles { f -> f.name.endsWith(".wal") }!!.sortedBy { it.name }) {
            println("==== ${wal.name}")
            val files = ZipSkinFiles(wal)
            val host = TestHost(files)
            val player = FakePlayer()
            val rt = SkinRuntime(files, host, player)
            val t0 = System.currentTimeMillis()
            try {
                rt.load()
            } catch (e: Exception) {
                println("load failed: ${e.stackTraceToString().trimEnd()}")
            }
            println(
                "load ${System.currentTimeMillis() - t0} ms; containers=" +
                    rt.containers.joinToString { "${it.id}[${it.layouts.joinToString { l -> l.id }}]" },
            )
            val main = rt.mainLayout
            println("main=${main?.container?.id}/${main?.id} size=${main?.size} scripts=${rt.scripts.size}")
            player.play()
            rt.tick()
            val cmds =
                runCatching { rt.draw() }.getOrElse { e ->
                    println("draw failed: $e")
                    emptyList()
                }
            println(
                "draw: ${cmds.size} cmds, ${cmds.count {
                    it is DrawCmd.Image
                }} images, ${cmds.count { it is DrawCmd.Text || it is DrawCmd.BitmapText }} texts",
            )
            val grouped = host.log.groupingBy { it.replace(Regex("\\(.*\\)"), "()").take(90) }.eachCount()
            println("log (${host.log.size} lines, ${grouped.size} distinct):")
            grouped.entries
                .sortedByDescending { it.value }
                .take(25)
                .forEach { println("  ${it.value}x ${it.key}") }
            if (main != null && wal.name != "CornerAmp_Redux.wal") println(dump(main, rt, max = 6))
        }
    }
}

/** The object tree of a group, for reading a skin's structure in a test log. */
fun dump(
    o: nl.mattix.andamp.modernskin.skin.GuiObject,
    rt: SkinRuntime,
    depth: Int = 0,
    max: Int = 4,
): String {
    val sb = StringBuilder()
    val img =
        (o as? nl.mattix.andamp.modernskin.skin.Layer)?.image ?: (o as? nl.mattix.andamp.modernskin.skin.Button)?.image
            ?: (o as? nl.mattix.andamp.modernskin.skin.Group)?.background
    val imgNote =
        (img?.let { if (rt.bitmap(it) == null) " img=$it(MISSING)" else " img=$it" } ?: "") +
            (
                (o as? nl.mattix.andamp.modernskin.skin.Text)?.let {
                    " font=${it.font} display=${it.display} text='${it.currentText}'"
                }
                    ?: ""
            )
    sb
        .append(
            "  ".repeat(depth),
        ).append(
            "${o.tag}#${o.id} ${o.rect.left},${o.rect.top} ${o.rect.width}x${o.rect.height}" +
                "${if (!o.visible) " hidden" else ""}$imgNote\n",
        )
    if (o is nl.mattix.andamp.modernskin.skin.Group && depth < max) for (c in o.children) sb.append(dump(c, rt, depth + 1, max))
    return sb.toString()
}
