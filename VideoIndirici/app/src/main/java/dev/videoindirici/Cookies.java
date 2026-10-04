package dev.videoindirici;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * cookies.txt aktarımı.
 *
 * Android'de masaüstündeki gibi tarayıcı çerezlerine doğrudan erişilemez.
 * Kullanıcı tarayıcısından dışa aktardığı Netscape biçimli cookies.txt
 * dosyasını seçer; program onu uygulamaya özel klasöre kopyalar ve yt-dlp'e
 * {@code --cookies <yol>} olarak verir.
 */
public final class Cookies {

    private static final String TAG = "VideoIndirici";
    private static final String FILE_NAME = "cookies.txt";

    private Cookies() {
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** Kayıtlı çerez dosyasının yolu; yoksa null. */
    public static String path(Context ctx) {
        File f = file(ctx);
        return f.isFile() && f.length() > 0 ? f.getAbsolutePath() : null;
    }

    public static boolean exists(Context ctx) {
        return path(ctx) != null;
    }

    /** Seçilen URI'yi uygulamaya kopyalar. Başarıda yol döner. */
    public static String save(Context ctx, Uri uri) {
        if (uri == null) {
            return null;
        }
        ContentResolverHolder holder = new ContentResolverHolder(ctx);
        try (InputStream in = holder.resolver.openInputStream(uri);
             FileOutputStream out = new FileOutputStream(file(ctx))) {
            if (in == null) {
                return null;
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "çerez dosyası kopyalanamadı", e);
            return null;
        }
        return path(ctx);
    }

    public static void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
    }

    private static final class ContentResolverHolder {
        final android.content.ContentResolver resolver;

        ContentResolverHolder(Context ctx) {
            resolver = ctx.getContentResolver();
        }
    }
}
