package com.android.vending.billing.util;

import android.content.Intent;
import android.os.Bundle;

import com.android.vending.billing.util.IabHelper.OnIabPurchaseFinishedListener;
import com.android.vending.billing.util.IabHelper.OnIabSetupFinishedListener;

import java.util.List;

import androidx.appcompat.app.AppCompatActivity;

public abstract class PurchaseActivity extends AppCompatActivity implements OnIabSetupFinishedListener, OnIabPurchaseFinishedListener {
    private IabHelper billingHelper;
    protected boolean iabSetupSuccess = false;

    /**
     * Return your key from the Dev Console
     * */
    protected abstract String getKey();

    /**
     * Return all the skus you want to verify purchases for
     * */
    protected abstract List<String> getSkus();

    @Deprecated
    protected void skuFound(String sku, boolean found) {
        if (found) {
            onSkuFound(sku);
        } else {
            onSkuLost(sku);
        }
    }

    protected void onSkuFound(String sku) {}

    protected void onSkuLost(String sku) {}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        billingHelper = new IabHelper(this, getKey());
        try {
            billingHelper.startSetup(this);
        } catch(NullPointerException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onIabSetupFinished(IabResult result) {
        if (result.isSuccess()) {
            iabSetupSuccess = true;
            onIabSetupSuccess();
        } else {
            onIabSetupFailure();
        }

        // Check to see if the item has already been purchased
        IabHelper.QueryInventoryFinishedListener gotInventoryListener = new IabHelper.QueryInventoryFinishedListener() {
            @Override
            public void onQueryInventoryFinished(IabResult result, Inventory inventory) {
                if(inventory != null) {
                    for (String sku : getSkus()) {
                        skuFound(sku, inventory.getAllOwnedSkus().contains(sku));
                    }
                }
            }
        };
        try {
            billingHelper.queryInventoryAsync(true, getSkus(), gotInventoryListener);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    protected void onIabSetupSuccess() {}

    protected void onIabSetupFailure() {}

    protected void onPurchaseSuccess(IabResult result, Purchase info) {}

    protected void onPurchaseFailed(IabResult result) {}

    public void purchaseItem(String sku) {
        try {
            billingHelper.launchPurchaseFlow(this, sku, 1001, this);
        }
        catch(IllegalStateException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        billingHelper.handleActivityResult(requestCode, resultCode, data);
    }

    /**
     * Security Recommendation: When you receive the purchase response from Google Play, make sure to check the returned data signature, the orderId, and the developerPayload string in the Purchase object to make sure that you are getting the expected values. You should verify that the orderId is a unique value that you have not previously processed, and the developerPayload string matches the token that you sent previously with the purchase request. As a further security precaution, you should perform the verification on your own secure server.
     */
    @Override
    public void onIabPurchaseFinished(IabResult result, Purchase info) {
        if (result.isFailure()) {
            onPurchaseFailed(result);
        } else {
            skuFound(info.getSku(), true);
            onPurchaseSuccess(result, info);
        }
    }

    @Override
    protected void onDestroy() {
        disposeBillingHelper();
        super.onDestroy();
    }

    private void disposeBillingHelper() {
        if(billingHelper != null) {
            try {
                billingHelper.dispose();
            }
            catch(IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        billingHelper = null;
    }
}
