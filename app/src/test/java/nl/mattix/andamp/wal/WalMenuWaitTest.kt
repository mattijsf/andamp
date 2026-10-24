// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.wal

import nl.mattix.andamp.modernskin.skin.SkinMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The engine thread waits on a skin menu until the listener answers, the menu is dismissed, the
 * wait runs out or the skin goes.
 */
class WalMenuWaitTest {
    private val menu = SkinMenu(listOf(SkinMenu.Command(5, "Five", checked = false, disabled = false)))

    /**
     * Calls [WalMenuWait.choose] on another thread, as the engine does, and returns once the menu
     * shows. [onShow] runs on that thread as the menu opens, before the wait starts.
     */
    private fun WalMenuWait.chooseAside(
        menu: SkinMenu,
        onShow: () -> Unit = {},
    ): CompletableFuture<Int?> {
        val opened = CompletableFuture<Unit>()
        onChange = {
            if (this.menu != null && !opened.isDone) {
                onShow()
                opened.complete(Unit)
            }
        }
        val answer = CompletableFuture.supplyAsync { choose(menu) }
        opened.get(5, TimeUnit.SECONDS)
        return answer
    }

    private fun CompletableFuture<Int?>.answer(): Int? = get(5, TimeUnit.SECONDS)

    @Test(timeout = 10_000)
    fun `the engine hears the command the screen picked, and the menu closes`() {
        val wait = WalMenuWait()
        val answer = wait.chooseAside(menu)
        assertSame(menu, wait.menu)

        wait.answer(menu, 5)

        assertEquals(5, answer.answer())
        assertNull(wait.menu)
    }

    @Test(timeout = 10_000)
    fun `an answer to another menu is ignored, and the menu still waits for its own`() {
        val wait = WalMenuWait()
        val answer = wait.chooseAside(menu)

        wait.answer(SkinMenu(emptyList()), 9)
        assertSame(menu, wait.menu)
        wait.answer(menu, 5)

        assertEquals(5, answer.answer())
    }

    @Test(timeout = 10_000)
    fun `nothing picked answers 0`() {
        val wait = WalMenuWait()
        val answer = wait.chooseAside(menu)

        wait.answer(menu, 0)

        assertEquals(0, answer.answer())
    }

    @Test(timeout = 10_000)
    fun `a menu left open closes without an answer when the wait runs out, and says so in the log`() {
        val logged = mutableListOf<String>()
        val wait = WalMenuWait(timeoutMs = 50) { logged += it }

        assertNull(wait.choose(menu))
        assertNull(wait.menu)
        assertEquals(listOf("menu closed after 50 ms without an answer"), logged)
    }

    @Test(timeout = 10_000)
    fun `a menu answered or dismissed logs nothing`() {
        val logged = mutableListOf<String>()
        val wait = WalMenuWait { logged += it }

        val answered = wait.chooseAside(menu)
        wait.answer(menu, 5)
        assertEquals(5, answered.answer())
        val dismissed = wait.chooseAside(menu)
        wait.dismiss()
        assertNull(dismissed.answer())

        assertEquals(emptyList<String>(), logged)
    }

    @Test(timeout = 10_000)
    fun `dismissing closes the menu without an answer, and the next menu shows as usual`() {
        val wait = WalMenuWait()
        val first = wait.chooseAside(menu)

        wait.dismiss()
        assertNull(first.answer())

        val second = wait.chooseAside(menu)
        wait.answer(menu, 5)
        assertEquals(5, second.answer())
    }

    @Test(timeout = 10_000)
    fun `an answer left from one menu does not reach the next`() {
        val wait = WalMenuWait()
        // a double tap: both answers queue before the engine reads the first
        val first =
            wait.chooseAside(menu) {
                wait.answer(menu, 5)
                wait.answer(menu, 5)
            }
        assertEquals(5, first.answer())

        val second = wait.chooseAside(menu)
        assertSame("the second menu waits for its own answer", menu, wait.menu)
        wait.answer(menu, 0)
        assertEquals(0, second.answer())
    }

    @Test(timeout = 10_000)
    fun `closing frees the engine at once, and later menus answer without showing`() {
        val wait = WalMenuWait()
        val answer = wait.chooseAside(menu)

        wait.close()

        assertNull(answer.answer())
        var shown = false
        wait.onChange = { shown = true }
        assertNull(wait.choose(menu))
        assertEquals(false, shown)
    }

    @Test(timeout = 10_000)
    fun `with nobody to pick every menu answers 0 at once, without showing`() {
        val wait = WalMenuWait().apply { unattended = true }
        var shown = false
        wait.onChange = { shown = true }

        assertEquals(0, wait.choose(menu))
        assertEquals(false, shown)
    }
}
