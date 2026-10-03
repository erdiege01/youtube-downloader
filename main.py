"""
YouTube İndirici - Ana Program
Tek video, kanalın tüm videoları ve MP3 indirme desteği
"""
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from tkinter import (
    Tk, StringVar, filedialog, messagebox, ttk, scrolledtext, Menu
)

import yt_dlp
from yt_dlp import utils as ytdlp_utils

from updater import cleanup_old_builds, get_current_version


def _find_ffmpeg() -> str | None:
    """
    FFmpeg'i PATH'e güvenmeden bulur.

    Windows, yeni açılan süreçlere güncel PATH'i her zaman iletmez (özellikle
    masaüstünden çift tıklandığında). Bu yüzden bilinen klasörler de taranır;
    yoksa video+ses birleştirme (merge) sessizce başarısız olur ve geriye
    sesi olmayan bir video ile ayrı bir ses dosyası kalır.
    """
    found = shutil.which("ffmpeg")
    if found:
        return found

    # Programın çalıştığı klasör — yanına ffmpeg/ klasörü konulduysa.
    # PyInstaller (onefile) farklı yollar verebildiği için olası tüm
    # temel dizinler denenir.
    candidates_base = []
    for value in (sys.argv[0] if sys.argv else "",
                  sys.executable,
                  os.getcwd(),
                  __file__):
        if not value:
            continue
        try:
            parent = Path(value).resolve().parent
        except OSError:
            continue
        if parent not in candidates_base:
            candidates_base.append(parent)

    patterns = ("ffmpeg.exe", "ffmpeg/bin/ffmpeg.exe",
                "bin/ffmpeg.exe", "ffmpeg-*/bin/ffmpeg.exe")
    for base in candidates_base:
        for pattern in patterns:
            try:
                matches = sorted(base.glob(pattern))
            except OSError:
                continue
            if matches:
                return str(matches[0])

    home = Path.home()
    system_drive = os.environ.get("SystemDrive", "C:")
    roots = [
        Path(f"{system_drive}\\ffmpeg"),
        Path(f"{system_drive}\\ffmpeg\\bin"),
        home / "AppData" / "Local",
        Path(f"{system_drive}\\Program Files"),
        Path(f"{system_drive}\\Program Files (x86)"),
    ]
    patterns = ("ffmpeg.exe", "bin\\ffmpeg.exe")

    for root in roots:
        for pattern in patterns:
            try:
                candidate = root / pattern
                if candidate.is_file():
                    return str(candidate)
            except OSError:
                continue

    # Çıkarılmış arşivler için (ör. Downloads\ffmpeg-... \bin\ffmpeg.exe)
    for base in (home / "Downloads", home / "Desktop"):
        if not base.is_dir():
            continue
        for pattern in ("ffmpeg*/bin/ffmpeg.exe", "ffmpeg*/ffmpeg.exe",
                        "ffmpeg*/*/bin/ffmpeg.exe"):
            try:
                matches = sorted(base.glob(pattern))
            except OSError:
                continue
            if matches:
                return str(matches[0])

    return None


class CancelledByUser(Exception):
    """Kullanıcı indirmeyi durdurduğunda fırlatılır."""


class _YdlLogger:
    """yt-dlp'nin beklediği logger arayüzü — hata/uyarıları toplar."""

    def __init__(self, errors: list, log_fn, cancelled_fn=None):
        self._errors = errors
        self._log = log_fn
        self._cancelled = cancelled_fn or (lambda: False)

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        if not self._cancelled():
            self._log(f"UYARI: {msg}")

    def error(self, msg):
        if self._cancelled():
            return  # İptal kaynaklı hataları raporlama
        self._errors.append(str(msg))
        self._log(f"HATA: {msg}")


class YouTubeDownloader:
    def __init__(self, root):
        self.root = root
        self.root.title(f"YouTube İndirici v{get_current_version()}")
        self.root.geometry("720x640")
        self.root.resizable(True, True)

        # Değişkenler
        self.url_var = StringVar()
        self.path_var = StringVar(value=str(Path.home() / "Downloads"))
        self.quality_var = StringVar(value="En İyi")
        self.format_var = StringVar(value="MP4 (Video)")
        self.is_downloading = False
        self.cancel_requested = False

        # İndirme sonucu takibi (worker thread'de doldurulur)
        self._done_files: list[str] = []
        self._errors: list[str] = []
        self._last_partial: str | None = None
        self._last_progress_ts = 0.0

        # GUI yalnızca ana thread'den güncellenir
        self._ui_queue: queue.Queue = queue.Queue()

        # Ortam kontrolleri
        self.has_node = shutil.which("node") is not None
        self.ffmpeg_path = _find_ffmpeg()
        self.has_ffmpeg = self.ffmpeg_path is not None

        # FFmpeg'i PATH dışında bulduysak süreç PATH'ine de ekleyelim
        if self.ffmpeg_path and not shutil.which("ffmpeg"):
            ffmpeg_dir = str(Path(self.ffmpeg_path).parent)
            os.environ["PATH"] = os.environ.get("PATH", "") + os.pathsep + ffmpeg_dir

        self.create_widgets()
        self.root.after(50, self._drain_ui_queue)

        self.log(f"FFmpeg: {'bulundu — ' + self.ffmpeg_path if self.has_ffmpeg else 'BULUNAMADI'}")
        self.log(f"Node.js: {'bulundu' if self.has_node else 'BULUNAMADI (bazı videolarda sorun olabilir)'}")
        if not self.has_ffmpeg:
            self.log("UYARI: FFmpeg yok — video+ses birleştirme ve MP3 yapılamaz.")
        elif not shutil.which("ffmpeg"):
            self.log("NOT: FFmpeg PATH'te değildi, program ekledi.")

        cleanup_old_builds()
        self.check_update_background()

    # ------------------------------------------------------------------
    # Thread-güvenli GUI yardımcıları
    # ------------------------------------------------------------------
    def _drain_ui_queue(self):
        """Kuyruktaki GUI güncellemelerini ana thread'de çalıştırır."""
        try:
            while True:
                fn = self._ui_queue.get_nowait()
                try:
                    fn()
                except Exception:
                    pass
        except queue.Empty:
            pass
        self.root.after(50, self._drain_ui_queue)

    def ui(self, fn, *args):
        """Worker thread'den güvenli GUI güncellemesi sıraya alır."""
        self._ui_queue.put(lambda: fn(*args))

    # ------------------------------------------------------------------
    # Arayüz
    # ------------------------------------------------------------------
    def create_widgets(self):
        """GUI bileşenlerini oluşturur."""
        menubar = Menu(self.root)
        help_menu = Menu(menubar, tearoff=0)
        help_menu.add_command(label="Hakkında", command=self.show_about)
        menubar.add_cascade(label="Yardım", menu=help_menu)
        self.root.config(menu=menubar)

        main_frame = ttk.Frame(self.root, padding="10")
        main_frame.grid(row=0, column=0, sticky="nsew")
        self.root.columnconfigure(0, weight=1)
        self.root.rowconfigure(0, weight=1)

        # URL girişi
        ttk.Label(main_frame, text="YouTube URL'si:").grid(
            row=0, column=0, sticky="w", pady=(0, 5))
        url_frame = ttk.Frame(main_frame)
        url_frame.grid(row=1, column=0, columnspan=3, sticky="ew", pady=(0, 10))
        url_frame.columnconfigure(0, weight=1)

        self.url_entry = ttk.Entry(url_frame, textvariable=self.url_var, width=60)
        self.url_entry.grid(row=0, column=0, sticky="ew", padx=(0, 5))
        ttk.Button(url_frame, text="Yapıştır", command=self.paste_url).grid(row=0, column=1)

        # URL tipi göstergesi
        self.url_type_label = ttk.Label(main_frame, text="", foreground="blue")
        self.url_type_label.grid(row=2, column=0, sticky="w", pady=(0, 10))
        self.url_var.trace_add("write", self.on_url_change)

        # İndirme yolu
        ttk.Label(main_frame, text="İndirme Konumu:").grid(
            row=3, column=0, sticky="w", pady=(0, 5))
        path_frame = ttk.Frame(main_frame)
        path_frame.grid(row=4, column=0, columnspan=3, sticky="ew", pady=(0, 10))
        path_frame.columnconfigure(0, weight=1)
        ttk.Entry(path_frame, textvariable=self.path_var).grid(
            row=0, column=0, sticky="ew", padx=(0, 5))
        ttk.Button(path_frame, text="Gözat", command=self.browse_folder).grid(row=0, column=1)

        # Format seçimi
        ttk.Label(main_frame, text="Format:").grid(row=5, column=0, sticky="w", pady=(0, 5))
        format_combo = ttk.Combobox(
            main_frame, textvariable=self.format_var, state="readonly")
        format_combo["values"] = ("MP4 (Video)", "MP3 (Sadece Ses)")
        format_combo.grid(row=6, column=0, sticky="w", pady=(0, 10))

        # Kalite seçimi
        ttk.Label(main_frame, text="Kalite:").grid(row=7, column=0, sticky="w", pady=(0, 5))
        quality_combo = ttk.Combobox(
            main_frame, textvariable=self.quality_var, state="readonly")
        quality_combo["values"] = ("En İyi", "1080p", "720p", "480p", "360p")
        quality_combo.grid(row=8, column=0, sticky="w", pady=(0, 10))

        # Butonlar
        btn_frame = ttk.Frame(main_frame)
        btn_frame.grid(row=9, column=0, columnspan=3, pady=(10, 10), sticky="ew")
        btn_frame.columnconfigure(0, weight=1)

        self.download_btn = ttk.Button(
            btn_frame, text="İNDİR", command=self.start_download)
        self.download_btn.grid(row=0, column=0, sticky="ew", padx=(0, 5))

        self.cancel_btn = ttk.Button(
            btn_frame, text="İPTAL", command=self.request_cancel, state="disabled")
        self.cancel_btn.grid(row=0, column=1, sticky="ew")

        # İlerleme çubuğu
        self.progress = ttk.Progressbar(main_frame, mode="determinate")
        self.progress.grid(row=10, column=0, columnspan=3, sticky="ew", pady=(0, 5))

        # Durum etiketi
        self.status_label = ttk.Label(main_frame, text="Hazır")
        self.status_label.grid(row=11, column=0, sticky="w", pady=(0, 5))

        # Log alanı
        ttk.Label(main_frame, text="İşlem Günlüğü:").grid(
            row=12, column=0, sticky="w", pady=(0, 5))
        self.log_text = scrolledtext.ScrolledText(
            main_frame, height=10, state="disabled")
        self.log_text.grid(row=13, column=0, columnspan=3, sticky="nsew", pady=(0, 10))
        main_frame.rowconfigure(13, weight=1)

    # ------------------------------------------------------------------
    # URL yardımcıları
    # ------------------------------------------------------------------
    def on_url_change(self, *args):
        """URL değiştiğinde tipini algılar."""
        url = self.url_var.get().strip()
        if self.is_channel_url(url):
            self.url_type_label.config(
                text="📺 Kanalın tüm videoları algılandı", foreground="green")
        elif self.is_video_url(url):
            self.url_type_label.config(
                text="🎬 Tek video algılandı", foreground="blue")
        else:
            self.url_type_label.config(text="")

    @staticmethod
    def is_video_url(url: str) -> bool:
        """Tek video URL'si mi kontrol eder."""
        return bool(re.search(
            r"youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/", url))

    @staticmethod
    def is_channel_url(url: str) -> bool:
        """Kanal (tüm videolar) URL'si mi kontrol eder."""
        # /@kanal, /@kanal/videos, /channel/ID, /c/ad, /user/ad (+ opsiyonel sekme)
        return bool(re.search(
            r"youtube\.com/@[\w.\-]+(?:/[A-Za-z_]+)?/?$"
            r"|youtube\.com/(?:channel|user|c)/[\w.\-]+(?:/[A-Za-z_]+)?/?$",
            url))

    def paste_url(self):
        """Panodan URL yapıştırır."""
        try:
            self.url_var.set(self.root.clipboard_get())
        except Exception:
            pass

    def browse_folder(self):
        """İndirme klasörü seçer."""
        folder = filedialog.askdirectory()
        if folder:
            self.path_var.set(folder)

    # ------------------------------------------------------------------
    # Log ve durum
    # ------------------------------------------------------------------
    def log(self, message: str):
        """Log mesajı ekler (her thread'den güvenli)."""
        def _write():
            self.log_text.config(state="normal")
            self.log_text.insert("end", message + "\n")
            self.log_text.see("end")
            self.log_text.config(state="disabled")

        if threading.current_thread() is threading.main_thread():
            _write()
        else:
            self.ui(_write)

    def _set_status(self, text: str):
        self.status_label.config(text=text)

    def _set_progress(self, percent: float, speed: str = "", eta: str = ""):
        self.progress["value"] = max(0.0, min(100.0, percent))
        parts = [f"İndiriliyor: {percent:.1f}%"]
        if speed:
            parts.append(f"Hız: {speed}")
        if eta:
            parts.append(f"Kalan: {eta}")
        self.status_label.config(text=" | ".join(parts))

    # ------------------------------------------------------------------
    # yt-dlp geri çağrıları (worker thread)
    # ------------------------------------------------------------------
    @staticmethod
    def _percent_of(d: dict) -> float | None:
        """İlerleme yüzdesini güvenli biçimde hesaplar."""
        total = d.get("total_bytes") or d.get("total_bytes_estimate")
        done = d.get("downloaded_bytes")
        if total and done is not None:
            try:
                return float(done) * 100.0 / float(total)
            except (TypeError, ValueError, ZeroDivisionError):
                pass

        raw = d.get("_percent_str")
        if raw:
            try:
                return float(str(raw).replace("%", "").strip())
            except ValueError:
                pass
        return None

    def _on_progress(self, d: dict):
        """yt-dlp ilerleme hook'u."""
        if self.cancel_requested:
            self._last_partial = d.get("tmpfilename") or d.get("filename")
            raise CancelledByUser("kullanıcı iptal etti")

        status = d.get("status")
        if status == "downloading":
            now = time.monotonic()
            if now - self._last_progress_ts < 0.1:  # GUI'yi boğma
                return
            self._last_progress_ts = now

            percent = self._percent_of(d)
            if percent is None:
                return
            speed = str(d.get("_speed_str") or "").strip()
            eta = str(d.get("_eta_str") or "").strip()
            self.ui(self._set_progress, percent, speed, eta)

        elif status == "finished":
            self.ui(self._set_status, "İndirme bitti, dönüştürülüyor...")

    def _on_post_hook(self, filename: str):
        """Başarıyla tamamlanan her dosya için çağrılır."""
        self._done_files.append(filename)
        self.ui(self.log, f"✓ Tamamlandı: {os.path.basename(filename)}")

    def _match_filter(self, info, *, incomplete=False):
        """Kanal indirmesinde iptal isteğini hemen uygular."""
        if self.cancel_requested:
            raise ytdlp_utils.DownloadCancelled("kullanıcı iptal etti")
        return None

    # ------------------------------------------------------------------
    # İndirme seçenekleri
    # ------------------------------------------------------------------
    def get_ydl_opts(self, settings: dict) -> dict:
        """yt-dlp seçeneklerini oluşturur.

        `settings`, ana thread'de okunan tkinter değişkenlerinin değerlerini
        içerir (worker thread'de tkinter'a dokunulmamalıdır).
        """
        download_path = settings["path"] or str(Path.home() / "Downloads")
        os.makedirs(download_path, exist_ok=True)

        is_mp3 = settings["format"] == "MP3 (Sadece Ses)"
        quality = settings["quality"]

        opts = {
            "outtmpl": os.path.join(download_path, "%(title)s.%(ext)s"),
            "progress_hooks": [self._on_progress],
            "post_hooks": [self._on_post_hook],
            "match_filter": self._match_filter,
            "noplaylist": not settings["is_channel"],
            "quiet": True,
            "no_warnings": True,
            # Tek bir video hatası tüm kanal indirmesini durdurmasın
            "ignoreerrors": True,
            "logger": _YdlLogger(self._errors, self.log,
                                 lambda: self.cancel_requested),
        }

        # FFmpeg: birleştirme (merge) ve MP3 dönüşümü için şart
        if self.ffmpeg_path:
            opts["ffmpeg_location"] = self.ffmpeg_path

        # YouTube, format listelemesi için JS çalıştırıcı ister
        if self.has_node:
            opts["js_runtimes"] = {"node": {}}

        # Kapak fotoğrafı indirilen dosyanın içine gömülür (MP4 ve MP3).
        # yt-dlp kendi indirdiği thumbnail'ı gömmeden önce diske yazar.
        opts["writethumbnail"] = True

        if is_mp3:
            opts["format"] = "bestaudio/best"
            opts["postprocessors"] = [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": "192",
                },
                # SIRALAMA ÖNEMLİ: dosya önce .mp3'e dönüşmeli,
                # ancak ondan sonra kapak eklenebilir.
                {"key": "EmbedThumbnail"},
            ]
        else:
            # Ses her zaman videoya gömülür; sonuç tek bir dosyadır.
            opts["merge_output_format"] = "mp4"
            # Birleştirmeden sonra (post_process) eklenir.
            opts["postprocessors"] = [{"key": "EmbedThumbnail"}]
            if quality == "En İyi":
                # Önce H.264/MP4 (her oynatıcıda çalışır), olmazsa genel seçenek
                opts["format"] = (
                    "bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/"
                    "bestvideo[ext=mp4]+bestaudio[ext=m4a]/"
                    "bestvideo*+bestaudio/"
                    "best"
                )
            else:
                height = quality.replace("p", "")
                opts["format"] = (
                    f"bestvideo[height<={height}][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/"
                    f"bestvideo[height<={height}][ext=mp4]+bestaudio[ext=m4a]/"
                    f"bestvideo[height<={height}]+bestaudio/"
                    f"best[height<={height}]"
                )

        return opts

    # ------------------------------------------------------------------
    # İndirme akışı
    # ------------------------------------------------------------------
    def start_download(self):
        """Doğrulama yapar ve indirme işlemini ayrı thread'de başlatır."""
        if self.is_downloading:
            messagebox.showwarning("Uyarı", "Zaten bir indirme işlemi devam ediyor!")
            return

        url = self.url_var.get().strip()
        if not url:
            messagebox.showwarning("Uyarı", "Lütfen bir YouTube URL'si girin!")
            return

        if not (self.is_video_url(url) or self.is_channel_url(url)):
            messagebox.showwarning(
                "Uyarı",
                "Geçersiz YouTube URL'si!\n\nÖrnekler:\n"
                "  • https://www.youtube.com/watch?v=...\n"
                "  • https://youtu.be/...\n"
                "  • https://www.youtube.com/@kanal/videos")
            return

        if not self.has_ffmpeg:
            messagebox.showerror(
                "FFmpeg Gerekli",
                "Video ile sesin birleştirilmesi ve MP3 üretimi için FFmpeg gerekli.\n\n"
                "FFmpeg kurulup PATH'e eklenmeden indirilen videoda ses OLMAZ.\n\n"
                "Lütfen FFmpeg'i kurun, sonra programı yeniden başlatın.")
            return

        # tkinter değişkenlerini ANA thread'de oku — worker'da Tk'a dokunulmaz
        settings = {
            "path": self.path_var.get().strip(),
            "format": self.format_var.get(),
            "quality": self.quality_var.get(),
            "is_channel": self.is_channel_url(url),
        }

        self._prepare_download()
        threading.Thread(
            target=self._download_worker, args=(url, settings), daemon=True
        ).start()

    def _prepare_download(self):
        """İndirme öncesi arayüz durumunu ayarlar."""
        self.is_downloading = True
        self.cancel_requested = False
        self._done_files = []
        self._errors = []
        self._last_partial = None
        self._last_progress_ts = 0.0

        self.download_btn.config(state="disabled")
        self.cancel_btn.config(state="normal")
        self.progress["value"] = 0
        self._set_status("Hazırlanıyor...")

    def _finish_download(self):
        """İndirme sonrası arayüz durumunu normale alır."""
        self.is_downloading = False
        self.cancel_requested = False
        self.download_btn.config(state="normal")
        self.cancel_btn.config(state="disabled")

    def request_cancel(self):
        """Kullanıcı İPTAL'e bastığında çağrılır (ana thread)."""
        if not self.is_downloading:
            return
        self.cancel_requested = True
        self.cancel_btn.config(state="disabled")
        self._set_status("İptal ediliyor...")
        self.log("İptal isteği gönderildi...")

    def _cleanup_partial(self):
        """Yarım kalan indirme dosyasını siler."""
        for path in filter(None, (self._last_partial,)):
            for candidate in (path, path + ".part"):
                try:
                    if candidate and os.path.exists(candidate):
                        os.remove(candidate)
                except OSError:
                    pass

    def _find_ffprobe(self) -> str | None:
        """ffprobe'u bulur (ses doğrulaması için)."""
        found = shutil.which("ffprobe")
        if found:
            return found
        if self.ffmpeg_path:
            sibling = Path(self.ffmpeg_path).with_name("ffprobe.exe")
            if sibling.is_file():
                return str(sibling)
        return None

    def _files_without_audio(self, files) -> list:
        """
        İndirilen videolarda ses stream'i olmayan dosyaları listeler.

        Birleştirme (merge) başarısız olursa geriye yalnızca video stream'i
        kalan dosyalar kalır; kullanıcı bunu "videoda ses yok" olarak görür.

        ffprobe varsa o kullanılır; yoksa ffmpeg'in çıktı analiziyle yetinilir
        (ffprobe şart değil, böylece dağıtım boyutu yarıya iner).
        """
        video_exts = {".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v"}
        probe = self._find_ffprobe()
        ffmpeg = self.ffmpeg_path if self.has_ffmpeg else None

        if not probe and not ffmpeg:
            return []

        problems = []
        for path in files:
            if Path(path).suffix.lower() not in video_exts:
                continue
            try:
                if probe:
                    result = subprocess.run(
                        [probe, "-v", "error", "-select_streams", "a",
                         "-show_entries", "stream=codec_type", "-of", "csv=p=0", path],
                        capture_output=True, text=True,
                        encoding="utf-8", errors="replace", timeout=60,
                    )
                    has_audio = bool((result.stdout or "").strip())
                else:
                    # ffmpeg -i dosya : akış bilgisini stderr'e yazar
                    result = subprocess.run(
                        [ffmpeg, "-hide_banner", "-i", path],
                        capture_output=True, text=True,
                        encoding="utf-8", errors="replace", timeout=60,
                    )
                    has_audio = bool(re.search(
                        r"Stream\s+#\d+:\d+.*\bAudio:", result.stderr or ""))
                if not has_audio:
                    problems.append(path)
            except (OSError, subprocess.SubprocessError):
                continue
        return problems

    def _files_without_cover(self, files) -> list:
        """
        Kapak fotoğrafı gömülmemiş MP4/MP3 dosyalarını listeler.

        mutagen yoksa doğrulama yapılamaz -> [] döner (ses kontrolünün
        ffprobe/yok durumunda davrandığı gibi).
        """
        try:
            import mutagen  # noqa: F401
        except ImportError:
            return []

        problems = []
        for path in files:
            ext = Path(path).suffix.lower()
            try:
                if ext == ".mp4":
                    from mutagen.mp4 import MP4
                    meta = MP4(path)
                    has_cover = bool(meta.tags and meta.tags.get("covr"))
                elif ext == ".mp3":
                    from mutagen.id3 import ID3
                    has_cover = bool(ID3(path).getall("APIC"))
                else:
                    continue
            except Exception:
                # Etiket yok / okunamadı -> kapak da yoktur
                has_cover = False
            if not has_cover:
                problems.append(path)
        return problems

    def _download_worker(self, url: str, settings: dict):
        """İndirme işlemi — her zaman worker thread'de çalışır (Tk'a dokunmaz)."""
        is_channel = settings["is_channel"]

        try:
            opts = self.get_ydl_opts(settings)
            self.log("Kanalın tüm videoları indiriliyor: " + url if is_channel
                     else "Video indiriliyor: " + url)

            with yt_dlp.YoutubeDL(opts) as ydl:
                ydl.download([url])

            self._report_result()

        except (CancelledByUser, ytdlp_utils.DownloadCancelled):
            self._cleanup_partial()
            self.log("İndirme iptal edildi.")
            self.ui(self._set_status, "İptal edildi")
            self.ui(self.progress.configure, {"value": 0})
            self.ui(messagebox.showinfo, "İptal", "İndirme iptal edildi.")

        except Exception as e:
            self.log(f"HATA: {e}")
            self.ui(self._set_status, "Hata oluştu!")
            self.ui(messagebox.showerror, "Hata",
                    f"İndirme sırasında hata oluştu:\n{e}")

        finally:
            # GUI güncellemeleri her zaman ana thread'de yapılmalı
            self.ui(self._finish_download)

    def _report_result(self):
        """İndirme sonucunu dürüstçe raporlar (worker thread'den çağrılır)."""
        done = len(self._done_files)
        failed = len(self._errors)

        if self.cancel_requested:
            self.ui(self._set_status, "İptal edildi")
            self.ui(self.progress.configure, {"value": 0})
            self.log("İptal edildi — kalan videolar atlandı.")
            return

        if done > 0:
            # Birleştirme gerçekten oldu mu? (ses olmayan videoları yakala)
            no_audio = self._files_without_audio(self._done_files)
            # Kapak fotoğrafı gömüldü mü? (estetik — pencere açmaya değmez)
            no_cover = self._files_without_cover(self._done_files)

            summary = f"Tamamlandı — {done} dosya indirildi"
            if failed:
                summary += f" ({failed} video atlandı)"

            self.ui(self._set_status, summary)
            self.ui(self.progress.configure, {"value": 100})
            self.log(summary + ".")

            if failed:
                self.log(f"Atlana(n) video sayısı: {failed}")
                for err in self._errors[:5]:
                    self.log(f"  • {err}")

            if no_audio:
                self.log(f"UYARI: {len(no_audio)} dosyada SES YOK "
                         "(video+ses birleştirilemedi):")
                for path in no_audio:
                    self.log(f"  • {os.path.basename(path)}")
                if self._errors:
                    for err in self._errors[:5]:
                        self.log(f"  • {err}")
                self.ui(
                    messagebox.showwarning, "Dikkat",
                    summary + ".\n\n"
                    f"Ancak {len(no_audio)} videoda SES YOK.\n"
                    "Birleştirme başarısız olmuş olabilir; "
                    "FFmpeg kurulumunu kontrol edin.")
            else:
                self.ui(messagebox.showinfo, "Başarılı", summary + ".")

            if no_cover:
                self.log(f"Bilgi: {len(no_cover)} dosyada kapak fotoğrafı "
                         "eklenemedi (video ve ses sorunsuz):")
                for path in no_cover:
                    self.log(f"  • {os.path.basename(path)}")
        else:
            self.ui(self._set_status, "İndirme başarısız")
            self.ui(self.progress.configure, {"value": 0})

            detail = ""
            if failed:
                detail = "\n\nHatalar:\n" + "\n".join(f"• {e}" for e in self._errors[:5])
                self.log("Hiçbir dosya indirilemedi.")
                for err in self._errors[:5]:
                    self.log(f"  • {err}")
            else:
                self.log("Hiçbir dosya indirilemedi.")

            self.ui(messagebox.showerror, "Başarısız",
                    "Hiçbir dosya indirilemedi." + detail)

    # ------------------------------------------------------------------
    # Güncelleme
    # ------------------------------------------------------------------
    def check_update_background(self):
        """Arka planda güncelleme kontrolü yapar (ana thread'de sonuç gösterir)."""
        def check():
            try:
                from updater import check_for_update
                update_info = check_for_update()
                if update_info:
                    self.ui(self.prompt_update, update_info)
            except Exception:
                pass

        threading.Thread(target=check, daemon=True).start()

    def prompt_update(self, update_info: dict):
        """Güncelleme bildirimi gösterir."""
        result = messagebox.askyesno(
            "Güncelleme Mevcut",
            f"Yeni sürüm bulundu: {update_info['version']}\n\n"
            f"Notlar:\n{update_info.get('notes') or 'Bilgi yok'}\n\n"
            f"Şimdi güncellemek ister misiniz?")
        if not result:
            return

        self.log("Güncelleme indiriliyor...")

        def do_update():
            from updater import download_and_replace
            if download_and_replace(update_info):
                self.ui(messagebox.showinfo,
                        "Güncelleme Tamamlandı",
                        "Program güncellendi. Pencereyi kapatıp yeniden açın.")
                self.ui(self.root.quit)
            else:
                self.ui(messagebox.showerror,
                        "Güncelleme Hatası",
                        "Güncelleme uygulanamadı. Program eski sürümde kalacak.")

        threading.Thread(target=do_update, daemon=True).start()

    def show_about(self):
        """Hakkında penceresi."""
        messagebox.showinfo(
            "Hakkında",
            f"YouTube İndirici v{get_current_version()}\n\n"
            "Özellikler:\n"
            "• Tek video indirme\n"
            "• Kanalın tüm videolarını indirme\n"
            "• MP3 ses indirme\n"
            "• İptal desteği\n"
            "• Otomatik güncelleme\n\n"
            "yt-dlp kullanılarak geliştirilmiştir.")


def _run_self_test():
    """
    Gizli mod: `YouTubeDownloader.exe --test`

    Paketi olduğu gibi indirme yaparak doğrular ve sonucu
    "YouTubeIndirici_test.txt" dosyasına yazar. Paketi kopyaladığınız
    bilgisayarda bu dosyayı açarak sonucu görebilirsiniz.
    """
    lines = []
    root = None
    try:
        root = Tk()
        root.withdraw()
        app = YouTubeDownloader(root)
        root.withdraw()

        lines.append(f"Sürüm: {get_current_version()}")
        lines.append(f"Bulunan FFmpeg: {app.ffmpeg_path}")
        lines.append(f"Node.js: {'var' if app.has_node else 'yok (sorun değil)'}")
        if not app.has_ffmpeg:
            raise RuntimeError("FFmpeg bulunamadı — ffmpeg klasörünü exe yanına koyun")

        outdir = Path(tempfile.gettempdir()) / "yt_kendi_testi"
        outdir.mkdir(parents=True, exist_ok=True)
        for old in outdir.iterdir():
            try:
                old.unlink()
            except OSError:
                pass

        settings = {
            "path": str(outdir),
            "format": "MP4 (Video)",
            "quality": "En İyi",
            "is_channel": False,
        }
        opts = app.get_ydl_opts(settings)
        opts["ignoreerrors"] = False  # testte hata gizlenmesin

        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download(["https://www.youtube.com/watch?v=jNQXAC9IVRw"])

        files = [str(p) for p in outdir.iterdir() if p.is_file()]
        lines.append(f"İndirilen: {[Path(f).name for f in files] or 'YOK'}")

        if not files:
            raise RuntimeError("Hiç dosya indirilemedi")

        no_audio = app._files_without_audio(files)
        no_cover = app._files_without_cover(files)
        if no_audio:
            lines.append(f"SONUÇ: BAŞARISIZ — ses yok: "
                         f"{[Path(f).name for f in no_audio]}")
        else:
            size = sum(Path(f).stat().st_size for f in files)
            cover = "kapak eklendi" if not no_cover else "KAPAK EKLENEMEDİ"
            lines.append(f"SONUÇ: BAŞARILI — video+ses birleştirildi, "
                         f"{cover} ({size // 1024} KB)")

    except Exception as e:
        lines.append(f"SONUÇ: BAŞARISIZ — {type(e).__name__}: {e}")
    finally:
        if root is not None:
            try:
                root.destroy()
            except Exception:
                pass

    text = "\n".join(lines) + "\n"
    for target in (Path.cwd() / "YouTubeIndirici_test.txt",
                   Path(tempfile.gettempdir()) / "YouTubeIndirici_test.txt"):
        try:
            target.write_text(text, encoding="utf-8")
            break
        except OSError:
            continue


def _run_diagnostics():
    """
    Gizli mod: `YouTubeDownloader.exe --diag`

    Ortam bilgisini bir metin dosyasına yazar ve çıkar. Paketin başka bir
    bilgisayarda neden çalışmadığını anlamak için kullanılır.
    """
    info = {
        "Sürüm": get_current_version(),
        "argv[0]": sys.argv[0] if sys.argv else "",
        "sys.executable": sys.executable,
        "Çalışma klasörü": os.getcwd(),
        "Paketlenmiş (frozen)": bool(getattr(sys, "frozen", False)),
        "_MEIPASS": getattr(sys, "_MEIPASS", None),
        "PATH'te ffmpeg": shutil.which("ffmpeg"),
        "Bulunan ffmpeg": _find_ffmpeg(),
        "PATH'te node": shutil.which("node"),
        "PATH'te ffprobe": shutil.which("ffprobe"),
        "İşletim sistemi": os.name,
    }

    lines = [f"{k}: {v}" for k, v in info.items()]
    text = "\n".join(lines) + "\n"

    targets = [
        Path.cwd() / "YouTubeIndirici_diagnostik.txt",
        Path(tempfile.gettempdir()) / "YouTubeIndirici_diagnostik.txt",
    ]
    for target in targets:
        try:
            target.write_text(text, encoding="utf-8")
            break
        except OSError:
            continue


def main():
    root = Tk()
    YouTubeDownloader(root)
    root.mainloop()


if __name__ == "__main__":
    if "--diag" in sys.argv:
        _run_diagnostics()
    elif "--test" in sys.argv:
        _run_self_test()
    else:
        main()
