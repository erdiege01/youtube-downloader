package dev.videoindirici;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.media.MediaScannerConnection;
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

    /**
     * Dosyayı Medya Deposu'na (MediaStore) bildirir; böylece Galeri, Müzik ve
     * Dosyalar uygulaması görür.
     *
     * <p>Uygulama bir dosyayı doğrudan dosya yoluyla yazdığında Android bunu
     * her zaman kendiliğinden indekslemez — indirilen video Galeri'de görünmez.
     * Bu çağrı (MediaScannerConnection) tam bu boşluğu doldurur.
     */
    public static void indexFile(Context ctx, File f) {
        if (f == null || !f.isFile() || !isMedia(f.getName())) {
            return;
        }
        try {
            MediaScannerConnection.scanFile(
                    ctx,
                    new String[]{f.getAbsolutePath()},
                    new String[]{mimeFor(f.getName())},
                    (path, uri) -> Log.i(TAG, "Galeri'ye eklendi: " + path + " -> " + uri));
        } catch (Exception e) {
            Log.w(TAG, "tarama başarısız: " + e.getMessage());
        }
    }

    /** Klasördeki tüm medya dosyalarını Medya Deposu'na tarar. @return dosya sayısı */
    public static int indexAll(Context ctx, File dir) {
        if (dir == null) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int n = 0;
        for (File f : files) {
            if (f.isFile() && isMedia(f.getName())) {
                indexFile(ctx, f);
                n++;
            }
        }
        return n;
    }

    /** Dosya adı medya dosyası mı? (.txt ve diğer rapor dosyaları hariç) */
    public static boolean isMedia(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".m4v") || n.endsWith(".webm")
                || n.endsWith(".mkv") || n.endsWith(".mov")
                || n.endsWith(".mp3") || n.endsWith(".m4a")
                || n.endsWith(".opus") || n.endsWith(".ogg")
                || n.endsWith(".wav") || n.endsWith(".flac");
    }

    /**
     * Dosyalar uygulamasını klasörü doğrudan açacak şekilde başlatır.
     *
     * <p>Kullanıcının "nereye kaydetti?" sorusunu ekranda çözmenin en güvenli
     * yolu budur: uygulama klasörün kendisini açar, kullanıcı kaydırma yapmaz.
     *
     * @return true = klasör açıldı
     */
    public static boolean openFolder(Context ctx, File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        // /storage/emulated/0/Download/VideoIndirici -> primary:Download/VideoIndirici
        String rel = dir.getAbsolutePath()
                .replaceFirst("^/storage/emulated/\\d+/", "")
                .replaceFirst("^/sdcard/", "");
        if (rel.equals(dir.getAbsolutePath())) {
            return false; // standart olmayan yol: DocumentsProvider eşleşmez
        }
        Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/"
                + Uri.encode("primary:" + rel));
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "vnd.android.document/directory");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "klasör açılamadı: " + e.getMessage());
            return false;
        }
    }

    /**
     * İndirme sırasında kalan artık dosyaları siler.
     *
     * <p>Kaçırılan iki klasik artık: yt-dlp'nin ara çıktısı {@code X.temp.mp4}
     * (bit adı {@code .temp} değil, {@code .temp.mp4} olduğundan eski kontrol
     * bunu yakalamıyordu — 1 GB'lık kopya diskte kalıyordu) ve gömülmemiş
     * kapak {@code X.webp}. İkisi de yalnızca hedef dosya gerçekten varsa
     * silinir; böylece gerçek bir indirme asla zarar görmez.
     */
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
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);

            // Yanlışlıkla silinmeyecek kesin artıklar: .part / .ytdl / .tmp
            boolean leftover = lower.endsWith(".part")
                    || lower.endsWith(".ytdl")
                    || lower.endsWith(".tmp");

            if (!leftover && lower.contains(".temp.")) {
                // X.temp.mp4 -> hedef X.mp4 mevcut mu?
                int i = lower.lastIndexOf(".temp.");
                String base = name.substring(0, i) + name.substring(i + ".temp".length());
                leftover = new File(dir, base).isFile();
            }

            if (!leftover && isImage(lower)) {
                // X.webp -> gömülecek X.mp4 / X.mp3 mevcut mu?
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    String stem = name.substring(0, dot);
                    leftover = hasMediaTwin(dir, stem);
                }
            }

            if (leftover && f.delete()) {
                removed++;
                Log.i(TAG, "artık dosya silindi: " + name);
            }
        }
        return removed;
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".webp") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    /** Aynı adın medya uzantılı ikizi var mı? (ör. "X.webp" için "X.mp4") */
    private static boolean hasMediaTwin(File dir, String stem) {
        String[] exts = {".mp4", ".m4v", ".webm", ".mkv", ".mov",
                ".mp3", ".m4a", ".opus", ".ogg", ".wav", ".flac"};
        for (String e : exts) {
            if (new File(dir, stem + e).isFile()) {
                return true;
            }
        }
        return false;
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
