package dev.videoindirici;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * yt-dlp + FFmpeg motorunun hazırlığı ve indirme isteğinin kurulması.
 *
 * Format zincirleri masaüstü sürümündeki main.py'deki
 * {@code _video_format()} ile aynıdır.
 */
public final class Engine {

    private static final String TAG = "VideoIndirici";

    /** Sonucu beklemek için tek seferlik kilit. */
    private static final CountDownLatch LATCH = new CountDownLatch(1);
    private static volatile Throwable initError;

    private Engine() {
    }

    /** Kütüphaneleri arka planda hazırlar (ilk açılışta Python sıkıştırması açılır). */
    public static void initAsync(final Context appContext) {
        final Context app = appContext.getApplicationContext();
        new Thread(() -> {
            try {
                YoutubeDL.getInstance().init(app);
                FFmpeg.getInstance().init(app);
                Log.i(TAG, "motor hazır");
            } catch (Throwable t) {
                initError = t;
                Log.e(TAG, "motor hazırlığı başarısız", t);
            } finally {
                LATCH.countDown();
            }
        }, "engine-init").start();
    }

    /** Hazırlık bitene kadar bekler. true = hazır. */
    public static boolean awaitReady(long millis) {
        try {
            LATCH.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return isReady();
    }

    public static boolean isReady() {
        return LATCH.getCount() == 0 && initError == null;
    }

    public static boolean isFinished() {
        return LATCH.getCount() == 0;
    }

    public static Throwable initError() {
        return initError;
    }

    // ------------------------------------------------------------------
    // Çıkış klasörü
    // ------------------------------------------------------------------

    /** Genel Download/VideoIndirici klasörü. */
    public static File publicDownloadDir() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VideoIndirici");
    }

    /**
     * Yazılabilir olan ilk klasörü seçer.
     *
     * Android 10 öncesi yazma izni gerekebilir; izin yoksa uygulamaya özel
     * klasöre düşer ve dosyalar sonra MediaStore'a kopyalanır.
     */
    public static File resolveOutputDir(Context ctx) {
        File pub = publicDownloadDir();
        if (ensureWritable(pub)) {
            return pub;
        }
        File ext = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File priv = ext != null ? new File(ext, "VideoIndirici") : new File(ctx.getFilesDir(), "VideoIndirici");
        ensureWritable(priv);
        return priv;
    }

    private static boolean ensureWritable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".yazma_testi");
            FileOutputStream fos = new FileOutputStream(probe);
            fos.write(0);
            fos.close();
            return probe.delete();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // İstek kurulumu
    // ------------------------------------------------------------------

    /** İndirme isteğini hazırlar — masaüstündeki get_ydl_opts() ile aynı mantık. */
    public static YoutubeDLRequest buildRequest(DownloadTask task) {
        YoutubeDLRequest req = new YoutubeDLRequest(task.url);

        req.addOption("--no-mtime");
        if (!task.channel) {
            req.addOption("--no-playlist");
        }
        req.addOption("-o", new File(task.outputDir, "%(title)s.%(ext)s").getAbsolutePath());

        if (task.isMp3()) {
            req.addOption("-f", "bestaudio/best");
            req.addOption("--extract-audio");
            req.addOption("--audio-format", "mp3");
            req.addOption("--audio-quality", "192K");
        } else {
            req.addOption("-f", videoFormat(task.site, task.quality));
            req.addOption("--merge-output-format", "mp4");
        }

        // Kapak fotoğrafı + başlık bilgisi dosyanın içine gömülür
        req.addOption("--embed-thumbnail");
        req.addOption("--embed-metadata");

        if (task.cookiesPath != null && !task.cookiesPath.isEmpty()) {
            req.addOption("--cookies", task.cookiesPath);
        }

        return req;
    }

    /**
     * Platforma ve seçilen kaliteye göre format zinciri üretir.
     *
     * YouTube H.264/MP4 + m4a zincirleriyle en uyumlu sonucu verir.
     * Instagram/TikTok/Facebook'un format adları farklı olduğundan genel
     * zincir kullanılır; sonuç yine mp4'e birleştirilir.
     */
    public static String videoFormat(String site, String quality) {
        boolean best = quality == null || quality.isEmpty() || "En İyi".equals(quality);

        if ("youtube".equals(site)) {
            if (best) {
                return "bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]"
                        + "/bestvideo[ext=mp4]+bestaudio[ext=m4a]"
                        + "/bestvideo*+bestaudio"
                        + "/best";
            }
            String h = quality.replace("p", "");
            return "bestvideo[height<=" + h + "][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]"
                    + "/bestvideo[height<=" + h + "][ext=mp4]+bestaudio[ext=m4a]"
                    + "/bestvideo[height<=" + h + "]+bestaudio"
                    + "/best[height<=" + h + "]";
        }

        // Diğer platformlar: ayrı video+ses varsa birleştir, yoksa tek parça al
        if (best) {
            return "bestvideo+bestaudio/best";
        }
        String h = quality.replace("p", "");
        return "bestvideo[height<=" + h + "]+bestaudio"
                + "/best[height<=" + h + "]"
                + "/best";
    }
}
