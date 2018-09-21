package com.android.vending.billing.util;

import android.os.Bundle;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;

import java.util.List;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public abstract class PurchaseActivity extends AppCompatActivity implements PurchaseActivityLifecycleCallbacks.BillingListener {
    private PurchaseActivityLifecycleCallbacks mPurchaseActivityLifecycleCallbacks;

    /**
     * Return your key from the Dev Console.
     */
    protected abstract String getKey();

    /**
     * Return all the skus you want to verify purchases for.
     */
    protected abstract List<String> getSkus();

    /**
     * Successfully connected to the Play Store. You can purchase items now.
     */
    @Override
    public void onBillingAvailable() {}

    /**
     * Failed to connect to the Play Store. Disable options to purchase items.
     */
    @Override
    public void onBillingUnavailable() {}

    /**
     * An in app purchase has been confirmed.
     */
    @Override
    public void onPurchaseFound(String sku, Purchase purchase) {}

    /**
     * An in app purchase was not found.
     */
    @Override
    public void onPurchaseLost(String sku) {}

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPurchaseActivityLifecycleCallbacks = new PurchaseActivityLifecycleCallbacks(getKey(), getSkus());
        mPurchaseActivityLifecycleCallbacks.register(this, this);
    }

    public void purchaseItem(String sku) {
        mPurchaseActivityLifecycleCallbacks.purchaseItem(sku);
    }

    public void purchaseItem(String sku, @BillingClient.SkuType String skuType) {
        mPurchaseActivityLifecycleCallbacks.purchaseItem(sku, skuType);
    }
}
