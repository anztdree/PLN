# Website Admin PLN — Server Pusat

Backend + dashboard admin untuk menerima laporan dari APK Petugas.
Berjalan 100% di **Cloudflare free tier** — tanpa VPS, tanpa biaya.
Tampilan: tema terang **"Navy Teknis"** (v1.12.0-m12), mobile-first, PWA, tanpa web font.

## Komponen
- **Workers + Hono** — API server
- **D1** (`pln-admin-db`) — database laporan & obrolan
- **R2** (`pln-admin-photos`) — gudang foto
- **Cron** — pembersih data tua, tiap hari 00:10 WIB
- **Web admin statis** (`public/`) — dilayani langsung dari worker ini, PWA (manifest + service worker)

## URL Produksi
```
https://pln-admin.andri-12744.workers.dev
```
> Alamat server SENGAJA tidak ditampilkan di dalam web (kartu Server) demi keamanan.

## Baru di v1.12.0-m12
- **Obrolan kembali jadi TAB menu** — FAB dihapus dari web/PWA; tombol obrolan melayang kini NATIVE di Aplikasi Admin (seperti WhatsApp, terpisah dari menu) dengan lencana pesan belum dibaca. Web menyediakan `window.__plnOpenChat` + jembatan `PlnApp.fab()` agar FAB native ikut sembunyi saat tab Obrolan terbuka.
- **Countdown reset kuota harian Cloudflare** di kartu Server — hitung mundur langsung ke 07.00 WIB (00.00 UTC), menggantikan kalimat pemantauan yang kaku.
- **Laporan Excel & PDF bergaya karangan** — judul besar, periode, NARASI PEMBUKA otomatis dari data nyata (jumlah laporan, petugas, selesai/tertunda, foto), tabel foto tetap di samping Nama Pelanggan, blok "Catatan otomatis" (mis. "Foto tampil pada kolom Foto — tersusun urut sesuai unggahan petugas."), kalimat penutup, dan footer — semuanya dihasilkan otomatis, tanpa kolom jam.
- **APK Admin v1.0.0 (code 2)** — FAB obrolan native di atas WebView + lencana merah + notifikasi pesan masuk saat aplikasi terbuka (loop 20 dtk) & tertutup (WorkManager 15 menit) + ikon launcher BARU emas-petir-navy agar jelas berbeda dari APK Petugas.

## Riwayat v1.11.0-m11
- Kartu Server ikonik (gauge kelancaran % + judul semangat + chip ms) + bar penyimpanan foto R2 nyata vs kuota gratis 10 GB; export Excel & PDF kolom Foto tepat setelah Nama Pelanggan.

## API (v1.12.0-m12)
| Endpoint | Metode | Fungsi |
|---|---|---|
| `/api/health` | GET | Cek server hidup + statistik |
| `/api/dashboard` | GET¹ | SEMUA data web dalam 1 request (statistik, petugas+admin, laporan+foto) |
| `/api/server` | GET | Kualitas server terukur (kelancaran % + ms dari pemeriksaan otomatis) + pemakaian hari ini dari analitik Cloudflare (angka nyata) + 7 hari terakhir |
| `/api/server/ping` | POST² | Laporan latensi nyata dari dasbor browser (maks 1x/60 dtk) |
| `/api/petugas` | GET | Daftar petugas + status online |
| `/api/petugas/register` | POST² | Daftar/pulihkan petugas (APK) |
| `/api/petugas/heartbeat` | POST² | Detak online (APK) |
| `/api/petugas/:id` | DELETE² | Hapus petugas dari daftar |
| `/api/reports` | GET | 100 laporan terbaru |
| `/api/reports` | POST² | Kirim laporan + foto (multipart, APK) — status dinormalisasi ke TERTUNDA/SELESAI |
| `/api/reports/:id/status` | PATCH² | Ganti status laporan TERTUNDA ↔ SELESAI (admin web) |
| `/api/export/month.xlsx` | GET³ | Unduh laporan 1 bulan sebagai Excel asli |
| `/api/export/month.pdf` | GET³ | Unduh laporan 1 bulan sebagai PDF A4 landscape |
| `/api/photos/:id` | GET | Foto dari R2 |
| `/api/chat/send` | POST² | Kirim pesan (global/private) |
| `/api/chat/messages` | GET | Ambil pesan + read receipts |
| `/api/chat/previews` | GET¹ | Pesan terakhir tiap percakapan (1 request) |
| `/api/chat/summary` | GET | Ringkasan percakapan utk notifikasi APK |

¹ header `X-API-Key` opsional (mencatat kehadiran admin) · ² wajib `X-API-Key` · ³ `X-API-Key` header atau `?key=`

## Fitur Web Admin (M9)
- **3 widget dasbor**: Petugas Terdaftar · Jumlah Laporan Masuk (+rincian tertunda/selesai) · Petugas Sedang Aktif
- **2 jenis laporan untuk semua**: TERTUNDA & SELESAI — chip filter berjumlah, tombol "Tandai Selesai/Tertunda" di tiap kartu
- **Kartu Server**: kualitas terukur (Lancar/Lambat/Gangguan + persen kelancaran + ms rata-rata — dari pemeriksaan otomatis tiap 5 menit & latensi dasbor, BUKAN pengaturan manual) + progres pemakaian nyata dari analitik Cloudflare (permintaan API /100.000, baris D1 dibaca /5.000.000, ditulis /100.000) + foto R2 & ukuran basis data — **tanpa URL**
- **Obrolan**: admin bisa menghapus pesan (ketuk gelembung) + pesan lama dibersihkan otomatis setelah 30 hari (cron tiap 5 menit)
- **Export Excel & PDF**: laporan bulanan bergaya karangan (judul, narasi otomatis, tabel, catatan otomatis, penutup) — seluruh FOTO lapangan tertanam di kolom Foto tepat setelah Nama Pelanggan, hanya tanggal WIB tanpa jam
- **Unduh Laporan Bulanan**: Excel (.xlsx asli) & PDF (A4 landscape) — dibuat murni di worker tanpa dependensi
- Cepat: 1 request dasbor tiap 10 dtk, pratinjau obrolan 1 request, hidrasi dari cache lokal, render hanya saat data berubah, tanpa web font
- PWA: bisa di-Install ke layar utama (Android), shell ter-cache, offline fallback

## Setup & Deploy
```bash
bun install
export CLOUDFLARE_API_TOKEN="<token>"
export CLOUDFLARE_ACCOUNT_ID="5feae6bee2c20ab606078c63934726bc"
bunx wrangler d1 execute pln-admin-db --remote --file=migrations/0005_status_usage.sql -y
bunx wrangler deploy
```

## Struktur
```
src/index.ts        # API (Hono) + pelacak pemakaian free tier
public/             # web admin + PWA (index.html, style.css, app.js, sw.js, manifest)
migrations/         # skema D1 (0005 = status 2 jenis + settings + usage_daily)
wrangler.toml       # konfigurasi (binding D1, R2, cron)
```

> Kredensial TIDAK disimpan di repo. Simpan di `.env.cloudflare` (gitignored).
