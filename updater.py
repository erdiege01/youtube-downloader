"""
Otomatik Güncelleme Modülü
Program her açıldığında GitHub Releases'teki en güncel sürümü kontrol eder.

NOT: Güncellemenin çalışması için GitHub'da bir repository oluşturup
     sürüm (release) yayınlamanız ve aşağıdaki GITHUB_REPO değerini
     "kullaniciadi/depo-adi" biçiminde doldurmanız gerekir.
     GITHUB_REPO boş bırakılırsa güncelleme özelliği kapalıdır.
"""
import os
import re
import shutil
import sys
import tempfile

import requests

# GitHub repo bilgileri (release'ler burada yayınlanır)
GITHUB_REPO = "erdiege01/youtube-downloader"

# Yayınlanecek exe dosyasının adı (release ekine eklediğiniz dosya)
ASSET_NAME = "YouTubeDownloader.exe"

CURRENT_VERSION = "1.2.0"


def get_current_version() -> str:
    """Mevcut sürümü döndürür."""
    return CURRENT_VERSION


def is_update_enabled() -> bool:
    """Güncelleme özelliği kullanıma açık mı?"""
    return bool(GITHUB_REPO)


def _version_key(version: str) -> tuple:
    """"1.2.3" -> (1, 2, 3) biçiminde karşılaştırılabilir anahtar üretir."""
    parts = re.findall(r"\d+", version or "")
    return tuple(int(p) for p in parts) or (0,)


def check_for_update() -> dict | None:
    """Güncelleme var mı kontrol eder. Varsa release bilgisini döndürür."""
    if not is_update_enabled():
        return None

    try:
        response = requests.get(
            f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest",
            timeout=10,
        )
        if response.status_code != 200:
            return None

        release = response.json()
        latest = (release.get("tag_name") or "").lstrip("v")
        if not latest or _version_key(latest) <= _version_key(CURRENT_VERSION):
            return None  # Aynı veya eski sürüm — güncelleme yok

        # İlgili exe dosyasını bul, yoksa ilk eki kullan
        assets = release.get("assets") or []
        download_url = next(
            (a["browser_download_url"] for a in assets if a.get("name") == ASSET_NAME),
            assets[0]["browser_download_url"] if assets else
            f"https://github.com/{GITHUB_REPO}/releases/latest/download/{ASSET_NAME}",
        )

        return {
            "version": latest,
            "download_url": download_url,
            "notes": release.get("body") or "",
        }
    except Exception as e:
        print(f"Güncelleme kontrolü başarısız: {e}")
        return None


def cleanup_old_builds() -> None:
    """
    Önceki güncellemeden kalan yedek dosyaları siler.

    Windows, çalışan bir exe'yi silmeye izin vermez; bu yüzden güncelleme
    sırasında eski exe '.old' olarak yeniden adlandırılır ve silinemez.
    Bir sonraki açılışta (eski süreç kapanmışken) burada temizlenir.
    """
    if not getattr(sys, "frozen", False):
        return

    for suffix in (".old", ".new"):
        path = sys.executable + suffix
        try:
            if os.path.exists(path):
                os.remove(path)
        except OSError:
            pass


def download_and_replace(update_info: dict) -> bool:
    """Yeni sürümü indirip mevcut exe'nin yerine koyar."""
    # Kaynaktan çalışırken (python main.py) kendini değiştirmeye çalışma
    if not getattr(sys, "frozen", False):
        print("Güncelleme yalnızca paketlenmiş .exe dosyalarında uygulanır.")
        return False

    current_exe = sys.executable
    backup_exe = current_exe + ".old"
    temp_exe = os.path.join(tempfile.gettempdir(), ASSET_NAME + ".download")
    downloaded = False

    try:
        print(f"Yeni sürüm indiriliyor: {update_info['version']}...")
        with requests.get(update_info["download_url"], stream=True, timeout=300) as response:
            response.raise_for_status()
            with open(temp_exe, "wb") as f:
                for chunk in response.iter_content(chunk_size=65536):
                    if chunk:
                        f.write(chunk)
        downloaded = True

        # Eski yedek varsa temizlemeyi dene (kilitliyse sessizce geç)
        try:
            if os.path.exists(backup_exe):
                os.remove(backup_exe)
        except OSError:
            pass

        # Çalışan exe'yi yeniden adlandır — Windows buna izin verir
        # (silme/üzerine yazma yasaktır, yeniden adlandırma serbesttir)
        os.rename(current_exe, backup_exe)

        # Yeni sürümü asıl konumuna kopyala
        shutil.copyfile(temp_exe, current_exe)
        os.remove(temp_exe)

        print(f"Güncelleme hazır: {update_info['version']} — yeniden başlatın.")
        return True

    except Exception as e:
        print(f"Güncelleme başarısız: {e}")
        # Başarısız olduysa eski exe'yi yerine geri koy
        try:
            if not os.path.exists(current_exe) and os.path.exists(backup_exe):
                os.rename(backup_exe, current_exe)
        except OSError:
            pass
        return False
    finally:
        if downloaded and os.path.exists(temp_exe):
            try:
                os.remove(temp_exe)
            except OSError:
                pass
        # backup_exe silinemez (çalışıyor); bir sonraki açılışta
        # cleanup_old_builds() onu temizleyecek.


def auto_update(silent: bool = True) -> bool:
    """Otomatik güncelleme kontrolü yapar. Güncelleme varsa indirir."""
    update_info = check_for_update()
    if update_info:
        if not silent:
            print(f"Yeni sürüm bulundu: {update_info['version']}")
        return download_and_replace(update_info)
    return False
