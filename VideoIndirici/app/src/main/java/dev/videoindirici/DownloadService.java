package dev.videoindirici;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * İndirmeyi ön planda serviste çalıştırır.
 *
 * Telefonda uygulama arka plana alınsa bile indirme ölmez; ilerleme
 * bildiriminde görünür.
 */
public class DownloadService extends Service {

    private static final String TAG = "VideoIndirici";
    public static final String PROCESS_ID = "VideoIndiriciIndirme";
    private static final String CHANNEL_ID = "videoindirici_indirme";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_DOWNLOAD = "dev.videoindirici.action.DOWNLOAD";
    public static final String ACTION_CANCEL = "dev.videoindirici.action.CANCEL";

    /** Arayüzün dinlediği olaylar (ana thread'e taşınarak çağrılır). */
    public interface Listener {
        void onStatus(String text);

        void onProgress(int percent);

        void onLog(String line);

        /** @param success true = bitti, false = hata/iptal */
        void onFinished(boolean success, String message);
    }

    public static volatile Listener listener;

    /** Durum ekranı kapandığında geri yüklenebilsin diye saklanır. */
    public static volatile String lastStatus = "";
    public static volatile int lastPercent = -1;
    public static final StringBuilder logBuffer = new StringBuilder();
    public static volatile boolean running = false;

    private static volatile boolean cancelRequested = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastUiUpdate = 0;
    private int lastNotifPercent = -1;

    // ------------------------------------------------------------------
    // Servis yaşam döngüsü
    // ------------------------------------------------------------------

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (running) {
            appendLog("Zaten bir indirme devam ediyor.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final DownloadTask task = (DownloadTask) intent.getSerializableExtra("task");
        if (task == null || task.url == null || task.url.isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // startForeground ÇOK ERKEN çağrılmalı (Android 12+ zaman sınırı var)
        startAsForeground();

        running = true;
        cancelRequested = false;
        lastPercent = -1;
        lastStatus = getString(R.string.status_running);

        new Thread(() -> run(task, startId), "videoindirici-dl").start();
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.notif_channel_desc));
            nm.createNotificationChannel(ch);
        }

        Notification notification = buildNotification(getString(R.string.notif_title), -1);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground başarısız", e);
        }
    }

    private Notification buildNotification(String text, int percent) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (percent >= 0 && percent <= 100) {
            b.setProgress(100, percent, false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        // Sistem servisi öldürürse işlem başıboş kalmasın.
        // Bloklayan çağrı olduğu için arka plana alınır.
        new Thread(() -> {
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
            } catch (Throwable ignored) {
                // yoksay
            }
        }, "videoindirici-stop").start();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Servisi başlat (arka planda). */
    public static void start(Context ctx, DownloadTask task) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(ACTION_DOWNLOAD);
        i.putExtra("task", task);
        ContextCompat.startForegroundService(ctx, i);
    }

    /**
     * Devam edenden indirmeyi iptal eder.
     *
     * Not: servisi tekrar başlatmıyoruz — {@code startForegroundService}
     * çağrısının ardından {@code startForeground} zorunluluğu var; doğrudan
     * statik alan üzerinden iptal edip çalışan iş parçacığının bitmesini
     * beklemek daha güvenli.
     */
    public static void cancel(Context ctx) {
        if (!running) {
            return;
        }
        cancelRequested = true;
        final Listener l = listener;
        final String msg = "İptal isteği gönderildi...";
        synchronized (logBuffer) {
            logBuffer.append(msg).append('\n');
        }
        if (l != null) {
            new Handler(Looper.getMainLooper()).post(() -> l.onLog(msg));
        }
        // destroyProcessById süreç öldürür ve waitFor() yapar — ana thread'de
        // ANR (yanıt vermeyen uygulama) olur. Arka plana alıyoruz.
        new Thread(() -> {
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
            } catch (Throwable t) {
                Log.e(TAG, "iptal hatası", t);
            }
        }, "videoindirici-cancel").start();
    }

    // ------------------------------------------------------------------
    // İndirme
    // ------------------------------------------------------------------

    private void run(DownloadTask task, int startId) {
        try {
            if (!Engine.awaitReady(90_000)) {
                Throwable e = Engine.initError();
                throw new Exception("Motor hazırlanamadı: " + (e != null ? e.getMessage() : "bilinmiyor"));
            }

            File dir = new File(task.outputDir);
            long startTime = System.currentTimeMillis() - 3000;
            Set<String> before = snapshot(dir);

            appendLog("Platform: " + task.siteName());
            appendLog("Bağlantı: " + task.url);
            appendLog(task.isMp3()
                    ? "Biçim: MP3 (ses ayrıştırılır)"
                    : "Biçim: MP4 — " + Engine.videoFormat(task.site, task.quality));

            YoutubeDLRequest req = Engine.buildRequest(task);

            // Daha önce yarıda kalmış bir işlem kimliği haritada kaldıysa
            // "Process ID already exists" hatası verir; temizleyelim.
            try {
                YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
            } catch (Throwable ignored) {
                // yoksay
            }

            YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req, PROCESS_ID, callback);

            if (cancelRequested) {
                finish(false, getString(R.string.cancel_done));
                return;
            }

            List<String> produced = newCreatedFiles(dir, before, startTime);
            int leftovers = Storage.cleanupLeftovers(dir);

            if (produced.isEmpty()) {
                appendLog("UYARI: beklenen dosya bulunamadı. yt-dlp çıktısı:");
                appendLog(truncate(resp != null ? resp.getOut() : "", 1500));
                finish(false, "İndirme tamamlandı ancak dosya bulunamadı.");
                return;
            }

            if (leftovers > 0) {
                appendLog(leftovers + " artık dosya temizlendi.");
            }

            boolean publish = Storage.needsPublishing(this, dir);
            StringBuilder sb = new StringBuilder();
            for (String name : produced) {
                File f = new File(dir, name);
                if (publish) {
                    if (Storage.publish(this, f)) {
                        appendLog("Kaydedildi: Download/VideoIndirici/" + name);
                    } else {
                        appendLog("Klasöre kaydedildi: " + name);
                        Storage.indexFile(this, f);
                    }
                } else {
                    // Doğrudan genel klasöre yazıldı: Medya Deposu'na bildir,
                    // yoksa Galeri bu dosyayı göstermez.
                    Storage.indexFile(this, f);
                    appendLog("İndirildi: " + name);
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(name);
            }

            finish(true, "Tamamlandı (" + produced.size() + " dosya): " + sb);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String cancelled = getString(R.string.cancel_done);
            appendLog(cancelled);
            finish(false, cancelled);
        } catch (Throwable t) {
            String raw = t.getMessage();
            if (raw == null || raw.trim().isEmpty()) {
                raw = String.valueOf(t);
            }
            // İptal edildiğinde süreç öldürülür; yt-dlp bunu hata olarak bildirir.
            if (cancelRequested) {
                String cancelled = getString(R.string.cancel_done);
                appendLog(cancelled);
                finish(false, cancelled);
            } else {
                String errMsg = "HATA: " + firstLines(raw, 6);
                appendLog(errMsg);
                String hint = cookieHint(raw);
                if (!hint.isEmpty()) {
                    appendLog(hint);
                    finish(false, errMsg + "\n" + hint);
                } else {
                    finish(false, errMsg);
                }
            }
        } finally {
            running = false;
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.cancel(NOTIF_ID);
                }
            } catch (Exception ignored) {
                // yoksay
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    private final Function3<Float, Long, String, Unit> callback =
            new Function3<Float, Long, String, Unit>() {
                @Override
                public Unit invoke(Float progress, Long eta, String line) {
                    if (line == null) {
                        return Unit.INSTANCE;
                    }
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        return Unit.INSTANCE;
                    }

                    // İlerleme parçacıkları (yt-dlp \r ile çok sık yazar) —
                    // günlüğe değil, durum çubuğuna gider.
                    boolean isProgressFragment = trimmed.contains("ETA")
                            || trimmed.startsWith("size=");

                    if (!isProgressFragment) {
                        appendLog(trimmed);
                    }

                    int pct = (progress == null || progress < 0) ? -1 : Math.round(progress);
                    publishUi(pct, isProgressFragment ? trimmed : lastStatus);
                    return Unit.INSTANCE;
                }
            };

    private void publishUi(final int percent, final String status) {
        long now = System.currentTimeMillis();
        if (percent >= 0) {
            lastPercent = percent;
        }
        if (status != null) {
            lastStatus = status;
        }
        // Bildirim/güncelleme sıklığını sınırla (yt-dlp saniyede onlarca satır yazar)
        if (now - lastUiUpdate < 500 && percent >= 0 && percent != 100 && percent != lastNotifPercent) {
            return;
        }
        lastUiUpdate = now;

        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                boolean showPct = percent >= 0;
                nm.notify(NOTIF_ID, buildNotification(
                        showPct ? (percent + "% — " + shortText(lastStatus)) : shortText(lastStatus),
                        percent));
                lastNotifPercent = percent;
            }
        } catch (Exception ignored) {
            // yoksay
        }

        final Listener l = listener;
        if (l == null) {
            return;
        }
        final String st = lastStatus;
        final int p = percent;
        main.post(() -> {
            if (p >= 0) {
                l.onProgress(p);
            }
            l.onStatus(st);
        });
    }

    private void appendLog(final String line) {
        synchronized (logBuffer) {
            if (logBuffer.length() > 60000) {
                logBuffer.delete(0, 30000);
            }
            logBuffer.append(line).append('\n');
        }
        final Listener l = listener;
        if (l == null) {
            return;
        }
        main.post(() -> l.onLog(line));
    }

    private void finish(final boolean success, final String message) {
        lastStatus = message;
        if (success) {
            lastPercent = 100;
        }
        final Listener l = listener;
        if (l == null) {
            return;
        }
        main.post(() -> l.onFinished(success, message));
    }

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------

    private static Set<String> snapshot(File dir) {
        Set<String> names = new HashSet<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    names.add(f.getName());
                }
            }
        }
        return names;
    }

    private static List<String> newCreatedFiles(File dir, Set<String> before, long startTime) {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean isMedia = lower.endsWith(".mp4") || lower.endsWith(".mp3")
                    || lower.endsWith(".m4a") || lower.endsWith(".webm")
                    || lower.endsWith(".mkv") || lower.endsWith(".opus")
                    || lower.endsWith(".ogg") || lower.endsWith(".wav");
            if (!isMedia) {
                continue;
            }
            boolean isNew = !before.contains(name);
            boolean isRewritten = f.lastModified() >= startTime;
            if (isNew || isRewritten) {
                out.add(name);
            }
        }
        return out;
    }

    private static String shortText(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 70 ? (t.substring(0, 67) + "…") : t;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * yt-dlp'nin hatası giriş/kısıtlama kaynaklıysa kullanıcıya çerez
     * önerisini Türkçe olarak ekler.
     *
     * Örnek: "[Instagram] ... This content isn't available to everyone:
     * It can't be seen by certain audiences."
     */
    private static String cookieHint(String raw) {
        String l = raw.toLowerCase(Locale.ROOT);
        boolean needsCookies =
                l.contains("cookie")
                        || l.contains("log in") || l.contains("login")
                        || l.contains("sign in") || l.contains("signin")
                        || l.contains("isn't available to everyone")
                        || l.contains("not available to everyone")
                        || l.contains("age-restricted") || l.contains("age restricted")
                        || l.contains("restricted to") || l.contains("private")
                        || l.contains("authenticated") || l.contains("password");
        if (!needsCookies) {
            return "";
        }
        return "ÖNERİ: Bu içerik giriş (login) istiyor ya da belirli kitleyle sınırlı. "
                + "Aşağıdaki \"Tarayıcı çerezlerini kullan (cookies.txt)\" kutucuğunu "
                + "işaretleyin ve giriş yapmış olduğunuz tarayıcıdan dışa aktardığınız "
                + "cookies.txt dosyasını seçin. (Menü → Çerez dosyası seç…)";
    }

    private static String firstLines(String s, int max) {        String[] lines = s.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
            if (++n >= max) {
                break;
            }
        }
        return sb.toString();
    }
}
