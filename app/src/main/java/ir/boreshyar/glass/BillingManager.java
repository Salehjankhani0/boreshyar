package ir.boreshyar.glass;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * ⚠ پکیج import کتابخانه را در Android Studio تأیید کنید: دسترسی به مخزن کتابخانه در محیط ساخت این پروژه نبود و
 * نام پکیج از روی مستند رسمی (که import ننوشته) فرض شده است. اگر قرمز شد، با Alt+Enter همان کلاس‌ها را import کنید.
 */
import ir.myket.billingclient.IabHelper;
import ir.myket.billingclient.util.IabResult;
import ir.myket.billingclient.util.Inventory;
import ir.myket.billingclient.util.Purchase;

/**
 * Adapter پرداخت فروشگاه. یک کتابخانه (myket-billing-client) برای هر دو فروشگاه؛ تفاوت‌ها (بستهٔ فروشگاه، آدرس BIND،
 * کلید عمومی) در build.gradle هر Flavor است، نه در این کد.
 *
 * این کلاس هرگز «موفقیت اشتراک» اعلام نمی‌کند؛ فقط JSON خرید + امضا را به سایت برمی‌گرداند تا سرور تأیید کند.
 * مستند مایکت: هم‌زمان فقط یک درخواست به سرویس پرداخت؛ پس عملیات هم‌زمان رد می‌شوند (busy).
 * مدل اشتراکی (subs) در مایکت پشتیبانی نمی‌شود؛ همهٔ پلن‌ها «محصول مصرف‌شدنی» هستند و پس از تحویل توسط سرور Consume می‌شوند.
 */
final class BillingManager {
    interface Callback { void done(JSONObject result); }

    // کدهای پاسخ استاندارد IAB v3 (مشترک مایکت/بازار/گوگل)
    private static final int RESP_USER_CANCELED = 1;
    private static final int RESP_BILLING_UNAVAILABLE = 3;
    private static final int RESP_ALREADY_OWNED = 7;
    private static final int HELPER_USER_CANCELLED = -1005;
    private static final int HELPER_REMOTE_EXCEPTION = -1001;

    private final Context appContext;
    private IabHelper helper;
    private boolean ready = false;
    private boolean setupRunning = false;
    private boolean busy = false;
    private final Map<String, Purchase> known = new HashMap<>(); // توکن → خرید (برای Consume)

    BillingManager(Context appContext) {
        this.appContext = appContext.getApplicationContext();
    }

    static boolean enabled() {
        return !BuildConfig.IAB_PUBLIC_KEY.isEmpty() && !allowedSkus().isEmpty();
    }

    static List<String> allowedSkus() {
        List<String> out = new ArrayList<>();
        for (String s : BuildConfig.ALLOWED_SKUS.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static boolean skuAllowed(String sku) {
        return sku != null && allowedSkus().contains(sku);
    }

    private static JSONObject res(boolean ok, String status, String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            if (status != null) o.put("status", status);
            if (error != null) o.put("error", error);
        } catch (Exception ignored) { }
        return o;
    }

    private interface Ready { void run(boolean ok); }

    private void ensureSetup(final Ready cb) {
        if (ready && helper != null) { cb.run(true); return; }
        if (setupRunning) { cb.run(false); return; }
        if (!enabled()) { cb.run(false); return; }
        setupRunning = true;
        try {
            helper = new IabHelper(appContext, BuildConfig.IAB_PUBLIC_KEY);
            helper.enableDebugLogging(BuildConfig.DEBUG);
            helper.startSetup(new IabHelper.OnIabSetupFinishedListener() {
                @Override public void onIabSetupFinished(IabResult result) {
                    setupRunning = false;
                    if (helper == null) { cb.run(false); return; }
                    ready = result != null && result.isSuccess();
                    if (!ready) disposeHelper();
                    cb.run(ready);
                }
            });
        } catch (Exception e) {
            setupRunning = false;
            ready = false;
            disposeHelper();
            cb.run(false);
        }
    }

    private void disposeHelper() {
        try { if (helper != null) helper.dispose(); } catch (Exception ignored) { }
        helper = null;
        ready = false;
    }

    private Callback guarded(final Callback cb) {
        return new Callback() {
            @Override public void done(JSONObject r) { busy = false; cb.done(r); }
        };
    }

    /** باز کردن صفحهٔ پرداخت رسمی فروشگاه */
    void purchase(final Activity activity, final String sku, final String payload, Callback callback) {
        if (busy) { callback.done(res(false, "error", "busy")); return; }
        if (!skuAllowed(sku)) { callback.done(res(false, "error", "sku_not_allowed")); return; }
        busy = true;
        final Callback cb = guarded(callback);
        ensureSetup(new Ready() {
            @Override public void run(boolean ok) {
                if (!ok) { cb.done(res(false, "unavailable", "setup_failed")); return; }
                try {
                    helper.launchPurchaseFlow(activity, sku, new IabHelper.OnIabPurchaseFinishedListener() {
                        @Override public void onIabPurchaseFinished(IabResult result, Purchase p) {
                            if (helper == null) { cb.done(res(false, "error", "disposed")); return; }
                            if (result != null && result.isSuccess() && p != null) {
                                if (!sku.equals(p.getSku()) || !payload.equals(p.getDeveloperPayload())) {
                                    cb.done(res(false, "error", "payload_mismatch"));
                                    return;
                                }
                                known.put(p.getToken(), p);
                                cb.done(purchaseJson(p, "purchased"));
                                return;
                            }
                            int code = result == null ? 0 : result.getResponse();
                            if (code == RESP_USER_CANCELED || code == HELPER_USER_CANCELLED) cb.done(res(false, "cancelled", null));
                            else if (code == RESP_ALREADY_OWNED) cb.done(res(false, "already_owned", null));
                            else if (code == RESP_BILLING_UNAVAILABLE || code == HELPER_REMOTE_EXCEPTION) cb.done(res(false, "unavailable", String.valueOf(code)));
                            else cb.done(res(false, "error", String.valueOf(code)));
                        }
                    }, payload);
                } catch (Exception e) {
                    cb.done(res(false, "error", "launch_failed"));
                }
            }
        });
    }

    /** خریدهای مصرف‌نشدهٔ کاربر (برای بازیابی). باید هنگام شروع برنامه صدا زده شود. */
    void queryPurchases(final List<String> skus, Callback callback) {
        if (busy) { callback.done(res(false, "error", "busy")); return; }
        final List<String> safe = new ArrayList<>();
        for (String s : skus) if (skuAllowed(s)) safe.add(s);
        if (safe.isEmpty()) {
            JSONObject o = res(true, "ok", null);
            try { o.put("purchases", new JSONArray()); } catch (Exception ignored) { }
            callback.done(o);
            return;
        }
        busy = true;
        final Callback cb = guarded(callback);
        ensureSetup(new Ready() {
            @Override public void run(boolean ok) {
                if (!ok) { cb.done(res(false, "unavailable", "setup_failed")); return; }
                try {

                    helper.queryInventoryAsync(false, safe, new IabHelper.QueryInventoryFinishedListener() {
                        @Override public void onQueryInventoryFinished(IabResult result, Inventory inv) {
                            if (helper == null) { cb.done(res(false, "error", "disposed")); return; }
                            if (result == null || result.isFailure() || inv == null) { cb.done(res(false, "error", "query_failed")); return; }
                            JSONArray arr = new JSONArray();
                            for (String sku : safe) {
                                Purchase p = inv.getPurchase(sku);
                                if (p == null) continue;
                                known.put(p.getToken(), p);
                                arr.put(purchaseJson(p, "purchased"));
                            }
                            JSONObject o = res(true, "ok", null);
                            try { o.put("purchases", arr); } catch (Exception ignored) { }
                            cb.done(o);
                        }
                    });
                } catch (Exception e) {
                    cb.done(res(false, "error", "query_failed"));
                }
            }
        });
    }

    /** مصرف خرید پس از اینکه سرور اشتراک را تحویل داد */
    void consume(String token, Callback callback) {
        if (busy) { callback.done(res(false, "error", "busy")); return; }
        final Purchase p = known.get(token);
        if (p == null) { callback.done(res(false, "error", "unknown_purchase")); return; }
        busy = true;
        final Callback cb = guarded(callback);
        final String t = token;
        ensureSetup(new Ready() {
            @Override public void run(boolean ok) {
                if (!ok) { cb.done(res(false, "unavailable", "setup_failed")); return; }
                try {
                    helper.consumeAsync(p, new IabHelper.OnConsumeFinishedListener() {
                        @Override public void onConsumeFinished(Purchase purchase, IabResult result) {
                            if (result != null && result.isSuccess()) { known.remove(t); cb.done(res(true, "consumed", null)); }
                            else cb.done(res(false, "error", "consume_failed"));
                        }
                    });
                } catch (Exception e) {
                    cb.done(res(false, "error", "consume_failed"));
                }
            }
        });
    }

    private static JSONObject purchaseJson(Purchase p, String status) {
        JSONObject o = res(true, status, null);
        try {
            o.put("sku", p.getSku());
            o.put("token", p.getToken());
            o.put("originalJson", p.getOriginalJson());
            o.put("signature", p.getSignature());
        } catch (Exception ignored) { }
        return o;
    }

    void dispose() {
        disposeHelper();
        known.clear();
        busy = false;
        setupRunning = false;
    }
}
