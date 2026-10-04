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
    }
}
