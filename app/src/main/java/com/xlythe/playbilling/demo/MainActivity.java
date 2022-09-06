package com.xlythe.playbilling.demo;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.xlythe.playbilling.SupportBillingClient;

public class MainActivity extends Activity {
  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    SupportBillingClient.getInstance(this, "foo").purchaseItem("bar");
  }
}
