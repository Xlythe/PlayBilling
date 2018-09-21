package com.android.vending.billing.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchaseHistoryResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;

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
        public void onBillingSetupFinished(@BillingClient.BillingResponse int billingResponseCode) {
            if (billingResponseCode == BillingClient.BillingResponse.OK) {
                Log.d(TAG, "Billing successfully setup");
                mBillingListener.onBillingAvailable();
                mBillingClient.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP, mPurchaseHistoryResponseListener);
            } else {
                Log.w(TAG, "Billing successfully setup, but received error " + PurchaseActivityLifecycleCallbacks.toString(billingResponseCode));
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
        public void onPurchaseHistoryResponse(@BillingClient.BillingResponse int billingResponseCode, List<Purchase> purchases) {
            if (billingResponseCode == BillingClient.BillingResponse.OK) {
                // Ask the app for all in app purchases they expect the user can buy.
                List<String> expectedPurchases = new ArrayList<>(mSkus);

                // Ask the play store for the user's purchase history, and notify the app.
                // This way, if the user bought a new device, the app can re-adjust its state.
                for (Purchase purchase : purchases) {
                    Log.d(TAG, "Discovered " + purchase.getSku() + " in the user's purchase history");
                    if (Security.verifyPurchase(mApiKey, purchase.getOriginalJson(), purchase.getSignature())) {
                        mBillingListener.onPurchaseFound(purchase.getSku(), purchase);
                        expectedPurchases.remove(purchase.getSku());
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
                Log.w(TAG, "Attempted to query purchase history, but received error: " + PurchaseActivityLifecycleCallbacks.toString(billingResponseCode));
            }
        }
    };

    // Fires whenever a purchase has been made, with the status of the purchase.
    private final PurchasesUpdatedListener mPurchasesUpdatedListener = new PurchasesUpdatedListener() {
        @Override
        public void onPurchasesUpdated(@BillingClient.BillingResponse int billingResponseCode, List<Purchase> purchases) {
            if (billingResponseCode == BillingClient.BillingResponse.OK) {
                for (Purchase purchase : purchases) {
                    Log.d(TAG, "User purchased " + purchase.getSku());
                    if (Security.verifyPurchase(mApiKey, purchase.getOriginalJson(), purchase.getSignature())) {
                        mBillingListener.onPurchaseFound(purchase.getSku(), purchase);
                    } else {
                        Log.w(TAG, "Failed to verify the purchase. Ignoring.");
                    }
                }
            } else {
                Log.w(TAG, "Attempted to purchase an item, but received error: " + PurchaseActivityLifecycleCallbacks.toString(billingResponseCode));
            }
        }
    };

    PurchaseActivityLifecycleCallbacks(String apiKey, List<String> skus) {
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
    public void onActivityCreated(Activity activity, @Nullable Bundle savedInstanceState) {
        if (mActivity != activity) {
            return;
        }

        mBillingClient = BillingClient.newBuilder(activity).setListener(mPurchasesUpdatedListener).build();
        mBillingClient.startConnection(mBillingClientStateListener);
    }

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
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
        mBillingClient.launchBillingFlow(mActivity, BillingFlowParams.newBuilder()
                .setSku(sku)
                .setType(skuType)
                .build());
    }

    private static String toString(@BillingClient.BillingResponse int billingResponseCode) {
        switch (billingResponseCode) {
            case BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED:
                return String.format(Locale.US, "[%d]FEATURE_NOT_SUPPORTED", billingResponseCode);
            case BillingClient.BillingResponse.SERVICE_DISCONNECTED:
                return String.format(Locale.US, "[%d]SERVICE_DISCONNECTED", billingResponseCode);
            case BillingClient.BillingResponse.USER_CANCELED:
                return String.format(Locale.US, "[%d]USER_CANCELED", billingResponseCode);
            case BillingClient.BillingResponse.SERVICE_UNAVAILABLE:
                return String.format(Locale.US, "[%d]SERVICE_UNAVAILABLE", billingResponseCode);
            case BillingClient.BillingResponse.BILLING_UNAVAILABLE:
                return String.format(Locale.US, "[%d]BILLING_UNAVAILABLE", billingResponseCode);
            case BillingClient.BillingResponse.ITEM_UNAVAILABLE:
                return String.format(Locale.US, "[%d]ITEM_UNAVAILABLE", billingResponseCode);
            case BillingClient.BillingResponse.DEVELOPER_ERROR:
                return String.format(Locale.US, "[%d]DEVELOPER_ERROR", billingResponseCode);
            case BillingClient.BillingResponse.ERROR:
                return String.format(Locale.US, "[%d]ERROR", billingResponseCode);
            case BillingClient.BillingResponse.ITEM_ALREADY_OWNED:
                return String.format(Locale.US, "[%d]ITEM_ALREADY_OWNED", billingResponseCode);
            case BillingClient.BillingResponse.ITEM_NOT_OWNED:
                return String.format(Locale.US, "[%d]ITEM_NOT_OWNED", billingResponseCode);
            case BillingClient.BillingResponse.OK:
                return String.format(Locale.US, "[%d]OK", billingResponseCode);
            default:
                return String.format(Locale.US, "[%d]UNKNOWN", billingResponseCode);
        }
    }

    public interface BillingListener {
        void onPurchaseFound(String sku, Purchase purchase);
        void onPurchaseLost(String sku);
        void onBillingAvailable();
        void onBillingUnavailable();
    }
}
