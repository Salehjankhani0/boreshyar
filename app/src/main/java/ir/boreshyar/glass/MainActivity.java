package ir.boreshyar.glass;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import androidx.appcompat.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.firebase.messaging.FirebaseMessaging;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * پوستهٔ اندروید: فقط سایت اصلی (SITE_URL، HTTPS) را در WebView باز می‌کند. سایت داخل APK نیست؛ آفلاین بودن را
 * Service Worker خود سایت (PWA) تأمین می‌کند. نشست (JWT) در کوکی HttpOnly WebView می‌ماند و از Java رد نمی‌شود.
 */
public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ProgressBar progress;
    private View errorView;
    private TextView errorTitle;
    private TextView errorText;
    private BillingManager billing;
    private boolean bridgeReady = false;
    private boolean firstLoadDone = false;
    private String nativeTweaks = "";
    private String pendingUrl = null;           // صفحه‌ای که اعلان می‌خواهد باز کند (فقط مبدأ مجاز)
    private boolean askedNotifPermission = false;
    private ActivityResultLauncher<String> notifPermission;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.error_view);
        errorTitle = findViewById(R.id.error_title);
        errorText = findViewById(R.id.error_text);
        Button retry = findViewById(R.id.error_retry);
        retry.setOnClickListener(v -> loadSite());

        billing = new BillingManager(this);

        // ---- Firebase / اعلان‌ها ----
        notifPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });
        PushNotifier.ensureChannel(this);
        if (PushNotifier.firebaseReady(this)) {
            FirebaseMessaging.getInstance().subscribeToTopic("all");
            FirebaseMessaging.getInstance().subscribeToTopic(BuildConfig.PLATFORM.toLowerCase(java.util.Locale.ROOT));
        }
        pendingUrl = trustedUrlFrom(getIntent());

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);          // برنامهٔ React
        s.setDomStorageEnabled(true);          // localStorage/IndexedDB
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);                    // تغییر اندازهٔ فونت سیستم، چیدمان سایت را به‌هم نریزد
        s.setSafeBrowsingEnabled(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, false);

        // ---- ظاهر بومی: بدون افکت کشیدن/اسکرول‌بار/لانگ‌پرس مرورگر ----
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setBackgroundColor(Color.parseColor("#f2f5f9")); // همرنگ پس‌زمینهٔ پنجره؛ فلش سفید نداریم
        webView.setAlpha(0f);                                    // تا اولین بارگذاری کامل، پنهان؛ بعد fade-in
        webView.setOnLongClickListener(v -> {
            // لانگ‌پرس فقط روی فیلدهای متنی کار کند (چسباندن/کپی)؛ جای دیگر هیچ منو/انتخابی نیست
            WebView.HitTestResult r = webView.getHitTestResult();
            return r == null || r.getType() != WebView.HitTestResult.EDIT_TEXT_TYPE;
        });
        nativeTweaks = readAsset("native-tweaks.js");
        if (!nativeTweaks.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, nativeTweaks, TrustedOrigin.rules());
        }
        webView.postDelayed(this::revealWebView, 6000); // اگر به هر دلیل onPageFinished نیامد، صفحه همچنان دیده شود

        webView.setWebViewClient(new SiteClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int p) {
                // نوار پیشرفت فقط برای بارگذاری اول؛ جابه‌جایی بین صفحات شبیه مرورگر نشود
                if (firstLoadDone) { progress.setVisibility(View.GONE); return; }
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            // alert/confirm با دیالوگ بومی و عنوان نام برنامه (نه «The page at https://... says»)
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (isFinishing()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.dialog_ok, (d, w) -> result.confirm())
                    .show();
                return true;
            }

            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (isFinishing()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.dialog_ok, (d, w) -> result.confirm())
                    .setNegativeButton(R.string.dialog_cancel, (d, w) -> result.cancel())
                    .show();
                return true;
            }
        });

        // پل امن: فقط مبدأ مجاز. اگر WebView سیستم قدیمی است، سایت بارگذاری نمی‌شود (تا پلتفرم/پرداخت اشتباه ثبت نشود).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, NativeBridge.NAME, TrustedOrigin.rules(), new NativeBridge(this, billing));
            bridgeReady = true;
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { onBack(); }
        });

        if (bridgeReady) loadSite();
        else showError(getString(R.string.err_webview_title), getString(R.string.err_webview), false);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** نمایش نرم صفحه (fade-in) بعد از اولین بارگذاری */
    private void revealWebView() {
        if (webView == null || webView.getAlpha() >= 1f) return;
        webView.animate().alpha(1f).setDuration(180).start();
    }

    /** آدرس درخواستی اعلان؛ فقط اگر مال سایت اصلی (HTTPS) باشد */
    private String trustedUrlFrom(Intent i) {
        if (i == null) return null;
        String u = i.getStringExtra(PushNotifier.EXTRA_URL);
        return TrustedOrigin.isTrusted(u) ? u : null;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String u = trustedUrlFrom(intent);
        if (u != null && bridgeReady) { pendingUrl = u; loadSite(); }
    }

    /** مجوز اعلان (اندروید ۱۳+) یک‌بار، بعد از اینکه کاربر برنامه را دید */
    private void askNotificationPermissionOnce() {
        if (askedNotifPermission || Build.VERSION.SDK_INT < 33) return;
        askedNotifPermission = true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void loadSite() {
        if (!bridgeReady) return;
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        String target = pendingUrl != null ? pendingUrl : TrustedOrigin.siteUrl();
        pendingUrl = null;
        webView.loadUrl(target);
    }

    private void showError(String title, String text, boolean canRetry) {
        errorTitle.setText(title);
        errorText.setText(text);
        findViewById(R.id.error_retry).setVisibility(canRetry ? View.VISIBLE : View.GONE);
        webView.setVisibility(View.INVISIBLE);
        errorView.setVisibility(View.VISIBLE);
    }

    /** بازگشت: ابتدا صفحه (بستن پنجرهٔ بالایی)، در غیر این صورت خروج */
    private void onBack() {
        if (errorView.getVisibility() == View.VISIBLE || !TrustedOrigin.isTrusted(webView.getUrl())) {
            finish();
            return;
        }
        webView.evaluateJavascript(
            "(function(){try{return !!(window.__gcHandleBack&&window.__gcHandleBack());}catch(e){return false;}})()",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (!"true".equals(v)) finish();
                }
            });
    }

    private final class SiteClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (TrustedOrigin.isTrusted(u)) return false;
            // دامنهٔ دیگر هرگز داخل WebView باز نمی‌شود (پل ارتباطی فقط برای سایت اصلی است)
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (request.isForMainFrame() && ("https".equals(scheme) || "http".equals(scheme) || "tel".equals(scheme) || "mailto".equals(scheme))) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            errorView.setVisibility(View.GONE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush(); // کوکی نشست حتماً روی دیسک نوشته شود
            // اگر WebView قدیمی از document-start-script پشتیبانی نکند، اینجا تزریق می‌شود (idempotent)
            if (!nativeTweaks.isEmpty() && TrustedOrigin.isTrusted(url)
                    && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(nativeTweaks, null);
            }
            firstLoadDone = true;
            askNotificationPermissionOnce();
            progress.setVisibility(View.GONE);
            revealWebView();
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) showError(getString(R.string.err_title), getString(R.string.err_offline), true);
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel(); // هرگز خطای گواهی نادیده گرفته نمی‌شود
            showError(getString(R.string.err_title), getString(R.string.err_offline), true);
        }
    }

    @Override protected void onPause() {
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override protected void onDestroy() {
        billing.dispose();
        webView.stopLoading();
        webView.destroy();
        super.onDestroy();
    }
}
