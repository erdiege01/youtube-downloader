"""
PyInstaller ile tek dosyalık .exe oluşturma scripti.
Kullanım: python build_exe.py
"""
import PyInstaller.__main__
import os

def build():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    main_py = os.path.join(script_dir, "main.py")
    icon_path = os.path.join(script_dir, "icon.ico")  # İsteğe bağlı: ikon dosyası

    args = [
        main_py,
        '--name=YouTubeDownloader',
        '--onefile',
        '--windowed',  # Konsol penceresi gösterme
        '--clean',
        '--noconfirm',
    ]

    if os.path.exists(icon_path):
        args.append(f'--icon={icon_path}')

    print("exe oluşturuluyor...")
    PyInstaller.__main__.run(args)
    print("exe oluşturuldu: dist/YouTubeDownloader.exe")

if __name__ == "__main__":
    build()
