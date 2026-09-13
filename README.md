# 📱 VPN Client App

Aplikasi Android VPN client seperti **HTTP Custom** / **NapsternetV**.
Dibuat dengan Python + KivyMD. Mendukung **SSH-WS** dan **Xray** (VMess/VLess/Trojan/SS).

---

## ✨ Fitur

| Fitur | Keterangan |
|-------|------------|
| 🔐 SSH-WS | Konek SSH over WebSocket (seperti HTTP Custom) |
| ⚙️ Xray Core | VMess, VLess, Trojan, Shadowsocks |
| 📋 Multi Profil | Simpan banyak profil server |
| 📝 Custom Payload | Header injeksi + preset payload |
| 📊 Speed Monitor | Upload / Download / Ping real-time |
| 🔗 Import Link | Paste vmess:// vless:// trojan:// langsung |

---

## 📱 Tampilan Aplikasi

```
┌─────────────────────┐
│   TIDAK TERHUBUNG   │
│                     │
│    [ HUBUNGKAN ]    │  ← Tombol besar tengah
│                     │
│     00:00:00        │
├──────────┬──────────┤
│ ↓ 0 B/s │ ↑ 0 B/s  │  ← Speed stats
│          │ -- ms    │
├──────────┴──────────┤
│ Profil Aktif   Ganti│
│ Demo SSH-WS         │
│ your-server.com:80  │
├─────────────────────┤
│ Log Koneksi...      │
├──────┬───────┬──────┤
│Konek │ Profil│Payload│  ← Bottom nav
└──────┴───────┴──────┘
```

---

## 🚀 Build APK via GitHub Actions

1. Upload semua file ke GitHub repo
2. Push ke branch `main`
3. GitHub Actions otomatis build APK
4. Download APK dari tab **Releases** atau **Actions > Artifacts**

---

## ⚙️ Cara Pakai Setelah Install

### Konek SSH-WS:
1. Buka tab **Profil** → Tambah profil baru → pilih tipe **SSH-WS**
2. Isi: Host, Port (WS), Username, Password
3. Buka tab **Payload** → pilih preset atau isi payload manual
4. Kembali ke tab **Konek** → tekan **HUBUNGKAN**

### Konek Xray (VMess/VLess/Trojan):
1. Buka tab **Profil** → tekan **Paste Link**
2. Paste link `vmess://...` atau `vless://...` atau `trojan://...`
3. Tekan **IMPORT** → profil otomatis tersimpan
4. Pilih profil → tekan **Konek**

---

## 📁 Struktur Project

```
vpn-client-app/
├── .github/workflows/build.yml  ← Auto build APK
├── engines/
│   ├── ssh_ws.py        ← SSH-WS engine (paramiko + websocket)
│   └── xray_engine.py   ← Xray core engine
├── screens/
│   ├── home.py          ← Halaman utama (connect)
│   ├── profiles.py      ← Manajemen profil server
│   └── payload.py       ← Custom payload & injeksi
├── profile_store.py     ← Simpan/load profil JSON
├── main.py              ← Entry point app
├── buildozer.spec       ← Konfigurasi build APK
└── assets/
    └── xray             ← Xray binary ARM64 (isi sendiri)
```

---

## ⚠️ Catatan Penting

### File Xray Binary
Untuk fitur Xray, kamu perlu menyediakan **xray binary** untuk Android ARM64:
1. Download dari: https://github.com/XTLS/Xray-core/releases
2. Pilih `Xray-android-arm64-v8a.zip`
3. Extract, taruh file `xray` ke folder `assets/`

### Proxy Lokal
Setelah konek, app membuka proxy lokal:
- **SOCKS5**: `127.0.0.1:1080`
- **HTTP Proxy**: `127.0.0.1:8080` (Xray)

Atur proxy di pengaturan WiFi Android untuk route traffic.
