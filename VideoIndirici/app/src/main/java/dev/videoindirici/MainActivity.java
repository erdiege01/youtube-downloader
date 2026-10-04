package dev.videoindirici;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DownloadService.Listener {

    private TextInputEditText urlInput;
    private TextView urlTypeLabel;
    private RadioGroup formatGroup;
    private Spinner qualitySpinner;
    private MaterialCheckBox cookiesCheck;
    private MaterialButton downloadButton;
    private MaterialButton cancelButton;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView dirText;
    private TextView logText;
    private NestedScrollView scrollArea;
    private View cookiesRow;

    private AlertDialog updateDialog;
    private File pendingInstall;

    // ------------------------------------------------------------------
    // İzinler
    // ------------------------------------------------------------------

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> refreshOutputDir());

    private final ActivityResultLauncher<String[]> cookiePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                String path = Cookies.save(this, uri);
                if (path != null) {
                    cookiesCheck.setChecked(true);
                    log("Çerez dosyası kaydedildi: " + new File(path).getName());
                    toast(getString(R.string.cookies_saved, new File(path).getName()));
                } else {
                    toast("Çerez dosyası okunamadı");
                }
            });

    private final ActivityResultLauncher<Intent> installPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                File apk = pendingInstall;
                pendingInstall = null;
                if (apk != null && Updater.canInstall(this)) {
                    Updater.install(this, apk);
                } else if (apk != null) {
                    toast("Kurulum izni verilmedi");
                }
            });

    // ------------------------------------------------------------------
    // Yaşam döngüsü
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        urlInput = findViewById(R.id.urlInput);
        urlTypeLabel = findViewById(R.id.urlTypeLabel);
        formatGroup = findViewById(R.id.formatGroup);
        qualitySpinner = findViewById(R.id.qualitySpinner);
        cookiesCheck = findViewById(R.id.cookiesCheck);
        cookiesRow = cookiesCheck;
        downloadButton = findViewById(R.id.downloadButton);
        cancelButton = findViewById(R.id.cancelButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        dirText = findViewById(R.id.dirText);
        logText = findViewById(R.id.logText);
        scrollArea = findViewById(R.id.scrollArea);

        downloadButton.setOnClickListener(v -> startDownload());
        cancelButton.setOnClickListener(v -> DownloadService.cancel(this));
        findViewById(R.id.pasteButton).setOnClickListener(v -> pasteFromClipboard());

        urlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshUrlHint();
            }
        });

        DownloadService.listener = this;
        restoreLog();
        refreshOutputDir();
        refreshUrlHint();
        requestNeededPermissions();

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.listener = this;
        if (DownloadService.running) {
            setRunning(true);
            progressBar.setProgress(Math.max(0, DownloadService.lastPercent));
            statusText.setText(DownloadService.lastStatus);
        }
        File apk = pendingInstall;
        if (apk != null && Updater.canInstall(this)) {
            pendingInstall = null;
            Updater.install(this, apk);
        }
    }

    @Override
    protected void onDestroy() {
        if (DownloadService.listener == this) {
            DownloadService.listener = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // Menü
    // ------------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_check_update) {
            checkUpdate();
            return true;
        }
        if (id == R.id.action_update_ytdlp) {
            updateYtDlp();
            return true;
        }
        if (id == R.id.action_pick_cookies) {
            pickCookies();
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------
    // İndirme
    // ------------------------------------------------------------------

    private void startDownload() {
        String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (url.isEmpty()) {
            toast(getString(R.string.error_empty_url));
            return;
        }

        boolean video = UrlDetector.isVideoUrl(url);
        boolean channel = UrlDetector.isChannelUrl(url);

        if (!video && !channel) {
            String platform = UrlDetector.profilePlatform(url);
            if (platform != null) {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.error_profile_title, platform))
                        .setMessage(getString(R.string.error_profile_body, platform))
                        .setPositiveButton("Tamam", null)
                        .show();
            } else {
                new AlertDialog.Builder(this)
                        .setMessage(getString(R.string.error_invalid_url))
                        .setPositiveButton("Tamam", null)
                        .show();
            }
            return;
        }

        if (cookiesCheck.isChecked() && Cookies.path(this) == null) {
            toast(getString(R.string.cookies_missing));
            pickCookies();
            return;
        }

        if (DownloadService.running) {
            toast("Zaten bir indirme devam ediyor.");
            return;
        }

        String format = formatGroup.getCheckedRadioButtonId() == R.id.radioMp3 ? "mp3" : "mp4";
        String quality = qualitySpinner.getSelectedItem() != null
                ? String.valueOf(qualitySpinner.getSelectedItem())
                : "En İyi";
        String site = UrlDetector.detectSite(url);
        File outDir = Engine.resolveOutputDir(this);

        DownloadTask task = new DownloadTask(
                url, format, quality, channel, site,
                cookiesCheck.isChecked() ? Cookies.path(this) : null,
                outDir.getAbsolutePath());

        logOutputDir(outDir);
        setRunning(true);
        progressBar.setProgress(0);
        statusText.setText(R.string.status_running);
        DownloadService.start(this, task);
    }

    @Override
    public void onStatus(String text) {
        runUi(() -> statusText.setText(text));
    }

    @Override
    public void onProgress(int percent) {
        runUi(() -> progressBar.setProgress(Math.max(0, percent)));
    }

    @Override
    public void onLog(String line) {
        runUi(() -> appendLog(line));
    }

    @Override
    public void onFinished(boolean success, String message) {
        runUi(() -> {
            setRunning(false);
            statusText.setText(message);
            if (success) {
                progressBar.setProgress(100);
                appendLog("BAŞARILI: " + message);
            }
            // Hata mesajını servis zaten günlüğe yazdı; tekrar eklemiyoruz.
        });
    }

    private void setRunning(boolean running) {
        downloadButton.setEnabled(!running);
        cancelButton.setEnabled(running);
        urlInput.setEnabled(!running);
        formatGroup.setEnabled(!running);
        qualitySpinner.setEnabled(!running);
    }

    // ------------------------------------------------------------------
    // Arayüz yardımcıları
    // ------------------------------------------------------------------

    private void refreshUrlHint() {
        String url = urlInput.getText() != null ? urlInput.getText().toString().trim() : "";
        if (url.isEmpty()) {
            urlTypeLabel.setVisibility(View.GONE);
            return;
        }
        urlTypeLabel.setVisibility(View.VISIBLE);
        String site = UrlDetector.detectSite(url);

        if (UrlDetector.isChannelUrl(url)) {
            urlTypeLabel.setText(R.string.hint_channel);
            urlTypeLabel.setTextColor(getResources().getColor(R.color.brand, getTheme()));
            return;
        }
        String profile = UrlDetector.profilePlatform(url);
        if (profile != null) {
            urlTypeLabel.setText(getString(R.string.hint_profile, profile));
            urlTypeLabel.setTextColor(android.graphics.Color.parseColor("#C62828"));
            return;
        }
        if (UrlDetector.isVideoUrl(url)) {
            String name = UrlDetector.SITE_NAMES.get(site);
            urlTypeLabel.setText(getString(R.string.hint_video, name != null ? name : site));
            urlTypeLabel.setTextColor(getResources().getColor(R.color.brand, getTheme()));
            return;
        }
        urlTypeLabel.setText(R.string.error_invalid_url);
        urlTypeLabel.setTextColor(android.graphics.Color.parseColor("#C62828"));
    }

    private void restoreLog() {
        synchronized (DownloadService.logBuffer) {
            if (DownloadService.logBuffer.length() > 0) {
                logText.setText(DownloadService.logBuffer.toString());
                scrollToBottom();
            }
        }
    }

    private void appendLog(String line) {
        logText.append(line);
        logText.append("\n");
        scrollToBottom();
    }

    private void log(String line) {
        appendLog(line);
        synchronized (DownloadService.logBuffer) {
            DownloadService.logBuffer.append(line).append('\n');
        }
    }

    private void scrollToBottom() {
        scrollArea.post(() -> scrollArea.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshOutputDir() {
        File dir = Engine.resolveOutputDir(this);
        dirText.setText("Kayıt: " + dir.getAbsolutePath());
    }

    private void logOutputDir(File dir) {
        appendLog("Çıkış klasörü: " + dir.getAbsolutePath());
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            toast("Pano boş");
            return;
        }
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence text = item.getText();
        if (text == null && item.getUri() != null) {
            text = item.getUri().toString();
        }
        if (text == null || text.length() == 0) {
            toast("Panoda metin yok");
            return;
        }
        urlInput.setText(text.toString());
        urlInput.setSelection(urlInput.getText() != null ? urlInput.getText().length() : 0);
    }

    private void runUi(Runnable r) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        runOnUiThread(r);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------
    // İzinler
    // ------------------------------------------------------------------

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            permissionLauncher.launch(need.toArray(new String[0]));
        }
    }

    // ------------------------------------------------------------------
    // Çerez dosyası
    // ------------------------------------------------------------------

    private void pickCookies() {
        try {
            cookiePicker.launch(new String[]{"text/plain", "application/octet-stream", "*/*"});
        } catch (Exception e) {
            toast("Dosya seçici açılamadı: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Güncelleme
    // ------------------------------------------------------------------

    private void checkUpdate() {
        String current = Updater.currentVersion(this);
        toast("Sürüm kontrol ediliyor…");

        Updater.check(this, new Updater.Callback() {
            @Override
            public void onUpdateAvailable(String latestVersion, String downloadUrl) {
                runUi(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Yeni sürüm: " + latestVersion)
                        .setMessage(getString(R.string.update_available, latestVersion)
                                + "\n\nŞu anki sürüm: " + current)
                        .setPositiveButton("Güncelle", (d, w) ->
                                downloadUpdate(downloadUrl, latestVersion))
                        .setNegativeButton("Şimdi değil", null)
                        .show());
            }

            @Override
            public void onUpToDate(String version) {
                runUi(() -> toast(getString(R.string.update_none, version)));
            }

            @Override
            public void onError(String message) {
                runUi(() -> toast(getString(R.string.update_error, message)));
            }

            @Override
            public void onDownloadProgress(int percent) {
                runUi(() -> {
                    if (updateDialog != null && updateDialog.isShowing()) {
                        TextView tv = updateDialog.findViewById(android.R.id.message);
                        if (tv != null) {
                            tv.setText(getString(R.string.update_downloading, percent));
                        }
                    }
                });
            }

            @Override
            public void onDownloadFinished(File apk) {
                runUi(() -> {
                    if (!Updater.canInstall(MainActivity.this)) {
                        pendingInstall = apk;
                        toast("Kurulum için \"bilinmeyen uygulamalar\" izni gerekiyor");
                        Updater.openInstallPermissionScreen(MainActivity.this);
                        return;
                    }
                    toast(getString(R.string.update_ready));
                    Updater.install(MainActivity.this, apk);
                });
            }
        });
    }

    private void downloadUpdate(String url, String version) {
        updateDialog = new AlertDialog.Builder(this)
                .setTitle("Güncelleme")
                .setMessage(getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .setNegativeButton("İptal", (d, w) -> updateDialog = null)
                .show();

        Updater.download(this, url, new Updater.Callback() {
            @Override
            public void onUpdateAvailable(String v, String u) {
            }

            @Override
            public void onUpToDate(String v) {
            }

            @Override
            public void onError(String message) {
                runUi(() -> {
                    if (updateDialog != null) {
                        updateDialog.dismiss();
                        updateDialog = null;
                    }
                    toast(getString(R.string.update_error, message));
                });
            }

            @Override
            public void onDownloadProgress(int percent) {
                runUi(() -> {
                    if (updateDialog != null && updateDialog.isShowing()) {
                        TextView tv = updateDialog.findViewById(android.R.id.message);
                        if (tv != null) {
                            tv.setText(getString(R.string.update_downloading, percent));
                        }
                    }
                });
            }

            @Override
            public void onDownloadFinished(File apk) {
                runUi(() -> {
                    if (updateDialog != null) {
                        updateDialog.dismiss();
                        updateDialog = null;
                    }
                    if (!Updater.canInstall(MainActivity.this)) {
                        pendingInstall = apk;
                        Updater.openInstallPermissionScreen(MainActivity.this);
                        return;
                    }
                    toast(getString(R.string.update_ready));
                    Updater.install(MainActivity.this, apk);
                });
            }
        });
    }

    private void updateYtDlp() {
        toast("yt-dlp güncelleniyor…");
        new Thread(() -> {
            try {
                YoutubeDlUpdater.Result r = YoutubeDlUpdater.run(this);
                runUi(() -> toast(r.message));
                runUi(() -> log(r.message));
            } catch (Throwable t) {
                String m = "yt-dlp güncellenemedi: " + t.getMessage();
                runUi(() -> toast(m));
                runUi(() -> log(m));
            }
        }, "ytdlp-update").start();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, Updater.currentVersion(this)))
                .setPositiveButton("Tamam", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Paylaş / test kipi
    // ------------------------------------------------------------------

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        if (intent.getBooleanExtra("checkupdate", false)) {
            checkUpdate();
        }

        if (intent.getBooleanExtra("test", false)) {
            String spec = intent.getStringExtra("test_urls");
            String[] urls;
            if (spec != null && !spec.trim().isEmpty()) {
                urls = spec.split("\\|");
            } else {
                urls = new String[]{
                        "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                };
            }
            boolean cookies = intent.getBooleanExtra("cookies", false);
            log("Test kipi başlatıldı (" + urls.length + " bağlantı)");
            statusText.setText("Test çalışıyor…");
            TestRunner.run(this, urls, cookies, this::appendLog);
            return;
        }

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(intent.getType())) {
            String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (shared == null && intent.getClipData() != null
                    && intent.getClipData().getItemCount() > 0) {
                CharSequence t = intent.getClipData().getItemAt(0).getText();
                shared = t != null ? t.toString() : null;
            }
            if (shared != null && !shared.trim().isEmpty()) {
                urlInput.setText(shared.trim());
                urlInput.setSelection(shared.trim().length());
            }
        }
    }
}
