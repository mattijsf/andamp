// SPDX-License-Identifier: GPL-3.0-or-later
package nl.mattix.andamp.modernskin

import nl.mattix.andamp.modernskin.maki.Value
import nl.mattix.andamp.modernskin.skin.DrawCmd
import nl.mattix.andamp.modernskin.skin.DrawPlanner
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import nl.mattix.andamp.modernskin.skin.SkinsFolderFiles
import nl.mattix.andamp.modernskin.skin.WindowPlan
import nl.mattix.andamp.modernskin.skin.WithGeneratedImages
import nl.mattix.andamp.modernskin.skin.ZipSkinFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The planner on the corpus skins (local files, skipped without them), playing on a stepped clock,
 * then stopped, left resting past the live hold and played again: it draws exactly what drawWindows
 * draws, every plan holds its promises, and a renderer keeping windows and runs by version never
 * shows a stale picture. Once a skin has played a while no window is recorded again; the skins that
 * draw the most keep their moving parts apart, and those runs end once their objects rest. Prints
 * what each skin gets kept apart, and what planning costs beside drawing.
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

    /** One skin playing: [PLAY_MS] of play, then Stop, a rest past the live hold, and play again. */
    private fun play(
        res: File,
        name: String,
    ) {
        var t = START
        val (rt, player) = runtime(res, name) { t }
        val (twin, twinPlayer) = runtime(res, name) { t }
        val run = Run(name, rt, twin)
        repeat(SETTLE_FRAMES) {
            t += FRAME
            rt.tick()
            twin.tick()
        }
        player.play()
        twinPlayer.play()
        val playing = t
        var steadyRecords = -1
        var steadyRuns = 0
        while (t < playing + PLAY_MS) {
            t += FRAME
            // the last seconds of play: objects that move turned live long before
            val steady = t >= playing + PLAY_MS - STEADY_MS
            if (steady && steadyRecords < 0) steadyRecords = run.cache.windowRecords
            val plans = run.frame(t) ?: continue
            if (steady) steadyRuns = maxOf(steadyRuns, plans.sumOf { it.runs.size })
        }
        assertEquals(
            "$name: no window recorded again in the last ${STEADY_MS / 1000} s of play",
            steadyRecords,
            run.cache.windowRecords,
        )
        if (name in KEPT_APART) assertTrue("$name keeps moving parts apart while it plays", steadyRuns > 0)
        val playRecords = run.cache.windowRecords
        player.stop()
        twinPlayer.stop()
        val stopped = t
        while (t < stopped + STOP_MS) {
            t += FRAME
            run.frame(t)
        }
        // nothing drawn for longer than the live hold, then a frame, then play again
        t += DrawPlanner.LIVE_HOLD_MS + FRAME
        run.frame(t, force = true)
        player.play()
        twinPlayer.play()
        val again = t
        while (t < again + STOP_MS) {
            t += FRAME
            run.frame(t)
        }
        if (name in KEPT_APART) assertTrue("$name: runs end once their objects rest", run.runsEnded > 0)
        val sizes = rt.windows.map { rt.windowRect(it) }
        val published = run.published
        println(
            "PLAN $name: ${rt.windows.size} windows $sizes, $published frames published, " +
                "runs per frame ${run.runsSeen.toSortedMap()}, window records $playRecords while playing " +
                "(none in the last ${STEADY_MS / 1000} s, with up to $steadyRuns runs) and " +
                "${run.cache.windowRecords - playRecords} from Stop on, " +
                "run records ${run.cache.runRecords}, runs ended ${run.runsEnded}, " +
                "planned ${run.planner.fullPlans} times from the start, " +
                "planning ${run.planner.planNanos / 1000 / published} us " +
                "against drawing ${run.drawNanos / 1000 / published} us a frame",
        )
    }

    /** A skin's runtime planned frame by frame beside a [twin] that draws as drawWindows draws, checked each frame. */
    private inner class Run(
        val name: String,
        val rt: SkinRuntime,
        val twin: SkinRuntime,
    ) {
        val planner = DrawPlanner()
        val cache = PlanCache()
        val runsSeen = HashMap<Int, Int>()
        var published = 0
        var drawNanos = 0L
        var runsEnded = 0
        private var frames = 0
        private var lastRuns = emptySet<Long>()

        /** Ticks both runtimes at [t]; when they changed, or with [force], plans and checks a frame and gives its plans. */
        fun frame(
            t: Long,
            force: Boolean = false,
        ): List<WindowPlan>? {
            val at = frames++
            val changed = rt.tick()
            assertEquals("$name frame $at: both runtimes tick alike", changed, twin.tick())
            if (!changed && !force && at > 0) return null
            published++
            val planned = planner.draw(rt, t)
            val d0 = System.nanoTime()
            val stack = twin.drawWindows()
            drawNanos += System.nanoTime() - d0
            assertEquals("$name frame $at: windows", stack.size, planned.stack.size)
            for (i in stack.indices) {
                val (r, cmds) = stack[i]
                val (pr, pcmds) = planned.stack[i]
                assertEquals("$name frame $at window $i", r, pr)
                assertEquals("$name frame $at window $i: commands", cmds.size, pcmds.size)
                for (k in cmds.indices) assertTrue("$name frame $at window $i command $k", alike(cmds[k], pcmds[k]))
            }
            cache.frame(planned.stack, planned.plans, "$name frame $at")
            val runs = planned.plans.sumOf { it.runs.size }
            runsSeen[runs] = (runsSeen[runs] ?: 0) + 1
            val ids = planned.plans.flatMap { p -> p.runs.map { it.id } }.toSet()
            runsEnded += lastRuns.count { it !in ids }
            lastRuns = ids
            return planned.plans
        }
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

        /** The skins whose moving parts are kept apart while they play: the three that draw the most. */
        val KEPT_APART = setOf("Bento.wal", "BigBento.wal", "MMD3.wal")
        const val START = 1_792_324_800_000L

        /** The phone's size in skin pixels, as a full-screen Bento has it. */
        const val W = 633
        const val H = 1258
        const val FRAME = 16L
        const val SETTLE_FRAMES = 120
        const val PLAY_MS = 40_000L
        const val STEADY_MS = 15_000L
        const val STOP_MS = 2_000L
    }
}
