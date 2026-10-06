// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One amount a listener can give, with the name and the price the store shows for it. */
data class Tip(
    val id: String,
    val name: String,
    /** The price as the store words it for this listener: their currency, tax included. */
    val price: String,
    /** The same price in millionths of the currency, which is what the tips are ordered by. */
    val micros: Long,
)

/** What the support page has to offer. */
sealed interface TipShelf {
    /** The store has not answered yet. */
    data object Loading : TipShelf

    /** There is no store on this phone, or it has no tips for this app. */
    data object Closed : TipShelf

    /** The tips, smallest first. */
    data class Open(
        val tips: List<Tip>,
    ) : TipShelf
}

/** What became of the last tip, for the page to say. */
enum class TipNote {
    /** Paid and settled. */
    THANKS,

    /** Started, and the payment has not finished: a bank transfer, or cash at a shop. */
    WAITING,

    /** The store could not take it. */
    FAILED,
}

/** A purchase as the store reports it. */
data class Sale(
    val token: String,
    /** False while the payment has not finished. */
    val paid: Boolean,
)

/** What the store says when its purchase screen closes. */
data class SaleNews(
    /** The purchases it made; empty when the listener backed out. */
    val sales: List<Sale> = emptyList(),
    val failed: Boolean = false,
)

/** The store's side of a tip. [TipJar] decides what to do; this only asks and answers. */
interface Till {
    /** Called when the purchase screen closes, whatever the outcome. */
    var onNews: (SaleNews) -> Unit

    /** Reaches the store; false when this phone has none or it cannot be reached. */
    suspend fun connect(): Boolean

    /** The tips among [ids] that the store sells for this app. */
    suspend fun tips(ids: List<String>): List<Tip>

    /** Every tip bought and not settled yet, or null when the store could not say. */
    suspend fun sales(): List<Sale>?

    /**
     * Tells the store the tip was received, which also lets the same tip be given again. A tip
     * that is not settled within three days is refunded by the store.
     */
    suspend fun settle(sale: Sale): Boolean

    /** Opens the store's purchase screen over [activity]; false when it could not be opened. */
    fun sell(
        activity: Activity,
        tip: Tip,
    ): Boolean
}

/**
 * The tip jar: what can be given, giving it, and thanking for it.
 *
 * A tip unlocks nothing, so all there is to keep is whether one was ever given, and whether the
 * store may still hold one that was paid for and not settled. The second matters because a payment
 * can finish days after it was started, with the app closed: [TipLedger.owed] is set when a tip is
 * started, and while it is set the store is asked again each time the app starts.
 */
class TipJar(
    private val till: Till,
    private val ledger: TipLedger,
    private val scope: CoroutineScope,
) {
    var shelf by mutableStateOf<TipShelf>(TipShelf.Loading)
        private set

    var note by mutableStateOf<TipNote?>(null)
        private set

    /** Whether this listener ever gave a tip. */
    val given: Boolean get() = ledger.given

    /** The tips settled since the app started. The store can list one a moment longer. */
    private val settled = mutableSetOf<String>()

    private var opening: Job? = null

    init {
        till.onNews = { news ->
            scope.launch {
                if (news.failed) note = TipNote.FAILED
                settle(news.sales)
            }
        }
        if (ledger.owed) open()
    }

    /**
     * Reaches the store, settles what is owed and fills the shelf. Called when the support page
     * opens, and at the start of the app while a tip may be waiting.
     */
    fun open() {
        if (opening?.isActive == true) return
        opening =
            scope.launch {
                if (!till.connect()) {
                    if (shelf !is TipShelf.Open) shelf = TipShelf.Closed
                    return@launch
                }
                settle()
                if (shelf !is TipShelf.Open) {
                    val tips = till.tips(IDS).sortedBy { it.micros }
                    shelf = if (tips.isEmpty()) TipShelf.Closed else TipShelf.Open(tips)
                }
            }
    }

    /** Starts a tip: the store's purchase screen opens over [activity]. */
    fun give(
        activity: Activity,
        tip: Tip,
    ) {
        note = null
        // set before the purchase screen opens, because the app can be gone before it closes
        ledger.owed = true
        if (!till.sell(activity, tip)) note = TipNote.FAILED
    }

    /**
     * Settles every paid tip the store holds, and [known] ones it has just reported. A tip whose
     * payment has not finished stays owed.
     */
    private suspend fun settle(known: List<Sale> = emptyList()) {
        val asked = till.sales()
        // the report first: it is newer than what the store lists
        val sales = (known + asked.orEmpty()).distinctBy { it.token }.filterNot { it.token in settled }
        val (paid, waiting) = sales.partition { it.paid }
        val done = paid.filter { till.settle(it) }
        settled += done.map { it.token }
        when {
            done.isNotEmpty() -> {
                ledger.given = true
                note = TipNote.THANKS
            }

            waiting.isNotEmpty() -> {
                note = TipNote.WAITING
            }
        }
        // without the store's own list there may be more than was reported, so it stays owed
        if (asked != null) ledger.owed = done.size < sales.size
    }

    companion object {
        /**
         * The products this app sells as tips. They are made in the Play Console, which is also
         * where each gets its name and price; one that is not there is left off the shelf.
         */
        val IDS = listOf("tip_small", "tip_medium", "tip_large")
    }
}
