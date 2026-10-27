# Website Admin PLN — Server Pusat

Backend + dashboard admin untuk menerima laporan dari APK Petugas.
Berjalan 100% di **Cloudflare free tier** — tanpa VPS, tanpa biaya.
Tampilan: tema terang "KINETIK" (v1.8-m9), mobile-first, tanpa web font.

## Komponen
- **Workers + Hono** — API server
- **D1** (`pln-admin-db`) — database laporan & obrolan
- **R2** (`pln-admin-photos`) — gudang foto
- **Cron** — pembersih data tua, tiap hari 00:10 WIB
- **Web admin statis** (`public/`) — dilayani langsung dari worker ini

## URL Produksi
```
https://pln-admin.andri-12744.workers.dev
```

## API (v1.8.0-m9)
| Endpoint | Metode | Fungsi |
|---|---|---|
| `/api/health` | GET | Cek server hidup + statistik |
| `/api/web/bootstrap` | GET¹ | SEMUA data web dalam 1 request (statistik, petugas, laporan, pratinjau obrolan) |
| `/api/petugas` | GET | Daftar petugas + status online |
| `/api/petugas/register` | POST | Daftar/pulihkan petugas (APK) |
| `/api/petugas/heartbeat` | POST | Detak online (APK) |
| `/api/petugas/:id` | DELETE² | Hapus petugas dari daftar |
| `/api/reports` | GET | 100 laporan terbaru; `?month=YYYY-MM` = satu bulan (WIB, utk unduh rekap) |
| `/api/reports` | POST² | Kirim laporan + foto (multipart, APK) |
| `/api/photos/:id` | GET | Foto dari R2 |
| `/api/chat/send` | POST² | Kirim pesan (global/private) |
| `/api/chat/messages` | GET | Ambil pesan + read receipts |
| `/api/chat/summary` | GET | Ringkasan percakapan utk notifikasi APK |

¹ wajib header `X-API-Key` · ² wajib `X-API-Key`

## Fitur Web Admin (M9)
- Tema terang baru, responsif Android (tab bawah) & desktop (dua panel obrolan)
- Cepat: 1 request bootstrap tiap 10 dtk, render hanya saat data berubah, polling berhenti saat layar tidak aktif, tanpa web font
- **Unduh Rekap Bulanan**: pilih bulan + tahun → **Excel (.xlsx)** & **PDF (A4 landscape)** — dibuat langsung di browser tanpa library/CDN (`public/exporters.js`)

## Setup & Deploy
```bash
bun install
export CLOUDFLARE_API_TOKEN="<token>"
export CLOUDFLARE_ACCOUNT_ID="5feae6bee2c20ab606078c63934726bc"
bunx wrangler deploy
```

## Struktur
```
src/index.ts        # API (Hono)
public/             # web admin (index.html, style.css, app.js, exporters.js)
scripts/            # uji generator xlsx/pdf lokal (bun scripts/uji-export.mjs)
migrations/         # skema D1
wrangler.toml       # konfigurasi (binding D1, R2, cron)
```

> Kredensial TIDAK disimpan di repo. Simpan di `.env.cloudflare` (gitignored).
