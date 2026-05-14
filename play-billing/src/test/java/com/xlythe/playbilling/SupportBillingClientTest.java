package com.xlythe.playbilling;

import android.app.Activity;
import android.os.Looper;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesResponseListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.vending.billing.util.Security;
import com.google.android.gms.tasks.Task;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class SupportBillingClientTest {

    private static class SameThreadScheduledExecutor extends ScheduledThreadPoolExecutor {
        public SameThreadScheduledExecutor() {
            super(1);
        }
        @Override
        public void execute(Runnable command) {
            command.run();
        }
        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return null;
        }
        @Override
        public boolean remove(Runnable task) {
            return true;
        }
    }

    @Mock
    private BillingClient mMockBillingClient;

    private Activity mActivity;
    private SupportBillingClient mSupportBillingClient;
    private AutoCloseable mMockitoCloseable;

    @Before
    public void setUp() throws Exception {
        mMockitoCloseable = MockitoAnnotations.openMocks(this);
        mActivity = Robolectric.buildActivity(Activity.class).create().get();

        resetSingleton();

        mSupportBillingClient = SupportBillingClient.getInstance(mActivity, "test_api_key");
        injectMockBillingClient(mSupportBillingClient, mMockBillingClient);
        injectSameThreadExecutor(mSupportBillingClient);

        // Mock startConnection to immediately succeed
        doAnswer(invocation -> {
            BillingClientStateListener listener = invocation.getArgument(0);
            listener.onBillingSetupFinished(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build());
            return null;
        }).when(mMockBillingClient).startConnection(any(BillingClientStateListener.class));

        // Mock queryPurchasesAsync to return empty by default
        doAnswer(invocation -> {
            PurchasesResponseListener listener = invocation.getArgument(1);
            listener.onQueryPurchasesResponse(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(), Collections.emptyList());
            return null;
        }).when(mMockBillingClient).queryPurchasesAsync(any(QueryPurchasesParams.class), any(PurchasesResponseListener.class));
    }

    @After
    public void tearDown() throws Exception {
        resetSingleton();
        if (mMockitoCloseable != null) {
            mMockitoCloseable.close();
        }
    }

    private void resetSingleton() throws Exception {
        Field field = SupportBillingClient.class.getDeclaredField("sBillingClient");
        field.setAccessible(true);
        field.set(null, null);
    }

    private void injectMockBillingClient(SupportBillingClient client, BillingClient mockBillingClient) throws Exception {
        Field field = SupportBillingClient.class.getDeclaredField("mBillingClient");
        field.setAccessible(true);
        field.set(client, mockBillingClient);
    }

    private void injectSameThreadExecutor(SupportBillingClient client) throws Exception {
        Field field = SupportBillingClient.class.getDeclaredField("mExecutor");
        field.setAccessible(true);
        field.set(client, new SameThreadScheduledExecutor());
    }

    @Test
    public void testConnectionStateUpdated() throws Exception {
        Task<BillingResult> task = mSupportBillingClient.verifyBillingSupport();
        ShadowLooper.shadowMainLooper().idle();

        assertTrue(task.isSuccessful());
        assertEquals(BillingClient.BillingResponseCode.OK, task.getResult().getResponseCode());
        verify(mMockBillingClient, times(1)).startConnection(any(BillingClientStateListener.class));

        Task<BillingResult> task2 = mSupportBillingClient.verifyBillingSupport();
        ShadowLooper.shadowMainLooper().idle();

        assertTrue(task2.isSuccessful());
        verify(mMockBillingClient, times(1)).startConnection(any(BillingClientStateListener.class));
    }

    @Test
    public void testQueryPurchasesPartialOwnership() throws Exception {
        Purchase mockPurchase = mock(Purchase.class);
        when(mockPurchase.getProducts()).thenReturn(Collections.singletonList("PRO"));
        when(mockPurchase.getPurchaseState()).thenReturn(Purchase.PurchaseState.PURCHASED);
        when(mockPurchase.isAcknowledged()).thenReturn(true);
        when(mockPurchase.getOriginalJson()).thenReturn("{}");
        when(mockPurchase.getSignature()).thenReturn("sig");

        doAnswer(invocation -> {
            PurchasesResponseListener listener = invocation.getArgument(1);
            listener.onQueryPurchasesResponse(BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build(), Collections.singletonList(mockPurchase));
            return null;
        }).when(mMockBillingClient).queryPurchasesAsync(any(QueryPurchasesParams.class), any(PurchasesResponseListener.class));

        SupportBillingClient.PurchaseListener mockListener = mock(SupportBillingClient.PurchaseListener.class);

        try (MockedStatic<Security> mockedSecurity = mockStatic(Security.class)) {
            mockedSecurity.when(() -> Security.verifyPurchase(any(), any(), any())).thenReturn(true);

            Task<BillingResult> task = mSupportBillingClient.registerPurchaseListener(Arrays.asList("PRO", "EXTRA_LEVELS"), mockListener);
            ShadowLooper.shadowMainLooper().idle();

            assertTrue(task.isSuccessful());
            assertEquals(BillingClient.BillingResponseCode.OK, task.getResult().getResponseCode());

            verify(mockListener, times(1)).onPurchaseFound(mockPurchase);
            verify(mockListener, times(1)).onPurchaseLost("EXTRA_LEVELS");
        }
    }

    @Test
    public void testListenerThreadSafety() throws Exception {
        SupportBillingClient.PurchaseListener listener1 = mock(SupportBillingClient.PurchaseListener.class);
        SupportBillingClient.PurchaseListener listener2 = mock(SupportBillingClient.PurchaseListener.class);

        mSupportBillingClient.registerPurchaseListener(Collections.singletonList("PRO"), listener1);
        mSupportBillingClient.registerPurchaseListener(Collections.singletonList("PRO"), listener2);

        mSupportBillingClient.unregisterPurchaseListener(listener1);

        Task<BillingResult> task = mSupportBillingClient.queryPurchases(Collections.singletonList("PRO"));
        ShadowLooper.shadowMainLooper().idle();
        assertTrue(task.isSuccessful());
    }

    @Test
    public void testActivityContextHandling() throws Exception {
        Activity activity1 = Robolectric.buildActivity(Activity.class).create().get();
        Activity activity2 = Robolectric.buildActivity(Activity.class).create().get();

        SupportBillingClient client1 = SupportBillingClient.getInstance(activity1, "key");
        SupportBillingClient client2 = SupportBillingClient.getInstance(activity2, "key");

        assertEquals(client1, client2);

        Field field = SupportBillingClient.class.getDeclaredField("mActivity");
        field.setAccessible(true);
        java.lang.ref.WeakReference<Activity> weakRef = (java.lang.ref.WeakReference<Activity>) field.get(client1);

        assertEquals(activity2, weakRef.get());
    }
}
