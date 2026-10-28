-- PLN Admin — M10b: sumber pemeriksaan kesehatan
-- 'cron' = pemeriksaan ketersediaan tiap 5 menit (dari worker itu sendiri)
-- 'web'  = waktu tanggap nyata yang dilaporkan dasbor browser admin
ALTER TABLE server_health ADD COLUMN sumber TEXT NOT NULL DEFAULT 'cron';
