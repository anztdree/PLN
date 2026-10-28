# PLN — Repo Gabungan

Repositori backup untuk dua proyek sistem pencatatan pemasangan listrik:

| Folder | Isi |
|---|---|
| [`Petugas APK/`](Petugas%20APK/) | Aplikasi Android petugas (Kotlin + Jetpack Compose) — input data pelanggan, foto, koordinat GPS, Plus Code |
| [`Website Admin/`](Website%20Admin/) | Server pusat + dashboard admin (Cloudflare Workers + D1 + R2, gratis tier) + PWA |
| [`Admin APK/`](Admin%20APK/) | Aplikasi Android admin (dasbor web + OBROLAN NATIVE penuh + notifikasi status bar + FAB) untuk Website Admin |

## Alur Sistem
```
APK Petugas ──kirim laporan + foto──▶ Website Admin (Cloudflare)
                                           ├── Workers  = API
                                           ├── D1       = database laporan
                                           ├── R2       = gudang foto
                                           └── Cron     = pembersih data tua
```

## Server Produksi
- URL : `https://pln-admin.andri-12744.workers.dev`
- Health check: `GET /api/health`

## Terbaru (v1.2.2 / APK Admin code 10 — Rombak Tampilan Obrolan)
- **Pemisah seksi daftar obrolan kini JELAS** (masukan pemilik: "pemisah
  antara grup dan list anggota online kurang jelas"): antara grup dan
  pesan pribadi kini ada **label seksi bergaya garis — pil — garis**:
  "GRUP DISKUSI" dan **"PESAN PRIBADI · n ONLINE"** (jumlah petugas
  online hidup mengikuti status).
- **Ikon tidak kaku lagi**: avatar grup **kotak emas gradasi + garis
  tepi + bayangan**, avatar pribadi **lingkaran gradasi navy**,
  **titik online berdenyut lembut** (animasi scale berulang, dihentikan
  saat baris tak lagi online), tombol kembali/hapus/kirim kini
  **memantul saat disentuh** (mengecil lalu balik). Avatar kepala
  obrolan pribadi **bercincin hijau** saat lawan bicara online.
- **Wallpaper obrolan DIROMBAK**: latar **gradasi navy gelap** (bukan
  abu polos) + pola **titik lembut, kilau emas, dan petir PLN samar** —
  gaya aplikasi pesan premium; **gelembung putih** lawan bicara kini
  menonjol kontra latar gelap, gelembung admin tetap **emas gradasi**.
- **Gelembung kembali MEMBENTANG PENUH** (masukan pemilik: "bubble
  tetap memanjang full"): gelembung mengisi seluruh lebar baris
  bersama avatar 32dp di sisi — bukan lagi wrap-content 78%; jam rata
  kanan di dalam gelembung, status "Terkirim / Dilihat oleh / Gagal"
  tetap di bawah rangkaian.
- **Detail baru**: chip tanggal jadi **pil gelap translusen** menyatu
  dengan wallpaper, nama pengirim di grup warna **emas**, kepala panel
  daftar diberi **label "PLN · PUSAT KENDALI" + gradasi navy + garis
  emas**, kepala obrolan gradasi navy + **garis emas tipis** di bawahnya,
  pratinjau pesan **menebal otomatis** saat ada yang belum dibaca,
  keadaan kosong daftar kini ada teks penuntun.
- **APK Admin**: versionCode **10**, versionName tetap **1.0.0**
  (dibekukan sesuai permintaan pemilik). Rilis:
  `PLN-Admin-v1.0.0-code10-release.apk` (ttd kunci sama — bisa dipasang
  langsung tanpa hapus-install ulang).

## Rilis Sebelumnya (v1.2.1 / APK Admin code 9 — Tema Native Obrolan)
- **Statistik "Pesan Belum Dibaca" DIHAPUS dari dasbor** (permintaan
  pemilik) — kartu banner di sebelah "Petugas Sedang Aktif" tidak ada
  lagi; dasbor kini 3 kartu statistik (ponsel: 2 + 1 lebar penuh,
  desktop: 3 sejajar). Lencana pesan tetap ada di FAB & notifikasi.
- **FAB obrolan ditingkatkan (APK Admin)**: badan lingkaran **emas
  gradasi PLN** berbingkai navy (senada gelembung pesan admin), kilau
  lembut (halo) di sekeliling, bayangan lebih dalam, ikon chat navy
  kontras, **animasi tekan memantul** (mengecil saat disentuh, memantul
  balik saat dilepas), lencana merah berbingkai putih lebih rapi.
- **Tema obrolan native ("native apps obrolan")** — ChatActivity code 9:
  - **Wallpaper obrolan berpola titik lembut** (gaya aplikasi pesan,
    dibuat programatik — tanpa berkas gambar).
  - **Kepala obrolan kaya**: avatar lawan bicara (kotak kuning ikon
    forum utk grup / lingkaran inisial utk pribadi), status **"online"
    hijau**, tombol kembali & hapus riwayat kini **ikon vektor putih**
    (bukan "←" dan emoji 🗑 yang tampil beda tiap perangkat).
  - **Gelembung wrap-content**: pendek memuat isi, panjang membungkus
    (lebar maks ±78% layar), bersudut **"ekor" kecil di sisi avatar** +
    bayangan halus — persis rasa aplikasi pesan modern.
  - **Daftar obrolan jadi kartu membulat** berjarak lega (bukan baris
    kotak menempel), pemisah tanggal jadi pil putih berbayang.
  - **Bilah tulis baru**: tombol kirim emas gradasi + ikon pesawat
    kertas navy (pengganti "➤"), bilah dengan bayangan lebih tegas.
- **APK Admin**: versionCode **9**, versionName tetap **1.0.0**
  (dibekukan sesuai permintaan pemilik). Rilis:
  `PLN-Admin-v1.0.0-code9-release.apk` (ttd kunci sama — bisa dipasang
  langsung tanpa hapus-install ulang).
- Web: cache PWA naik (`pln-admin-v17`, aset `?v=121`).

## Rilis Sebelumnya (Tema Web v2.0 "ELEKTRIK NAVY" / APK Admin code 8)
- **Tema dasbor web diganti total — bukan minimalis lagi** (permintaan
  pemilik): kanvas navy malam + jala halus, panel kaca, gradasi emas PLN,
  aksen cyan, glow energi — identitas visual kini sependam Aplikasi
  Petugas ("Electric Premium"). Semua komponen ikut: appbar, tab, hero,
  kartu, daftar petugas/laporan, obrolan (pesan sendiri = gelembung EMAS
  PLN, sama dgn APK native), dialog, toast, lightbox. Tetap 0 font
  eksternal & ringan.
- **Fitur dasbor bertambah**:
  - **Jam hidup** di hero (detik berjalan; label zona jujur WIB/WITA/WIT
    sesuai perangkat).
  - **Statistik ke-4 "Pesan Belum Dibaca"** (ketuk → tab Obrolan);
    grid statistik kini 2×2 di ponsel, 4 sejajar di desktop.
  - **Kartu "Aktivitas Terbaru"**: 3 laporan termutakhir + lencana
    status + waktu relatif; ketuk baris/kartu → tab Laporan.
  - **Kartu "Laporan 7 Hari"**: grafik batang mini per hari (emas;
    hari ini cyan) + total pekan + delta vs kemarin.
- **Perbaikan**: kartu dasbor tak bisa lagi meluber keluar layar
  (min-width 0 — laporan dengan alamat panjang sekalipun); navigasi tab
  memakai delegasi klik (tab bawah/atas, tile statistik & tombol
  "Lihat semua" sama-sama jalan).
- **APK Admin**: versionName DIBEKUKAN di **1.0.0** (permintaan pemilik —
  pembaruan ditandai versionCode, kini **code 8**). **Rilis pertama
  versi RELEASE**: `PLN-Admin-v1.0.0-code8-release.apk` menggantikan
  APK debug lama — ditandatangani kunci yang sama dgn APK terpasang,
  jadi bisa dipasang langsung TANPA hapus-install ulang.
- Web: cache PWA naik (`pln-admin-v16`, aset `?v=120`), warna tema
  manifest/`theme-color` mengikuti navy malam.

## Rilis Sebelumnya (v1.2.0 / APK Admin code 7)
- **Obrolan Admin kini semirip mungkin dengan versi Petugas**:
  - Gelembung kotak penuh gaya Petugas (avatar bulat 32dp di sisi +
    gelembung membentang penuh, sudut 12dp); pesanku emas PLN,
    pesan lawan kartu terang.
  - Nama pengirim tampil DI ATAS gelembung (grup, awal rangkaian);
    jam hanya di ujung rangkaian pesan beruntun (gap < 5 menit).
  - **Status pesan admin**: "Terkirim" → "Dilihat oleh: <nama>" di
    bawah pesan admin terakhir (sumber: chat_reads, sama seperti Petugas).
  - **Kirim tanpa menggantung**: teks langsung pindah jadi gelembung
    "Mengirim…" pudar, jadi pesan sungguhan saat server OK; gagal →
    merah "Gagal · ketuk untuk kirim ulang" (ketuk = kirim ulang);
    beberapa pesan boleh antre sekaligus.
- **Jarak baris daftar obrolan diperjelas** — grup "Diskusi Petugas"
  tidak lagi menempel dengan baris petugas di bawahnya.
- **Avatar grup diganti**: kotak kuning + ikon forum (dua gelembung
  navy) — jelas terlihat, bukan lagi ⚡ bulat yang nyaris tak tampak.
- Rilis GitHub: tag `admin-v1.2.0`, berkas `PLN-Admin-v1.2.0-debug.apk`
  (menggantikan admin-v1.0.0; memuat semua perbaikan code 5–7).

## Rilis Sebelumnya (v1.15.1-m16 / APK Admin code 6)
- **Bug fix — hapus riwayat chat tidak lagi menghilangkan grup utama**:
  setelah SELURUH riwayat grup dihapus, baris "Diskusi Petugas" tetap ada
  di daftar obrolan (web & Aplikasi Admin) dengan keterangan "Belum ada
  pesan" — server kini selalu mengirim objek grup di `/api/chat/previews`.
- **Tombol hapus laporan**: tiap kartu laporan kini punya tombol sampah —
  menghapus laporan terpilih beserta seluruh fotonya di R2
  (`DELETE /api/reports/:id`, wajib X-API-Key).
- **Tombol refresh dihapus** dari header web (data menyegarkan otomatis
  tiap 10 detik).
- **Ikon bulat kuning ⚡ di samping tombol hapus riwayat dihapus** dari
  header obrolan native Aplikasi Admin.
- APK Admin naik ke versionCode 6 (versionName 1.1.1).

## v1.15.0-m15 / APK Admin code 5
- **Hapus riwayat chat kembali lengkap**: tombol sampah di header percakapan
  (web & obrolan native Aplikasi Admin) menghapus SELURUH isi satu percakapan —
  grup "Diskusi Petugas" atau satu DM pribadi — untuk semua pengguna, dengan
  dialog konfirmasi. Lencana belum dibaca & "dilihat oleh" mulai dari nol
  (`DELETE /api/chat/history`, catatan pembaca ikut dibersihkan).
- **Excel: logo & tata letak diperbaiki total**: semua gambar kini
  `twoCellAnchor` terkunci ke geometri sel — di penampil Excel ponsel logo
  tidak lagi menimpa judul dan foto tidak meluber antar baris/kolom (penyebabnya
  `oneCellAnchor`+`ext` salah dihitung sebagian penampil). Logo diganti PNG
  transparan 48px terpusat di sel A1. Print/PDF dari Excel kini landscape A4
  fit-to-width, tabel utuh satu lebar halaman.
- **Teks web dipangkas** jadi efisien (tanpa kalimat penjelas untuk pemula):
  kartu unduh, hint export, catatan server, empty state, dialog konfirmasi,
  dan kata kondisi server ("Ngebut!/rewel" diganti "Kondisi prima/lancar/...").
- Notifikasi status bar, FAB native, dan hapus petugas/pesan tetap seperti
  rilis sebelumnya (code 4).

## v1.14.0-m14 / APK Admin code 4
- **Obrolan kini 100% NATIVE di Aplikasi Admin** — bukan lagi tampilan web:
  daftar percakapan (Diskusi Petugas + pesan pribadi tiap petugas) dengan
  pratinjau, lencana belum dibaca, status online, layar pesan bergaya
  aplikasi pesan (gelembung kiri/kanan, pemisah tanggal), dan kirim pesan
  langsung dari aplikasi sebagai Admin pusat.
- **Notifikasi status bar spesifik**: "Pesan baru dari <nama> — <isi pesan>",
  mengetuknya langsung membuka layar obrolan native. Tetap aktif walau
  aplikasi ditutup (WorkManager 15 menit + loop 20 detik saat terbuka).
- **FAB**: lencana penghitung tidak lagi terpotong (clipping dimatikan di
  FAB & induknya); klik FAB membuka obrolan native.
- **Hapus petugas/pesan diperbaiki**: penyebabnya dialog `confirm()` browser
  tidak berjalan di WebView aplikasi — kini web memakai dialog konfirmasi
  buatan sendiri (selalu jalan) + WebChromeClient ditambahkan di aplikasi.
- **Excel export lebih rapi**: logo PLN utuh (file logo yang cacat
  diganti), border tiap sel, baris zebra, teks wrap, lebar kolom pas,
  status berwarna (hijau SELESAI / merah TERTUNDA), foto seragam 2 sejajar.
- `/api/chat/previews` kini menyertakan daftar petugas + status online.

## Build APK
```bash
cd "Petugas APK" && ./gradlew assembleDebug   # aplikasi petugas
cd "Admin APK"   && ./gradlew assembleDebug   # aplikasi admin (PWA wrapper)
```
> Rilis memakai GitHub Releases — contoh: `admin-v1.0.0` berisi `PLN-Admin-v1.0.0-debug.apk`.
> `versionName` selalu `1.0.0`, hanya `versionCode` yang naik.

## Deploy Website Admin
```bash
cd "Website Admin"
bun install
export CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=...
bunx wrangler deploy
```

---
Manager : Imam Sahal F
PT PLN (PERSERO)
