package com.android.vending.billing.util;

import android.os.Bundle;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchaseHistoryResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;

import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public abstract class PurchaseActivity extends AppCompatActivity {
    private static final String TAG = "PlayBilling";

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
                onIabSetupSuccess();
                mBillingClient.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP, mPurchaseHistoryResponseListener);
            } else {
                Log.w(TAG, "Billing successfully setup, but received error " + PurchaseActivity.toString(billingResponseCode));
                onIabSetupFailure();
            }
        }

        @Override
        public void onBillingServiceDisconnected() {
            Log.w(TAG, "Billing temporarily disconnected");
            onIabSetupFailure();
        }
    };

    private final PurchaseHistoryResponseListener mPurchaseHistoryResponseListener = new PurchaseHistoryResponseListener() {
        @Override
        public void onPurchaseHistoryResponse(@BillingClient.BillingResponse int billingResponseCode, List<Purchase> purchases) {
            if (billingResponseCode == BillingClient.BillingResponse.OK) {
                // Ask the app for all in app purchases they expect the user can buy.
                List<String> expectedPurchases = getSkus();

                // Ask the play store for the user's purchase history, and notify the app.
                // This way, if the user bought a new device, the app can re-adjust its state.
                for (Purchase purchase : purchases) {
                    onPurchaseSuccess(purchase.getSku(), purchase);
                    expectedPurchases.remove(purchase.getSku());
                }

                // For everything the play store did not have a purchase history for, report it as such.
                // This way, if the purchase was refunded, the app can re-adjust its state.
                for (String sku : expectedPurchases) {
                    onPurchaseFailed(sku);
                }
            } else {
                Log.w(TAG, "Attempted to query purchase history, but received error: " + PurchaseActivity.toString(billingResponseCode));
            }
        }
    };

    // Fires whenever a purchase has been made, with the status of the purchase.
    private final PurchasesUpdatedListener mPurchasesUpdatedListener = new PurchasesUpdatedListener() {
        @Override
        public void onPurchasesUpdated(@BillingClient.BillingResponse int billingResponseCode, List<Purchase> purchases) {
            if (billingResponseCode == BillingClient.BillingResponse.OK) {
                for (Purchase purchase : purchases) {
                    onPurchaseSuccess(purchase.getSku(), purchase);
                }
            } else {
                Log.w(TAG, "Attempted to purchase an item, but received error: " + PurchaseActivity.toString(billingResponseCode));
            }
        }
    };

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
    protected void onIabSetupSuccess() {}

    /**
     * Failed to connect to the Play Store. Disable options to purchase items.
     */
    protected void onIabSetupFailure() {}

    /**
     * An in app purchase has been confirmed.
     */
    protected void onPurchaseSuccess(String sku, Purchase info) {}

    /**
     * An in app purchase was not found.
     */
    protected void onPurchaseFailed(String sku) {}

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBillingClient = BillingClient.newBuilder(this).setListener(mPurchasesUpdatedListener).build();
        mBillingClient.startConnection(mBillingClientStateListener);
    }

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
}
