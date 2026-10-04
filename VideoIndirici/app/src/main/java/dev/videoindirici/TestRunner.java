package dev.videoindirici;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Gizli test kipi: {@code adb shell am start -n dev.videoindirici/.MainActivity --ez test true}
 *
 * Gerçek indirmeleri yapar, dosyaların oluştuğunu doğrular ve sonucu hem
 * logcat'e hem de Download/VideoIndirici/test_sonuc.txt dosyasına yazar.
 * Masaüstü sürümdeki {@code --test} kipinin karşılığıdır.
 */
public final class TestRunner {

    private static final String TAG = "VideoIndirici";

    public interface Listener {
        void onLine(String line);
    }

    private TestRunner() {
    }

    public static void run(final Context ctx, final String[] urls, final boolean cookies,
                           final Listener listener) {
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int pass = 0;
            int fail = 0;

            line(listener, report, "=== Video İndirici Android testi ===");
            line(listener, report, "Cihaz: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            line(listener, report, "ABI: " + Arrays.toString(Build.SUPPORTED_ABIS));
            line(listener, report, "");

            if (!Engine.awaitReady(120_000)) {
                Throwable e = Engine.initError();
                line(listener, report, "MOTOR BASLATILAMADI: "
                        + (e != null ? String.valueOf(e) : "bilinmiyor"));
                write(ctx, report.toString());
                return;
            }
            line(listener, report, "Motor hazir.");
            line(listener, report, "");

            // yt-dlp periyodik olarak güncellenmezse platformlar engel koyar.
            line(listener, report, "yt-dlp guncelleniyor...");
            try {
                YoutubeDlUpdater.Result r = YoutubeDlUpdater.run(ctx);
                line(listener, report, r.message);
                line(listener, report, "yt-dlp surum: "
                        + com.yausername.youtubedl_android.YoutubeDL.getInstance()
                        .versionName(ctx));
            } catch (Throwable t) {
                line(listener, report, "yt-dlp guncelleme atlandi: " + t.getMessage());
            }
            line(listener, report, "");

            File outDir = Engine.resolveOutputDir(ctx);
            line(listener, report, "Cikis klasoru: " + outDir.getAbsolutePath());

            String cookiesPath = cookies ? Cookies.path(ctx) : null;
            if (cookies && cookiesPath == null) {
                line(listener, report, "UYARI: cookies.txt bulunamadi, cookiesiz devam ediliyor.");
            }
            line(listener, report, "");

            for (String raw : urls) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                raw = raw.trim();
                // Biçim eki: "bağlantı!mp3" → o bağlantı MP3 olarak indirilir.
                String urlPart = raw;
                String format = "mp4";
                int bang = raw.lastIndexOf('!');
                if (bang > 0 && bang > raw.indexOf("//") + 2) {
                    String suffix = raw.substring(bang + 1).trim().toLowerCase(Locale.ROOT);
                    if (suffix.equals("mp3") || suffix.equals("mp4")) {
                        format = suffix;
                        urlPart = raw.substring(0, bang);
                    }
                }
                boolean ok = testOne(ctx, urlPart.trim(), format, outDir, cookiesPath,
                        listener, report);
                if (ok) {
                    pass++;
                } else {
                    fail++;
                }
                line(listener, report, "");
            }

            line(listener, report, "================================");
            line(listener, report, "SONUC: " + (fail == 0 ? "TUM KONTROLLER GECTI" : "BASARISIZ"));
            line(listener, report, "Gecen: " + pass + "  Basarisiz: " + fail);

            String text = report.toString();
            write(ctx, text);
            line(listener, report, "Rapor: Download/VideoIndirici/test_sonuc.txt");
        }, "videoindirici-test").start();
    }

    private static boolean testOne(Context ctx, String url, String format, File outDir,
                                   String cookiesPath, Listener listener, StringBuilder report) {
        String site = UrlDetector.detectSite(url);
        line(listener, report, "--------------------------------");
        line(listener, report, "URL: " + url + "  [format: " + format + "]");
        line(listener, report, "Platform: " + site
                + " | tek video: " + UrlDetector.isVideoUrl(url)
                + " | kanal: " + UrlDetector.isChannelUrl(url)
                + " | profil: " + UrlDetector.profilePlatform(url));

        Set<String> before = snapshot(outDir);
        try {
            DownloadTask task = new DownloadTask(
                    url, format, "En İyi", UrlDetector.isChannelUrl(url),
                    site, cookiesPath, outDir.getAbsolutePath());

            YoutubeDLRequest req = Engine.buildRequest(task);
            YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req, "VideoIndiriciTest", null);

            Set<String> after = snapshot(outDir);
            after.removeAll(before);

            line(listener, report, "yt-dlp cikti (son kisim):");
            line(listener, report, indent(lastLines(resp.getOut(), 8)));

            if (after.isEmpty()) {
                line(listener, report, "HATA: dosya olusturulmadi");
                String err = resp.getErr();
                if (err != null && !err.trim().isEmpty()) {
                    line(listener, report, "stderr: " + lastLines(err, 6));
                }
                return false;
            }

            boolean allOk = true;
            for (String name : after) {
                if (!name.toLowerCase(Locale.ROOT).endsWith(".mp4")
                        && !name.toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                    continue;
                }
                File f = new File(outDir, name);
                long size = f.length();
                boolean hasCover = MediaProbe.hasCover(f);
                boolean hasAudio = MediaProbe.hasAudio(f);
                line(listener, report, "DOSYA: " + name + " (" + (size / 1024) + " KB)");
                line(listener, report, "  ses var: " + (hasAudio ? "EVET" : "HAYIR")
                        + " | kapak var: " + (hasCover ? "EVET" : "HAYIR"));
                if (size <= 0 || !hasAudio) {
                    allOk = false;
                }
                if (!hasCover) {
                    line(listener, report, "  UYARI: kapak gomulmedi");
                }
            }
            line(listener, report, allOk ? "  -> OK" : "  -> EKSIK");
            return allOk;

        } catch (Throwable t) {
            line(listener, report, "HATA: " + summarize(t));
            return false;
        }
    }

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

    private static void line(Listener l, StringBuilder sb, String s) {
        sb.append(s).append('\n');
        Log.i(TAG, s);
        if (l != null) {
            // Arayüz güncellemeleri yalnızca ana iş parçacığından yapılabilir.
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> l.onLine(s));
        }
    }

    private static void write(Context ctx, String text) {
        try {
            File dir = Engine.resolveOutputDir(ctx);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File out = new File(dir, "test_sonuc.txt");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "test raporu yazilamadi", e);
        }
    }

    private static String lastLines(String s, int n) {
        if (s == null) {
            return "";
        }
        String[] lines = s.split("\r?\n");
        int start = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i].trim()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String indent(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return "  " + s.replace("\n", "\n  ");
    }

    private static String summarize(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return String.valueOf(t);
        }
        String[] lines = m.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String l : lines) {
            String x = l.trim();
            if (x.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(x);
            if (++n >= 5) {
                break;
            }
        }
        return sb.toString();
    }
}
