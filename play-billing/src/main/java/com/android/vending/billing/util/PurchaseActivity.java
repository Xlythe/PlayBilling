package com.android.vending.billing.util;

import android.os.Bundle;

import com.android.billingclient.api.BillingClient.ProductType;
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
     * Return all the Product IDs you want to verify purchases for.
     */
    protected abstract List<String> getProductIds();

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
    public void onPurchaseFound(String productId, Purchase purchase) {}

    /**
     * An in app purchase was not found.
     */
    @Override
    public void onPurchaseLost(String productId) {}

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPurchaseActivityLifecycleCallbacks = new PurchaseActivityLifecycleCallbacks(getKey(), getProductIds());
        mPurchaseActivityLifecycleCallbacks.register(this, this);
    }

    public void purchaseItem(String productId) {
        mPurchaseActivityLifecycleCallbacks.purchaseItem(productId);
    }

    public void purchaseItem(String productId, @ProductType String productType) {
        mPurchaseActivityLifecycleCallbacks.purchaseItem(productId, productType);
    }
}
