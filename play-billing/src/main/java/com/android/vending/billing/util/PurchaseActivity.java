package com.android.vending.billing.util;

import android.os.Bundle;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;

import java.util.List;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public abstract class PurchaseActivity extends AppCompatActivity {

    private BillingClient mBillingClient;

    /**
     * Return your key from the Dev Console
     * */
    protected abstract String getKey();

    /**
     * Return all the skus you want to verify purchases for
     * */
    protected abstract List<String> getSkus();

    protected void onSkuFound(String sku) {}

    protected void onSkuLost(String sku) {}

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);

        mBillingClient = BillingClient.newBuilder(this).setListener(new PurchasesUpdatedListener() {
            @Override
            public void onPurchasesUpdated(@BillingClient.BillingResponse int responseCode, @Nullable List<Purchase> purchases) {
                String sku = null; // TODO
                Purchase purchase = null; // TODO
                switch (responseCode) {
                    case BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED:
                    case BillingClient.BillingResponse.SERVICE_DISCONNECTED:
                    case BillingClient.BillingResponse.USER_CANCELED:
                    case BillingClient.BillingResponse.SERVICE_UNAVAILABLE:
                    case BillingClient.BillingResponse.BILLING_UNAVAILABLE:
                    case BillingClient.BillingResponse.ITEM_UNAVAILABLE:
                    case BillingClient.BillingResponse.DEVELOPER_ERROR:
                    case BillingClient.BillingResponse.ERROR:
                    case BillingClient.BillingResponse.ITEM_ALREADY_OWNED:
                    case BillingClient.BillingResponse.ITEM_NOT_OWNED:
                        onPurchaseFailed(sku);
                        break;
                    case BillingClient.BillingResponse.OK:
                        onSkuFound(sku);
                        onPurchaseSuccess(sku, purchase);
                        break;
                }
            }
        }).build();
        mBillingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@BillingClient.BillingResponse int billingResponseCode) {
                if (billingResponseCode == BillingClient.BillingResponse.OK) {
                    // The billing client is ready. You can query purchases here.
                }
            }
            @Override
            public void onBillingServiceDisconnected() {
                // Try to restart the connection on the next request to
                // Google Play by calling the startConnection() method.
            }
        });
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

    protected void onPurchaseSuccess(String sku, Purchase info) {}

    protected void onPurchaseFailed(String sku) {}

    public void purchaseItem(String sku) {
        purchaseItem(sku, BillingClient.SkuType.INAPP);
    }

    public void purchaseItem(String sku, @BillingClient.SkuType String skuType) {
        mBillingClient.launchBillingFlow(this, BillingFlowParams.newBuilder()
                .setSku(sku)
                .setType(skuType)
                .build());
    }

    @Override
    protected void onDestroy() {
        mBillingClient.endConnection();
        super.onDestroy();
    }
}
