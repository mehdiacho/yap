package com.mehdiacho.yap.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams

/**
 * Play Billing scaffold. Product ids must exist in Play Console before flows return
 * real data; until then [launchPurchase] fails fast with a readable message.
 *
 * REMAINING (tracked): create the four products in Play Console, add a server-side
 * `verifyPlayPurchase` callable (Play Developer API) that grants the entitlement, and
 * call it from [handlePurchase] before acknowledging.
 */
object BillingManager {
    const val PRO_MONTHLY = "pro_monthly"        // subscription
    const val PRO_YEARLY = "pro_yearly"          // subscription
    const val BYOK_STORAGE_MONTHLY = "byok_storage_monthly" // subscription
    const val BYOK_UNLOCK = "byok_unlock"        // one-time (in-app)

    private const val TAG = "YapBilling"
    private var client: BillingClient? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach(::handlePurchase)
        } else {
            Log.w(TAG, "purchase flow ended: ${result.responseCode} ${result.debugMessage}")
        }
    }

    fun init(context: Context) {
        if (client != null) return
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                Log.d(TAG, "billing setup: ${result.responseCode}")
            }
            override fun onBillingServiceDisconnected() {
                Log.d(TAG, "billing disconnected")
            }
        })
    }

    /**
     * Looks up the product and launches the Google Play purchase sheet.
     * Calls [onError] with a readable message when the product isn't available
     * (e.g. products not yet created in Play Console, or a debug build).
     */
    fun launchPurchase(activity: Activity, productId: String, onError: (String) -> Unit) {
        val c = client ?: return onError("Billing isn't ready yet")
        val isSub = productId != BYOK_UNLOCK
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(productId)
                    .setProductType(if (isSub) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP)
                    .build()
            ))
            .build()
        c.queryProductDetailsAsync(params) { result, details ->
            val product: ProductDetails? = details.firstOrNull()
            if (result.responseCode != BillingClient.BillingResponseCode.OK || product == null) {
                onError("Payments aren't live yet — coming soon")
                return@queryProductDetailsAsync
            }
            val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product)
                .apply { if (offerToken != null) setOfferToken(offerToken) }
                .build()
            val flow = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()
            c.launchBillingFlow(activity, flow)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        // TODO(server): verify purchase.purchaseToken via a verifyPlayPurchase callable that
        // grants the entitlement, THEN acknowledge. Acknowledging client-side keeps the
        // purchase alive during development but grants nothing by itself.
        if (!purchase.isAcknowledged) {
            client?.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            ) { result -> Log.d(TAG, "acknowledge: ${result.responseCode}") }
        }
    }
}
