package com.android.vending.billing.util;

import android.app.Activity;
import android.app.Application;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchaseHistoryRecord;
import com.android.billingclient.api.PurchaseHistoryResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import org.json.JSONException;

public class PurchaseActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "PlayBilling";

    // The Google Play Store API key this app is tied to.
    private final String mApiKey;

    // The various in app purchases this app is interested in.
    private final List<String> mSkus;

    // The Activity we're tied to.
    @Nullable private Activity mActivity;

    // Where we report callbacks for billing events.
    @Nullable private BillingListener mBillingListener;

    // Allows us to talk to the Play Store app on the device, and query it for in app purchase and subscription
    // information.
    private BillingClient mBillingClient;

    // Fires whenever we connect (or disconnect) from the Play Store. While we're connected, we can
    // query for purchase history or purchase new items.
    private final BillingClientStateListener mBillingClientStateListener = new BillingClientStateListener() {
        @Override
        public void onBillingSetupFinished(BillingResult billingResult) {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Billing successfully setup");
                mBillingListener.onBillingAvailable();
                queryPurchases();
            } else {
                Log.w(TAG, "Billing successfully setup, but received error " + PurchaseActivityLifecycleCallbacks.toString(billingResult.getResponseCode()));
                mBillingListener.onBillingUnavailable();
            }
        }

        @Override
        public void onBillingServiceDisconnected() {
            Log.w(TAG, "Billing temporarily disconnected");
            mBillingListener.onBillingUnavailable();
        }
    };

    private final PurchaseHistoryResponseListener mPurchaseHistoryResponseListener = new PurchaseHistoryResponseListener() {
        @Override
        public void onPurchaseHistoryResponse(BillingResult billingResult, List<PurchaseHistoryRecord> purchases) {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                // Ask the app for all in app purchases they expect the user can buy.
                List<String> expectedPurchases = new ArrayList<>(mSkus);

                // Ask the play store for the user's purchase history, and notify the app.
                // This way, if the user bought a new device, the app can re-adjust its state.
                for (PurchaseHistoryRecord purchaseHistoryRecord : purchases) {
                    Log.d(TAG, "Discovered " + purchaseHistoryRecord.getSku() + " in the user's purchase history");
                    if (Security.verifyPurchase(mApiKey, purchaseHistoryRecord.getOriginalJson(), purchaseHistoryRecord.getSignature())) {
                        Purchase purchase;
                        try {
                            purchase = new Purchase(purchaseHistoryRecord.getOriginalJson(), purchaseHistoryRecord.getSignature());
                        } catch (JSONException e) {
                            continue;
                        }
                        mBillingListener.onPurchaseFound(purchaseHistoryRecord.getSku(), purchase);
                        expectedPurchases.remove(purchaseHistoryRecord.getSku());
                    } else {
                        Log.w(TAG, "Failed to verify the purchase. Ignoring.");
                    }
                }

                // For everything the play store did not have a purchase history for, report it as such.
                // This way, if the purchase was refunded, the app can re-adjust its state.
                for (String sku : expectedPurchases) {
                    Log.d(TAG, "Failed to find " + sku + " in the user's purchase history");
                    mBillingListener.onPurchaseLost(sku);
                }
            } else {
                Log.w(TAG, "Attempted to query purchase history, but received error: " + PurchaseActivityLifecycleCallbacks.toString(billingResult.getResponseCode()));
            }
        }
    };

    // Fires whenever a purchase has been made, with the status of the purchase.
    private final PurchasesUpdatedListener mPurchasesUpdatedListener = new PurchasesUpdatedListener() {
        @Override
        public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                for (Purchase purchase : purchases) {
                    Log.d(TAG, "User purchased " + purchase.getSku());
                    if (Security.verifyPurchase(mApiKey, purchase.getOriginalJson(), purchase.getSignature())) {
                        mBillingListener.onPurchaseFound(purchase.getSku(), purchase);
                    } else {
                        Log.w(TAG, "Failed to verify the purchase. Ignoring.");
                    }
                }
            } else {
                Log.w(TAG, "Attempted to purchase an item, but received error: " + PurchaseActivityLifecycleCallbacks.toString(billingResult.getResponseCode()));
            }
        }
    };

    public PurchaseActivityLifecycleCallbacks(String apiKey, List<String> skus) {
        mApiKey = apiKey;
        mSkus = new ArrayList<>(skus);
    }

    public void register(Activity activity, BillingListener billingListener) {
        mActivity = activity;
        mBillingListener = billingListener;
        activity.getApplication().registerActivityLifecycleCallbacks(this);
        onActivityCreated(mActivity, null);
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        if (mActivity != activity) {
            return;
        }

        mBillingClient = BillingClient.newBuilder(activity).setListener(mPurchasesUpdatedListener).build();
        mBillingClient.startConnection(mBillingClientStateListener);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {}

    @Override
    public void onActivityResumed(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (mActivity != activity) {
            return;
        }

        mBillingClient.endConnection();
        mActivity.getApplication().unregisterActivityLifecycleCallbacks(this);
    }

    public void purchaseItem(String sku) {
        purchaseItem(sku, BillingClient.SkuType.INAPP);
    }

    public void purchaseItem(String sku, @BillingClient.SkuType String skuType) {
        SkuDetailsParams skuDetailsParams = SkuDetailsParams.newBuilder()
                .setSkusList(Collections.singletonList(sku))
                .setType(skuType)
                .build();
        mBillingClient.querySkuDetailsAsync(skuDetailsParams, (billingResult, skuDetailsList) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                mBillingClient.launchBillingFlow(mActivity, BillingFlowParams.newBuilder()
                        .setSkuDetails(skuDetailsList.get(0))
                        .build());
            } else {
                Log.w(TAG, "Attempted to purchase an item, but received error: " + PurchaseActivityLifecycleCallbacks.toString(billingResult.getResponseCode()));
            }
        });
    }

    /**
     * Attempts to query the Play Store for In App Purchases.
     * Uses {@link BillingListener#onPurchaseFound(String, Purchase)} to report purchases.
     */
    public void queryPurchases() {
        Log.d(TAG, "Querying for the user's purchases. Checking cache first.");
        new AsyncTask<Void, Integer, Purchase.PurchasesResult>() {
            @WorkerThread
            @Override
            protected Purchase.PurchasesResult doInBackground(Void... voids) {
                // Query the Play Store's cache on a background thread.
                // Note: It's marked @UiThread, but it does an IPC call which generally recommends
                // background threads.
                return mBillingClient.queryPurchases(BillingClient.SkuType.INAPP);
            }

            @UiThread
            @Override
            protected void onPostExecute(Purchase.PurchasesResult result) {
                Log.d(TAG, "Cache result retrieved: " + PurchaseActivityLifecycleCallbacks.toString(result.getResponseCode()));
                mPurchaseHistoryResponseListener.onPurchaseHistoryResponse(
                        BillingResult.newBuilder().setResponseCode(result.getResponseCode()).build(),
                        toHistoryRecord(result.getPurchasesList()));

                // We failed to find the user's purchase in the cache. Attempt a network call just to make sure.
                if (result.getPurchasesList() == null || result.getPurchasesList().isEmpty()) {
                    Log.d(TAG, "Cache was empty. Attempting to query purchase history.");
                    mBillingClient.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP, mPurchaseHistoryResponseListener);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static List<PurchaseHistoryRecord> toHistoryRecord(List<Purchase> purchases) {
        List<PurchaseHistoryRecord> purchaseHistoryRecords = new ArrayList<>();
        for (Purchase purchase : purchases) {
            try {
                purchaseHistoryRecords.add(new PurchaseHistoryRecord(purchase.getOriginalJson(), purchase.getSignature()));
            } catch (JSONException e) {
                Log.w(TAG, "Failed to convert Purchase " + purchase + " into a PurchaseHistoryRecord");
            }
        }
        return purchaseHistoryRecords;
    }

    private static String toString(@BillingClient.BillingResponseCode int billingResponseCode) {
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

    public interface BillingListener {
        default void onPurchaseFound(String sku, Purchase purchase) {}
        default void onPurchaseLost(String sku) {}
        default void onBillingAvailable() {}
        default void onBillingUnavailable() {}
    }
}
