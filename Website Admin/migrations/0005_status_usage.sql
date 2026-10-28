-- PLN Admin — M9: 2 jenis status laporan + pemakaian free tier + status server
-- Jalankan: bunx wrangler d1 execute pln-admin-db --remote --file migrations/0005_status_usage.sql -y

-- ============================================================
-- 1. STATUS LAPORAN — TEPAT 2 JENIS: TERTUNDA & SELESAI
--    Nilai lama ('BARU', 'PENDING', dll.) dinormalisasi ke TERTUNDA.
--    Tidak ada baris yang dihapus — hanya kolom status diperbarui.
-- ============================================================
UPDATE reports SET status = 'TERTUNDA' WHERE status NOT IN ('SELESAI');

-- ============================================================
-- 2. PENGATURAN SERVER (key-value) — status server disimpan di sini
--    server_status : 'lancar' | 'maintenance' | 'down'
-- ============================================================
CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
INSERT OR IGNORE INTO settings (key, value) VALUES ('server_status', 'lancar');
INSERT OR IGNORE INTO settings (key, value) VALUES ('server_status_at', '');

-- ============================================================
-- 3. PEMAKAIAN HARIAN FREE TIER (dicatat worker, hari = UTC —
--    limit Cloudflare direset 00:00 UTC)
--    requests   : permintaan /api/* hari itu (aset statis gratis)
--    rows_read  : baris D1 dibaca  (limit free 5.000.000/hari)
--    rows_written: baris D1 ditulis (limit free 100.000/hari)
-- ============================================================
CREATE TABLE IF NOT EXISTS usage_daily (
  day          TEXT PRIMARY KEY,
  requests     INTEGER NOT NULL DEFAULT 0,
  rows_read    INTEGER NOT NULL DEFAULT 0,
  rows_written INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL DEFAULT ''
);
