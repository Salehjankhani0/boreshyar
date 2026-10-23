package ir.boreshyar.glass;

import android.net.Uri;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * تنها مبدأ مجاز برای WebView و پل JavaScript↔Java: همان سایت اصلی با HTTPS (BuildConfig.SITE_URL).
 * هر دامنهٔ دیگر (حتی زیردامنه) نه در WebView بارگذاری می‌شود و نه به پل دسترسی دارد.
 */
final class TrustedOrigin {
    private static final String SCHEME = "https";
    private static final String HOST;
    private static final int PORT; // -1 = پیش‌فرض
    private static final String ORIGIN;

    static {
        Uri u = Uri.parse(BuildConfig.SITE_URL);
        HOST = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        PORT = (u.getPort() == 443) ? -1 : u.getPort();
        ORIGIN = SCHEME + "://" + HOST + (PORT == -1 ? "" : ":" + PORT);
    }

    private TrustedOrigin() {}

    /** قاعدهٔ مبدأ برای WebViewCompat.addWebMessageListener */
    static Set<String> rules() {
        return Collections.singleton(ORIGIN);
    }

    static String siteUrl() {
        return BuildConfig.SITE_URL;
    }

    static boolean isTrusted(Uri uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) return false;
        if (!SCHEME.equals(uri.getScheme().toLowerCase(Locale.ROOT))) return false;
        if (!HOST.equals(uri.getHost().toLowerCase(Locale.ROOT))) return false;
        int port = uri.getPort() == 443 ? -1 : uri.getPort();
        return port == PORT;
    }

    static boolean isTrusted(String url) {
        return url != null && isTrusted(Uri.parse(url));
    }

    /** sourceOrigin از WebMessageListener به شکل "https://host[:port]" می‌آید */
    static boolean isTrustedOrigin(Uri origin) {
        return isTrusted(origin);
    }
}
