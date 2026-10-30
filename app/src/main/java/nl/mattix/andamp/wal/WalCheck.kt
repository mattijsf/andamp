// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinFiles
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import java.io.File

/**
 * Runs the modern skins in a folder once each, off screen, so a debug build can be tried over adb
 * (`--es wal check` to its adb switch): load, [TICKS] ticks on a stepped clock with a demo track
 * playing, and the windows drawn at 1x into a PNG beside the skin. Each skin's name before it
 * loads, then its load time, the p95 of a tick plus its draw list and the drawing time, or its
 * failure, go to the log under [WalHost.TAG].
 *
 * A run that runs out of time stops between skins, after one skin at least. A file in the folder
 * keeps which skins the run has finished, for the installed build and each skin's size and date, so
 * the next run carries on; a new build, a replaced skin or `fresh` starts over, and a finished run's
 * next check does every skin again.
 *
 * It touches nothing of the player: not the skin library, the settings or the screen. One thread
 * does all of it, so the host's caches meant for the engine thread and for drawing share that thread.
 */
internal object WalCheck {
    /** Ticks per skin, one display frame apart. */
    const val TICKS = 200

    private const val FRAME_MS = 16L

    /** The run's progress: the build it ran on, then one line per skin it finished, with "ok" or "failed". */
    private const val PROGRESS = ".check-progress"

    /**
     * Checks the skins in [dir] that this run has not finished on [build] (all of them when [fresh]
     * or when the last run finished), until [budgetMs] has passed. True when the run is done.
     */
    fun run(
        dir: File,
        cacheDir: File,
        budgetMs: Long,
        fresh: Boolean,
        build: String,
    ): Boolean {
        val skins = dir.listFiles { f -> f.name.endsWith(".wal", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        if (skins.isEmpty()) {
            Log.w(WalHost.TAG, "check: no .wal in ${dir.absolutePath}")
            return true
        }
        val progress = File(dir, PROGRESS)
        val finished = if (fresh) emptyMap() else finishedOn(progress, build)
        if (finished.isEmpty()) progress.writeText("$build\n")
        val due = skins.filter { stamp(it) !in finished }
        val carrying = if (finished.isEmpty()) "" else ", carrying on"
        Log.i(WalHost.TAG, "check: ${due.size} of ${skins.size} skins in ${dir.absolutePath}$carrying")
        val start = System.currentTimeMillis()
        val failed =
            finished
                .filterValues { it == FAILED }
                .keys
                .map { it.substringBefore('\t') }
                .toMutableList()
        for ((i, skin) in due.withIndex()) {
            // each run gets through one skin at least, so a check sent again always carries on
            if (i > 0 && System.currentTimeMillis() - start > budgetMs) {
                Log.i(WalHost.TAG, "check paused: ${due.size - i} skins left, send check again to carry on")
                return false
            }
            // named first, so a skin that never comes back (hangs, or ends the process) shows which one
            Log.i(WalHost.TAG, "check ${skin.name}: started")
            val outcome =
                runCatching { check(skin, skins, cacheDir) }
                    .onSuccess { Log.i(WalHost.TAG, "check ${skin.name}: $it") }
                    .onFailure { e ->
                        failed += skin.name
                        Log.e(WalHost.TAG, "check ${skin.name} failed", e)
                    }
            progress.appendText("${stamp(skin)}\t${if (outcome.isSuccess) OK else FAILED}\n")
        }
        progress.delete()
        val failures = if (failed.isEmpty()) "none failed" else "${failed.size} failed: ${failed.joinToString()}"
        Log.i(WalHost.TAG, "check done: ${skins.size} skins, $failures")
        return true
    }

    /** The skins the run in [progress] finished on [build], by stamp, with "ok" or "failed"; none for another build. */
    private fun finishedOn(
        progress: File,
        build: String,
    ): Map<String, String> {
        val lines = if (progress.exists()) progress.readLines() else return emptyMap()
        if (lines.firstOrNull() != build) return emptyMap()
        return lines.drop(1).filter { it.isNotBlank() }.associate { it.substringBeforeLast('\t') to it.substringAfterLast('\t') }
    }

    /** A skin as the progress file names it: a skin replaced under the same name reads as another. */
    private fun stamp(skin: File) = "${skin.name}\t${skin.length()}\t${skin.lastModified()}"

    private fun pngOf(skin: File) = File(skin.parentFile, skin.nameWithoutExtension + ".png")

    /** One skin: loaded, ticked and drawn; what it took, for the log. */
    private fun check(
        skin: File,
        siblings: List<File>,
        cacheDir: File,
    ): String = WalFiles.open(skin, siblings).use { files -> check(skin, files, cacheDir) }

    /** [skin], read from [files]: loaded, ticked and drawn; what it took, for the log. */
    private fun check(
        skin: File,
        files: SkinFiles,
        cacheDir: File,
    ): String {
        // nobody is there to pick from a skin's menu
        val host = WalHost(files, cacheDir, WalPrepare.windowOpen()).apply { menus.unattended = true }
        var now = 0L
        val player = FakePlayer().apply { clock = { now } }
        // set up as the player sets a skin up, named by its file
        val rt = SkinRuntime(files, host, player, WalPrepare.setup(skin.nameWithoutExtension), clock = { now })
        val loadStart = System.nanoTime()
        rt.load()
        val loadMs = (System.nanoTime() - loadStart) / NANOS_PER_MS
        player.play()
        val frames =
            LongArray(TICKS) {
                now += FRAME_MS
                val t0 = System.nanoTime()
                rt.tick()
                rt.drawWindows()
                System.nanoTime() - t0
            }
        val stack = rt.drawWindows()
        val drawStart = System.nanoTime()
        val drawn = draw(stack, host)
        val drawMs = (System.nanoTime() - drawStart) / NANOS_PER_MS
        drawn?.let { png ->
            pngOf(skin).outputStream().use { png.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val p95 = frames.sorted()[(TICKS * P95).toInt()] / NANOS_PER_US / 1000.0
        return "load $loadMs ms, tick and draw list p95 ${"%.1f".format(java.util.Locale.US, p95)} ms, " +
            "drawn in $drawMs ms, ${stack.size} windows" + if (drawn == null) " (nothing to draw)" else ""
    }

    /** The stack at 1x, from its corner; null when it has no size. */
    private fun draw(
        stack: List<Pair<nl.mattix.andamp.modernskin.skin.Rect, List<nl.mattix.andamp.modernskin.skin.DrawCmd>>>,
        host: WalHost,
    ): ImageBitmap? {
        val w = stack.maxOfOrNull { it.first.right } ?: 0
        val h = stack.maxOfOrNull { it.first.bottom } ?: 0
        if (w <= 0 || h <= 0) return null
        val img = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawWalStack(stack, host, 1f, 0f, layer = null)
        }
        return img
    }

    private const val OK = "ok"
    private const val FAILED = "failed"
    private const val NANOS_PER_MS = 1_000_000L
    private const val NANOS_PER_US = 1_000L
    private const val P95 = 0.95
}
