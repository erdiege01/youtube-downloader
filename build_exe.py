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
        '--name=VideoIndirici',
        '--onefile',
        '--windowed',  # Konsol penceresi gösterme
        '--clean',
        '--noconfirm',
        # MP4/MP3 kapak fotoğrafı gömme (EmbedThumbnail) için
        '--hidden-import=mutagen',
        '--hidden-import=mutagen.mp4',
        '--hidden-import=mutagen.id3',
        # TikTok / Instagram bot korumasını aşmak için (impersonation)
        '--hidden-import=curl_cffi',
        '--hidden-import=curl_cffi.impersonate',
        '--hidden-import=curl_cffi.requests',
    ]

    if os.path.exists(icon_path):
        args.append(f'--icon={icon_path}')

    print("exe oluşturuluyor...")
    PyInstaller.__main__.run(args)
    print("exe oluşturuldu: dist/VideoIndirici.exe")

if __name__ == "__main__":
    build()
