// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.ui.menu

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import nl.mattix.andamp.state.SkinLibrary
import nl.mattix.andamp.state.WinampViewModel
import nl.mattix.andamp.state.zipWith
import nl.mattix.andamp.ui.window.testViewModel
import nl.mattix.andamp.wal.WalBuild
import nl.mattix.andamp.wal.WalFit
import nl.mattix.andamp.wal.WalGate
import nl.mattix.andamp.wal.switchModernSkinsOn
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.io.File

/** A modern skin that loads: one window with a play button, and a color theme per name in [themes], in that order. */
internal fun themedModernSkin(vararg themes: String): ByteArray =
    zipWith(
        "skin.xml" to """<WinampAbstractionLayer version="0.8"><include file="player.xml"/></WinampAbstractionLayer>""",
        "player.xml" to
            themes.joinToString("") { """<gammaset id="$it"/>""" } +
            """<container id="main" name="t" default_visible="1"><layout id="normal" w="200" h="100">""" +
            """<button id="play" action="PLAY" x="10" y="10" w="20" h="20"/></layout></container>""",
    )

/**
 * A view model wearing a modern skin with color themes named [themes], from an empty library with
 * modern skins switched on. With [started] its look is fitted to a phone's box and has loaded, so
 * its themes are known; without, it is made but not started, as after a cold start from the
 * widget's menu. The caller ends it with [letGoOfModernSkin].
 */
internal fun wearThemedModernSkin(
    app: Application,
    vararg themes: String,
    started: Boolean = true,
): WinampViewModel {
    switchModernSkinsOn(app)
    app
        .getSharedPreferences("skins", 0)
        .edit()
        .clear()
        .commit()
    File(app.filesDir, "skins").deleteRecursively()
    val library = SkinLibrary(app)
    val bytes = themedModernSkin(*themes)
    val id = library.idFor(bytes)
    val tmp = File.createTempFile("themed", ".tmp", app.cacheDir).apply { writeBytes(bytes) }
    library.storeModern(tmp, id)
    library.setName(id, "Themed")
    library.choose(id)

    val vm = testViewModel()
    awaitModern("the modern skin is on screen") { vm.skinOps.wal != null }
    if (started) {
        val look = vm.skinOps.wal!!
        look.fit(WalFit(1080, 2000, 3f, sideways = false))
        // a debug build gives a skin this long to load, so a busy machine still sees it on or failed;
        // what the look says then is reported below
        runCatching {
            awaitModern("the skin has loaded or given up", WalBuild.START_BUDGET_MS + LOAD_GRACE_MS) {
                look.layout != null || vm.skinOps.wal !== look
            }
        }
        assertNotNull(
            "the skin has loaded (on screen: ${vm.skinOps.wal === look}, let go: ${look.released}, " +
                "started: ${look.engine != null}, loaded as: '${look.loadedName}')",
            look.layout,
        )
    }
    return vm
}

/** How much longer than its start's time a test waits for a skin to load. */
private const val LOAD_GRACE_MS = 5_000L

/** Lets go of the modern skin [vm] wears, without a save, and of the switch [wearThemedModernSkin] turned on. */
internal fun letGoOfModernSkin(
    app: Application,
    vm: WinampViewModel?,
) {
    vm?.skinOps?.wal?.release(save = false)
    WalGate.clear(app)
    WalGate.forget()
}

/**
 * Runs the main looper, with snapshot writes reaching the view model's collectors, until
 * [condition] holds, for up to [timeoutMs].
 */
internal fun awaitModern(
    what: String,
    timeoutMs: Long = 10_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    do {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    } while (System.currentTimeMillis() < deadline)
    fail(what)
}
