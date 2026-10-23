# کتابخانهٔ پرداخت مایکت/بازار (IabHelper و AIDL) نباید حذف یا تغییر نام داده شود
-keep class ir.myket.** { *; }
-keep class com.android.vending.billing.** { *; }
-keep class ir.mservices.** { *; }
-dontwarn ir.myket.**
# پل JavaScript از WebMessageListener استفاده می‌کند؛ نیازی به @JavascriptInterface نیست.
