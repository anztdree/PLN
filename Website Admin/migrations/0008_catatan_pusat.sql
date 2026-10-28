-- PLN Admin — v1.16.0-m17: CATATAN PUSAT untuk laporan
-- Admin pusat kini menulis catatan saat mengubah status laporan
-- (TERTUNDA <-> SELESAI) atau lewat tombol catatan sendiri; catatan itu
-- tampil di web admin dan DIKIRIM ke APK Petugas (badge angka +
-- notifikasi) atas data yang pernah petugas kirim.
--   catatan_pusat    : isi catatan dari pusat (kosong = belum ada)
--   catatan_pusat_at : ISO-8601 UTC terakhir catatan ditulis/diubah
ALTER TABLE reports ADD COLUMN catatan_pusat TEXT;
ALTER TABLE reports ADD COLUMN catatan_pusat_at TEXT;

CREATE INDEX IF NOT EXISTS idx_reports_catatan_pusat_at ON reports (catatan_pusat_at);
