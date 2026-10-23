package ir.boreshyar.glass;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * پل JavaScript↔Java. با WebViewCompat.addWebMessageListener فقط برای «مبدأ مجاز» (سایت اصلی با HTTPS) و فقط فریم اصلی
 * نصب می‌شود. پیام‌ها JSON با فهرست بستهٔ نوع‌ها هستند؛ هیچ متدی برای اجرای دستور/URL دلخواه وجود ندارد و همهٔ ورودی‌ها
 * اعتبارسنجی می‌شوند. توکن JWT هرگز از این پل عبور نمی‌کند (نشست = کوکی HttpOnly خود WebView).
 */
final class NativeBridge implements WebViewCompat.WebMessageListener {
    static final String NAME = "GlassCutNative";

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9]{1,64}$");
    private static final Pattern SKU = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final Pattern PAYLOAD = Pattern.compile("^[A-Za-z0-9]{8,64}$");
    private static final int MAX_MESSAGE_CHARS = 28 * 1024 * 1024; // ~20MB فایل در Base64

    private final MainActivity activity;
    private final BillingManager billing;

    NativeBridge(MainActivity activity, BillingManager billing) {
        this.activity = activity;
        this.billing = billing;
    }

    @Override
    public void onPostMessage(@NonNull WebView view, @NonNull WebMessageCompat message, @NonNull Uri sourceOrigin,
                              boolean isMainFrame, @NonNull JavaScriptReplyProxy replyProxy) {
        // دفاع دوباره: حتی با قاعدهٔ مبدأ، فقط فریم اصلی از مبدأ مجاز پذیرفته می‌شود
        if (!isMainFrame || !TrustedOrigin.isTrustedOrigin(sourceOrigin)) return;
        String raw = message.getData();
        if (raw == null || raw.length() > MAX_MESSAGE_CHARS) return;

        JSONObject req;
        try { req = new JSONObject(raw); } catch (Exception e) { return; }
        final String id = req.optString("id", "");
        if (!ID.matcher(id).matches()) return;
        final String type = req.optString("type", "");
        final JavaScriptReplyProxy reply = replyProxy;

        try {
            switch (type) {
                case "getInfo": {
                    JSONObject o = base(id, true);
                    o.put("platform", BuildConfig.PLATFORM);
                    o.put("appVersionName", BuildConfig.VERSION_NAME);
                    o.put("appVersionCode", BuildConfig.VERSION_CODE);
                    o.put("billingEnabled", BillingManager.enabled());
                    send(reply, o);
                    break;
                }
                case "purchase": {
                    String sku = req.optString("sku", "");
                    String payload = req.optString("payload", "");
                    if (!SKU.matcher(sku).matches() || !PAYLOAD.matcher(payload).matches() || !BillingManager.skuAllowed(sku)) {
                        send(reply, err(id, "invalid_args"));
                        break;
                    }
                    billing.purchase(activity, sku, payload, r -> send(reply, merge(id, r)));
                    break;
                }
                case "queryPurchases": {
                    JSONArray arr = req.optJSONArray("skus");
                    List<String> skus = new ArrayList<>();
                    if (arr == null || arr.length() > 10) { send(reply, err(id, "invalid_args")); break; }
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i, "");
                        if (SKU.matcher(s).matches()) skus.add(s);
                    }
                    billing.queryPurchases(skus, r -> send(reply, merge(id, r)));
                    break;
                }
                case "consume": {
                    String token = req.optString("token", "");
                    if (token.isEmpty() || token.length() > 1024) { send(reply, err(id, "invalid_args")); break; }
                    billing.consume(token, r -> send(reply, merge(id, r)));
                    break;
                }
                case "saveFile":
                    saveFile(id, req, reply);
                    break;
                case "getPushToken": {
                    // توکن FCM برای ثبت در سرور سایت (ارسال اعلان به همین دستگاه). توکن JWT/نشست از این پل رد نمی‌شود.
                    if (!PushNotifier.firebaseReady(activity)) { send(reply, err(id, "firebase_unavailable")); break; }
                    FirebaseMessaging.getInstance().getToken().addOnCompleteListener(t -> {
                        try {
                            if (!t.isSuccessful() || t.getResult() == null) { send(reply, err(id, "token_failed")); return; }
                            JSONObject o = base(id, true);
                            o.put("token", t.getResult());
                            o.put("platform", BuildConfig.PLATFORM);
                            o.put("notificationsEnabled", NotificationManagerCompat.from(activity).areNotificationsEnabled());
                            send(reply, o);
                        } catch (Exception e) {
                            send(reply, err(id, "internal_error"));
                        }
                    });
                    break;
                }
                case "openStore":
                    // آدرس صفحهٔ فروشگاه را خود اپ از packageName می‌سازد؛ هیچ آدرسی از JavaScript پذیرفته نمی‌شود
                    send(reply, openStore() ? base(id, true) : err(id, "open_failed"));
                    break;
                default:
                    send(reply, err(id, "unknown_type"));
            }
        } catch (Exception e) {
            send(reply, err(id, "internal_error")); // جزئیات خطا (و هیچ داده‌ٔ حساسی) لاگ/ارسال نمی‌شود
        }
    }

    private boolean openStore() {
        String pkg = activity.getPackageName();
        String[] uris = "MYKET".equals(BuildConfig.PLATFORM)
            ? new String[]{"myket://details?id=" + pkg, "https://myket.ir/app/" + pkg}
            : new String[]{"bazaar://details?id=" + pkg, "https://cafebazaar.ir/app/" + pkg};
        for (String u : uris) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) { /* برنامهٔ فروشگاه نصب نیست؛ آدرس بعدی */ }
        }
        return false;
    }

    private void saveFile(final String id, JSONObject req, final JavaScriptReplyProxy reply) {
        final String name = req.optString("name", "");
        final String mode = req.optString("mode", "");
        final String data = req.optString("data", "");
        if (!FileSaver.validName(name) || !("downloads".equals(mode) || "share".equals(mode))) { send(reply, err(id, "invalid_args")); return; }
        // رمزگشایی و نوشتن فایل خارج از نخ UI
        new Thread(() -> {
            try {
                byte[] bytes = FileSaver.decode(data);
                if (bytes == null || bytes.length == 0) { send(reply, err(id, "invalid_data")); return; }
                if ("downloads".equals(mode)) {
                    if (!FileSaver.saveToDownloads(activity, name, bytes)) { send(reply, err(id, "legacy_android")); return; }
                    JSONObject o = base(id, true);
                    o.put("name", name);
                    send(reply, o);
                } else {
                    final Intent chooser = FileSaver.shareIntent(activity, name, bytes, activity.getString(R.string.share_title));
                    activity.runOnUiThread(() -> {
                        try {
                            activity.startActivity(chooser);
                            JSONObject o = base(id, true);
                            o.put("name", name);
                            send(reply, o);
                        } catch (Exception e) {
                            send(reply, err(id, "share_failed"));
                        }
                    });
                }
            } catch (Exception e) {
                send(reply, err(id, "save_failed"));
            }
        }, "gc-save").start();
    }

    /* ---------- کمک‌ها ---------- */
    private static JSONObject base(String id, boolean ok) {
        JSONObject o = new JSONObject();
        try { o.put("id", id); o.put("ok", ok); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject err(String id, String code) {
        JSONObject o = base(id, false);
        try { o.put("error", code); } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject merge(String id, JSONObject r) {
        try { r.put("id", id); } catch (Exception ignored) { }
        return r;
    }

    private void send(@Nullable JavaScriptReplyProxy reply, JSONObject o) {
        if (reply == null) return;
        final String s = o.toString();
        activity.runOnUiThread(() -> {
            try { reply.postMessage(s); } catch (Exception ignored) { /* صفحه بسته شده */ }
        });
    }
}
