# -*- coding: utf-8 -*-
"""dist/VideoIndirici.zip'i APK'lar olmadan yeniden üretir.

Neden APK'lar çıkarılıyor?
    dist/VideoIndirici/ tek kullanım paketidir (Windows + Android).
    Windows zip'inin içinde APK olursa ~64 MB yerine ~175 MB olur ve
    Windows kullanıcısı için anlamsız bir indirme yükü doğar.

Neden PowerShell değil?
    PowerShell 5.1, BOM'suz .ps1 dosyasını ANSI okur; yolun içindeki
    "Varsayılan" kelimesindeki "ı" bozulur ve yol bulunamaz.
    Python kaynak dosyayı UTF-8 okur.

Kullanım:
    python make_zip.py
"""
import shutil
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "dist" / "VideoIndirici"
ZIP = ROOT / "dist" / "VideoIndirici.zip"


def main() -> None:
    if not SRC.is_dir():
        raise SystemExit(f"paket klasörü yok: {SRC}")

    stage = Path(tempfile.mkdtemp(prefix="vi_zip_"))
    try:
        for item in sorted(SRC.iterdir()):
            if item.is_file() and item.suffix.lower() == ".apk":
                continue  # APK'lar Windows zip'ine girmez
            target = stage / item.name
            if item.is_dir():
                shutil.copytree(item, target)
            else:
                shutil.copy2(item, target)

        if ZIP.exists():
            ZIP.unlink()
        # make_archive'in ilk parametresi ".zip" uzantısı olmadan verilir
        shutil.make_archive(str(ZIP.with_suffix("")), "zip", root_dir=stage)
    finally:
        shutil.rmtree(stage, ignore_errors=True)

    print(f"VideoIndirici.zip = {ZIP.stat().st_size / 1024 / 1024:.1f} MB")
    print()
    print("zip içeriği:")
    with zipfile.ZipFile(ZIP) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            print(f"  {info.filename:<40} {info.file_size // 1024:>8} KB")


if __name__ == "__main__":
    main()
