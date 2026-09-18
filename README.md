# XrayLite Client (Android)

Client tunnel ringan untuk VPS pribadimu: **VLESS, VMess, Trojan** (lewat Xray-core) dan
**SSH murni** (port-forward via sshj). Native Kotlin, tanpa Flutter.

## Status: sudah lengkap secara kode, tinggal push ke GitHub

Versi ini bukan skeleton lagi. Semua bug dari review sebelumnya sudah diperbaiki, dan
`.github/workflows/build.yml` sekarang **otomatis build `libv2ray.aar` (Xray-core) dari
source Go via gomobile** sebelum compile APK — jadi kamu tidak perlu build apa pun secara
manual di komputer sendiri. Lihat bagian **"Yang masih perlu kamu perhatikan"** di bawah
untuk risiko yang jujur tidak bisa saya hilangkan sepenuhnya dari sini.

### Ringkasan perbaikan dari versi sebelumnya

- `SshTunnelManager` sebelumnya manggil method sshj yang **tidak ada** (`newDirectTCPIPChannel`)
  — tidak akan pernah compile. Diganti pakai `SSHClient.newLocalPortForwarder(...)` bawaan
  sshj, yang juga otomatis benar berhenti saat disconnect (versi lama membiarkan
  `ServerSocket` menggantung selamanya).
- `loadKeys(pem)` sebelumnya salah overload — memperlakukan isi PEM sebagai **path file**,
  bukan isi key, jadi private-key auth selalu gagal. Diganti ke overload yang benar.
- Notifikasi VPN pakai `Notification.Builder(ctx, channelId)` yang **crash di Android 7.0/7.1**
  (constructor itu baru ada dari API 26, padahal `minSdk = 24`). Diganti `NotificationCompat.Builder`
  yang aman di semua API level yang didukung.
- Manifest referensi `@mipmap/ic_launcher` yang filenya **tidak ada sama sekali** di project
  (bikin build gagal duluan sebelum sempat nyentuh masalah AAR). Diganti icon vector yang
  disertakan langsung.
- `foregroundServiceType="specialUse"` butuh `<property>` `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
  sejak Android 14 — tanpa itu `startForeground()` crash `MissingForegroundServiceTypeException`
  persis di targetSdk 34 project ini. Sudah ditambahkan.
- `toXrayOutboundJson` bikin JSON lewat string template manual (rawan rusak kalau
  uuid/password/path mengandung `"`), dan asal gabung field `vnext`+`servers` apa pun
  protokolnya. Ditulis ulang pakai `JsonObject` (Gson) yang auto-escape dan spesifik per
  protokol.
- Ping async di `ServerListAdapter` bisa nempel di baris yang salah kalau RecyclerView
  recycle view sebelum hasil ping (timeout 3 detik) selesai. Sudah dicek posisi/id dulu.
- `proguard-rules.pro` direferensikan di `build.gradle.kts` tapi filenya tidak ada — build
  release akan gagal. Sudah dibuat, termasuk keep-rules untuk kelas binding gomobile
  (`go.**`, `libv2ray.**`) yang gampang rusak kalau di-strip R8.
- **`tun2socks`/`hev-socks5-tunnel` dihapus total** — versi AndroidLibXrayLite yang sekarang
  bisa terima TUN file descriptor langsung di `CoreController.startLoop(config, tunFd)`,
  jadi satu native dependency lebih sedikit yang tadinya harus kamu build sendiri.
- `XrayVpnService` sebelumnya isinya `TODO` kosong. Sekarang manggil API asli
  `Libv2ray.newCoreController(...)`, `.startLoop()`, `.stopLoop()`, dan
  `.queryAllOutboundTrafficStats()` (dicek langsung ke source
  `github.com/2dust/AndroidLibXrayLite`, bukan tebakan) — termasuk counter upload/download
  di layar utama yang sebelumnya placeholder 0, sekarang isi angka asli.
- Dialog **"+ Tambah SSH manual"** ditambahkan (host/port/user/password/private key PEM) —
  sebelumnya UI cuma punya hint teks kosong tanpa form beneran.
- `ConfigStore` (penyimpanan duplikat, terpisah dari `ServerRepository`) dihapus; sekarang
  cuma satu sumber data.

## Struktur

```
app/src/main/java/com/lite/xraylite/
├── XrayLiteApp.kt               # Application: go.Seq.setContext() + initCoreEnv() sekali di awal
├── config/ConfigParser.kt       # parse link vless://, vmess://, trojan://, ssh://
│                                 #  + builder JSON config Xray-core lengkap
├── config/ServerRepository.kt   # penyimpanan multi-profile server + session stats
├── model/ServerConfig.kt        # data class profil server
├── util/PingTester.kt           # tes latency TCP handshake per server
├── vpn/XrayVpnService.kt        # VpnService inti: TUN fd -> CoreController.startLoop()
├── ssh/SshTunnelManager.kt      # tunnel SSH murni pakai sshj (LocalPortForwarder)
└── ui/
    ├── MainActivity.kt          # layar utama: monitor + daftar server + connect
    └── ServerListAdapter.kt     # RecyclerView daftar profil + indikator ping
```

## Fitur Xray yang didukung parser link

`ConfigParser` baca parameter berikut dari link `vless://` / `vmess://` / `trojan://`:

| Parameter | Field | Keterangan |
|---|---|---|
| `security` | `security` | `tls`, `reality`, atau `none` |
| `sni` | `sni` | default ke host kalau tidak diisi |
| `alpn` | `alpn` | comma-separated (`h2,http/1.1`) |
| `fp` | `fingerprint` | uTLS fingerprint (`chrome`, `firefox`, `safari`, `random`, dst) |
| `allowInsecure` | `allowInsecure` | `1` = skip verifikasi sertifikat |
| `type` | `network` | `ws`, `grpc`, `tcp` |
| `path`, `host` | `wsPath`, `wsHost` | khusus network `ws` |
| `flow` | `flow` | khusus VLESS, mis. `xtls-rprx-vision` |
| `pbk`, `sid`, `spx` | `realityPublicKey`, `realityShortId`, `realitySpiderX` | khusus `security=reality` |

Belum didukung: transport `xhttp`/`httpupgrade`/`kcp`/`quic`, dan `mux`. Kalau butuh salah
satu itu, tambahkan branch baru di `ConfigParser.buildStreamSettings()` — polanya sama
seperti blok `ws`/`grpc` yang sudah ada.



```bash
cd XrayLiteClient
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<username>/<nama-repo>.git
git push -u origin main
```

Buka tab **Actions** di repo GitHub kamu → run terbaru **"Build debug APK"** akan:
1. Clone `2dust/AndroidLibXrayLite` dan build `libv2ray.aar` via gomobile (job ini yang
   paling lama, wajar kalau makan waktu 10-20 menit — cross-compile Go+cgo buat 4 arsitektur
   Android bukan proses instan).
2. Taruh AAR itu ke `app/libs/libv2ray.aar`.
3. Compile APK debug project ini.

Hasil APK ada di **Artifacts** run tersebut (`xraylite-debug-apk`), tinggal diunduh dan
di-sideload ke HP Android.

Build lokal (`gradle assembleDebug` biasa) **tidak akan jalan** kecuali kamu taruh
`libv2ray.aar` sendiri di `app/libs/` terlebih dulu — build lokal tidak menjalankan tahap
gomobile dari CI. Kalau mau build lokal, jalankan langkah "Build libv2ray.aar" dari
`.github/workflows/build.yml` di komputer sendiri (butuh Go + Android NDK terpasang).

## Yang masih perlu kamu perhatikan

Jujur soal batas dari perbaikan yang bisa dilakukan tanpa akses ke Android SDK/NDK/Go
toolchain dan tanpa perangkat fisik untuk uji coba:

- **Belum pernah dicompile & dites di device sungguhan.** Semua perbaikan di atas saya
  verifikasi manual terhadap source sshj dan `AndroidLibXrayLite` yang sebenarnya (bukan
  tebakan), tapi saya tidak punya lingkungan Android untuk benar-benar menjalankan
  `gradle build`. Ada kemungkinan kecil masih ada typo/error yang baru kelihatan pas CI
  jalan pertama kali — kalau gagal, tempel log error-nya, saya bantu perbaiki.
- **Interface `CoreCallbackHandler` bisa berubah** kalau upstream `AndroidLibXrayLite`
  mengubah API-nya setelah tanggal ini (workflow selalu clone branch `main` mereka, bukan
  versi yang dipin). Kalau build gagal persis di `object : CoreCallbackHandler { ... }`
  dengan pesan "method tidak match", itu tandanya — sesuaikan tipe/nama method di
  `XrayVpnService.kt` sesuai pesan error compiler.
- **`PromiscuousVerifier()` di `SshTunnelManager`** menerima host key SSH apa saja (mirip
  `StrictHostKeyChecking=no`) — cukup aman untuk VPS milik sendiri yang kamu percaya, tapi
  rentan MITM di jaringan tidak tepercaya. Ganti ke verifier fingerprint kalau mau lebih aman.
- **Stats upload/download** dari `queryAllOutboundTrafficStats()` cuma akurat untuk trafik
  lewat jalur Xray (VLESS/VMess/Trojan); untuk SSH murni counter itu belum dihubungkan ke
  socket SSH (masih 0 di mode SSH).
- Ikon launcher yang saya buat cuma vector sederhana, ganti sesuai selera lewat
  `res/drawable/ic_launcher.xml`.

## Alur pakai aplikasi

1. Buka app → **"+ Import link"** untuk tempel `vless://`/`vmess://`/`trojan://`, atau
   **"+ Tambah SSH manual"** untuk isi host/user/password/private key SSH langsung lewat form.
2. Tekan **CONNECT** → untuk Xray, Android minta izin VPN (dialog sistem) sekali.
3. Untuk SSH murni, tidak ada TUN interface — hanya buka local SOCKS di device (port bisa
   diatur di form, default 1080), yang perlu diarahkan manual dari app lain (browser dengan
   proxy setting, dll).

## Catatan ukuran APK

- Kotlin native + `libv2ray.aar`: ~15-20MB
- sshj: ~500KB
- Total realistis: ~16-21MB per-APK

## Update: rebrand "OG Tunnel" + fitur baru (Settings, Show logs, tools, list management)

App di-rebrand jadi **OG Tunnel** (nama + icon, `applicationId`/package tetap
`com.lite.xraylite` — tidak diubah supaya minim risiko). Tiga kelompok fitur baru
ditambahkan (dipilih dari daftar referensi UI "NetMod"), protokol yang didukung
TIDAK berubah (masih VLESS/VMess/Trojan lewat Xray + SSH murni):

- **Layar Settings** (`SettingsActivity`, berbasis `androidx.preference`): General
  (IPv6, Socks local port, Hardware ID, Battery optimization, Split tunnel + app
  picker, Network log, Bypass LAN route), DNS, Xray options (FakeDNS, Allow
  insecure, Mux + concurrency + XUDP QUIC traffic, Fragment), SSH options
  (WakeLock, reconnect max attempt/interval, TLS version, buffer), HTTP ping
  (auto start + address), Appearance (tema). Semua field dibaca dari
  `settings/Prefs.kt` dan yang genuinely applicable BENAR-BENAR dipakai di
  `ConfigParser` (Fragment/Mux/FakeDNS/TLS via outbound Xray resmi, dicek ke
  docs xtls.github.io) dan `XrayVpnService`/`SshTunnelManager` (IPv6, Bypass LAN
  lewat `excludeRoute()` Android 13+, split tunnel, WakeLock, auto-reconnect).
  Beberapa toggle (Proxy tethering, VPN tethering root, Content Sniffing,
  Resolve outbound externally, SSH send/receive buffer) masih **placeholder**
  (tersimpan tapi belum benar-benar mengubah perilaku) — ditandai jelas di
  komentar `Prefs.kt` supaya tidak ada yang salah kira fitur itu sudah jalan.
- **Show logs + tools** (`LogActivity`, `util/Logger.kt`, `util/HttpPingTester.kt`,
  `NetworkToolsActivity`): log ring-buffer in-memory yang diisi dari
  `XrayLiteApp`/`XrayVpnService`/`SshTunnelManager` (start/stop service, versi
  app+core, WakeLock, hasil HTTP ping periodik), dengan tombol copy & clear di
  toolbar. Drawer menu nambah "What's my IP?" (lewat ipapi.co), "Host to IP"
  (`InetAddress.getAllByName`), "Host checker" (test TCP connect), "Settings",
  "About", "Share".
- **List management**: toolbar overflow nambah "Import from file" (pilih file
  teks, satu link per baris), "Sort by" (nama/ping/tipe), "View as" (list/grid,
  `GridLayoutManager`), "Ping tool" (ping ulang semua server sekaligus), "Find
  selected" (scroll ke server aktif).

Sebagai bonus perbaikan mendasar: `ServerRepository` yang tadinya **murni
in-memory** (semua server hilang tiap app di-kill) sekarang dipersist ke
SharedPreferences (Gson) — supaya "Import from file" benar-benar berguna lintas
sesi. Kalau build release (`isMinifyEnabled=true`), pastikan `proguard-rules.pro`
yang sudah ditambah `-keepattributes Signature` ikut ke-apply, kalau tidak
deserialize `List<ServerConfig>` bisa rusak.

## Update 2: perbaikan bug besar (SSH tidak jalan, notifikasi hilang) + edit/copy per-item + rebrand visual

Laporan user setelah instal APK hasil Update 1: Xray tidak bisa diedit/copy link, SSH
sama sekali tidak jalan, tidak ada notifikasi VPN, tampilan masih generic (ungu bawaan
Material), tombol tambah masih di bawah, dan SSH butuh mode payload+proxy & SNI. Semua
sudah ditelusuri akar masalahnya dan diperbaiki, bukan ditambal asal jalan:

1. **Bug arsitektur SSH (paling krusial):** sebelumnya mode SSH murni CUMA bikin local
   port-forward (`SshTunnelManager`) tanpa pernah bikin `VpnService`/TUN sama sekali --
   jadi trafik device tidak pernah benar-benar lewat situ (makanya "gak bisa dipakai
   sama sekali") DAN karena tidak lewat `XrayVpnService`, `startForeground()` juga
   tidak pernah dipanggil (makanya "notifikasi gak ada"). Sekarang SSH JUGA lewat
   `XrayVpnService`: SSH konek dulu bikin SOCKS lokal, baru TUN dibuka dengan config
   Xray minimal yang outbound-nya "socks" menunjuk ke SOCKS lokal itu -- tun2socks
   bawaan Xray-core yang urus penyaluran paket TUN ke tunnel SSH-nya. Satu jalur kode
   dipakai ulang untuk Xray maupun SSH, termasuk notifikasi foreground-nya.
2. **Edit / Copy link / Hapus per server:** sebelumnya menu ini memang belum pernah
   ada sama sekali di kode (bukan hilang, tapi belum dibuat) -- sekarang ada ikon titik
   tiga di tiap baris server yang membuka Edit (form generik utk VLESS/VMess/Trojan,
   form lengkap utk SSH), Copy link (generate ulang `vless://`/`vmess://`/`trojan://`/
   `ssh://` dari config tersimpan), dan Hapus.
3. **Log & swipe:** geser layar utama ke kiri/kanan sekarang langsung membuka "Show
   logs" (`GestureDetector` + transisi slide), dan menekan CONNECT otomatis membuka
   layar log itu juga supaya proses konek kelihatan real-time, bukan cuma bisa diakses
   lewat drawer.
4. **Tampilan generic:** ternyata project ini dari awal TIDAK PERNAH punya
   `colors.xml` sama sekali, jadi seluruh app diam-diam pakai warna default Material
   Components (ungu). Sekarang ada palet emas/hitam (`colors.xml` + `themes.xml`)
   senada logo OG Tunnel.
5. **Tombol tambah dipindah:** "+ Import link" dan "+ Tambah SSH manual" yang tadinya
   tombol besar di bawah list, sekarang jadi satu ikon "+" di pojok kanan atas toolbar
   (`actionAddServer`), sesuai pola app VPN lain.
6. **SSH: mode SNI + payload/proxy:** [`ServerConfig`] nambah field `sshUseTls`+
   `sshSni` (bungkus TLS dgn SNI custom sebelum handshake SSH) dan `sshPayload`+
   `sshProxyHost`/`sshProxyPort` (kirim HTTP payload custom, opsional lewat bug host,
   sebelum handshake SSH). Diimplementasikan lewat `javax.net.SocketFactory` custom
   yang di-inject ke `SSHClient` -- ini pola resmi yang didokumentasikan sshj sendiri
   untuk connect via proxy/socket custom, bukan reka-reka. **Keterbatasan yang disadari
   dan didokumentasikan di kode:** proses "buang respons HTTP awal" mengasumsikan bug
   host benar-benar membalas header HTTP dulu sebelum byte SSH mengalir -- kalau ada
   bug host yang langsung terusin byte SSH mentah tanpa respons apa pun, payload mode
   bisa salah makan sebagian banner SSH (soalnya `java.net.Socket` tidak punya
   "unread"). Kalau ketemu kasus itu, kosongkan field Payload dan pakai mode SNI/proxy
   saja.

Field baru di `ServerConfig` (sshUseTls, sshSni, sshPayload, sshProxyHost,
sshProxyPort) otomatis ke-cover `-keepclassmembers class com.lite.xraylite.model.**`
yang sudah ada di proguard-rules.pro, jadi tidak perlu ubah apa pun di situ.

## Update 3: fix bug "allowInsecure removed" (Xray gagal connect total) + redesign total halaman utama

### Bug fix kritis: SEMUA koneksi Xray gagal connect
Dari log yang dikirim user:
```
Gagal start core: config error: ... Failed to build TLS config. > common/errors:
The feature "allowInsecure" has been removed and migrated to "pinnedPeerCertSha256"(pcs)
and "verifyPeerCertByName"(vcn).
```
Xray-core versi terbaru (project ini build AAR-nya otomatis dari source Go terbaru lewat
CI, jadi otomatis ikut versi baru) **menghapus total** field `allowInsecure` dari
`tlsSettings` -- bukan cuma deprecated, tapi config langsung DITOLAK kalau field ini masih
ada, sehingga di versi sebelumnya **semua** koneksi VLESS/VMess/Trojan (bukan cuma yang
butuh insecure) gagal start. Fix: field `allowInsecure` sudah TIDAK ditulis ke JSON sama
sekali di `ConfigParser.buildStreamSettings()`. Server dengan sertifikat valid (Let's
Encrypt dkk, kasus paling umum) connect normal seperti biasa; server dengan sertifikat
self-signed sekarang akan gagal TLS handshake -- itu keterbatasan Xray-core sekarang
(penggantinya `pinnedPeerCertSha256`, belum diimplementasikan di app ini karena butuh user
tahu hash SHA256 sertifikat server-nya lebih dulu). Toggle "Allow insecure (TLS)" di
Settings summary-nya sudah diupdate supaya jujur bilang sudah tidak berefek.

### Redesign total halaman utama (Beranda / Server / Tentang + bottom nav)
Sesuai referensi gambar dari user:
- Header custom: hamburger, logo teks "OG Tunnel" + tagline "FAST • STABLE • SECURE",
  ikon gear (langsung ke Settings, gak lewat drawer lagi).
- Tombol CONNECT jadi lingkaran besar dengan ring emas, diapit teks tagline dekoratif
  kiri-kanan ("SIMPLE STABLE SECURE EVERYWHERE" / "Connect To a Better Tomorrow").
- Kartu statistik (Durasi/Upload/Download) dengan ikon bulat.
- Quick action grid 2x2: Pengaturan, Log, **Tes Kecepatan (fitur baru)**, Bagikan.
- Banner "Koneksi Aman" yang bisa ditap, isinya dinamis sesuai status+tipe koneksi aktif.
- **Bottom navigation 3 tab** (Beranda/Server/Tentang) -- diimplementasikan sebagai 3
  section yang ditukar visibility-nya (BUKAN Fragment terpisah), supaya tidak menambah
  risiko masalah lifecycle Fragment tanpa bisa compile-test.
- Tab **Server**: daftar server penuh + toolbar mini (+, ping semua, dan menu "..." utk
  Import from file/Sort by/View as/Find selected).
- Item server didesain ulang total meniru referensi: badge ping berwarna (hijau <150ms,
  emas 150-400ms, merah di atasnya/timeout), 3 ikon langsung (share/edit/delete, bukan
  disembunyikan di menu overflow lagi), tag protokol berwarna di bagian bawah kartu.
- **Fitur baru: Tes Kecepatan** -- download benchmark dari endpoint resmi
  `speed.cloudflare.com/__down` (dipakai juga speedtest browser resmi Cloudflare),
  maks. 10 detik/20MB, dihitung throughput real-time dalam Mbps. Kalau VPN sedang aktif,
  otomatis ikut lewat tunnel karena seluruh trafik device dirutekan lewat VpnService.

Semua drawable ikon baru (share/edit/delete/power/clock/upload/download/shield/chevron/
gear/nav-home/nav-server/nav-about/speed/log) dibuat sebagai vector drawable manual (bukan
Material Icons library, supaya tidak nambah dependency) -- kalau mau ganti ke ikon yang
lebih detail/branded, tinggal timpa file-file `res/drawable/ic_*.xml` yang bersangkutan.
