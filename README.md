Play Billing
====================

A wrapper around the Billing API for the Android Play Store


Where to Download
-----------------
```groovy
dependencies {
  implementation 'com.xlythe:play-billing:3.0.1'
  implementation 'androidx.appcompat:appcompat:+'
  implementation "com.android.billingclient:billing:+"
  implementation "com.google.android.gms:play-services-base:+"
  implementation "com.google.guava:guava:28.2-android"
}
```


How to use
-----------------
```java
/** Listen to purchases (and refunds) */
SupportBillingClient.getInstance(activity, apiKey).registerPurchaseListener(skus, purchaseListener);

/** Purchase an item */
SupportBillingClient.getInstance(activity, apiKey).purchaseItem(sku);
```

License
-------

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
