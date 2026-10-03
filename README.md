# YouTube İndirici 🎬

Windows için tek dosyalık YouTube indirme programı. Tek video, bir kanalın
**tüm** videoları ve MP3 (sadece ses) indirmeyi destekler.

## Özellikler

- **Tek video indirme** — `watch?v=`, `youtu.be/`, `shorts/` bağlantıları
- **Kanalın tüm videoları** — `@kanal/videos` bağlantısını yapıştırın, hepsi sırayla iner
- **MP3 indirme** — "MP3 (Sadece Ses)" formatını seçin
- **Kalite seçimi** — En İyi / 1080p / 720p / 480p / 360p
- **İptal butonu** — devam eden indirmeyi durdurur, tamamlananlar silinmez
- **Ses kontrolü** — birleştirme başarısız olursa program açıkça uyarır
- **Otomatik güncelleme** — her açılışta GitHub'daki son sürümü kontrol eder

## Kurulum

1. [`YouTubeIndirici.zip`](https://github.com/erdiege01/youtube-downloader/releases) dosyasını indirin
2. Herhangi bir klasöre çıkartın
3. `YouTubeDownloader.exe` dosyasına çift tıklayın

Klasör yapısı bozulmamalıdır:

```
YouTubeIndirici\
  ├── YouTubeDownloader.exe
  ├── BENIOKU.txt
  └── ffmpeg\bin\ffmpeg.exe
```

**Gereksinimler:** Windows 10/11 (64 bit) ve internet. Python, Node.js veya
FFmpeg kurulumu gerekmez — hepsi paketle birlikte gelir.

## Sorun Giderme

Programı komut satırından test edebilirsiniz:

```powershell
YouTubeDownloader.exe --test     # indirmeyi deneyip sonucu yazar
YouTubeDownloader.exe --diag     # ortam bilgisini yazar
```

Sonuçlar `YouTubeIndirici_test.txt` ve `YouTubeIndirici_diagnostik.txt`
dosyalarına yazılır.

İlk açılışta Windows "Bilinmeyen yayıncı" uyarısı verirse:
**Daha fazla bilgi → Yine de çalıştır**.

## Teknik

- **Python 3** + [yt-dlp](https://github.com/yt-dlp/yt-dlp) (indirme motoru)
- **tkinter** (arayüz)
- **FFmpeg** (video+ses birleştirme, MP3 dönüşümü)
- **PyInstaller** (tek dosyalık `.exe` derlemesi)

### Sürüm yayınlama

1. `updater.py` içindeki `CURRENT_VERSION` değerini artırın
2. `python build_exe.py` çalıştırın
3. Yeni sürümü yayınlayın:

```powershell
gh release create v1.2.0 --repo erdiege01/youtube-downloader --title "v1.2.0" --notes "..." dist\YouTubeDownloader.exe
```

Kullanıcılar bir sonraki açılışta güncellemeyi otomatik alır.

## Lisans

MIT — bkz. [LICENSE](LICENSE).

`ffmpeg\bin\ffmpeg.exe` [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) tarafından
derlenen GPL sürümüdür ve FFmpeg'in GPL koşullarına tabidir.
