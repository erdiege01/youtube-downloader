package dev.videoindirici;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GitHub Releases üzerinden kendini günceller.
 *
 * Masaüstü sürümüyle aynı depo kullanılır; masaüstü .exe, Android .apk
 * yayınlar. İlgili varlık cihazın mimarisine göre seçilir
 * (arm64-v8a → armeabi-v7a → universal).
 */
public final class Updater {

    private static final String TAG = "VideoIndirici";

    /** updater.py içindeki GITHUB_REPO ile AYNI kalmalı. */
    public static final String REPO = "erdiege01/youtube-downloader";

    public interface Callback {
        void onUpdateAvailable(String latestVersion, String downloadUrl);

        void onUpToDate(String currentVersion);

        void onError(String message);

        /** Yüzde değişimi; -1 = bilinmiyor. */
        void onDownloadProgress(int percent);

        void onDownloadFinished(File apk);
    }

    private Updater() {
    }

    public static String currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Sürümü arka planda kontrol eder. */
    public static void check(final Context ctx, final Callback cb) {
        new Thread(() -> {
            try {
                String current = currentVersion(ctx);
                Release rel = fetchLatest();
                if (rel == null) {
                    cb.onError("Sürüm bilgisi alınamadı");
                    return;
                }
                if (!isNewer(rel.version, current)) {
                    cb.onUpToDate(current);
                    return;
                }
                if (rel.apkUrl == null) {
                    cb.onError("Bu cihaz için uygun APK paketi yayınlanmış değil ("
                            + String.join(", ", Build.SUPPORTED_ABIS) + ")");
                    return;
                }
                cb.onUpdateAvailable(rel.version, rel.apkUrl);
            } catch (Exception e) {
                Log.e(TAG, "sürüm kontrolü hatası", e);
                cb.onError(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            }
        }, "updater-check").start();
    }

    /** APK'yı indirir; bittiğinde {@link Callback#onDownloadFinished}. */
    public static void download(final Context ctx, final String url, final Callback cb) {
        new Thread(() -> {
            File target = new File(ctx.getCacheDir(), "updates");
            if (!target.exists() && !target.mkdirs()) {
                cb.onError("Önbellek klasörü oluşturulamadı");
                return;
            }
            File apk = new File(target, "guncelleme.apk");
            if (apk.exists() && !apk.delete()) {
                cb.onError("Eski güncelleme dosyası silinemedi");
                return;
            }

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    cb.onError("Sunucu yanıtı: HTTP " + code);
                    return;
                }

                long total = conn.getContentLength();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[128 * 1024];
                    long done = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                cb.onDownloadProgress(pct);
                            }
                        }
                    }
                }

                if (apk.length() < 10_000) {
                    cb.onError("İndirilen dosya çok küçük (" + apk.length() + " bayt)");
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    return;
                }
                cb.onDownloadFinished(apk);
            } catch (Exception e) {
                Log.e(TAG, "güncelleme indirme hatası", e);
                cb.onError(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, "updater-download").start();
    }

    /**
     * İndirilen APK'nın kurulum ekranını açar.
     *
     * @return false = bilinmeyen kaynak izni verilmemiş (ayar ekranı açılmalı)
     */
    public static boolean install(Activity act, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    act, act.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "kurulum ekranı açılamadı", e);
            return false;
        }
    }

    /** Bilinmeyen kaynak izni ekranını açar. */
    public static void openInstallPermissionScreen(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "ayar ekranı açılamadı", e);
        }
    }

    // ------------------------------------------------------------------
    // GitHub
    // ------------------------------------------------------------------

    private static final class Release {
        String version;
        String tag;
        String apkUrl;
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection conn = null;
        try {
            String endpoint = "https://api.github.com/repos/" + REPO + "/releases/latest";
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "VideoIndirici-Android");

            int code = conn.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code < 200 || code >= 400) {
                throw new Exception("HTTP " + code);
            }

            String body = readAll(conn.getInputStream());
            JSONObject root = new JSONObject(body);

            Release r = new Release();
            r.tag = root.optString("tag_name", "");
            r.version = stripV(r.tag);

            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                List<String> names = new ArrayList<>();
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        names.add(name);
                    }
                }
                String best = pickAsset(names);
                if (best != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if (best.equals(a.optString("name", ""))) {
                            r.apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
            }
            return r;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Cihaza uyan APK'yı seçer: desteklenen ABI'ler sırayla. Bulunamazsa null. */
    static String pickAsset(List<String> apkNames) {
        if (apkNames == null || apkNames.isEmpty()) {
            return null;
        }
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            for (String n : apkNames) {
                String lower = n.toLowerCase(Locale.ROOT);
                if (lower.contains(abi.toLowerCase(Locale.ROOT))) {
                    return n;
                }
            }
        }
        // Uyumsuz mimari: "universal" paket yayınlanmadığından güncelleme yok.
        return null;
    }

    static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /** "1.4.1" > "1.4.0" gibi sayısal karşılaştırma. */
    static boolean isNewer(String candidate, String current) {
        int a = compareVersions(candidate, current);
        return a > 0;
    }

    static int compareVersions(String a, String b) {
        String[] x = stripV(a).split("[.+\\-]");
        String[] y = stripV(b).split("[.+\\-]");
        int len = Math.max(x.length, y.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < x.length ? toInt(x[i]) : 0;
            int n2 = i < y.length ? toInt(y[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    // Ekran izni kontrolü için
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ctx.getPackageManager().canRequestPackageInstalls();
        }
        return ctx.checkSelfPermission(Manifest.permission.REQUEST_INSTALL_PACKAGES)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static File pendingApk(Context ctx) {
        File f = new File(ctx.getCacheDir(), "updates/guncelleme.apk");
        return f.isFile() ? f : null;
    }
}
