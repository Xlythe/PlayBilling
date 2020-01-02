package com.xlythe.playbilling;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.SkuType;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchaseHistoryRecord;
import com.android.billingclient.api.PurchaseHistoryResponseListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.vending.billing.util.Security;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.SettableFuture;

import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
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
        }
        return billingClient;
    }

    public interface PurchaseListener {
        default void onPurchaseFound(Purchase purchase) {}
        default void onPurchaseLost(String sku) {}
    }

    private enum ServiceConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    // The context of our caller.
    private final Activity mActivity;
    // The API key of our caller.
    private final String mApiKey;
    // The BillingClient used to talk to the Play Store.
    private final BillingClient mBillingClient;
    // Listener that the client may register to be notified about purchases.
    private final Set<PurchaseListener> mPurchaseListeners = new ArraySet<>();
    // An executor to run tasks on the background.
    private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1, new ThreadPoolExecutor.DiscardPolicy());

    // The state of our connection to the Play Store.
    private volatile ServiceConnectionState mServiceConnectionState = ServiceConnectionState.DISCONNECTED;
    // A task that's scheduled in the future to disconnect from the Play Store when we're no longer interested.
    private final Runnable mDisconnectTask = this::attemptToDisconnect;

    private SupportBillingClient(Activity activity, String apiKey) {
        this.mActivity = activity;
        this.mApiKey = apiKey;
        this.mBillingClient = BillingClient.newBuilder(activity)
                .enablePendingPurchases()
                .setListener((billingResult, purchases) -> {
                    if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                        Log.w(TAG, "Attempted to purchase an item, but received error: " + toString(billingResult));
                        return;
                    }

                    for (Purchase purchase : purchases) {
                        onPurchaseFound(purchase);
                    }
                })
                .build();
    }

    private void onPurchaseFound(Purchase purchase) {
        if (!Security.verifyPurchase(mApiKey, purchase.getOriginalJson(), purchase.getSignature())) {
            Log.w(TAG, "Failed to verify purchase " + purchase + ". Ignoring.");
            onPurchaseLost(purchase.getSku());
            return;
        }


        if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
            Log.w(TAG, "Purchase " + purchase + " has not been paid for yet. Ignoring.");
            onPurchaseLost(purchase.getSku());
            return;
        }

        Log.d(TAG, "User purchased " + purchase.getSku());
        for (PurchaseListener l : mPurchaseListeners) {
            mActivity.runOnUiThread(() -> l.onPurchaseFound(purchase));
        }
    }

    private void onPurchaseLost(String sku) {
        Log.d(TAG, "User has no longer purchased " + sku);
        for (PurchaseListener l : mPurchaseListeners) {
            mActivity.runOnUiThread(() -> l.onPurchaseLost(sku));
        }
    }

    /**
     * Launches a dialog for the user to purchase the given sku. If successful,
     * {@link PurchaseListener#onPurchaseFound(Purchase)} will be called.
     */
    public Task<BillingResult> purchaseItem(String sku) {
        return purchaseItem(sku, SkuType.INAPP);
    }

    /**
     * Launches a dialog for the user to purchase the given sku. If successful,
     * {@link PurchaseListener#onPurchaseFound(Purchase)} will be called.
     */
    public Task<BillingResult> purchaseItem(String sku, @SkuType String skuType) {
        Callable<BillingResult> callable = () -> {
            // Connect to the Play Store. This will throw an exception if we fail to connect.
            Tasks.await(verifyBillingSupport());

            // Look up the sku details.
            SettableFuture<SkuDetails> skuDetailsFuture = SettableFuture.create();
            SkuDetailsParams skuDetailsParams = SkuDetailsParams.newBuilder()
                    .setSkusList(Collections.singletonList(sku))
                    .setType(skuType)
                    .build();
            mBillingClient.querySkuDetailsAsync(skuDetailsParams, (billingResult, skuDetailsList) -> {
                if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                    Log.w(TAG, "Attempted to purchase an item, but received error: " + toString(billingResult));
                    skuDetailsFuture.setException(new Exception());
                    return;
                }

                skuDetailsFuture.set(skuDetailsList.get(0));
            });

            // Launch the billing flow for the sku.
            SkuDetails skuDetails = skuDetailsFuture.get();
            BillingResult billingResult = mBillingClient.launchBillingFlow(mActivity, BillingFlowParams.newBuilder()
                    .setSkuDetails(skuDetails)
                    .build());
            if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                throw new ApiException(new Status(billingResult.getResponseCode(), "Failed to purchase an item from the Play Store: " + toString(billingResult)));
            }
            return billingResult;
        };

        return Tasks.call(mExecutor, callable);
    }

    /**
     * Registers a listener to validate user purchases. When registered, the listener will
     * immediately be called with the current purchase state (restoring purchases if the user has
     * reinstalled the application). The listener will continue to be called whenever
     * {@link #purchaseItem(String)} results in a successful purchase.
     */
    public Task<BillingResult> registerPurchaseListener(List<String> skus, PurchaseListener purchaseListener) {
        if (mPurchaseListeners.contains(purchaseListener)) {
            return Tasks.forResult(BillingResult.newBuilder().setResponseCode(BillingResponseCode.DEVELOPER_ERROR).build());
        }

        mPurchaseListeners.add(purchaseListener);
        return queryPurchases(skus);
    }

    public Task<BillingResult> unregisterPurchaseListener(PurchaseListener purchaseListener) {
        mPurchaseListeners.remove(purchaseListener);
        return Tasks.forException(new Exception("Unsupported"));
    }

    public Task<BillingResult> queryPurchases(List<String> skus) {
        Callable<BillingResult> callable = () -> {
            // Connect to the Play Store. This will throw an exception if we fail to connect.
            Tasks.await(verifyBillingSupport());

            // Look up the purchases in the Play Store's on-device cache.
            Purchase.PurchasesResult purchasesResult = mBillingClient.queryPurchases(SkuType.INAPP);
            if (purchasesResult.getPurchasesList() != null && !purchasesResult.getPurchasesList().isEmpty()) {
                // We successfully found purchases in the cache. We can report these right away.
                for (Purchase purchase : purchasesResult.getPurchasesList()) {
                    onPurchaseFound(purchase);
                }
                return BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build();
            }

            // There was nothing in the Play Store's cache, but we can still make a live network query.
            SettableFuture<List<PurchaseHistoryRecord>> future = SettableFuture.create();
            PurchaseHistoryResponseListener purchaseHistoryResponseListener = (billingResult, purchaseHistoryRecords) -> {
                if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Attempted to query purchase history, but received error: " + toString(billingResult));
                    future.setException(new Exception());
                    return;
                }

                future.set(purchaseHistoryRecords);
            };
            mBillingClient.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP, purchaseHistoryResponseListener);

            // For every record that's found, report onPurchaseFound. For each sku that we did
            // not find, report onPurchaseLost.
            List<PurchaseHistoryRecord> purchaseHistoryRecords = future.get();
            List<String> expectedPurchases = new ArrayList<>(skus);
            for (PurchaseHistoryRecord purchaseHistoryRecord : purchaseHistoryRecords) {
                Log.d(TAG, "Discovered " + purchaseHistoryRecord.getSku() + " in the user's purchase history");
                Purchase purchase;
                try {
                    purchase = new Purchase(purchaseHistoryRecord.getOriginalJson(), purchaseHistoryRecord.getSignature());
                } catch (JSONException e) {
                    continue;
                }
                onPurchaseFound(purchase);
                expectedPurchases.remove(purchaseHistoryRecord.getSku());
            }

            // For everything the play store did not have a purchase history for, report it as such.
            // This way, if the purchase was refunded, the app can re-adjust its state.
            for (String sku : expectedPurchases) {
                Log.d(TAG, "Failed to find " + sku + " in the user's purchase history");
                onPurchaseLost(sku);
            }
            return BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build();
        };

        return Tasks.call(mExecutor, callable);
    }

    // Silently connects to the Play Store, if we're not already connected.
    public Task<BillingResult> verifyBillingSupport() {
        // A shortcut! We're already connected, so we can no-op here.
        if (mServiceConnectionState == ServiceConnectionState.CONNECTED) {
            // Reset the disconnect timer, since there's user interaction.
            mExecutor.remove(mDisconnectTask);
            mExecutor.schedule(mDisconnectTask, 15, TimeUnit.SECONDS);
            return Tasks.forResult(BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build());
        }

        // Darn, not connected yet. We'll have to do this the long way.
        mServiceConnectionState = ServiceConnectionState.CONNECTING;
        Callable<BillingResult> callable = () -> {
            SettableFuture<BillingResult> future = SettableFuture.create();
            mBillingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult billingResult) {
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
            BillingResult billingResult = future.get();
            if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                throw new ApiException(new Status(billingResult.getResponseCode(), "Failed to connect to the Play Store: " + toString(billingResult)));
            }

            // If we succeeded in connecting, then we need to start a timer to disconnect in the
            // future. Otherwise, we'll get trapped in a memory leak.
            mExecutor.remove(mDisconnectTask);
            mExecutor.schedule(mDisconnectTask, 15, TimeUnit.SECONDS);
            return billingResult;
        };

        return Tasks.call(mExecutor, callable);
    }

    private void attemptToDisconnect() {
        // Already disconnected! Nothing more to do.
        if (mServiceConnectionState == ServiceConnectionState.DISCONNECTED) {
            return;
        }

        // Unable to disconnect until the user is done with the connection.
        if (!mPurchaseListeners.isEmpty()) {
            mExecutor.remove(mDisconnectTask);
            mExecutor.schedule(mDisconnectTask, 15, TimeUnit.SECONDS);
            return;
        }

        // Goodbye.
        mBillingClient.endConnection();
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