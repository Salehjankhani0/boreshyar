package ir.boreshyar.glass;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * دریافت پیام FCM وقتی برنامه باز (foreground) است.
 * وقتی برنامه بسته/پس‌زمینه است و پیام از نوع «notification» باشد، خود سیستم اعلان را نشان می‌دهد
 * و با کلیک، کلید data به نام url در اینتنت MainActivity می‌آید.
 * پیام «data-only» با کلیدهای title / body / url همیشه از اینجا اعلان می‌شود.
 */
public class BoreshMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(@NonNull RemoteMessage m) {
        Map<String, String> d = m.getData();
        String title = null, body = null;
        RemoteMessage.Notification n = m.getNotification();
        if (n != null) { title = n.getTitle(); body = n.getBody(); }
        if (title == null) title = d.get("title");
        if (body == null) body = d.get("body");
        if ((title == null || title.isEmpty()) && (body == null || body.isEmpty())) return;
        PushNotifier.show(getApplicationContext(), title, body, d.get(PushNotifier.EXTRA_URL));
    }

    @Override
    public void onNewToken(@NonNull String token) {
        // توکن با پل (getPushToken) هنگام نیاز به سایت داده می‌شود؛ اینجا چیزی ذخیره/ارسال نمی‌شود.
    }
}
