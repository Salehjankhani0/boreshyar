package ir.boreshyar.glass;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;

/** نمایش اعلان‌های FCM و کمک‌های Firebase. اگر google-services.json نباشد همه‌چیز بی‌صدا غیرفعال است. */
final class PushNotifier {
    /** کلید داده‌ٔ پیام FCM (و extra اینتنت) برای باز کردن یک صفحهٔ مشخص از سایت؛ فقط آدرس مبدأ مجاز پذیرفته می‌شود */
    static final String EXTRA_URL = "url";

    private PushNotifier() {}

    static boolean firebaseReady(Context c) {
        try { return !FirebaseApp.getApps(c).isEmpty(); } catch (Exception e) { return false; }
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String id = c.getString(R.string.push_channel_id);
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(new NotificationChannel(id, c.getString(R.string.push_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    static void show(Context c, @Nullable String title, @Nullable String body, @Nullable String url) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (url != null && TrustedOrigin.isTrusted(url)) open.putExtra(EXTRA_URL, url);
        int rc = (int) (System.currentTimeMillis() & 0x7fffffff);
        PendingIntent pi = PendingIntent.getActivity(c, rc, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, c.getString(R.string.push_channel_id))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(c, R.color.notification_color))
                .setContentTitle(title == null || title.isEmpty() ? c.getString(R.string.app_name) : title)
                .setContentText(body == null ? "" : body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body == null ? "" : body))
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(c).notify(rc, b.build());
        } catch (SecurityException ignored) { /* مجوز اعلان رد شده */ }
    }
}
