package dev.videoindirici;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Desteklenen platformların URL'lerini tanır.
 *
 * Bu sınıf, masaüstü sürümündeki main.py içindeki URL yardımcılarının
 * birebir Java portudur (VIDEO_PATTERNS / CHANNEL_PATTERNS /
 * detect_site / is_video_url / is_channel_url / profile_platform).
 */
public final class UrlDetector {

    private UrlDetector() {
    }

    /** Site anahtarı -> arayüzde görünen ad. */
    public static final Map<String, String> SITE_NAMES = new HashMap<>();

    static {
        SITE_NAMES.put("youtube", "YouTube");
        SITE_NAMES.put("instagram", "Instagram");
        SITE_NAMES.put("tiktok", "TikTok");
        SITE_NAMES.put("facebook", "Facebook");
    }

    /** Tek video (tekil içerik) URL desenleri. */
    private static final Map<String, Pattern> VIDEO_PATTERNS = new HashMap<>();

    static {
        VIDEO_PATTERNS.put("youtube",
                Pattern.compile("youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/(?:shorts|live|embed)/"));

        VIDEO_PATTERNS.put("instagram",
                Pattern.compile("instagram\\.com/(?:p|reel|reels|tv|stories)/"));

        VIDEO_PATTERNS.put("tiktok",
                Pattern.compile("tiktok\\.com/@[\\w.-]+/video/\\d+|(?:vm|vt)\\.tiktok\\.com/"));

        VIDEO_PATTERNS.put("facebook",
                Pattern.compile("facebook\\.com/[\\w.-]+/videos/\\d+"
                        + "|facebook\\.com/watch/?\\?v="
                        + "|facebook\\.com/reel/\\d+"
                        + "|facebook\\.com/share/[vrl]/"
                        + "|facebook\\.com/(?:story|permalink|photo)\\.php"
                        + "|fb\\.watch/"));
    }

    /** Profil / kanal (toplu indirilecek) URL desenleri — şimdilik yalnızca YouTube. */
    private static final Pattern CHANNEL_PATTERNS = Pattern.compile(
            "youtube\\.com/@[\\w.-]+(?:/[A-Za-z_]+)?/?$"
                    + "|youtube\\.com/(?:channel|user|c)/[\\w.-]+(?:/[A-Za-z_]+)?/?$");

    /** URL'nin hangi platforma ait olduğunu döndürür ("other" olabilir). */
    public static String detectSite(String url) {
        String u = lower(url);
        if (u.contains("youtube.com") || u.contains("youtu.be")) {
            return "youtube";
        }
        if (u.contains("instagram.com")) {
            return "instagram";
        }
        if (u.contains("tiktok.com")) {
            return "tiktok";
        }
        if (u.contains("facebook.com") || u.contains("fb.watch") || u.contains("fb.com")) {
            return "facebook";
        }
        return "other";
    }

    /** Tek video URL'si mi? (desteklenen 4 platform) */
    public static boolean isVideoUrl(String url) {
        if (url == null) {
            return false;
        }
        Pattern p = VIDEO_PATTERNS.get(detectSite(url));
        return p != null && p.matcher(lower(url)).find();
    }

    /** Kanal (tüm videolar) URL'si mi? */
    public static boolean isChannelUrl(String url) {
        if (url == null || !"youtube".equals(detectSite(url))) {
            return false;
        }
        return CHANNEL_PATTERNS.matcher(url).find();
    }

    /**
     * YouTube dışı bir profil/sayfa URL'si ise platform adını döndürür.
     *
     * Bu profillerin "tüm videoları" çekilemez (giriş ve platform kısıtları);
     * kullanıcıya dürüst bir açıklama göstermek için kullanılır.
     */
    public static String profilePlatform(String url) {
        if (url == null) {
            return null;
        }
        String site = detectSite(url);
        if ("other".equals(site) || "youtube".equals(site) || isVideoUrl(url)) {
            return null;
        }

        String bare = url.replaceAll("[?#].*$", "").replaceAll("/+$", "");

        if ("instagram".equals(site) && bare.matches("https?://(?:www\\.)?instagram\\.com/[\\w.]+")) {
            return SITE_NAMES.get(site);
        }
        if ("tiktok".equals(site) && bare.matches("https?://(?:www\\.)?tiktok\\.com/@[\\w.-]+")) {
            return SITE_NAMES.get(site);
        }
        if ("facebook".equals(site)
                && bare.matches("https?://(?:www\\.|m\\.)?facebook\\.com/[\\w.-]+")) {
            return SITE_NAMES.get(site);
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
