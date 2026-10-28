-- PLN Admin — M10: riwayat kesehatan server (pemeriksaan otomatis tiap 5 menit)
-- ts : ISO timestamp pemeriksaan
-- ms : waktu tanggap /api/health dalam milidetik
-- ok : 1 = berhasil, 0 = gagal/timeout
CREATE TABLE IF NOT EXISTS server_health (
  ts TEXT PRIMARY KEY,
  ms INTEGER NOT NULL,
  ok INTEGER NOT NULL
);
