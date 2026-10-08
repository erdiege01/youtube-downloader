# Video İndirici 🎬

Windows ve **Android** için video indirme programı. **YouTube, Instagram,
TikTok ve Facebook** bağlantılarından video veya MP3 indirir.

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

## Android (telefon ve tablet)

Windows sürümünün birebir aynısı, uygulama olarak. Kaynak kodu
[`VideoIndirici/`](VideoIndirici/) klasöründedir.

**Kurulum:**

1. Yayın sayfasındaki APK'lardan **cihazınıza uygun olanı** indirin:
   - `VideoIndirici-arm64-v8a.apk` — 2017'den sonra çıkan telefon/tabletlerin
     neredeyse tamamı (bununla başlayın)
   - `VideoIndirici-armeabi-v7a.apk` — eski cihazlar
2. Dosyaya dokunun → **Kur**. "Bilinmeyen uygulama" izni isteyecektir
   (Ayarlar → Uygulamalar → Özel erişim → Bilinmeyen uygulamaları yükle).
3. İndirilen dosyalar `Download/VideoIndirici` klasörüne düşer.

**Özellikler:** tek video, YouTube kanalının tümü, MP3, kapak fotoğrafı gömme,
kalite seçimi, iptal, paylaş menüsünden link gönderme, otomatik güncelleme.

**Çerez (Facebook/Instagram girişi):** Android'de tarayıcı çerezlerine doğrudan
erişim yoktur. Kutucuğu işaretleyip tarayıcınızdan dışa aktardığınız
`cookies.txt` dosyasını seçin (tarayıcıya "Get cookies.txt" uzantısı kurarak
veya masaüstünden alıp telefona atarak).

**Test kipi** (adb):

```powershell
adb shell am start -n dev.videoindirici/.MainActivity --ez test true --es test_urls "<bağlantı>"
adb shell am start -n dev.videoindirici/.MainActivity --ez checkupdate true
```

Sonuç `Download/VideoIndirici/test_sonuc.txt` dosyasına yazılır.

**Derleme:**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew.bat assembleRelease
```

Çıktı (derleme çıktıları OneDrive yerine yerel diske yönlendirildiği için):
`C:\tools\videindirici-build\app\outputs\apk\release\`

Bu iki APK'yi paket klasörüne kopyalayıp yeniden adlandırın:

```powershell
$rel = "C:\tools\videindirici-build\app\outputs\apk\release"
Copy-Item "$rel\app-arm64-v8a-release.apk"   "dist\VideoIndirici\VideoIndirici-arm64-v8a.apk" -Force
Copy-Item "$rel\app-armeabi-v7a-release.apk" "dist\VideoIndirici\VideoIndirici-armeabi-v7a.apk" -Force
```

İmza anahtarı `VideoIndirici\keystore\videoindirici.keystore` — **kaybedilirse
kurulu uygulama güncellenemez**, yedekleyin (depoya eklenmez).

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

Tüm dağıtım dosyaları **tek klasörde** durur — `dist\VideoIndirici\`:

```
dist\VideoIndirici\
├── VideoIndirici.exe                  Windows
├── ffmpeg\bin\ffmpeg.exe              Windows (zorunlu)
├── VideoIndirici-arm64-v8a.apk        Android — yeni cihazlar
├── VideoIndirici-armeabi-v7a.apk      Android — eski cihazlar
├── BENIOKU.txt
└── KURULUM.txt
```

`dist\VideoIndirici.zip` bu klasörün **APK'lar hariç** hâlidir (Windows
kullanıcısının indirmesi gereken ~64 MB). ZIP'i `python make_zip.py` ile
üretin — PowerShell 5.1 betiği ANSI okuduğu için Türkçe yol bozuluyor.

1. `updater.py` içindeki `CURRENT_VERSION` değerini artırın (Android sürümüyle
   aynı tutun: `VideoIndirici\app\build.gradle` → `appVersionName`)
2. Windows: `python build_exe.py`, sonra exe'yi `dist\VideoIndirici\` içine kopyalayın
3. Android: `cd VideoIndirici && .\gradlew.bat assembleRelease` (yukarıdaki kopya adımı)
4. `python make_zip.py`
5. Yeni sürümü yayınlayın:

```powershell
gh release create v1.6.0 --repo erdiege01/youtube-downloader --title "v1.6.0" --notes "..." `
  dist\VideoIndirici.exe dist\VideoIndirici.zip `
  dist\VideoIndirici\VideoIndirici-arm64-v8a.apk `
  dist\VideoIndirici\VideoIndirici-armeabi-v7a.apk
```

> Her release **hem exe hem APK içermelidir**: Windows güncelleme denetimi
> `releases/latest`'i kullanır, Android de aynı etiketi. APK'sız bir release
> yayınlanırsa Windows kullanıcılarının indirme bağlantısı bozulur.

Kullanıcılar bir sonraki açılışta güncellemeyi otomatik alır.

> `updater.py` içindeki `ASSET_NAME` değeri release'e eklenen exe dosya
> adıyla **aynı** olmalıdır.

## Lisans

MIT — bkz. [LICENSE](LICENSE).

`ffmpeg\bin\ffmpeg.exe` [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) tarafından
derlenen GPL sürümüdür ve FFmpeg'in GPL koşullarına tabidir.

**Android uygulaması** [youtubedl-android](https://github.com/yausername/youtubedl-android)
kütüphanesini (GPL-3.0) kullanır; bu nedenle Android derlemesi GPL-3.0
koşullarına tabidir. Windows derlemesi MIT'dir.
