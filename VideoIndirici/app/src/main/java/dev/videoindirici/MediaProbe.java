package dev.videoindirici;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * İndirilen dosyanın içinde ses ve kapak fotoğrafı olup olmadığını kontrol
 * eder.
 *
 * Masaüstü sürümünde mutagen kullanılıyordu; Android'de bağımlılık
 * eklememek için dosyanın kendi atomları taranır:
 * MP4 → "covr" (kapak) ve "mp4a"/"soun" (ses), MP3 → "APIC" (kapak).
 */
public final class MediaProbe {

    private static final String TAG = "VideoIndirici";
    /** Büyük dosyalarda tarama sınırı (bayt). */
    private static final long SCAN_LIMIT = 96L * 1024 * 1024;

    private MediaProbe() {
    }

    public static boolean hasCover(File f) {
        String ext = ext(f);
        if (f == null || !f.isFile()) {
            return false;
        }
        if ("mp3".equals(ext)) {
            return contains(f, "APIC".getBytes(StandardCharsets.US_ASCII));
        }
        if ("mp4".equals(ext) || "m4a".equals(ext) || "m4v".equals(ext)) {
            return contains(f, "covr".getBytes(StandardCharsets.US_ASCII));
        }
        if ("webm".equals(ext) || "mkv".equals(ext)) {
            return contains(f, "attachments".getBytes(StandardCharsets.US_ASCII));
        }
        return false;
    }

    public static boolean hasAudio(File f) {
        if (f == null || !f.isFile() || f.length() == 0) {
            return false;
        }
        String ext = ext(f);

        if ("mp3".equals(ext)) {
            byte[] head = readHead(f, 4);
            if (head.length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                return true;
            }
            if (head.length >= 2
                    && (head[0] & 0xFF) == 0xFF
                    && (head[1] & 0xE0) == 0xE0) {
                return true;
            }
            // ID3 olmayan (veya dosyanın başında jenerik atom olan) durumda
            // tüm dosyada ses izi ara
            return contains(f, "mp4a".getBytes(StandardCharsets.US_ASCII));
        }

        if ("mp4".equals(ext) || "m4a".equals(ext) || "m4v".equals(ext)) {
            return contains(f, "mp4a".getBytes(StandardCharsets.US_ASCII))
                    || contains(f, "soun".getBytes(StandardCharsets.US_ASCII));
        }

        if ("webm".equals(ext) || "mkv".equals(ext)) {
            return contains(f, "A_OPUS".getBytes(StandardCharsets.US_ASCII))
                    || contains(f, "A_VORBIS".getBytes(StandardCharsets.US_ASCII))
                    || contains(f, "A_AAC".getBytes(StandardCharsets.US_ASCII));
        }

        // Bilinmeyen uzantı: en azından dosya dolu mu bak
        return f.length() > 1024;
    }

    private static String ext(File f) {
        if (f == null) {
            return "";
        }
        String n = f.getName().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1);
    }

    private static byte[] readHead(File f, int n) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[n];
            int read = raf.read(b);
            if (read <= 0) {
                return new byte[0];
            }
            if (read < n) {
                byte[] c = new byte[read];
                System.arraycopy(b, 0, c, 0, read);
                return c;
            }
            return b;
        } catch (Exception e) {
            Log.w(TAG, "dosya okunamadı: " + e.getMessage());
            return new byte[0];
        }
    }

    /** Dosyanın ilk SCAN_LIMIT baytında desen arar (parçalar halinde). */
    private static boolean contains(File f, byte[] pattern) {
        if (pattern.length == 0) {
            return false;
        }
        final int CHUNK = 256 * 1024;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = Math.min(raf.length(), SCAN_LIMIT);
            byte[] buf = new byte[CHUNK + pattern.length];
            long pos = 0;
            while (pos < len) {
                raf.seek(pos);
                int toRead = (int) Math.min(buf.length, len - pos);
                int read = raf.read(buf, 0, toRead);
                if (read <= 0) {
                    break;
                }
                if (indexOf(buf, read, pattern) >= 0) {
                    return true;
                }
                if (read < pattern.length) {
                    break;
                }
                // örtüşme payı: desen iki parça sınırında kalmasın
                pos += read - (pattern.length - 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "tarama hatası: " + e.getMessage());
        }
        return false;
    }

    private static int indexOf(byte[] haystack, int haystackLen, byte[] needle) {
        outer:
        for (int i = 0; i <= haystackLen - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
