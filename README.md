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

## Terbaru (v1.14.0-m14 / APK Admin code 4)
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
