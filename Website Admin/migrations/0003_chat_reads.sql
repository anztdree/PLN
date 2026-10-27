-- PLN Admin — M4b: status pesan & "dilihat oleh"
-- Jalankan: wrangler d1 execute pln-admin-db --remote --file migrations/0003_chat_reads.sql

-- ============================================================
-- PEMBACA PERCAKAPAN (read receipts)
--   conv_key     : 'global' atau pasangan terurut 'a|b' untuk DM
--   reader_id    : id petugas (teks) atau 'admin'
--   reader_nama  : snapshot nama pembaca
--   last_read_id : pembaca sudah melihat semua pesan dgn id <= nilai ini
-- Update terjadi otomatis saat pembaca mengambil pesan (GET
-- /api/chat/messages) — membuka layar obrolan berarti membaca.
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_reads (
  conv_key     TEXT NOT NULL,
  reader_id    TEXT NOT NULL,
  reader_nama  TEXT NOT NULL,
  last_read_id INTEGER NOT NULL DEFAULT 0,
  updated_at   TEXT NOT NULL,
  PRIMARY KEY (conv_key, reader_id)
);
CREATE INDEX IF NOT EXISTS idx_chat_reads_conv ON chat_reads (conv_key, last_read_id);
