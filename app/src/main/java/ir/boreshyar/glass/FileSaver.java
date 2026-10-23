package ir.boreshyar.glass;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/** ذخیرهٔ خروجی‌ها (PDF/PNG/JPG) در Downloads یا اشتراک‌گذاری از طریق برگهٔ سیستم. ورودی‌ها سخت‌گیرانه اعتبارسنجی می‌شوند. */
final class FileSaver {
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,120}\\.(pdf|png|jpe?g|csv|json|html|txt)$", Pattern.CASE_INSENSITIVE);

    private FileSaver() {}

    static boolean validName(String n) {
        return n != null && NAME.matcher(n).matches() && !n.contains("..");
    }

    static String mimeFor(String name) {
        String l = name.toLowerCase();
        if (l.endsWith(".pdf")) return "application/pdf";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".csv")) return "text/csv";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".txt")) return "text/plain";
        return "image/jpeg";
    }

    static byte[] decode(String b64) {
        if (b64 == null || b64.length() > (MAX_BYTES / 3) * 4 + 8) return null;
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            return b.length > MAX_BYTES ? null : b;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Android 10+: MediaStore.Downloads. نسخهٔ قدیمی‌تر: false (سمت JS به اشتراک‌گذاری برمی‌گردد). */
    static boolean saveToDownloads(Context ctx, String name, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        ContentResolver resolver = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("insert_failed");
        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) throw new Exception("stream_failed");
            os.write(bytes);
            os.flush();
        }
        return true;
    }

    /** فایل را در cache می‌نویسد و Intent اشتراک‌گذاری را برمی‌گرداند */
    static Intent shareIntent(Context ctx, String name, byte[] bytes, String chooserTitle) throws Exception {
        File dir = new File(ctx.getCacheDir(), "exports");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir_failed");
        File f = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(bytes);
        }
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", f);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mimeFor(name));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(send, chooserTitle);
    }
}
