# Website Admin PLN — Server Pusat

Backend + (nanti) dashboard admin untuk menerima laporan dari APK Petugas.
Berjalan 100% di **Cloudflare free tier** — tanpa VPS, tanpa biaya.

## Komponen
- **Workers + Hono** — API server
- **D1** (`pln-admin-db`) — database laporan
- **R2** (`pln-admin-photos`) — gudang foto
- **Cron** — pembersih data tua, tiap hari 00:10 WIB

## URL Produksi
```
https://pln-admin.andri-12744.workers.dev
```

## API (saat ini — M1)
| Endpoint | Metode | Fungsi |
|---|---|---|
| `/api/health` | GET | Cek server hidup |
| `/api/reports` | GET | Daftar 100 laporan terbaru |

## Rencana (M2+)
- `POST /api/reports` — kirim laporan + foto (multipart), wajib header `X-API-Key`
- `GET /api/reports/:id`, `DELETE /api/reports/:id`
- Retensi otomatis: bulan berjalan + 2 bulan sebelumnya

## Setup & Deploy
```bash
bun install
export CLOUDFLARE_API_TOKEN="<token>"
export CLOUDFLARE_ACCOUNT_ID="5feae6bee2c20ab606078c63934726bc"
bunx wrangler d1 execute pln-admin-db --remote --file=migrations/0001_init.sql -y
bunx wrangler deploy
```

## Struktur
```
src/index.ts        # API (Hono)
migrations/         # skema D1
wrangler.toml       # konfigurasi (binding D1, R2, cron)
```

> Kredensial TIDAK disimpan di repo. Simpan di `.env.cloudflare` (gitignored).
