package dev.videoindirici;

import android.content.Context;

import com.yausername.youtubedl_android.YoutubeDL;

/**
 * Gömülü yt-dlp'yi güncelleyen yardımcı.
 *
 * yt-dlp sık sık güncellenir; platform engelleri genelde güncel sürümde
 * çözülür.
 */
public final class YoutubeDlUpdater {

    public static final class Result {
        public final String message;

        Result(String message) {
            this.message = message;
        }
    }

    private YoutubeDlUpdater() {
    }

    /** Arka planda çağrılmalıdır (ağ işlemi yapar). */
    public static Result run(Context ctx) throws Exception {
        if (!Engine.awaitReady(120_000)) {
            Throwable e = Engine.initError();
            return new Result("Motor hazırlanamadı: "
                    + (e != null ? String.valueOf(e.getMessage()) : "bilinmiyor"));
        }

        YoutubeDL.UpdateStatus status = YoutubeDL.getInstance()
                .updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE.INSTANCE);

        if (status == null) {
            return new Result("yt-dlp güncelleme sonucu alınamadı.");
        }
        switch (status) {
            case DONE:
                return new Result("yt-dlp güncellendi.");
            case ALREADY_UP_TO_DATE:
                return new Result("yt-dlp zaten güncel.");
            default:
                return new Result("yt-dlp güncelleme: " + status.name());
        }
    }
}
