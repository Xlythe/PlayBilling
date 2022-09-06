package com.android.vending.billing.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.android.billingclient.api.BillingClient.ProductType;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.google.android.gms.tasks.Task;
import com.xlythe.playbilling.SupportBillingClient;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class PurchaseActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
    // The Google Play Store API key this app is tied to.
    private final String mApiKey;

    // The various in app purchases this app is interested in.
    private final List<String> mProductIds;

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
            if (mBillingListener == null) {
                return;
            }

            for (String productId : purchase.getProducts()) {
                mBillingListener.onPurchaseFound(productId, purchase);
            }
        }

        @Override
        public void onPurchaseLost(String productId) {
            if (mBillingListener == null) {
                return;
            }

            mBillingListener.onPurchaseLost(productId);
        }
    };

    public PurchaseActivityLifecycleCallbacks(String apiKey, List<String> productIds) {
        mApiKey = apiKey;
        mProductIds = new ArrayList<>(productIds);
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
        mBillingClient.registerPurchaseListener(mProductIds, mPurchaseListener);
        mBillingClient.verifyBillingSupport()
                .addOnSuccessListener(billingResult -> {
                    if (mBillingListener == null) {
                        return;
                    }
                    mBillingListener.onBillingAvailable();
                })
                .addOnFailureListener(billingResult -> {
                    if (mBillingListener == null) {
                        return;
                    }
                    mBillingListener.onBillingUnavailable();
                });
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

        mBillingClient.unregisterPurchaseListener(mPurchaseListener);
        mActivity.getApplication().unregisterActivityLifecycleCallbacks(this);
    }

    public Task<BillingResult> purchaseItem(String productId) {
        return mBillingClient.purchaseItem(productId);
    }

    public Task<BillingResult> purchaseItem(String productId, @ProductType String productType) {
        return mBillingClient.purchaseItem(productId, productType);
    }

    /**
     * Attempts to query the Play Store for In App Purchases.
     * Uses {@link BillingListener#onPurchaseFound(String, Purchase)} to report purchases.
     */
    public Task<BillingResult> queryPurchases() {
        return mBillingClient.queryPurchases(mProductIds);
    }

    public interface BillingListener {
        default void onPurchaseFound(String productId, Purchase purchase) {}
        default void onPurchaseLost(String productId) {}
        default void onBillingAvailable() {}
        default void onBillingUnavailable() {}
    }
}
