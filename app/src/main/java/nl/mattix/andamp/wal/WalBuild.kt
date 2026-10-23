// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.FakePlayer
import nl.mattix.andamp.modernskin.skin.SkinRuntime
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Loads a modern skin off screen, to learn whether it opens before it is added to the library. The
 * runtime is made and loaded on a separate engine thread, as [WalCheck] makes it, and is let go
 * once it has loaded. A skin menu opened while it loads is answered at once with nothing picked.
 */
internal object WalBuild {
    /** The longest a skin may take to load before it counts as one that does not open. */
    const val BUDGET_MS = 5_000L

    /**
     * Loads [skin] under [name], the name its scripts read; with an empty [name] the skin's
     * `<skininfo>` names it. Returns the name it loaded under, which may be empty.
     *
     * Throws what the load threw, or a [java.util.concurrent.TimeoutException] when it took longer
     * than [budgetMs]. A load that overruns carries on in the background and its result is dropped.
     */
    fun load(
        skin: File,
        name: String,
        cacheDir: File,
        budgetMs: Long = BUDGET_MS,
    ): String =
        within(budgetMs) {
            // the skin alone: a sibling skin it names is read as missing
            val files = WalFiles.open(skin, emptyList())
            val host = WalHost(files, cacheDir).apply { menus.unattended = true }
            SkinRuntime(files, host, FakePlayer())
                .apply {
                    skinName = name
                    haveLibrary = false
                    load()
                }.skinName
        }

    /**
     * Runs [task] on a separate thread and gives its result, waiting at most [budgetMs]. Throws
     * what [task] threw, or a [java.util.concurrent.TimeoutException] when time runs out first.
     */
    fun <T> within(
        budgetMs: Long,
        task: () -> T,
    ): T {
        val future = FutureTask(task)
        Thread(future, "wal-build").apply { isDaemon = true }.start()
        return try {
            future.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            // an Error (a stack overflow in a deep skin) stays wrapped, so callers catch it as an Exception
            throw e.cause as? Exception ?: e
        } finally {
            future.cancel(true)
        }
    }
}
