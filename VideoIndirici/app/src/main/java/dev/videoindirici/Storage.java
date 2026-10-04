package dev.videoindirici;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Dosyaların genel depolamaya yayınlanması ve artık dosyaların temizliği. */
public final class Storage {

    private static final String TAG = "VideoIndirici";
    private static final String SUB_DIR = "VideoIndirici";

    private Storage() {
    }

    /** Verilen klasör uygulamaya özel mi? (yani kopyalanması gerekiyor mu) */
    public static boolean needsPublishing(Context ctx, File dir) {
        try {
            File pub = Engine.publicDownloadDir().getCanonicalFile();
            return !dir.getCanonicalFile().equals(pub);
        } catch (IOException e) {
            return !dir.getAbsolutePath().contains("Android" + File.separator + "data");
        }
    }

    /**
     * Uygulamaya özel klasördeki dosyayı genel Download/VideoIndirici klasörüne
     * MediaStore üzerinden kopyalar ve kaynağı siler.
     *
     * @return true = yayınlandı
     */
    public static boolean publish(Context ctx, File src) {
        if (src == null || !src.isFile()) {
            return false;
        }
        if (Build.VERSION.SDK_INT < 29) {
            return false; // API 28 ve öncesi doğrudan yazılabilir
        }
        ContentResolver resolver = ctx.getContentResolver();
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(src.getName()));
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + SUB_DIR);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                Log.w(TAG, "MediaStore insert başarısız: " + src.getName());
                return false;
            }

            try (InputStream in = new FileInputStream(src);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("output stream yok");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            Log.i(TAG, "yayınlandı: " + src.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "yayınlama hatası: " + e.getMessage());
            return false;
        }
    }

    /** İndirme sırasında kalan artık dosyaları siler (.part, .ytdl, gömülmemiş kapak). */
    public static int cleanupLeftovers(File dir) {
        int removed = 0;
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".part") || name.endsWith(".ytdl")
                    || name.endsWith(".temp") || name.endsWith(".tmp")
                    || name.equals(".yazma_testi")) {
                if (f.delete()) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }
}
