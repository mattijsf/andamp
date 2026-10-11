// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.state

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Play as the [Till]. A tip is a consumable in-app product, so it can be given more than
 * once.
 *
 * The client is made on the first [connect] and kept: the purchase screen reports to it, and that
 * report can come after the page that asked has gone.
 */
class PlayTill(
    private val context: Context,
) : Till {
    override var onNews: (SaleNews) -> Unit = {}

    /** The store's own records of the tips it listed, which is what its purchase screen takes. */
    private val listed = mutableMapOf<String, ProductDetails>()

    private val client by lazy {
        BillingClient
            .newBuilder(context)
            .setListener { result, purchases -> onNews(newsOf(result, purchases.orEmpty())) }
            // a payment that finishes later is reported as waiting, not refused
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    override suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { waiting ->
            client.startConnection(
                object : BillingClientStateListener {
                    // called again on each reconnection, when nobody is waiting any more
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (waiting.isActive) waiting.resume(result.responseCode == BillingResponseCode.OK)
                    }

                    override fun onBillingServiceDisconnected() = Unit
                },
            )
        }
    }

    override suspend fun tips(ids: List<String>): List<Tip> {
        val asked =
            ids.map { id ->
                QueryProductDetailsParams.Product
                    .newBuilder()
                    .setProductId(id)
                    .setProductType(ProductType.INAPP)
                    .build()
            }
        val answer = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(asked).build())
        if (answer.billingResult.responseCode != BillingResponseCode.OK) return emptyList()
        return answer.productDetailsList.orEmpty().mapNotNull { details ->
            val offer = details.oneTimePurchaseOfferDetails ?: return@mapNotNull null
            listed[details.productId] = details
            Tip(details.productId, details.name, offer.formattedPrice, offer.priceAmountMicros)
        }
    }

    override suspend fun sales(): List<Sale>? {
        val answer = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build())
        if (answer.billingResult.responseCode != BillingResponseCode.OK) return null
        return answer.purchasesList.mapNotNull(::saleOf)
    }

    override suspend fun settle(sale: Sale): Boolean {
        val answer = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(sale.token).build())
        return answer.billingResult.responseCode == BillingResponseCode.OK
    }

    override fun sell(
        activity: Activity,
        tip: Tip,
    ): Boolean {
        val details = listed[tip.id] ?: return false
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        details.oneTimePurchaseOfferDetails
            ?.offerToken
            ?.takeIf { it.isNotEmpty() }
            ?.let(product::setOfferToken)
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product.build())).build()
        return client.launchBillingFlow(activity, flow).responseCode == BillingResponseCode.OK
    }

    private fun newsOf(
        result: BillingResult,
        purchases: List<Purchase>,
    ): SaleNews =
        when (result.responseCode) {
            BillingResponseCode.OK -> SaleNews(purchases.mapNotNull(::saleOf))

            // backed out, or the same tip is still unsettled: the jar asks the store what it holds
            BillingResponseCode.USER_CANCELED, BillingResponseCode.ITEM_ALREADY_OWNED -> SaleNews()

            else -> SaleNews(failed = true)
        }

    /** A purchase as a [Sale], or null for one that is not a tip. */
    private fun saleOf(purchase: Purchase): Sale? {
        if (purchase.products.any { it !in TipJar.IDS }) return null
        return Sale(purchase.purchaseToken, paid = purchase.purchaseState == Purchase.PurchaseState.PURCHASED)
    }
}
