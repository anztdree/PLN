# PLN — Repo Gabungan

Repositori backup untuk dua proyek sistem pencatatan pemasangan listrik:

| Folder | Isi |
|---|---|
| [`Petugas APK/`](Petugas%20APK/) | Aplikasi Android petugas (Kotlin + Jetpack Compose) — input data pelanggan, foto, koordinat GPS, Plus Code |
| [`Website Admin/`](Website%20Admin/) | Server pusat + dashboard admin (Cloudflare Workers + D1 + R2, gratis tier) |

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

## Build APK
```bash
cd "Petugas APK"
./gradlew assembleDebug
```
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
