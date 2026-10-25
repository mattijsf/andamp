// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.DrawPlanner
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SkinsFolderFiles
import nl.mattix.andamp.modernskin.skin.WithGeneratedImages
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The planner on the corpus skins (local files, skipped without them), playing for 300 frames on a
 * stepped clock: it draws exactly what drawWindows draws, every plan holds its promises, and a
 * renderer keeping windows and runs by version never shows a stale picture. Prints what each skin
 * gets kept apart, and what planning costs beside drawing.
 */
class DrawPlannerCorpusTest {
    @Test
    fun `the corpus skins keep the plan's promises while playing`() {
        val res = File(localResource("/CornerAmp_Redux.wal").toURI()).parentFile
        for (name in SKINS) play(res, name)
    }

    private fun files(
        res: File,
        name: String,
    ): SkinFiles {
        val siblings =
            res
                .listFiles { f -> f.name.endsWith(".wal") }
                .orEmpty()
                .associate { f -> f.nameWithoutExtension.replace(" ", "").lowercase() to ZipSkinFiles(f) }
        return WithGeneratedImages(SkinsFolderFiles(ZipSkinFiles(File(res, name)), siblings))
    }

    /** A runtime on [name] at the phone's size, its player on the stepped clock [now]. */
    private fun runtime(
        res: File,
        name: String,
        now: () -> Long,
    ): Pair<SkinRuntime, FakePlayer> {
        val files = files(res, name)
        val player =
            FakePlayer().apply {
                clock = now
                lengthMs = 600_000
                title = "Andamp - a title long enough to scroll in every ticker"
            }
        val rt = SkinRuntime(files, TestHost(files), player).apply { clock = now }
        rt.load()
        // a window that resizes both ways fills the phone, as the app fits Bento
        if (name.contains(
                "Bento",
            )
        ) {
            rt.mainLayout?.makiCall("resize", listOf(Value.Int(0), Value.Int(0), Value.Int(W), Value.Int(H)))
        }
        rt.viewSize = W to H
        return rt to player
    }

    private fun play(
        res: File,
        name: String,
    ) {
        var t = START
        val (rt, player) = runtime(res, name) { t }
        val (twin, twinPlayer) = runtime(res, name) { t }
        val planner = DrawPlanner()
        val cache = PlanCache()
        repeat(SETTLE_FRAMES) {
            t += FRAME
            rt.tick()
            twin.tick()
        }
        player.play()
        twinPlayer.play()
        var published = 0
        var drawNanos = 0L
        val runsSeen = HashMap<Int, Int>()
        var oneVersion = 0
        repeat(FRAMES) { frame ->
            t += FRAME
            val changed = rt.tick()
            assertEquals("$name frame $frame: both runtimes tick alike", changed, twin.tick())
            if (!changed && frame > 0) return@repeat
            published++
            val planned = planner.draw(rt, t)
            val d0 = System.nanoTime()
            val stack = twin.drawWindows()
            drawNanos += System.nanoTime() - d0
            assertEquals("$name frame $frame: windows", stack.size, planned.stack.size)
            for (i in stack.indices) {
                val (r, cmds) = stack[i]
                val (pr, pcmds) = planned.stack[i]
                assertEquals("$name frame $frame window $i", r, pr)
                assertEquals("$name frame $frame window $i: commands", cmds.size, pcmds.size)
                for (k in cmds.indices) assertTrue("$name frame $frame window $i command $k", alike(cmds[k], pcmds[k]))
            }
            cache.frame(planned.stack, planned.plans, "$name frame $frame")
            val runs = planned.plans.sumOf { it.runs.size }
            runsSeen[runs] = (runsSeen[runs] ?: 0) + 1
            if (frame > 0) oneVersion++
        }
        val sizes = rt.windows.map { rt.windowRect(it) }
        println(
            "PLAN $name: ${rt.windows.size} windows $sizes, $published frames published, " +
                "runs per frame ${runsSeen.toSortedMap()}, window records ${cache.windowRecords}, " +
                "run records ${cache.runRecords}, planned ${planner.fullPlans} times from the start, " +
                "planning ${planner.planNanos / 1000 / published} us against drawing ${drawNanos / 1000 / published} us a frame",
        )
        assertTrue("$name drew frames", published > 0 && oneVersion >= 0)
    }

    /** The same drawing from two runtimes: a window's shape is another object in each, so it is compared by what it holds. */
    private fun alike(
        a: DrawCmd,
        b: DrawCmd,
    ): Boolean {
        if (a !is DrawCmd.WindowStart || b !is DrawCmd.WindowStart) return a.drawsSameAs(b)
        val pa = a.region.pics()
        val pb = b.region.pics()
        return a.region.bounds() == b.region.bounds() && pa.size == pb.size && pa.indices.all { pa[it].drawsSameAs(pb[it]) }
    }

    private companion object {
        val SKINS =
            listOf(
                "WinampModern566.wal",
                "Bento.wal",
                "BigBento.wal",
                "CornerAmp_Redux.wal",
                "MMD3.wal",
                "DeClassified.wal",
                "Winamp3x.wal",
                "Winamp2000SP4.wal",
            )
        const val START = 1_792_324_800_000L

        /** The phone's size in skin pixels, as a full-screen Bento has it. */
        const val W = 633
        const val H = 1258
        const val FRAME = 16L
        const val FRAMES = 300
        const val SETTLE_FRAMES = 120
    }
}
