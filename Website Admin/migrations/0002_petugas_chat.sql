-- PLN Admin — M2: pondasi register petugas + obrolan
-- Jalankan: wrangler d1 execute pln-admin-db --remote --file migrations/0002_petugas_chat.sql

-- ============================================================
-- 1. LAPORAN — tambah kolom pengirim + kunci idempotensi
--    client_ref dikirim APK (format "L<id>-<createdAt>") supaya
--    laporan tidak dobel tersimpan saat kirim ulang.
-- ============================================================
ALTER TABLE reports ADD COLUMN petugas TEXT;
ALTER TABLE reports ADD COLUMN client_ref TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_reports_client_ref ON reports (client_ref);

-- ============================================================
-- 2. PETUGAS — 1 perangkat = 1 nama, tidak bisa diganti.
--    device_id  : UUID buatan aplikasi (identitas utama)
--    android_id : ID bawaan Android (pulihan setelah uninstall)
--    last_seen_at : diperbarui heartbeat -> status online admin web
-- ============================================================
CREATE TABLE IF NOT EXISTS petugas (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  nama         TEXT NOT NULL,
  device_id    TEXT NOT NULL,
  android_id   TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  last_seen_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_petugas_nama      ON petugas (nama);
CREATE UNIQUE INDEX IF NOT EXISTS idx_petugas_device    ON petugas (device_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_petugas_android   ON petugas (android_id);
CREATE INDEX        IF NOT EXISTS idx_petugas_last_seen ON petugas (last_seen_at);

-- ============================================================
-- 3. OBROLAN — global (grup umum, terpinah) + private (1 lawan 1)
--    sender_id : id petugas (angka sebagai teks) atau 'admin'
--    target_id : NULL untuk global; untuk private = lawan bicara
--    sender_nama : snapshot nama saat dikirim (nama tidak pernah berubah)
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_messages (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  scope       TEXT NOT NULL,
  sender_type TEXT NOT NULL,
  sender_id   TEXT NOT NULL,
  sender_nama TEXT NOT NULL,
  target_id   TEXT,
  body        TEXT NOT NULL,
  created_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chat_scope_id ON chat_messages (scope, id);
CREATE INDEX IF NOT EXISTS idx_chat_private  ON chat_messages (scope, target_id, id);
