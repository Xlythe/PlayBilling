package com.android.vending.billing.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.xlythe.playbilling.SupportBillingClient;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class PurchaseActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    // The Google Play Store API key this app is tied to.
    private final String mApiKey;

    // The various in app purchases this app is interested in.
    private final List<String> mSkus;

    // Our client's registered callback where we report billing events.
    @Nullable private BillingListener mBillingListener;

    // The Activity we're tied to. Null if no activity is currently visible.
    @Nullable private Activity mActivity;

    // Allows us to talk to the Play Store app on the device, and query it for in app purchase and subscription
    // information.
    private SupportBillingClient mBillingClient;
    // A callback from the BillingClient informing us of purchase events.
    private final SupportBillingClient.PurchaseListener mPurchaseListener = new SupportBillingClient.PurchaseListener() {
        @Override
        public void onPurchaseFound(Purchase purchase) {
            mBillingListener.onPurchaseFound(purchase.getSku(), purchase);
        }

        @Override
        public void onPurchaseLost(String sku) {
            mBillingListener.onPurchaseLost(sku);
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

        mBillingClient = SupportBillingClient.getInstance(activity, mApiKey);
        mBillingClient.registerPurchaseListener(mSkus, new SupportBillingClient.PurchaseListener() {
            @Override
            public void onPurchaseFound(Purchase purchase) {
                mBillingListener.onPurchaseFound(purchase.getSku(), purchase);
            }

            @Override
            public void onPurchaseLost(String sku) {
                mBillingListener.onPurchaseLost(sku);
            }
        });
        mBillingClient.verifyBillingSupport()
                .addOnSuccessListener(billingResult -> mBillingListener.onBillingAvailable())
                .addOnFailureListener(billingResult -> mBillingListener.onBillingUnavailable());
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

        mBillingClient.unregisterPurchaseListener(new SupportBillingClient.PurchaseListener() {
            @Override
            public void onPurchaseFound(Purchase purchase) {
                mBillingListener.onPurchaseFound(purchase.getSku(), purchase);
            }

            @Override
            public void onPurchaseLost(String sku) {
                mBillingListener.onPurchaseLost(sku);
            }
        });
        mActivity.getApplication().unregisterActivityLifecycleCallbacks(this);
    }

    public void purchaseItem(String sku) {
        mBillingClient.purchaseItem(sku);
    }

    public void purchaseItem(String sku, @BillingClient.SkuType String skuType) {
        mBillingClient.purchaseItem(sku, skuType);
    }

    /**
     * Attempts to query the Play Store for In App Purchases.
     * Uses {@link BillingListener#onPurchaseFound(String, Purchase)} to report purchases.
     */
    public void queryPurchases() {
        mBillingClient.queryPurchases(mSkus);
    }

    public interface BillingListener {
        default void onPurchaseFound(String sku, Purchase purchase) {}
        default void onPurchaseLost(String sku) {}
        default void onBillingAvailable() {}
        default void onBillingUnavailable() {}
    }
}
