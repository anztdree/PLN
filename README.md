# PLN — Repo Gabungan

Repositori backup untuk dua proyek sistem pencatatan pemasangan listrik:

| Folder | Isi |
|---|---|
| [`Petugas APK/`](Petugas%20APK/) | Aplikasi Android petugas (Kotlin + Jetpack Compose) — input data pelanggan, foto, koordinat GPS, Plus Code |
| [`Website Admin/`](Website%20Admin/) | Server pusat + dashboard admin (Cloudflare Workers + D1 + R2, gratis tier) + PWA |
| [`Admin APK/`](Admin%20APK/) | Aplikasi Android admin (WebView + notifikasi status bar + FAB obrolan native) untuk Website Admin |

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

## Terbaru (v1.13.0-m13 / APK code 3)
- Tombol obrolan HANYA di aplikasi (FAB native) — tab Obrolan dihapus dari web;
  keluar dari obrolan lewat tombol ✕ di kepala daftar obrolan.
- FAB native naik ke atas bilah navigasi web (tidak menumpuk/menutup tab).
- Laporan Excel & PDF dirapikan: logo PLN, judul "LAPORAN PEMASANGAN LAPANGAN",
  periode, tabel + foto — tanpa narasi/catatan otomatis/penutup.

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
