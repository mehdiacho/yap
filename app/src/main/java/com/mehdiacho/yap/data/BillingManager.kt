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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

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

    /** UI-observable lifecycle of the purchase the user just started (not reconciliation). */
    sealed interface PurchasePhase {
        data object Idle : PurchasePhase
        /** Play sheet is up (or about to be) — user hasn't committed yet. */
        data class Initiating(val productId: String) : PurchasePhase
        /** Play took the payment; server-side verification is running. */
        data class Verifying(val productId: String) : PurchasePhase
        data class Confirmed(val productId: String) : PurchasePhase
        data class Cancelled(val productId: String) : PurchasePhase
        data class Failed(val productId: String?, val message: String) : PurchasePhase
    }

    private val _purchasePhase = MutableStateFlow<PurchasePhase>(PurchasePhase.Idle)
    val purchasePhase: StateFlow<PurchasePhase> = _purchasePhase.asStateFlow()
    fun resetPurchasePhase() { _purchasePhase.value = PurchasePhase.Idle }

    /** productId of the purchase the UI is watching, or null when nothing is in flight. */
    private val watchedProductId: String?
        get() = when (val p = _purchasePhase.value) {
            is PurchasePhase.Initiating -> p.productId
            is PurchasePhase.Verifying -> p.productId
            else -> null
        }

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        val watched = watchedProductId
        when {
            result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null ->
                purchases.forEach(::handlePurchase)
            result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "purchase flow cancelled by user")
                if (watched != null) _purchasePhase.value = PurchasePhase.Cancelled(watched)
            }
            else -> {
                Log.w(TAG, "purchase flow ended: ${result.responseCode} ${result.debugMessage}")
                if (watched != null) _purchasePhase.value = PurchasePhase.Failed(
                    watched,
                    when (result.responseCode) {
                        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                            "You already own this — restoring it now."
                        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                        BillingClient.BillingResponseCode.NETWORK_ERROR ->
                            "Google Play couldn't be reached. Check your connection."
                        else -> "Google Play couldn't complete the payment."
                    },
                )
            }
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
        _purchasePhase.value = PurchasePhase.Initiating(productId)
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
                _purchasePhase.value = PurchasePhase.Idle
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
        val productId = purchase.products.firstOrNull() ?: return
        // Only the purchase the UI launched drives the modal; reconciliation stays silent.
        val uiDriven = watchedProductId == productId
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            if (uiDriven && purchase.purchaseState == Purchase.PurchaseState.PENDING) {
                _purchasePhase.value = PurchasePhase.Failed(
                    productId,
                    "Payment is pending — you'll get access as soon as Google Play confirms it.",
                )
            }
            return
        }
        if (uiDriven) _purchasePhase.value = PurchasePhase.Verifying(productId)
        // Server-side verification grants the entitlement; only then acknowledge (Play
        // auto-refunds unacknowledged purchases after ~3 days, so a failed verification
        // self-heals instead of granting nothing for money taken).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                TranscriptionClient.ensureSignedIn()
                val functions = com.google.firebase.functions.FirebaseFunctions.getInstance("africa-south1")
                kotlinx.coroutines.suspendCancellableCoroutine<Any?> { cont ->
                    functions.getHttpsCallable("verifyPlayPurchase")
                        .call(hashMapOf("productId" to productId, "purchaseToken" to purchase.purchaseToken))
                        .addOnSuccessListener { cont.resume(it, null) }
                        .addOnFailureListener { cont.cancel(it) }
                }
                Log.i(TAG, "purchase verified + entitlement granted for $productId")
                if (!purchase.isAcknowledged) {
                    client?.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                    ) { result -> Log.d(TAG, "acknowledge: ${result.responseCode}") }
                }
                if (watchedProductId == productId) _purchasePhase.value = PurchasePhase.Confirmed(productId)
            } catch (e: Exception) {
                // Leave unacknowledged — Play retries via onPurchasesUpdated / query on next start.
                Log.e(TAG, "verifyPlayPurchase failed for $productId: ${e.message}", e)
                if (watchedProductId == productId) _purchasePhase.value = PurchasePhase.Failed(
                    productId,
                    "Payment went through but we couldn't confirm it yet. " +
                        "It will be applied automatically — no money is lost.",
                )
            }
        }
    }

    /** Re-checks any pending unacknowledged purchases (call on app start after init). */
    fun reconcilePending() {
        val c = client ?: return
        listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).forEach { type ->
            c.queryPurchasesAsync(
                com.android.billingclient.api.QueryPurchasesParams.newBuilder().setProductType(type).build()
            ) { _, purchases -> purchases.forEach(::handlePurchase) }
        }
    }
}
