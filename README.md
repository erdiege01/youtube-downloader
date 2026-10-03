# Video İndirici 🎬

Windows için tek dosyalık video indirme programı. **YouTube, Instagram, TikTok
ve Facebook** bağlantılarından video veya MP3 indirir.

## Platformlar

| Platform | Tek video | Kanalın tümü |
|---|---|---|
| YouTube | ✅ | ✅ |
| Instagram | ✅ (reel / gönderi) | — (giriş ister) |
| TikTok | ✅ (tekil + `vm.tiktok.com`) | — (platform kısıtı) |
| Facebook | ✅ (`watch`, `/videos/`, `fb.watch`) | — (giriş ister) |

> **Toplu indirme** yalnızca YouTube kanallarında çalışır. Diğer platformların
> profilleri giriş (login) ve istek limiti kısıtları nedeniyle toplu olarak
> çekilemez; program bunu açıkça söyler ve tek tek link önerir.

## Özellikler

- **Tek video indirme** — dört platformdan bağlantı yapıştırın
- **YouTube kanalının tüm videoları** — `@kanal/videos` bağlantısı
- **MP3 indirme** — "MP3 (Sadece Ses)" formatını seçin
- **Kalite seçimi** — En İyi / 1080p / 720p / 480p / 360p
- **Kapak fotoğrafı** — MP4 ve MP3 dosyasının içine YouTube/Instagram kapak
  fotoğrafı gömülür (oynatıcıda albüm kapağı olarak görünür)
- **Tarayıcı çerezleri** — Facebook/Instagram'ın giriş isteyen içerikleri için
  kutucuğu işaretleyin; program tarayıcınızdaki (Edge/Chrome/Firefox…) çerezleri
  kullanır
- **İptal butonu** — devam eden indirmeyi durdurur, tamamlananlar silinmez
- **Ses kontrolü** — birleştirme başarısız olursa program açıkça uyarır
- **Otomatik güncelleme** — her açılışta GitHub'daki son sürümü kontrol eder

## Kurulum

1. [`VideoIndirici.zip`](https://github.com/erdiege01/youtube-downloader/releases)
   dosyasını indirin
2. Herhangi bir klasöre çıkartın
3. `VideoIndirici.exe` dosyasına çift tıklayın

Klasör yapısı bozulmamalıdır:

```
VideoIndirici\
  ├── VideoIndirici.exe
  ├── BENIOKU.txt
  └── ffmpeg\bin\ffmpeg.exe
```

**Gereksinimler:** Windows 10/11 (64 bit) ve internet. Python, Node.js veya
FFmpeg kurulumu gerekmez — hepsi paketle birlikte gelir.

## Sorun Giderme

Programı komut satırından test edebilirsiniz:

```powershell
VideoIndirici.exe --test     # indirmeyi deneyip sonucu yazar
VideoIndirici.exe --diag     # ortam bilgisini yazar
```

Sonuçlar `VideoIndirici_test.txt` ve `VideoIndirici_diagnostik.txt`
dosyalarına yazılır.

**Facebook/Instagram "giriş yap" hatası verirse:** programdaki
*"Tarayıcı çerezlerini kullan"* kutucuğunu işaretleyin ve o platforma
tarayıcınızdan giriş yapmış olun.

İlk açılışta Windows "Bilinmeyen yayıncı" uyarısı verirse:
**Daha fazla bilgi → Yine de çalıştır**.

## Teknik

- **Python 3** + [yt-dlp](https://github.com/yt-dlp/yt-dlp) (indirme motoru)
- **curl_cffi** — TikTok/Instagram bot korumasını aşmak için gerekli (impersonation)
- **mutagen** — MP4/MP3 içine kapak fotoğrafı gömme
- **tkinter** (arayüz)
- **FFmpeg** (video+ses birleştirme, MP3 dönüşümü)
- **PyInstaller** (tek dosyalık `.exe` derlemesi)

### Sürüm yayınlama

1. `updater.py` içindeki `CURRENT_VERSION` değerini artırın
2. `python build_exe.py` çalıştırın
3. Yeni sürümü yayınlayın:

```powershell
gh release create v1.3.0 --repo erdiege01/youtube-downloader --title "v1.3.0" --notes "..." dist\VideoIndirici.exe dist\VideoIndirici.zip
```

Kullanıcılar bir sonraki açılışta güncellemeyi otomatik alır.

> `updater.py` içindeki `ASSET_NAME` değeri release'e eklenen exe dosya
> adıyla **aynı** olmalıdır.

## Lisans

MIT — bkz. [LICENSE](LICENSE).

`ffmpeg\bin\ffmpeg.exe` [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) tarafından
derlenen GPL sürümüdür ve FFmpeg'in GPL koşullarına tabidir.
