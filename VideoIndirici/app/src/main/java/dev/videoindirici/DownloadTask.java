package dev.videoindirici;

import java.io.Serializable;

/** Tek bir indirme işleminin tüm ayarları. */
public final class DownloadTask implements Serializable {

    private static final long serialVersionUID = 1L;

    public final String url;
    /** "mp4" veya "mp3" */
    public final String format;
    /** "En İyi", "1080p", "720p", ... */
    public final String quality;
    /** YouTube kanalı/toplu indirme mi? */
    public final boolean channel;
    /** UrlDetector.detectSite() sonucu */
    public final String site;
    /** cookies.txt dosyasının gerçek yolu (yoksa null) */
    public final String cookiesPath;
    /** yt-dlp'nin dosya yazacağı klasör */
    public final String outputDir;

    public DownloadTask(String url, String format, String quality, boolean channel,
                        String site, String cookiesPath, String outputDir) {
        this.url = url;
        this.format = format;
        this.quality = quality;
        this.channel = channel;
        this.site = site;
        this.cookiesPath = cookiesPath;
        this.outputDir = outputDir;
    }

    public boolean isMp3() {
        return "mp3".equals(format);
    }

    public String siteName() {
        String n = UrlDetector.SITE_NAMES.get(site);
        return n != null ? n : "Video";
    }
}
