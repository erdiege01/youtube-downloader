package dev.videoindirici;

import android.app.Application;
import android.util.Log;

/**
 * Uygulama açılır açılmaz yt-dlp motorunu hazırlar (Python sıkıştırması
 * ilk açılışta birkaç saniye sürer).
 */
public class App extends Application {

    private static final String TAG = "VideoIndirici";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Video İndirici başlatılıyor");
        Engine.initAsync(this);
        indexExistingFiles();
    }

    /**
     * Daha önce indirilen dosyaları Medya Deposu'na tarar.
     *
     * <p>Uygulama dosyayı doğrudan dosya yoluyla yazdığından Android bunu her
     * zaman kendiliğinden indekslemez; Galeri oynatılabilir videoyu böylece
     * görür. Sessizce çalışır, açılışı geciktirmez.
     */
    private void indexExistingFiles() {
        new Thread(() -> {
            try {
                int n = Storage.indexAll(this, Engine.resolveOutputDir(this));
                Log.i(TAG, n + " dosya Medya Deposu'na tarandı");
            } catch (Throwable t) {
                Log.w(TAG, "tarama hatası: " + t.getMessage());
            }
        }, "videindirici-index").start();
    }
}
