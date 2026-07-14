package com.example.scrollcat

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play Billing for ScrollCat Pro subscriptions.
 *
 * Tiers:
 *  - scrollcat_creator_monthly  ($4.99/mo)
 *  - scrollcat_business_monthly ($9.99/mo)
 *
 * Entitlement state is mirrored into SharedPreferences so isPro() works
 * instantly on launch even before the billing connection is ready, and
 * survives temporary Play outages.
 */
class BillingManager private constructor(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "ScrollCat"
        const val PRODUCT_CREATOR = "scrollcat_creator_monthly"
        const val PRODUCT_BUSINESS = "scrollcat_business_monthly"
        private const val PREFS_NAME = "scrollcat_billing"
        private const val KEY_CREATOR_ACTIVE = "creator_active"
        private const val KEY_BUSINESS_ACTIVE = "business_active"

        @Volatile
        private var instance: BillingManager? = null

        fun getInstance(context: Context): BillingManager {
            return instance ?: synchronized(this) {
                instance ?: BillingManager(context.applicationContext).also { instance = it }
            }
        }

        fun getSubscriptionTier(context: Context): String {
            val billing = getInstance(context)
            return when {
                billing.isBusinessTier() -> "business"
                billing.isPro() -> "creator"
                else -> "free"
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var billingClient: BillingClient? = null
    private val productDetailsCache = mutableMapOf<String, ProductDetails>()

    /** UI hook — SubscriptionActivity refreshes its state through this. */
    var onEntitlementsChanged: (() -> Unit)? = null

    // ── Connection ──

    fun startConnection() {
        if (billingClient?.isReady == true) return
        val client = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        billingClient = client
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing connected")
                    queryProducts()
                    queryPurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing disconnected")
                // Reconnect lazily on next launch/purchase attempt
            }
        })
    }

    // ── Products ──

    fun queryProducts() {
        val client = billingClient ?: return
        val products = listOf(PRODUCT_CREATOR, PRODUCT_BUSINESS).map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()
        client.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetailsList?.forEach { details ->
                    productDetailsCache[details.productId] = details
                }
                Log.i(TAG, "Products loaded: ${productDetailsCache.keys}")
                onEntitlementsChanged?.invoke()
            } else {
                Log.w(TAG, "queryProducts failed: ${billingResult.debugMessage}")
            }
        }
    }

    /** Formatted price like "$4.99", or null while product details load. */
    fun getPrice(productId: String): String? {
        return productDetailsCache[productId]
            ?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()
            ?.formattedPrice
    }

    // ── Purchase flow ──

    /** Returns false when the flow couldn't start (not connected / unknown product). */
    fun launchPurchaseFlow(activity: Activity, productId: String): Boolean {
        val client = billingClient
        if (client == null || !client.isReady) {
            Log.w(TAG, "launchPurchaseFlow: billing not ready")
            startConnection()
            return false
        }
        val details = productDetailsCache[productId]
        if (details == null) {
            Log.w(TAG, "launchPurchaseFlow: no product details for $productId")
            queryProducts()
            return false
        }
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            Log.w(TAG, "launchPurchaseFlow: no offer token for $productId")
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase cancelled by user")
            }
            else -> {
                Log.w(TAG, "Purchase failed: ${billingResult.debugMessage}")
            }
        }
    }

    // ── Restore / query existing purchases ──

    fun queryPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchases failed: ${billingResult.debugMessage}")
                return@queryPurchasesAsync
            }
            // Rebuild entitlement state from Play's source of truth
            var creator = false
            var business = false
            purchases?.forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    if (PRODUCT_CREATOR in purchase.products) creator = true
                    if (PRODUCT_BUSINESS in purchase.products) business = true
                    if (!purchase.isAcknowledged) acknowledgePurchase(purchase)
                }
            }
            prefs.edit()
                .putBoolean(KEY_CREATOR_ACTIVE, creator)
                .putBoolean(KEY_BUSINESS_ACTIVE, business)
                .apply()
            Log.i(TAG, "Entitlements restored — creator=$creator business=$business")
            onEntitlementsChanged?.invoke()
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        if (PRODUCT_CREATOR in purchase.products) {
            prefs.edit().putBoolean(KEY_CREATOR_ACTIVE, true).apply()
        }
        if (PRODUCT_BUSINESS in purchase.products) {
            prefs.edit().putBoolean(KEY_BUSINESS_ACTIVE, true).apply()
        }
        if (!purchase.isAcknowledged) {
            acknowledgePurchase(purchase)
        }
        Log.i(TAG, "Purchase recorded: ${purchase.products}")
        onEntitlementsChanged?.invoke()
    }

    fun acknowledgePurchase(purchase: Purchase) {
        val client = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { billingResult ->
            Log.i(TAG, "Acknowledge result: ${billingResult.responseCode}")
        }
    }

    // ── Entitlement checks (backed by SharedPreferences cache) ──

    fun isPro(): Boolean {
        return prefs.getBoolean(KEY_CREATOR_ACTIVE, false) ||
            prefs.getBoolean(KEY_BUSINESS_ACTIVE, false)
    }

    fun isBusinessTier(): Boolean {
        return prefs.getBoolean(KEY_BUSINESS_ACTIVE, false)
    }
}
