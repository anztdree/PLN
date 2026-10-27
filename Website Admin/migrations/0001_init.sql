-- PLN Admin — skema awal (M1)
-- Catatan retensi: baris lebih tua dari (bulan berjalan - 2) dihapus otomatis oleh cron di M5.

CREATE TABLE IF NOT EXISTS reports (
  id             TEXT PRIMARY KEY,
  status         TEXT NOT NULL DEFAULT 'BARU',
  nama_pelanggan TEXT NOT NULL,
  id_pelanggan   TEXT,
  alamat         TEXT,
  plus_code      TEXT,
  latitude       REAL,
  longitude      REAL,
  catatan        TEXT,
  created_at     TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_created_at ON reports (created_at);
CREATE INDEX IF NOT EXISTS idx_reports_status     ON reports (status);

CREATE TABLE IF NOT EXISTS photos (
  id         TEXT PRIMARY KEY,
  report_id  TEXT NOT NULL,
  r2_key     TEXT NOT NULL,
  urutan     INTEGER NOT NULL DEFAULT 1,
  ukuran_kb  INTEGER,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_photos_report ON photos (report_id);
