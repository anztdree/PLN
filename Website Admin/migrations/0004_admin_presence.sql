-- M7 — jejak kehadiran ADMIN WEB (1 baris, id selalu 1).
-- Diperbarui otomatis saat dasbor admin membuka data (polling dengan
-- X-API-Key). Dipakai endpoint /api/petugas untuk menampilkan entri
-- ADMIN di baris pertama daftar lengkap dengan status online/offline.
CREATE TABLE IF NOT EXISTS admin_presence (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  last_seen_at TEXT NOT NULL DEFAULT ''
);

INSERT OR IGNORE INTO admin_presence (id, last_seen_at) VALUES (1, '');
