// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tip jar against a store that answers at once: what it offers, what it says after a tip, and
 * that a tip paid for is always settled, also when the payment finishes with the app closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TipJarTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val small = Tip("tip_small", "Small tip", "€2.00", 2_000_000)
    private val large = Tip("tip_large", "Large tip", "€10.00", 10_000_000)

    /** One file per test, and the same file for every jar in it: a second jar is the next start. */
    private val prefs = app.getSharedPreferences("tips-${System.nanoTime()}", Context.MODE_PRIVATE)

    /** A store that answers at once, from what the test put in it. */
    private class FakeTill(
        var reachable: Boolean = true,
        var shelf: List<Tip> = emptyList(),
    ) : Till {
        override var onNews: (SaleNews) -> Unit = {}

        /** What the store holds: bought and not settled. */
        val held = mutableListOf<Sale>()
        val settled = mutableListOf<String>()
        val sold = mutableListOf<Tip>()
        var connects = 0
        var sells = true
        var settles = true

        /** Whether the store still lists a tip after settling it, as its cache can. */
        var forgets = true

        override suspend fun connect(): Boolean {
            connects++
            return reachable
        }

        override suspend fun tips(ids: List<String>) = shelf.filter { it.id in ids }

        override suspend fun sales(): List<Sale>? = held.toList()

        override suspend fun settle(sale: Sale): Boolean {
            if (!settles) return false
            settled += sale.token
            if (forgets) held.removeAll { it.token == sale.token }
            return true
        }

        override fun sell(
            activity: Activity,
            tip: Tip,
        ): Boolean {
            if (sells) sold += tip
            return sells
        }

        /** The purchase screen closes with [sale] bought. */
        fun buys(sale: Sale) {
            held += sale
            onNews(SaleNews(listOf(sale)))
        }
    }

    private fun jar(till: FakeTill) = TipJar(till, TipLedger(prefs), scope)

    @Test
    fun `the shelf holds what the store sells, smallest first`() {
        val jar = jar(FakeTill(shelf = listOf(large, small)))
        assertEquals(TipShelf.Loading, jar.shelf)

        jar.open()

        assertEquals(TipShelf.Open(listOf(small, large)), jar.shelf)
    }

    @Test
    fun `without a store the shelf is closed`() {
        val jar = jar(FakeTill(reachable = false, shelf = listOf(small)))

        jar.open()

        assertEquals(TipShelf.Closed, jar.shelf)
    }

    @Test
    fun `a store with no tips for this app leaves the shelf closed`() {
        val jar = jar(FakeTill())

        jar.open()

        assertEquals(TipShelf.Closed, jar.shelf)
    }

    @Test
    fun `nothing reaches for the store until the page opens`() {
        val till = FakeTill()

        jar(till)

        assertEquals(0, till.connects)
    }

    @Test
    fun `a tip paid for is settled and thanked for`() {
        val till = FakeTill(shelf = listOf(small))
        val jar = jar(till)
        jar.open()

        jar.give(activity, small)
        assertEquals(listOf(small), till.sold)
        till.buys(Sale("a", paid = true))

        assertEquals(listOf("a"), till.settled)
        assertEquals(TipNote.THANKS, jar.note)
        assertTrue(jar.given)
        assertFalse("nothing is left to settle", TipLedger(prefs).owed)
    }

    @Test
    fun `backing out says nothing and owes nothing`() {
        val till = FakeTill(shelf = listOf(small))
        val jar = jar(till)
        jar.open()

        jar.give(activity, small)
        till.onNews(SaleNews())

        assertNull(jar.note)
        assertFalse(jar.given)
        assertFalse(TipLedger(prefs).owed)
    }

    @Test
    fun `a tip the store cannot take says so`() {
        val till = FakeTill(shelf = listOf(small))
        val jar = jar(till)
        jar.open()

        jar.give(activity, small)
        till.onNews(SaleNews(failed = true))

        assertEquals(TipNote.FAILED, jar.note)
        assertFalse(jar.given)
    }

    @Test
    fun `a purchase screen that does not open says so`() {
        val till = FakeTill(shelf = listOf(small)).apply { sells = false }
        val jar = jar(till)
        jar.open()

        jar.give(activity, small)

        assertEquals(TipNote.FAILED, jar.note)
    }

    @Test
    fun `a payment that has not finished waits, and is settled at a later start`() {
        val till = FakeTill(shelf = listOf(small))
        val jar = jar(till)
        jar.open()
        jar.give(activity, small)

        till.buys(Sale("a", paid = false))

        assertEquals(TipNote.WAITING, jar.note)
        assertTrue(till.settled.isEmpty())
        assertFalse(jar.given)

        // the next start, with the payment still open: the store is asked, and nothing is settled
        val second = FakeTill().apply { held += Sale("a", paid = false) }
        jar(second)
        assertEquals(1, second.connects)
        assertTrue(second.settled.isEmpty())

        // and the start after the payment finished
        val third = FakeTill().apply { held += Sale("a", paid = true) }
        val later = jar(third)
        assertEquals(listOf("a"), third.settled)
        assertEquals(TipNote.THANKS, later.note)
        assertTrue(later.given)

        // after which the store is left alone again
        val fourth = FakeTill()
        jar(fourth)
        assertEquals(0, fourth.connects)
    }

    @Test
    fun `a tip bought as the app went away is settled at the next start`() {
        val till = FakeTill(shelf = listOf(small))
        val jar = jar(till)
        jar.open()
        jar.give(activity, small)

        // no news arrives: the process is gone. The store holds the tip
        val next = FakeTill().apply { held += Sale("a", paid = true) }
        jar(next)

        assertEquals(listOf("a"), next.settled)
    }

    @Test
    fun `a tip that could not be settled is tried again at the next start`() {
        val till = FakeTill(shelf = listOf(small)).apply { settles = false }
        val jar = jar(till)
        jar.open()
        jar.give(activity, small)

        till.buys(Sale("a", paid = true))

        assertNull("nothing is said until it is settled", jar.note)
        assertTrue(TipLedger(prefs).owed)

        val next = FakeTill().apply { held += Sale("a", paid = true) }
        jar(next)
        assertEquals(listOf("a"), next.settled)
    }

    @Test
    fun `a tip the store lists a moment longer is settled once`() {
        val till = FakeTill(shelf = listOf(small)).apply { forgets = false }
        val jar = jar(till)
        jar.open()
        jar.give(activity, small)

        till.buys(Sale("a", paid = true))
        // the page opens again while the store still lists it
        jar.open()

        assertEquals(listOf("a"), till.settled)
        assertFalse(TipLedger(prefs).owed)
    }

    @Test
    fun `the same tip can be given again`() {
        val till = FakeTill(shelf = listOf(small))
        val jar = jar(till)
        jar.open()

        jar.give(activity, small)
        till.buys(Sale("a", paid = true))
        jar.give(activity, small)
        assertNull("the thanks for the first is gone while the second is under way", jar.note)
        till.buys(Sale("b", paid = true))

        assertEquals(listOf("a", "b"), till.settled)
        assertEquals(TipNote.THANKS, jar.note)
    }
}
