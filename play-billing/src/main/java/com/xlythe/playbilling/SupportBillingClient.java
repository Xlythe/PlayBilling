package com.xlythe.playbilling;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient.ProductType;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.vending.billing.util.Security;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.SettableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A wrapper around Google's {@link BillingClient} that simplifies the purchase flow. To get an
 * instance, call {@link SupportBillingClient#getInstance(Activity, String)}, passing in the API key
 * you got from the Google Play Console.
 *
 * Listen to purchases (and refunds) by calling {@link SupportBillingClient#registerPurchaseListener(List, PurchaseListener)}.
 * Purchase an item by calling {@link SupportBillingClient#purchaseItem(String)}.
 */
public class SupportBillingClient {
    private static final String TAG = "PlayBilling";

    @Nullable
    private static WeakReference<SupportBillingClient> sBillingClient;

    public static synchronized SupportBillingClient getInstance(Activity activity, String apiKey) {
        SupportBillingClient billingClient = sBillingClient != null ? sBillingClient.get() : null;
        if (billingClient == null) {
            billingClient = new SupportBillingClient(activity, apiKey);
            sBillingClient = new WeakReference<>(billingClient);
        } else {
            billingClient.mActivity = new WeakReference<>(activity);
        }
        return billingClient;
    }

    public interface PurchaseListener {
        default void onPurchaseFound(Purchase purchase) {}
        default void onPurchaseLost(String productId) {}
    }

    private enum ServiceConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    // The context of our caller.
    private volatile WeakReference<Activity> mActivity;
    // The API key of our caller.
    private final String mApiKey;
    // The BillingClient used to talk to the Play Store.
    private volatile BillingClient mBillingClient;
    // Listener that the client may register to be notified about purchases.
    private final Set<PurchaseListener> mPurchaseListeners = new CopyOnWriteArraySet<>();
    // An executor to run tasks on the background.
    private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.DiscardPolicy());

    // The state of our connection to the Play Store.
    private volatile ServiceConnectionState mServiceConnectionState = ServiceConnectionState.DISCONNECTED;
    // A task that's scheduled in the future to disconnect from the Play Store when we're no longer interested.
    private ScheduledFuture<?> mDisconnectTimer;

    private SupportBillingClient(Activity activity, String apiKey) {
        this.mActivity = new WeakReference<>(activity);
        this.mApiKey = apiKey;
        this.mApplication = activity.getApplicationContext();
        mExecutor.setRemoveOnCancelPolicy(true);
        this.mBillingClient = createClient();
    }

    private final Context mApplication;

    private BillingClient createClient() {
        return BillingClient.newBuilder(mApplication)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
                .setListener((billingResult, purchases) -> {
                    if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                        Log.w(TAG, "Attempted to purchase an item, but received error: " + toString(billingResult));
                        return;
                    }

                    if (purchases != null) {
                        for (Purchase purchase : purchases) {
                            onPurchaseFound(purchase);
                        }
                    }
                })
                .build();
    }

    private void onPurchaseFound(Purchase purchase) {
        if (!Security.verifyPurchase(mApiKey, purchase.getOriginalJson(), purchase.getSignature())) {
            Log.w(TAG, "Purchase signature verification failed");
            for (String productId : purchase.getProducts()) {
                onPurchaseLost(productId);
            }
            return;
        }


        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            Log.d(TAG, "Purchase is not paid yet");
            for (String productId : purchase.getProducts()) {
                onPurchaseLost(productId);
            }
            return;
        }

        Log.d(TAG, "Verified purchase");
        if (!purchase.isAcknowledged()) {
            AcknowledgePurchaseParams acknowledgePurchaseParams =
                    AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken())
                            .build();

            mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, (billingResult) -> {
                if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                    Log.w(TAG, "Failed to acknowledge purchase: " + toString(billingResult)
                            + ". The purchase will continue to work but may be refunded in the future.");
                    return;
                }

                Log.d(TAG, "Acknowledged purchase");
            });
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            for (PurchaseListener l : mPurchaseListeners) {
                l.onPurchaseFound(purchase);
            }
        });
    }

    private void onPurchaseLost(String productId) {
        Log.d(TAG, "User has no longer purchased " + productId);
        new Handler(Looper.getMainLooper()).post(() -> {
            for (PurchaseListener l : mPurchaseListeners) {
                l.onPurchaseLost(productId);
            }
        });
    }

    /**
     * Launches a dialog for the user to purchase the given product ID. If successful,
     * {@link PurchaseListener#onPurchaseFound(Purchase)} will be called.
     */
    public Task<BillingResult> purchaseItem(String productId) {
        return purchaseItem(productId, ProductType.INAPP);
    }

    /**
     * Launches a dialog for the user to purchase the given product ID. If successful,
     * {@link PurchaseListener#onPurchaseFound(Purchase)} will be called.
     */
    public Task<BillingResult> purchaseItem(String productId, @ProductType String productType) {
        return purchaseItem(productId, productType, null);
    }

    /** Select a specific offer when a product has more than one eligible price or base plan. */
    public Task<BillingResult> purchaseItem(String productId, @ProductType String productType, @Nullable String offerToken) {
        Callable<ProductDetails> callable = () -> {
            // Connect to the Play Store. This will throw an exception if we fail to connect.
            ensureConnected();

            // Look up the product details.
            SettableFuture<ProductDetails> productDetailsFuture = SettableFuture.create();
            QueryProductDetailsParams productDetailsParams = QueryProductDetailsParams.newBuilder()
                    .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(productType).build()))
                    .build();
            mBillingClient.queryProductDetailsAsync(productDetailsParams, (billingResult, queryProductDetailsResult) -> {
                if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                    Log.w(TAG, "Attempted to purchase an item, but received error: " + toString(billingResult));
                    productDetailsFuture.setException(new ApiException(new Status(billingResult.getResponseCode(), toString(billingResult))));
                    return;
                }

                List<ProductDetails> productDetailsList = queryProductDetailsResult.getProductDetailsList();
                if (productDetailsList == null || productDetailsList.isEmpty()) {
                    Log.w(TAG, "Attempted to purchase an item, but received an empty list");
                    productDetailsFuture.setException(new ApiException(new Status(BillingResponseCode.ITEM_UNAVAILABLE, "No eligible product details")));
                    return;
                }

                productDetailsFuture.set(productDetailsList.get(0));
            });

            // Launch the billing flow for the product.
            return productDetailsFuture.get(30, TimeUnit.SECONDS);
        };
        // Google Play requires the purchase UI to be launched from the main thread.
        return Tasks.call(mExecutor, callable).onSuccessTask(productDetails -> {
            Activity activity = mActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                throw new ApiException(new Status(BillingResponseCode.DEVELOPER_ERROR, "Activity is no longer attached"));
            }
            ProductDetailsParams.Builder details = ProductDetailsParams.newBuilder().setProductDetails(productDetails);
            String token = offerToken;
            if (token == null && ProductType.SUBS.equals(productType)) {
                List<ProductDetails.SubscriptionOfferDetails> offers = productDetails.getSubscriptionOfferDetails();
                if (offers == null || offers.size() != 1) {
                    throw new ApiException(new Status(BillingResponseCode.DEVELOPER_ERROR, "Select a subscription offer token"));
                }
                token = offers.get(0).getOfferToken();
            }
            if (token != null) details.setOfferToken(token);
            BillingResult result = mBillingClient.launchBillingFlow(activity, BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(details.build())).build());
            if (result.getResponseCode() != BillingResponseCode.OK) {
                throw new ApiException(new Status(result.getResponseCode(), toString(result)));
            }
            return Tasks.forResult(result);
        })
                .addOnFailureListener(e -> Log.w(TAG, "Failed to purchase item " + productId));
    }

    /**
     * Registers a listener to validate user purchases. When registered, the listener will
     * immediately be called with the current purchase state (restoring purchases if the user has
     * reinstalled the application). The listener will continue to be called whenever
     * {@link #purchaseItem(String)} results in a successful purchase.
     */
    public Task<BillingResult> registerPurchaseListener(List<String> productIds, PurchaseListener purchaseListener) {
        if (!mPurchaseListeners.add(purchaseListener)) {
            return Tasks.forResult(BillingResult.newBuilder().setResponseCode(BillingResponseCode.DEVELOPER_ERROR).build());
        }

        return queryPurchases(productIds);
    }

    public Task<BillingResult> unregisterPurchaseListener(PurchaseListener purchaseListener) {
        if (mPurchaseListeners.remove(purchaseListener)) {
            return Tasks.forResult(BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build());
        } else {
            return Tasks.forResult(BillingResult.newBuilder().setResponseCode(BillingResponseCode.DEVELOPER_ERROR).build());
        }
    }

    public Task<BillingResult> queryPurchases(List<String> productIds) {
        Callable<BillingResult> callable = () -> {
            // Connect to the Play Store. This will throw an exception if we fail to connect.
            ensureConnected();

            // Look up the purchases in the Play Store.
            List<Purchase> purchases = new ArrayList<>(ownedProducts(ProductType.INAPP));
            BillingResult subscriptions = mBillingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS);
            if (subscriptions.getResponseCode() == BillingResponseCode.OK) {
                purchases.addAll(ownedProducts(ProductType.SUBS));
            } else if (subscriptions.getResponseCode() != BillingResponseCode.FEATURE_NOT_SUPPORTED) {
                throw new ApiException(new Status(subscriptions.getResponseCode(), toString(subscriptions)));
            }
            List<String> expectedPurchases = new ArrayList<>(productIds);
            for (Purchase purchase : purchases) {
                onPurchaseFound(purchase);
                expectedPurchases.removeAll(purchase.getProducts());
            }

            for (String productId : expectedPurchases) {
                Log.d(TAG, "Failed to find " + productId + " in the user's purchases");
                onPurchaseLost(productId);
            }
            return BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build();
        };

        return Tasks.call(mExecutor, callable);
    }

    private List<Purchase> ownedProducts(@ProductType String type) throws Exception {
        SettableFuture<List<Purchase>> future = SettableFuture.create();
        mBillingClient.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build(),
                (result, purchases) -> {
                    if (result.getResponseCode() == BillingResponseCode.OK) future.set(purchases);
                    else future.setException(new ApiException(new Status(result.getResponseCode(), toString(result))));
                });
        return future.get(30, TimeUnit.SECONDS);
    }

    private void resetDisconnectTimer() {
        if (mDisconnectTimer != null) mDisconnectTimer.cancel(false);
        mDisconnectTimer = mExecutor.schedule(this::attemptToDisconnect, 15, TimeUnit.SECONDS);
    }

    // Silently connects to the Play Store, if we're not already connected.
    public Task<BillingResult> verifyBillingSupport() {
        return Tasks.call(mExecutor, this::ensureConnected);
    }

    @WorkerThread
    private BillingResult ensureConnected() throws Exception {
        // A shortcut! We're already connected, so we can no-op here.
        if (mServiceConnectionState == ServiceConnectionState.CONNECTED) {
            // Reset the disconnect timer, since there's user interaction.
            resetDisconnectTimer();
            return BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build();
        }

        // Darn, not connected yet. We'll have to do this the long way.
        mServiceConnectionState = ServiceConnectionState.CONNECTING;

        SettableFuture<BillingResult> future = SettableFuture.create();
        mBillingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                future.set(billingResult);
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.w(TAG, "Billing temporarily disconnected");
                mServiceConnectionState = ServiceConnectionState.DISCONNECTED;
            }
        });

        // If we failed to connect, throw an exception so the Task will return a failure.
        // It's not smart enough to know how to read the internals of BillingResult.
        BillingResult billingResult = future.get(30, TimeUnit.SECONDS);
        if (Objects.requireNonNull(billingResult).getResponseCode() != BillingResponseCode.OK) {
            throw new ApiException(new Status(billingResult.getResponseCode(), "Failed to connect to the Play Store: " + toString(billingResult)));
        }

        mServiceConnectionState = ServiceConnectionState.CONNECTED;

        // If we succeeded in connecting, then we need to start a timer to disconnect in the
        // future. Otherwise, we'll get trapped in a memory leak.
        resetDisconnectTimer();
        return billingResult;
    }

    private void attemptToDisconnect() {
        // Already disconnected! Nothing more to do.
        if (mServiceConnectionState == ServiceConnectionState.DISCONNECTED) {
            return;
        }

        // Unable to disconnect until the user is done with the connection.
        if (!mPurchaseListeners.isEmpty()) {
            resetDisconnectTimer();
            return;
        }

        // Goodbye.
        mBillingClient.endConnection();
        mBillingClient = createClient();
        mServiceConnectionState = ServiceConnectionState.DISCONNECTED;
    }

    public static String toString(BillingResult billingResult) {
        return toString(billingResult.getResponseCode());
    }

    public static String toString(@BillingResponseCode int billingResponseCode) {
        switch (billingResponseCode) {
            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return String.format(Locale.US, "[%d]FEATURE_NOT_SUPPORTED", billingResponseCode);
            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
                return String.format(Locale.US, "[%d]SERVICE_DISCONNECTED", billingResponseCode);
            case BillingClient.BillingResponseCode.SERVICE_TIMEOUT:
                return String.format(Locale.US, "[%d]SERVICE_TIMEOUT", billingResponseCode);
            case BillingClient.BillingResponseCode.USER_CANCELED:
                return String.format(Locale.US, "[%d]USER_CANCELED", billingResponseCode);
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                return String.format(Locale.US, "[%d]SERVICE_UNAVAILABLE", billingResponseCode);
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return String.format(Locale.US, "[%d]BILLING_UNAVAILABLE", billingResponseCode);
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return String.format(Locale.US, "[%d]ITEM_UNAVAILABLE", billingResponseCode);
            case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
                return String.format(Locale.US, "[%d]DEVELOPER_ERROR", billingResponseCode);
            case BillingClient.BillingResponseCode.ERROR:
                return String.format(Locale.US, "[%d]ERROR", billingResponseCode);
            case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                return String.format(Locale.US, "[%d]ITEM_ALREADY_OWNED", billingResponseCode);
            case BillingClient.BillingResponseCode.ITEM_NOT_OWNED:
                return String.format(Locale.US, "[%d]ITEM_NOT_OWNED", billingResponseCode);
            case BillingClient.BillingResponseCode.OK:
                return String.format(Locale.US, "[%d]OK", billingResponseCode);
            default:
                return String.format(Locale.US, "[%d]UNKNOWN", billingResponseCode);
        }
    }
}