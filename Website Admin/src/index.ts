import { Hono } from 'hono'

type Bindings = {
  DB: D1Database
  PHOTOS: R2Bucket
  API_KEY: string
  ADMIN_NAME: string
}

const app = new Hono<{ Bindings: Bindings }>()

// ============================================================
// KONSTANTA
// ============================================================
const ONLINE_WINDOW_MS = 150 * 1000 // heartbeat <= 2,5 menit lalu = ONLINE (APK detak 60 dtk)
const MAX_PHOTOS = 8
const MAX_PHOTO_BYTES = 3 * 1024 * 1024 // APK kirim ±150-300KB, ini pagar anti-spam
const MAX_CHAT_CHARS = 2000

const nowIso = () => new Date().toISOString()

// Versi worker — naik tiap milestone, tampil di /api/health & dasbor web
const VERSION = '1.9.0-m9'

// ============================================================
// MIDDLEWARE — semua operasi TULIS wajib X-API-Key (anti spam).
// Operasi BACA (GET) terbuka untuk sementara; dipertatut saat
// web admin jadi (M3).
// ============================================================
// ============================================================
// PELACAK PEMAKAIAN FREE TIER (v1.9.0-m9)
// Hitungan disimpan dulu di memori isolate lalu dibilas (flush) ke
// tabel usage_daily maksimal sekali per 30 dtk / 50 permintaan —
// penulisan D1 tetap sangat hemat. Hari = UTC (limit free tier
// Cloudflare direset 00:00 UTC).
//   - requests    : setiap permintaan /api/* (aset statis TIDAK
//                   dihitung — dilayani sistem aset, gratis)
//   - rows_read / rows_written : dibaca dari meta D1 setiap query
// ============================================================
type D1MetaLike = { rows_read?: number; rows_written?: number }
const gUsage = { requests: 0, rowsRead: 0, rowsWritten: 0 }
let gNextFlushAt = 0

const usageDay = () => new Date().toISOString().slice(0, 10)

async function flushUsage(db: D1Database) {
  const reqs = gUsage.requests
  const rr = gUsage.rowsRead
  const rw = gUsage.rowsWritten
  gUsage.requests = 0
  gUsage.rowsRead = 0
  gUsage.rowsWritten = 0
  if (!reqs && !rr && !rw) return
  try {
    await db
      .prepare(
        `INSERT INTO usage_daily (day, requests, rows_read, rows_written, updated_at) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (day) DO UPDATE SET
           requests = requests + excluded.requests,
           rows_read = rows_read + excluded.rows_read,
           rows_written = rows_written + excluded.rows_written,
           updated_at = excluded.updated_at`
      )
      .bind(usageDay(), reqs, rr, rw, nowIso())
      .run()
  } catch {
    /* pemantauan tak boleh mengganggu layanan */
  }
}

function maybeFlushUsage(db: D1Database, waitUntil: (p: Promise<unknown>) => void) {
  if (!(gUsage.requests + gUsage.rowsRead + gUsage.rowsWritten)) return
  if (gUsage.requests >= 50 || Date.now() >= gNextFlushAt) {
    gNextFlushAt = Date.now() + 30_000
    waitUntil(flushUsage(db))
  }
}

/**
 * Bungkus D1Database agar meta.rows_read / rows_written tiap query
 * terhitung ke gUsage (angka nyata untuk dasbor "Pemakaian Free Tier").
 */
function countingDb(db: D1Database): D1Database {
  const add = (meta?: D1MetaLike) => {
    if (!meta) return
    gUsage.rowsRead += meta.rows_read ?? 0
    gUsage.rowsWritten += meta.rows_written ?? 0
  }
  const wrapStmt = (stmt: D1PreparedStatement): D1PreparedStatement => {
    const w = {
      __raw: stmt,
      bind: (...values: unknown[]) =>
        wrapStmt(stmt.bind(...(values as Parameters<D1PreparedStatement['bind']>))),
      first: async (..._args: unknown[]) => {
        const r = await stmt.all()
        add(r.meta)
        return (r.results?.[0] as unknown) ?? null
      },
      run: async () => {
        const r = await stmt.run()
        add(r.meta)
        return r
      },
      all: async () => {
        const r = await stmt.all()
        add(r.meta)
        return r
      },
      raw: async (...args: unknown[]) =>
        stmt.raw(...(args as Parameters<D1PreparedStatement['raw']>)),
    }
    return w as unknown as D1PreparedStatement
  }
  return {
    prepare: (q: string) => wrapStmt(db.prepare(q)),
    batch: async (stmts: D1PreparedStatement[]) => {
      const raws = (stmts as unknown as { __raw?: D1PreparedStatement }[]).map(
        (s) => s.__raw ?? (s as unknown as D1PreparedStatement)
      )
      const res = await db.batch(raws)
      for (const r of res as D1Result<unknown>[]) add(r.meta)
      return res
    },
    exec: (q: string) => db.exec(q),
    withSession: (constrain: string | null) => db.withSession(constrain as never),
    dump: () => db.dump(),
  } as unknown as D1Database
}

app.use('/api/*', async (c, next) => {
  gUsage.requests++
  c.env.DB = countingDb(c.env.DB) // env per-permintaan -> aman ditimpa
  await next()
  maybeFlushUsage(c.env.DB, (p) => c.executionCtx.waitUntil(p))
})

app.use('/api/*', async (c, next) => {
  const m = c.req.method
  if (m === 'GET' || m === 'HEAD' || m === 'OPTIONS') return next()
  if ((c.req.header('X-API-Key') ?? '') !== c.env.API_KEY) {
    return c.json({ ok: false, error: 'unauthorized' }, 401)
  }
  await next()
})

// ============================================================
// HEALTH CHECK
// ============================================================
async function countSafe(db: D1Database, table: string): Promise<number> {
  try {
    const row = await db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>()
    return row?.n ?? 0
  } catch {
    return 0
  }
}

/**
 * M7 — kehadiran admin web. Web admin memanggil endpoint dengan X-API-Key
 * tiap 10 detik; kita catat (dengan jeda minimal 60 dtk) sehingga status
 * online/offline admin bisa tampil di daftar petugas APK & web.
 */
async function adminSeen(db: D1Database): Promise<string> {
  const r = await db
    .prepare('SELECT last_seen_at FROM admin_presence WHERE id = 1')
    .first<{ last_seen_at: string }>()
  return r?.last_seen_at ?? ''
}

async function catatAdminHadir(db: D1Database) {
  const cur = await db
    .prepare('SELECT last_seen_at FROM admin_presence WHERE id = 1')
    .first<{ last_seen_at: string }>()
  const last = cur?.last_seen_at ? Date.parse(cur.last_seen_at) : 0
  if (Date.now() - last < 60_000) return // throttle: cukup sekali tiap 60 dtk
  await db
    .prepare(
      `INSERT INTO admin_presence (id, last_seen_at) VALUES (1, ?)
       ON CONFLICT (id) DO UPDATE SET last_seen_at = excluded.last_seen_at`
    )
    .bind(nowIso())
    .run()
}

app.get('/api/health', async (c) => {
  const [reports, photos, petugas, messages] = await Promise.all([
    countSafe(c.env.DB, 'reports'),
    countSafe(c.env.DB, 'photos'),
    countSafe(c.env.DB, 'petugas'),
    countSafe(c.env.DB, 'chat_messages'),
  ])
  return c.json({
    ok: true,
    service: 'PLN Admin API',
    version: VERSION,
    time: nowIso(),
    statistik: { reports, photos, petugas, messages },
  })
})

// ============================================================
// REGISTER PETUGAS (v1.6.0-m6)
// Aturan identitas:
//  1. Perangkat dikenal + NAMA SAMA  -> pulihkan profil lama ('restored').
//  2. Perangkat dikenal + NAMA BERBEDA -> perangkat PINDAH identitas:
//     - nama sudah terdaftar -> profil itu tersambung ke perangkat ('rebound')
//     - nama belum ada       -> petugas BARU dibuat ('created')
//     Profil lama TETAP ada di daftar tapi dilepas dari perangkat ini
//     (device_id/android_id ditandai 'bebas-<id>' — tidak lagi menerima
//     heartbeat, tampil offline; admin bisa menghapusnya lewat web).
//  3. Perangkat baru + nama sudah ada -> daftar ulang dgn nama sama
//     tersambung ke profil lama ('rebound').
// ============================================================
app.post('/api/petugas/register', async (c) => {
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  if (!body) return c.json({ ok: false, error: 'body_json_tidak_valid' }, 400)

  const nama = String(body.nama ?? '').trim().replace(/\s+/g, ' ')
  const deviceId = String(body.device_id ?? '').trim()
  const androidId = String(body.android_id ?? '').trim()

  if (nama.length < 2 || nama.length > 40)
    return c.json({ ok: false, error: 'nama_harus_2_sampai_40_karakter' }, 400)
  if (!deviceId || !androidId)
    return c.json({ ok: false, error: 'identitas_perangkat_hilang' }, 400)

  const db = c.env.DB
  const t = nowIso()

  type Row = { id: number; nama: string; created_at: string }

  const known = await db
    .prepare('SELECT id, nama, created_at FROM petugas WHERE android_id = ? OR device_id = ? LIMIT 1')
    .bind(androidId, deviceId)
    .first<Row>()

  const taken = await db
    .prepare('SELECT id, nama, created_at FROM petugas WHERE nama = ? LIMIT 1')
    .bind(nama)
    .first<Row>()

  // --- 1) Perangkat dikenal + nama sama -> pulihkan profil lama ---
  if (known && known.nama === nama) {
    await db
      .prepare('UPDATE petugas SET last_seen_at = ?, device_id = ?, android_id = ? WHERE id = ?')
      .bind(t, deviceId, androidId, known.id)
      .run()
    return c.json({
      ok: true,
      status: 'restored',
      petugas: { id: known.id, nama: known.nama, created_at: known.created_at },
      server_time: t,
    })
  }

  // --- 2) Perangkat dikenal tapi nama BERBEDA -> pindah identitas ---
  if (known) {
    // Lepaskan dulu ikatan perangkat dari profil lama agar nama perangkat
    // ini bebas dipakai identitas baru (tanda 'bebas-<id>' tetap unik).
    await db
      .prepare("UPDATE petugas SET device_id = 'bebas-' || id, android_id = 'bebas-' || id WHERE id = ?")
      .bind(known.id)
      .run()

    if (taken) {
      // Nama sudah terdaftar (perangkat lain) -> sambungkan profil itu
      // ke perangkat ini (aturan daftar ulang dengan nama sama).
      await db
        .prepare('UPDATE petugas SET device_id = ?, android_id = ?, last_seen_at = ? WHERE id = ?')
        .bind(deviceId, androidId, t, taken.id)
        .run()
      return c.json({
        ok: true,
        status: 'rebound',
        petugas: { id: taken.id, nama: taken.nama, created_at: taken.created_at },
        server_time: t,
      })
    }

    // Nama belum ada -> petugas BARU milik perangkat ini.
    const ins = await db
      .prepare('INSERT INTO petugas (nama, device_id, android_id, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)')
      .bind(nama, deviceId, androidId, t, t)
      .run()
    const newId = Number(ins.meta.last_row_id)
    await seedBacaGlobal(db, newId, nama)
    return c.json({
      ok: true,
      status: 'created',
      petugas: { id: newId, nama, created_at: t },
      server_time: t,
    })
  }

  // --- 3) Perangkat baru ---
  if (taken) {
    // Nama sudah terdaftar tapi perangkatnya baru — kasus APK di-reset data
    // atau install ulang (device_id dibuat ulang, android_id bisa berubah).
    // Aturan: pengguna cukup DAFTAR ULANG dengan nama yang sama -> profil lama
    // tersambung kembali (id & riwayat obrolan tetap), bukan ditolak.
    await db
      .prepare('UPDATE petugas SET device_id = ?, android_id = ?, last_seen_at = ? WHERE id = ?')
      .bind(deviceId, androidId, t, taken.id)
      .run()
    return c.json({
      ok: true,
      status: 'rebound',
      petugas: { id: taken.id, nama: taken.nama, created_at: taken.created_at },
      server_time: t,
    })
  }

  const ins = await db
    .prepare('INSERT INTO petugas (nama, device_id, android_id, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)')
    .bind(nama, deviceId, androidId, t, t)
    .run()
  const newId = Number(ins.meta.last_row_id)
  await seedBacaGlobal(db, newId, nama)
  return c.json({
    ok: true,
    status: 'created',
    petugas: { id: newId, nama, created_at: t },
    server_time: t,
  })
})

/**
 * Petugas BARU memulai dengan obrolan lama dianggap sudah terbaca
 * (baris baca 'global' disemai) supaya badge belum-terbaca di APK
 * tidak membanjiri angka dari riwayat sebelum dia mendaftar.
 */
async function seedBacaGlobal(db: D1Database, petugasId: number, nama: string) {
  const max = await db
    .prepare('SELECT MAX(id) AS m FROM chat_messages WHERE scope = ?')
    .bind('global')
    .first<{ m: number | null }>()
  if (!max?.m) return
  await db
    .prepare(
      `INSERT INTO chat_reads (conv_key, reader_id, reader_nama, last_read_id, updated_at)
       VALUES ('global', ?, ?, ?, ?)
       ON CONFLICT (conv_key, reader_id) DO NOTHING`
    )
    .bind(String(petugasId), nama, max.m, nowIso())
    .run()
}

// ============================================================
// HEARTBEAT — dipanggil APK saat aplikasi dibuka + tiap 3 menit
// selama aplikasi terbuka. Admin web menghitung status online
// dari last_seen_at.
// ============================================================
app.post('/api/petugas/heartbeat', async (c) => {
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  if (!body) return c.json({ ok: false, error: 'body_json_tidak_valid' }, 400)

  const deviceId = String(body.device_id ?? '').trim()
  const androidId = String(body.android_id ?? '').trim()
  if (!deviceId && !androidId)
    return c.json({ ok: false, error: 'identitas_perangkat_hilang' }, 400)

  const t = nowIso()
  const res = deviceId
    ? await c.env.DB.prepare('UPDATE petugas SET last_seen_at = ? WHERE device_id = ?').bind(t, deviceId).run()
    : await c.env.DB.prepare('UPDATE petugas SET last_seen_at = ? WHERE android_id = ?').bind(t, androidId).run()

  if (!res.meta.changes)
    return c.json({ ok: false, error: 'belum_terdaftar', message: 'Perangkat belum terdaftar' }, 404)

  return c.json({ ok: true, server_time: t })
})

// ============================================================
// DAFTAR SELURUH PETUGAS (+ status online) — untuk admin web & APK
// M7: baris PERTAMA selalu ADMIN UTAMA (id 0, admin:true) lengkap
// dengan status online (dasbor admin terbuka) sehingga admin tampil
// di daftar anggota — di web dan di layar obrolan APK.
// ============================================================
app.get('/api/petugas', async (c) => {
  const db = c.env.DB
  const isAdminReq = !!c.env.API_KEY && (c.req.header('X-API-Key') ?? '') === c.env.API_KEY

  // Dasbor admin terbuka -> catat kehadiran (dipakai status online admin).
  if (isAdminReq) await catatAdminHadir(db).catch(() => {})

  const { results } = await db
    .prepare('SELECT id, nama, created_at, last_seen_at FROM petugas ORDER BY last_seen_at DESC LIMIT 500')
    .all<{ id: number; nama: string; created_at: string; last_seen_at: string }>()

  const now = Date.now()
  const petugas = (results ?? []).map((p) => {
    const seen = p.last_seen_at ? Date.parse(p.last_seen_at) : 0
    return { ...p, online: now - seen < ONLINE_WINDOW_MS }
  })

  const seenAdmin = await adminSeen(db)
  const adminOnline = seenAdmin ? now - Date.parse(seenAdmin) < ONLINE_WINDOW_MS : false
  const admin = {
    id: 0,
    nama: c.env.ADMIN_NAME || 'Admin',
    admin: true,
    online: adminOnline,
    last_seen_at: seenAdmin,
    created_at: '',
  }

  return c.json({
    ok: true,
    count: petugas.length + 1, // termasuk admin
    online_count: petugas.filter((p) => p.online).length + (adminOnline ? 1 : 0),
    petugas: [admin, ...petugas],
    server_time: nowIso(),
  })
})

// ============================================================
// HAPUS PETUGAS (admin web) — melepas anggota dari daftar.
// Riwayat laporan & pesan TETAP tersimpan; catatan pembaca
// (chat_reads) milik petugas ikut dibersihkan. Petugas yang
// dihapus bisa mendaftar ulang kapan saja lewat APK.
// ============================================================
app.delete('/api/petugas/:id', async (c) => {
  const id = Number(c.req.param('id'))
  if (id === 0) return c.json({ ok: false, error: 'admin_tidak_dapat_dihapus' }, 400)
  if (!Number.isInteger(id) || id <= 0)
    return c.json({ ok: false, error: 'id_tidak_valid' }, 400)

  const db = c.env.DB
  const p = await db
    .prepare('SELECT id, nama FROM petugas WHERE id = ?')
    .bind(id)
    .first<{ id: number; nama: string }>()
  if (!p) return c.json({ ok: false, error: 'petugas_tidak_ditemukan' }, 404)

  await db.prepare('DELETE FROM chat_reads WHERE reader_id = ?').bind(String(p.id)).run()
  await db.prepare('DELETE FROM petugas WHERE id = ?').bind(p.id).run()

  return c.json({
    ok: true,
    deleted: { id: p.id, nama: p.nama },
    catatan: 'Riwayat laporan & pesan tetap tersimpan',
  })
})

// ============================================================
// OBROLAN — KIRIM (global / private)
// Nama pengirim petugas diambil dari DB (anti pemalsuan nama).
// ============================================================
app.post('/api/chat/send', async (c) => {
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  if (!body) return c.json({ ok: false, error: 'body_json_tidak_valid' }, 400)

  const scope = body.scope === 'global' ? 'global' : body.scope === 'private' ? 'private' : null
  const senderType = body.sender_type === 'admin' ? 'admin' : body.sender_type === 'petugas' ? 'petugas' : null
  const text = String(body.body ?? '').trim()

  if (!scope) return c.json({ ok: false, error: 'scope_harus_global_atau_private' }, 400)
  if (!senderType) return c.json({ ok: false, error: 'sender_type_harus_petugas_atau_admin' }, 400)
  if (!text) return c.json({ ok: false, error: 'pesan_kosong' }, 400)
  if (text.length > MAX_CHAT_CHARS) return c.json({ ok: false, error: 'pesan_terlalu_panjang' }, 400)

  const db = c.env.DB

  let senderId: string
  let senderNama: string
  if (senderType === 'admin') {
    senderId = 'admin'
    senderNama = c.env.ADMIN_NAME || 'Admin'
  } else {
    // Anti-pemalsuan: identitas petugas WAJIB dibuktikan dengan kredensial
    // perangkat (device_id / android_id) — sender_id dari body diabaikan.
    // Tanpa ini, siapa pun yang punya API key bisa mengaku petugas lain.
    const deviceId = String(body.device_id ?? '').trim()
    const androidId = String(body.android_id ?? '').trim()
    if (!deviceId && !androidId)
      return c.json({ ok: false, error: 'identitas_perangkat_dibutuhkan' }, 401)
    const p = deviceId
      ? await db
          .prepare('SELECT id, nama FROM petugas WHERE device_id = ? OR android_id = ? LIMIT 1')
          .bind(deviceId, deviceId)
          .first<{ id: number; nama: string }>()
      : await db
          .prepare('SELECT id, nama FROM petugas WHERE android_id = ? LIMIT 1')
          .bind(androidId)
          .first<{ id: number; nama: string }>()
    if (!p) return c.json({ ok: false, error: 'petugas_tidak_dikenal' }, 404)
    senderId = String(p.id)
    senderNama = p.nama
  }

  let targetId: string | null = null
  if (scope === 'private') {
    const raw = String(body.target_id ?? '').trim()
    if (!raw) return c.json({ ok: false, error: 'target_id_dibutuhkan' }, 400)
    if (raw === senderId) return c.json({ ok: false, error: 'target_tidak_boleh_diri_sendiri' }, 400)
    if (raw !== 'admin') {
      const tid = Number(raw)
      if (!Number.isInteger(tid) || tid <= 0)
        return c.json({ ok: false, error: 'target_id_tidak_valid' }, 400)
      const exists = await db.prepare('SELECT id FROM petugas WHERE id = ?').bind(tid).first()
      if (!exists) return c.json({ ok: false, error: 'target_tidak_ditemukan' }, 404)
    }
    targetId = raw
  }

  const t = nowIso()
  const ins = await db
    .prepare('INSERT INTO chat_messages (scope, sender_type, sender_id, sender_nama, target_id, body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)')
    .bind(scope, senderType, senderId, senderNama, targetId, text, t)
    .run()

  return c.json({
    ok: true,
    message: {
      id: ins.meta.last_row_id,
      scope,
      sender_type: senderType,
      sender_id: senderId,
      sender_nama: senderNama,
      target_id: targetId,
      body: text,
      created_at: t,
    },
  })
})

// ============================================================
// OBROLAN — AMBIL PESAN (polling Opsi A)
//   ?scope=global&after=<id terakhir dikenal>&limit=100
//   ?scope=private&a=<party1>&b=<party2>&after=<id>
//   after=0 -> pesan terbaru (diurut lama->baru)
//   after>0 -> hanya pesan BARU setelah id tersebut
// Privasi DM: jika peminta mengirim X-Device-Id/X-Android-Id (dari APK),
// identitasnya dicek HARUS bagian dari pasangan a+b; web admin (tanpa
// header) tetap bisa membaca untuk kebutuhan pengawasan pusat.
// ============================================================
app.get('/api/chat/messages', async (c) => {
  const q = c.req.query('scope')
  const scope = q === 'global' ? 'global' : q === 'private' ? 'private' : null
  if (!scope) return c.json({ ok: false, error: 'scope_harus_global_atau_private' }, 400)

  const after = Math.max(0, Number.parseInt(c.req.query('after') ?? '0', 10) || 0)
  const limit = Math.min(300, Math.max(1, Number.parseInt(c.req.query('limit') ?? '100', 10) || 100))
  const db = c.env.DB
  const COLS = 'id, scope, sender_type, sender_id, sender_nama, target_id, body, created_at'

  let rows: Record<string, unknown>[] = []

  if (scope === 'global') {
    if (after > 0) {
      const r = await db
        .prepare(`SELECT ${COLS} FROM chat_messages WHERE scope = 'global' AND id > ? ORDER BY id ASC LIMIT ?`)
        .bind(after, limit)
        .all()
      rows = r.results ?? []
    } else {
      const r = await db
        .prepare(`SELECT ${COLS} FROM chat_messages WHERE scope = 'global' ORDER BY id DESC LIMIT ?`)
        .bind(limit)
        .all()
      rows = (r.results ?? []).reverse()
    }
  } else {
    const a = (c.req.query('a') ?? '').trim()
    const b = (c.req.query('b') ?? '').trim()
    if (!a || !b || a === b) return c.json({ ok: false, error: 'parameter_a_dan_b_dibutuhkan' }, 400)

    // Gerbang privasi opsional: peminta yang membuktikan diri lewat header
    // perangkat tidak boleh membaca DM yang bukan pasangannya.
    const devId = (c.req.header('X-Device-Id') ?? '').trim()
    const andId = (c.req.header('X-Android-Id') ?? '').trim()
    if (devId || andId) {
      const me = devId
        ? await db
            .prepare('SELECT id FROM petugas WHERE device_id = ? OR android_id = ? LIMIT 1')
            .bind(devId, devId)
            .first<{ id: number }>()
        : await db
            .prepare('SELECT id FROM petugas WHERE android_id = ? LIMIT 1')
            .bind(andId)
            .first<{ id: number }>()
      if (me) {
        const mine = String(me.id)
        if (mine !== a && mine !== b) return c.json({ ok: false, error: 'bukan_pasangan_dm' }, 403)
      }
    }

    const pair = '((sender_id = ? AND target_id = ?) OR (sender_id = ? AND target_id = ?))'
    if (after > 0) {
      const r = await db
        .prepare(`SELECT ${COLS} FROM chat_messages WHERE scope = 'private' AND ${pair} AND id > ? ORDER BY id ASC LIMIT ?`)
        .bind(a, b, b, a, after, limit)
        .all()
      rows = r.results ?? []
    } else {
      const r = await db
        .prepare(`SELECT ${COLS} FROM chat_messages WHERE scope = 'private' AND ${pair} ORDER BY id DESC LIMIT ?`)
        .bind(a, b, b, a, limit)
        .all()
      rows = (r.results ?? []).reverse()
    }
  }

  // ============================================================
  // STATUS "DILIHAH OLEH" (read receipts)
  // Pembaca dienali dari: header perangkat (APK petugas) atau
  // X-API-Key (web admin). Membuka layar obrolan = membaca semua
  // pesan hingga id terbaru percakapan tersebut.
  // ============================================================
  const hdrDevice = (c.req.header('X-Device-Id') ?? '').trim()
  const hdrAndroid = (c.req.header('X-Android-Id') ?? '').trim()
  const isAdminReq = !!c.env.API_KEY && (c.req.header('X-API-Key') ?? '') === c.env.API_KEY

  let meId: string | null = null
  let meNama: string | null = null
  let canSeeViewers = false
  if (hdrDevice || hdrAndroid) {
    const me = hdrDevice
      ? await db
          .prepare('SELECT id, nama FROM petugas WHERE device_id = ? OR android_id = ? LIMIT 1')
          .bind(hdrDevice, hdrDevice)
          .first<{ id: number; nama: string }>()
      : await db
          .prepare('SELECT id, nama FROM petugas WHERE android_id = ? LIMIT 1')
          .bind(hdrAndroid)
          .first<{ id: number; nama: string }>()
    if (me) {
      meId = String(me.id)
      meNama = me.nama
      canSeeViewers = true
    }
  } else if (isAdminReq) {
    meId = 'admin'
    meNama = c.env.ADMIN_NAME || 'Admin'
    canSeeViewers = true
  }

  const pa = (c.req.query('a') ?? '').trim()
  const pb = (c.req.query('b') ?? '').trim()

  // Kunci percakapan: 'global' atau pasangan terurut 'a|b' (angka diurut
  // secara numerik agar 3|10 tidak menjadi 10|3).
  const convKey =
    scope === 'global'
      ? 'global'
      : [pa, pb]
          .sort((x, y) => {
            const nx = Number(x)
            const ny = Number(y)
            if (Number.isInteger(nx) && Number.isInteger(ny)) return nx - ny
            return x < y ? -1 : x > y ? 1 : 0
          })
          .join('|')

  if (scope === 'private' && meId && meId !== pa && meId !== pb) {
    // Admin mengawasi DM dua petugas lewat web = bukan pihak dalam:
    // bacaannya TIDAK dicatat (tetap boleh melihat daftar pembaca).
    meId = null
  }

  if (meId && meNama) {
    let maxId: number | null = null
    if (scope === 'global') {
      const r = await db.prepare('SELECT MAX(id) AS m FROM chat_messages WHERE scope = ?').bind('global').first<{ m: number | null }>()
      maxId = r?.m ?? null
    } else if (pa && pb) {
      const pair = '((sender_id = ? AND target_id = ?) OR (sender_id = ? AND target_id = ?))'
      const r = await db
        .prepare(`SELECT MAX(id) AS m FROM chat_messages WHERE scope = 'private' AND ${pair}`)
        .bind(pa, pb, pb, pa)
        .first<{ m: number | null }>()
      maxId = r?.m ?? null
    }

    if (maxId != null) {
      const cur = await db
        .prepare('SELECT last_read_id FROM chat_reads WHERE conv_key = ? AND reader_id = ?')
        .bind(convKey, meId)
        .first<{ last_read_id: number }>()
      if (!cur || cur.last_read_id < maxId) {
        // Tulis hanya saat maju — polling yang tak menemukan pesan baru
        // tidak membebani D1.
        await db
          .prepare(
            `INSERT INTO chat_reads (conv_key, reader_id, reader_nama, last_read_id, updated_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT (conv_key, reader_id) DO UPDATE SET
               last_read_id = MAX(chat_reads.last_read_id, excluded.last_read_id),
               reader_nama = excluded.reader_nama,
               updated_at = excluded.updated_at`
          )
          .bind(convKey, meId, meNama, maxId, nowIso())
          .run()
      }
    }
  }

  // Daftar pembaca lain percakapan ini (untuk gelembung "Dilihat oleh …")
  let viewers: { reader_id: string; reader_nama: string; last_read_id: number; updated_at: string }[] = []
  if (canSeeViewers) {
    const r = await db
      .prepare('SELECT reader_id, reader_nama, last_read_id, updated_at FROM chat_reads WHERE conv_key = ? AND reader_id != ? ORDER BY last_read_id ASC')
      .bind(convKey, meId ?? '')
      .all<{ reader_id: string; reader_nama: string; last_read_id: number; updated_at: string }>()
    viewers = r.results ?? []
  }

  return c.json({
    ok: true,
    scope,
    count: rows.length,
    messages: rows,
    viewers,
    server_time: nowIso(),
  })
})

// ============================================================
// LAPORAN — TERIMA DARI APK (multipart: teks + foto)
// APK kode 19 sudah mengirim ke sini dengan X-API-Key.
// Idempoten via client_ref -> kirim ulang tidak menduplikasi.
// ============================================================
// ============================================================
// OBROLAN — RINGKASAN UNTUK NOTIFIKASI + LENCANA (APK, v1.6.0-m6)
// GET /api/chat/summary  (header X-Device-Id / X-Android-Id wajib)
//
// Mengembalikan PESAN TERAKHIR tiap percakapan yang melibatkan
// petugas peminta: global, DM dengan pusat (admin), dan DM dengan
// sesama petugas. APK membandingkan last_id dengan catatan lokal
// untuk memunculkan notifikasi "pesan masuk" tanpa membuka aplikasi.
//
// v1.6.0: tambah hitungan BELUM TERBACA per percakapan ('unread')
// dan totalnya ('unread_total') — dipakai lencana angka pada FAB
// obrolan di menu APK. Sumber kebenaran = tabel chat_reads (id
// pesan terakhir yang benar-benar dibuka pengguna) sehingga angka
// akurat lintas perangkat.
//
// Sengaja TIDAK mengubah last_seen_at — status online hanya dari
// heartbeat saat aplikasi benar-benar dibuka.
// ============================================================
app.get('/api/chat/summary', async (c) => {
  const devId = (c.req.header('X-Device-Id') ?? '').trim()
  const andId = (c.req.header('X-Android-Id') ?? '').trim()
  if (!devId && !andId)
    return c.json({ ok: false, error: 'identitas_perangkat_dibutuhkan' }, 401)

  const db = c.env.DB
  const me = devId
    ? await db
        .prepare('SELECT id, nama FROM petugas WHERE device_id = ? OR android_id = ? LIMIT 1')
        .bind(devId, devId)
        .first<{ id: number; nama: string }>()
    : await db
        .prepare('SELECT id, nama FROM petugas WHERE android_id = ? LIMIT 1')
        .bind(andId)
        .first<{ id: number; nama: string }>()
  if (!me) return c.json({ ok: false, error: 'petugas_tidak_dikenal' }, 404)

  const meId = String(me.id)
  const COLS = 'id, sender_type, sender_id, sender_nama, body, created_at'

  // Pesan terakhir Obrolan Umum
  const gLast = await db
    .prepare(`SELECT ${COLS} FROM chat_messages WHERE scope = 'global' ORDER BY id DESC LIMIT 1`)
    .first<{ id: number; sender_type: string; sender_id: string; sender_nama: string; body: string; created_at: string }>()

  // Semua pasangan DM yang melibatkan saya + id pesan terakhirnya
  const dmPairs = await db
    .prepare(
      `SELECT CASE WHEN sender_id = ?1 THEN target_id ELSE sender_id END AS partner, MAX(id) AS last_id
       FROM chat_messages WHERE scope = 'private' AND (sender_id = ?1 OR target_id = ?1)
       GROUP BY partner`
    )
    .bind(meId)
    .all<{ partner: string; last_id: number }>()
  const partners = new Set<string>(['admin']) // DM pusat selalu ada
  for (const row of dmPairs.results ?? []) if (row.partner) partners.add(String(row.partner))

  // Nama partner + pesan terakhirnya (batch = 1 putaran jaringan)
  const petugasStmts = [...partners]
    .filter((p) => p !== 'admin')
    .map((p) => db.prepare('SELECT id, nama FROM petugas WHERE id = ?').bind(p))
  const msgStmts = [...partners].map((p) =>
    db
      .prepare(
        `SELECT ${COLS} FROM chat_messages
         WHERE scope = 'private'
           AND ((sender_id = ? AND target_id = ?) OR (sender_id = ? AND target_id = ?))
         ORDER BY id DESC LIMIT 1`
      )
      .bind(meId, p, p, meId)
  )
  const [petugasRes, msgRes] = await Promise.all([
    petugasStmts.length ? db.batch(petugasStmts) : Promise.resolve([]),
    db.batch(msgStmts),
  ])

  const namaByPartner = new Map<string, string>([['admin', c.env.ADMIN_NAME || 'Admin']])
  for (const r of petugasRes as D1Result<{ id: number; nama: string }>[]) {
    const row = r.results?.[0]
    if (row) namaByPartner.set(String(row.id), row.nama)
  }

  const potong = (s: string) => (s.length > 140 ? s.slice(0, 139) + '…' : s)
  const convKey = (partner: string) => {
    const np = Number(partner)
    const nm = Number(meId)
    if (Number.isInteger(np) && Number.isInteger(nm)) {
      return np < nm ? `${partner}|${meId}` : `${meId}|${partner}`
    }
    return `${meId}|${partner}` // admin (bukan angka) selalu di belakang, sesuai klien
  }

  // ---- Hitungan BELUM TERBACA (lencana FAB obrolan) ----
  // Sumber: chat_reads = id pesan terakhir yang pengguna buka sungguhan.
  const readsRes = await db
    .prepare('SELECT conv_key, last_read_id FROM chat_reads WHERE reader_id = ?')
    .bind(meId)
    .all<{ conv_key: string; last_read_id: number }>()
  const bacaan = new Map<string, number>()
  for (const r of readsRes.results ?? []) bacaan.set(r.conv_key, r.last_read_id)

  const partnerArr = [...partners]
  const countStmts = partnerArr.map((p) =>
    db
      .prepare(
        `SELECT COUNT(*) AS n FROM chat_messages
         WHERE scope = 'private'
           AND ((sender_id = ? AND target_id = ?) OR (sender_id = ? AND target_id = ?))
           AND sender_id != ? AND id > ?`
      )
      .bind(meId, p, p, meId, meId, bacaan.get(convKey(p)) ?? 0)
  )
  const [countRes, gUnread] = await Promise.all([
    countStmts.length ? db.batch(countStmts) : Promise.resolve([]),
    db
      .prepare(
        `SELECT COUNT(*) AS n FROM chat_messages WHERE scope = 'global' AND sender_id != ? AND id > ?`
      )
      .bind(meId, bacaan.get('global') ?? 0)
      .first<{ n: number }>(),
  ])
  const unreadByPartner = new Map<string, number>()
  ;(countRes as D1Result<{ n: number }>[])?.forEach((r, i) => {
    unreadByPartner.set(partnerArr[i], r.results?.[0]?.n ?? 0)
  })
  const globalUnread = gUnread?.n ?? 0

  const convs: Record<string, unknown>[] = [
    {
      key: 'global',
      title: 'Diskusi Petugas',
      partner: 'global',
      unread: globalUnread,
      last_id: gLast?.id ?? 0,
      last_body: gLast ? potong(gLast.body) : '',
      last_sender_type: gLast?.sender_type ?? '',
      last_sender_id: gLast?.sender_id ?? '',
      last_sender_nama: gLast?.sender_nama ?? '',
      last_at: gLast?.created_at ?? '',
    },
  ]
  const results = (msgRes as D1Result<{ id: number; sender_type: string; sender_id: string; sender_nama: string; body: string; created_at: string }>[]) ?? []
  partnerArr.forEach((partner, i) => {
    const m = results[i]?.results?.[0]
    convs.push({
      key: convKey(partner),
      title: namaByPartner.get(partner) ?? 'Petugas',
      partner,
      unread: unreadByPartner.get(partner) ?? 0,
      last_id: m?.id ?? 0,
      last_body: m ? potong(m.body) : '',
      last_sender_type: m?.sender_type ?? '',
      last_sender_id: m?.sender_id ?? '',
      last_sender_nama: m?.sender_nama ?? '',
      last_at: m?.created_at ?? '',
    })
  })

  const unreadTotal =
    globalUnread + [...unreadByPartner.values()].reduce((a, b) => a + b, 0)

  return c.json({
    ok: true,
    me: { id: me.id, nama: me.nama },
    unread_total: unreadTotal,
    convs,
    server_time: nowIso(),
  })
})

app.post('/api/reports', async (c) => {
  let form: FormData
  try {
    form = await c.req.formData()
  } catch {
    return c.json({ ok: false, error: 'harus_multipart_form_data' }, 400)
  }

  const field = (k: string) => {
    const v = form.get(k)
    return typeof v === 'string' ? v.trim() : ''
  }

  const clientRef = field('client_ref')
  if (!clientRef) return c.json({ ok: false, error: 'client_ref_wajib' }, 400)

  const dup = await c.env.DB.prepare('SELECT id FROM reports WHERE client_ref = ?').bind(clientRef).first<{ id: string }>()
  if (dup) return c.json({ ok: true, duplicate: true, report_id: dup.id })

  const files = form.getAll('photos').filter((v): v is File => v instanceof File)
  if (files.length > MAX_PHOTOS)
    return c.json({ ok: false, error: 'terlalu_banyak_foto', max: MAX_PHOTOS }, 413)
  for (const f of files) {
    if (f.size > MAX_PHOTO_BYTES)
      return c.json({ ok: false, error: 'foto_terlalu_besar', max_kb: Math.round(MAX_PHOTO_BYTES / 1024) }, 413)
  }

  const db = c.env.DB
  const reportId = crypto.randomUUID()
  const createdAt = field('created_at') || nowIso()
  const lat = field('latitude')
  const lng = field('longitude')

  await db
    .prepare(
      'INSERT INTO reports (id, client_ref, status, petugas, nama_pelanggan, id_pelanggan, alamat, plus_code, latitude, longitude, catatan, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
    )
    .bind(
      reportId,
      clientRef,
      // 2 jenis laporan saja: TERTUNDA & SELESAI (APK kirim SELESAI/PENDING)
      String(field('status')).toUpperCase() === 'SELESAI' ? 'SELESAI' : 'TERTUNDA',
      field('petugas'),
      field('nama_pelanggan'),
      field('id_pelanggan'),
      field('alamat'),
      field('plus_code'),
      lat ? Number(lat) : null,
      lng ? Number(lng) : null,
      field('catatan'),
      createdAt,
    )
    .run()

  let saved = 0
  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    const key = `reports/${reportId}/${Date.now()}-${i}.jpg`
    const buf = await file.arrayBuffer()
    await c.env.PHOTOS.put(key, buf, { httpMetadata: { contentType: file.type || 'image/jpeg' } })
    await db
      .prepare('INSERT INTO photos (id, report_id, r2_key, urutan, ukuran_kb, created_at) VALUES (?, ?, ?, ?, ?, ?)')
      .bind(crypto.randomUUID(), reportId, key, i + 1, Math.round(buf.byteLength / 1024), nowIso())
      .run()
    saved++
  }

  return c.json({ ok: true, report_id: reportId, photos: saved })
})

// ============================================================
// LAPORAN — DAFTAR (untuk admin web) + id foto per laporan
// ============================================================
app.get('/api/reports', async (c) => {
  const { results } = await c.env.DB
    .prepare('SELECT * FROM reports ORDER BY created_at DESC LIMIT 100')
    .all()
  const reports = results ?? []

  const ids = reports.map((r) => r.id)
  const photosByReport: Record<string, { id: string; urutan: number; ukuran_kb: number | null }[]> = {}
  if (ids.length) {
    const marks = ids.map(() => '?').join(',')
    const ph = await c.env.DB
      .prepare(`SELECT id, report_id, urutan, ukuran_kb FROM photos WHERE report_id IN (${marks}) ORDER BY urutan ASC`)
      .bind(...ids)
      .all<{ id: string; report_id: string; urutan: number; ukuran_kb: number | null }>()
    for (const p of ph.results ?? []) {
      ;(photosByReport[p.report_id] ??= []).push({ id: p.id, urutan: p.urutan, ukuran_kb: p.ukuran_kb })
    }
  }

  return c.json({
    ok: true,
    count: reports.length,
    reports: reports.map((r) => ({ ...r, photos: photosByReport[String(r.id)] ?? [] })),
  })
})

// ============================================================
// DASBOR — SATU PERMINTAAN untuk web admin (v1.8.0-m8)
// Sebelumnya web menembak /api/health + /api/petugas + /api/reports
// (3 koneksi). Kini satu respons memuat semuanya -> buka halaman &
// polling di jaringan ponsel jauh lebih cepat.
// ============================================================
app.get('/api/dashboard', async (c) => {
  const db = c.env.DB
  const isAdminReq = !!c.env.API_KEY && (c.req.header('X-API-Key') ?? '') === c.env.API_KEY
  if (isAdminReq) await catatAdminHadir(db).catch(() => {})

  const [reportsRes, photosRes, petugasRes, adminSeenAt, counts] = await Promise.all([
    db.prepare('SELECT * FROM reports ORDER BY created_at DESC LIMIT 100').all(),
    db
      .prepare('SELECT id, report_id, urutan, ukuran_kb FROM photos ORDER BY urutan ASC LIMIT 4000')
      .all<{ id: string; report_id: string; urutan: number; ukuran_kb: number | null }>(),
    db
      .prepare('SELECT id, nama, created_at, last_seen_at FROM petugas ORDER BY last_seen_at DESC LIMIT 500')
      .all<{ id: number; nama: string; created_at: string; last_seen_at: string }>(),
    adminSeen(db).catch(() => ''),
    Promise.all([
      countSafe(db, 'reports'),
      countSafe(db, 'photos'),
      countSafe(db, 'petugas'),
      countSafe(db, 'chat_messages'),
    ]),
  ])

  const now = Date.now()
  const petugas = (petugasRes.results ?? []).map((p) => {
    const seen = p.last_seen_at ? Date.parse(p.last_seen_at) : 0
    return { ...p, online: now - seen < ONLINE_WINDOW_MS }
  })
  const adminOnline = adminSeenAt ? now - Date.parse(adminSeenAt) < ONLINE_WINDOW_MS : false
  const admin = {
    id: 0,
    nama: c.env.ADMIN_NAME || 'Admin',
    admin: true,
    online: adminOnline,
    last_seen_at: adminSeenAt,
    created_at: '',
  }

  const photosByReport: Record<string, { id: string; urutan: number; ukuran_kb: number | null }[]> = {}
  for (const p of photosRes.results ?? []) (photosByReport[p.report_id] ??= []).push(p)

  const [reports, photos, ptg, messages] = counts
  return c.json({
    ok: true,
    version: VERSION,
    time: nowIso(),
    statistik: { reports, photos, petugas: ptg, messages },
    petugas: [admin, ...petugas],
    reports: (reportsRes.results ?? []).map((r: Record<string, unknown>) => ({
      ...r,
      photos: photosByReport[String(r.id)] ?? [],
    })),
  })
})

// ============================================================
// PREVIEW OBROLAN — SATU PERMINTAAN (v1.8.0-m8)
// Sebelumnya daftar obrolan web menembak /api/chat/messages 1x untuk
// grup + 1x PER petugas (1+N koneksi) -> tab obrolan terasa lambat.
// Kini pesan terakhir tiap percakapan didapat sekaligus via db.batch.
// ============================================================
app.get('/api/chat/previews', async (c) => {
  const db = c.env.DB
  const COLS = 'id, sender_type, sender_id, sender_nama, body, created_at'

  const [gLastRes, petugasRes] = await Promise.all([
    db
      .prepare(`SELECT ${COLS} FROM chat_messages WHERE scope = 'global' ORDER BY id DESC LIMIT 1`)
      .first<{ id: number; sender_type: string; sender_id: string; sender_nama: string; body: string; created_at: string }>(),
    db.prepare('SELECT id, nama FROM petugas ORDER BY id ASC LIMIT 500').all<{ id: number; nama: string }>(),
  ])

  const list = petugasRes.results ?? []
  const stmts = list.map((p) =>
    db
      .prepare(
        `SELECT ${COLS} FROM chat_messages
         WHERE scope = 'private'
           AND ((sender_id = ? AND target_id = 'admin') OR (sender_id = 'admin' AND target_id = ?))
         ORDER BY id DESC LIMIT 1`
      )
      .bind(String(p.id), String(p.id))
  )
  const res = stmts.length ? await db.batch(stmts) : []

  const dm: Record<string, { id: number; sender_type: string; sender_id: string; sender_nama: string; body: string; created_at: string }> = {}
  ;(res as D1Result<{ id: number; sender_type: string; sender_id: string; sender_nama: string; body: string; created_at: string }>[]).forEach((r, i) => {
    const m = r.results?.[0]
    if (m) dm[String(list[i].id)] = m
  })

  return c.json({ ok: true, global: gLastRes ?? null, dm, server_time: nowIso() })
})

// ============================================================
// FOTO — sajikan dari R2 (dipakai admin web)
// ============================================================
app.get('/api/photos/:id', async (c) => {
  const id = c.req.param('id')
  const row = await c.env.DB.prepare('SELECT r2_key FROM photos WHERE id = ?').bind(id).first<{ r2_key: string }>()
  if (!row) return c.json({ ok: false, error: 'foto_tidak_ditemukan' }, 404)

  const obj = await c.env.PHOTOS.get(row.r2_key)
  if (!obj) return c.json({ ok: false, error: 'file_foto_hilang' }, 404)

  return new Response(obj.body, {
    headers: {
      'content-type': obj.httpMetadata?.contentType ?? 'image/jpeg',
      'cache-control': 'public, max-age=31536000, immutable',
    },
  })
})

// ============================================================
// EXPORT LAPORAN BULANAN (v1.8.0-m8) — Excel (.xlsx) & PDF
// Dibuat TANPA dependensi: xlsx = ZIP store + SpreadsheetML minimal;
// PDF = berkas PDF 1.4 ditulis manual (Helvetica bawaan viewer).
// Autentikasi: GET terbuka secara middleware, jadi endpoint ini
// mewajibkan X-API-Key (header) ATAU ?key= (untuk unduh langsung).
// ============================================================
const BULAN_ID = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember']

const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/

function monthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number)
  return `${BULAN_ID[m - 1]} ${y}`
}

async function laporanBulan(db: D1Database, month: string) {
  const [y, m] = month.split('-').map(Number)
  const start = `${month}-01`
  const end = new Date(Date.UTC(y, m, 1)).toISOString().slice(0, 10) // awal bulan berikutnya
  const { results } = await db
    .prepare('SELECT * FROM reports WHERE created_at >= ? AND created_at < ? ORDER BY created_at ASC')
    .bind(start, end)
    .all<Record<string, unknown>>()
  const rows = results ?? []
  const fotoByReport: Record<string, number> = {}
  if (rows.length) {
    const marks = rows.map(() => '?').join(',')
    const ph = await db
      .prepare(`SELECT report_id, COUNT(*) AS n FROM photos WHERE report_id IN (${marks}) GROUP BY report_id`)
      .bind(...rows.map((r) => String(r.id)))
      .all<{ report_id: string; n: number }>()
    for (const p of ph.results ?? []) fotoByReport[p.report_id] = p.n
  }
  return rows.map((r) => ({ r, foto: fotoByReport[String(r.id)] ?? 0 }))
}

// ---------- ZIP (store, tanpa kompresi) ----------
const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let i = 0; i < 256; i++) {
    let c = i
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[i] = c >>> 0
  }
  return t
})()

function crc32(buf: Uint8Array): number {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function u16(n: number): Uint8Array {
  const a = new Uint8Array(2)
  a[0] = n & 0xff
  a[1] = (n >>> 8) & 0xff
  return a
}

function u32(n: number): Uint8Array {
  const a = new Uint8Array(4)
  for (let i = 0; i < 4; i++) a[i] = (n >>> (8 * i)) & 0xff
  return a
}

function concatBytes(parts: Uint8Array[]): Uint8Array {
  const len = parts.reduce((s, p) => s + p.length, 0)
  const out = new Uint8Array(len)
  let o = 0
  for (const p of parts) {
    out.set(p, o)
    o += p.length
  }
  return out
}

function zipStore(entries: { name: string; data: Uint8Array }[]): Uint8Array {
  const te = new TextEncoder()
  const now = new Date()
  const dosTime = ((now.getUTCHours() & 31) << 11) | ((now.getUTCMinutes() & 63) << 5) | ((Math.floor(now.getUTCSeconds() / 2)) & 31)
  const dosDate = (((now.getUTCFullYear() - 1980) & 127) << 9) | (((now.getUTCMonth() + 1) & 15) << 5) | (now.getUTCDate() & 31)
  const local: Uint8Array[] = []
  const central: Uint8Array[] = []
  let offset = 0
  for (const e of entries) {
    const name = te.encode(e.name)
    const crc = crc32(e.data)
    const lh = concatBytes([
      u32(0x04034b50), u16(20), u16(0x0800), u16(0), u16(dosTime), u16(dosDate),
      u32(crc), u32(e.data.length), u32(e.data.length), u16(name.length), u16(0),
    ])
    local.push(lh, name, e.data)
    const ch = concatBytes([
      u32(0x02014b50), u16(20), u16(20), u16(0x0800), u16(0), u16(dosTime), u16(dosDate),
      u32(crc), u32(e.data.length), u32(e.data.length), u16(name.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(offset),
    ])
    central.push(ch, name)
    offset += lh.length + name.length + e.data.length
  }
  const cd = concatBytes(central)
  const eocd = concatBytes([
    u32(0x06054b50), u16(0), u16(0), u16(entries.length), u16(entries.length),
    u32(cd.length), u32(offset), u16(0),
  ])
  return concatBytes([...local, cd, eocd])
}

// ---------- XLSX ----------
function xmlEsc(s: string): string {
  return String(s ?? '')
    .replace(/[&<>]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[ch] as string))
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
}

function colRef(n: number): string {
  let s = ''
  while (n > 0) {
    const m = (n - 1) % 26
    s = String.fromCharCode(65 + m) + s
    n = Math.floor((n - 1) / 26)
  }
  return s
}

function buildXlsx(rows: (string | number | null)[][], widths: number[]): Uint8Array {
  const te = new TextEncoder()
  const cols = widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('')
  const body = rows
    .map((row, r) => {
      const cells = row
        .map((v, c) => {
          if (v === null || v === undefined || v === '') return ''
          const ref = `${colRef(c + 1)}${r + 1}`
          if (typeof v === 'number' && Number.isFinite(v)) return `<c r="${ref}"><v>${v}</v></c>`
          return `<c r="${ref}" t="inlineStr"${r === 0 ? ' s="1"' : ''}><is><t xml:space="preserve">${xmlEsc(String(v))}</t></is></c>`
        })
        .join('')
      return `<row r="${r + 1}">${cells}</row>`
    })
    .join('')

  const sheet = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${cols}</cols><sheetData>${body}</sheetData></worksheet>`

  const files = [
    {
      name: '[Content_Types].xml',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`),
    },
    {
      name: '_rels/.rels',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`),
    },
    {
      name: 'xl/workbook.xml',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Laporan" sheetId="1" r:id="rId1"/></sheets></workbook>`),
    },
    {
      name: 'xl/_rels/workbook.xml.rels',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`),
    },
    {
      name: 'xl/styles.xml',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FF1A2433"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD84D"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`),
    },
    { name: 'xl/worksheets/sheet1.xml', data: te.encode(sheet) },
  ]
  return zipStore(files)
}

// ---------- PDF ----------
const PDF_CHAR_MAP: Record<string, string> = {
  '—': '-', '–': '-', '‑': '-', '·': '-', '•': '-', '…': '...',
  '\u2018': "'", '\u2019': "'", '\u201C': '"', '\u201D': '"',
  '\u00A0': ' ', '\u200B': '', '\t': ' ',
}

function pdfEsc(s: string): string {
  let out = ''
  for (const ch of String(s ?? '')) {
    const mapped = PDF_CHAR_MAP[ch]
    const c = mapped !== undefined ? mapped : ch
    const cp = c.codePointAt(0) ?? 63
    out += cp > 255 ? '?' : c
  }
  return out.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)')
}

function wrapCell(text: string, width: number, fs: number, maxLines = 3): string[] {
  const t = String(text ?? '').trim()
  if (!t) return ['']
  const maxChars = Math.max(3, Math.floor(width / (fs * 0.545)))
  const words = t.split(/\s+/).filter(Boolean)
  const lines: string[] = []
  let cur = ''
  for (const w of words) {
    const cand = cur ? `${cur} ${w}` : w
    if (cand.length <= maxChars) {
      cur = cand
      continue
    }
    if (cur) lines.push(cur)
    if (lines.length >= maxLines) break
    cur = w.length > maxChars ? w.slice(0, maxChars - 1) + '...' : w
  }
  if (cur && lines.length < maxLines) lines.push(cur)
  if (lines.length === maxLines && lines.join(' ').replace(/(\.\.\.)?$/, '').length < t.length - 2) {
    lines[maxLines - 1] = lines[maxLines - 1].slice(0, Math.max(1, maxChars - 3)) + '...'
  }
  return lines
}

function buildMonthPdf(
  label: string,
  month: string,
  data: { r: Record<string, unknown>; foto: number }[],
  generatedAt: string,
): Uint8Array {
  const W = 842, H = 595, M = 32
  const COLS = [26, 52, 30, 128, 72, 238, 54, 92, 26]
  const HEAD = ['No', 'Tanggal', 'Jam', 'Pelanggan', 'ID Pelanggan', 'Alamat', 'Status', 'Petugas', 'Foto']
  const tableW = COLS.reduce((a, b) => a + b, 0)
  const fs = 7.6
  const rowH = 26
  const headH = 20
  const leading = 9
  const titleH = 100
  const footH = 34

  const total = data.length
  const nBaru = data.filter((d) => d.r.status === 'TERTUNDA').length
  const nSelesai = data.filter((d) => d.r.status === 'SELESAI').length
  const summary = `Total ${total} laporan  ·  TERTUNDA ${nBaru}  ·  SELESAI ${nSelesai}`

  const tableTop = H - titleH
  const rowsPerPage = Math.max(1, Math.floor((tableTop - headH - footH) / rowH))
  const pages = Math.max(1, Math.ceil(data.length / rowsPerPage))

  const rowCells = (d: { r: Record<string, unknown>; foto: number }, no: number): string[] => {
    const r = d.r
    const ca = String(r.created_at ?? '')
    return [
      String(no),
      ca.slice(0, 10),
      ca.length >= 16 ? ca.slice(11, 16) : '',
      String(r.nama_pelanggan ?? ''),
      String(r.id_pelanggan ?? ''),
      String(r.alamat ?? ''),
      String(r.status ?? ''),
      String(r.petugas ?? ''),
      String(d.foto || 0),
    ]
  }

  const streams: string[] = []
  for (let p = 0; p < pages; p++) {
    const cmds: string[] = []
    const bandTop = p === 0 ? tableTop : H - 44

    if (p === 0) {
      cmds.push('0.051 0.106 0.180 rg', `0 ${H - titleH} ${W} ${titleH} re f`)
      cmds.push('1 0.824 0.247 rg', `0 ${H - titleH} ${W} 3 re f`)
      cmds.push('1 1 1 rg', `BT /F2 16 Tf 1 0 0 1 ${M} ${H - 42} Tm (${pdfEsc(`LAPORAN PEMASANGAN PLN — ${label.toUpperCase()}`)}) Tj ET`)
      cmds.push('0.62 0.70 0.80 rg', `BT /F1 8.5 Tf 1 0 0 1 ${M} ${H - 58} Tm (${pdfEsc(`Rekap bulanan ${month}  ·  dibuat ${generatedAt}`)}) Tj ET`)
      cmds.push('1 0.824 0.247 rg', `BT /F2 9.5 Tf 1 0 0 1 ${M} ${H - 76} Tm (${pdfEsc(summary)}) Tj ET`)
    } else {
      cmds.push('0.051 0.106 0.180 rg', `0 ${H - 44} ${W} 44 re f`)
      cmds.push('1 0.824 0.247 rg', `0 ${H - 44} ${W} 2.5 re f`)
      cmds.push('1 1 1 rg', `BT /F2 11 Tf 1 0 0 1 ${M} ${H - 27} Tm (${pdfEsc(`Laporan PLN ${label} — lanjutan hal. ${p + 1}`)}) Tj ET`)
    }

    // header tabel
    cmds.push('0.051 0.106 0.180 rg', `${M} ${bandTop - headH} ${tableW} ${headH} re f`)
    let x = M
    HEAD.forEach((h, i) => {
      cmds.push('1 1 1 rg', `BT /F2 ${fs + 0.4} Tf 1 0 0 1 ${x + 5} ${bandTop - headH + 6} Tm (${pdfEsc(h)}) Tj ET`)
      x += COLS[i]
    })

    // baris data
    const slice = data.slice(p * rowsPerPage, (p + 1) * rowsPerPage)
    slice.forEach((d, ri) => {
      const top = bandTop - headH - (ri + 1) * rowH
      if (ri % 2 === 1) cmds.push('0.953 0.961 0.973 rg', `${M} ${top} ${tableW} ${rowH} re f`)
      const vals = rowCells(d, p * rowsPerPage + ri + 1)
      let cx = M
      vals.forEach((v, ci) => {
        const lines = wrapCell(v, COLS[ci] - 10, fs)
        cmds.push('0.13 0.18 0.26 rg')
        lines.forEach((ln, li) => {
          cmds.push(`BT /F1 ${fs} Tf 1 0 0 1 ${cx + 5} ${top + rowH - 12 - li * leading} Tm (${pdfEsc(ln)}) Tj ET`)
        })
        cx += COLS[ci]
      })
    })

    // garis kolom
    cmds.push('0.84 0.87 0.90 RG', '0.6 w')
    let gx = M
    for (let i = 0; i <= COLS.length; i++) {
      cmds.push(`${gx} ${bandTop - headH - slice.length * rowH} m ${gx} ${bandTop} l S`)
      if (i < COLS.length) gx += COLS[i]
    }

    // footer
    cmds.push('0.45 0.51 0.60 rg')
    cmds.push(`BT /F1 7.5 Tf 1 0 0 1 ${M} 16 Tm (${pdfEsc(`PLN Admin · dihasilkan otomatis ${generatedAt}`)}) Tj ET`)
    cmds.push(`BT /F1 7.5 Tf 1 0 0 1 ${W - M - 44} 16 Tm (${pdfEsc(`Hal. ${p + 1}/${pages}`)}) Tj ET`)

    streams.push(cmds.join('\n'))
  }

  // rakit berkas PDF + xref
  const te = new TextEncoder()
  const objects: string[] = []
  const kids = streams.map((_, i) => `${5 + i * 2} 0 R`).join(' ')
  objects[1] = '<< /Type /Catalog /Pages 2 0 R >>'
  objects[2] = `<< /Type /Pages /Kids [${kids}] /Count ${streams.length} >>`
  objects[3] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
  objects[4] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>'
  streams.forEach((s, i) => {
    const pageNum = 5 + i * 2
    objects[pageNum] = `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${W} ${H}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${pageNum + 1} 0 R >>`
    objects[pageNum + 1] = `<< /Length ${te.encode(s).length} >>\nstream\n${s}\nendstream`
  })

  const parts: Uint8Array[] = [te.encode('%PDF-1.4\n')]
  const offsets: number[] = []
  let pos = parts[0].length
  for (let i = 1; i < objects.length; i++) {
    const body = te.encode(`${i} 0 obj\n${objects[i]}\nendobj\n`)
    offsets[i] = pos
    parts.push(body)
    pos += body.length
  }
  const xrefStart = pos
  let xref = `xref\n0 ${objects.length}\n0000000000 65535 f \n`
  for (let i = 1; i < objects.length; i++) {
    xref += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`
  }
  xref += `trailer\n<< /Size ${objects.length} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`
  parts.push(te.encode(xref))
  return concatBytes(parts)
}

// ---------- endpoint export ----------
async function requireExportKey(c: { env: { API_KEY: string }; req: { header: (k: string) => string | undefined; query: (k: string) => string | undefined } }): Promise<boolean> {
  const q = (c.req.query('key') ?? '').trim()
  const h = (c.req.header('X-API-Key') ?? '').trim()
  return q === c.env.API_KEY || h === c.env.API_KEY
}

app.get('/api/export/month.xlsx', async (c) => {
  if (!(await requireExportKey(c))) return c.json({ ok: false, error: 'unauthorized' }, 401)
  const month = (c.req.query('month') ?? '').trim()
  if (!MONTH_RE.test(month)) return c.json({ ok: false, error: 'month_harus_format_YYYY-MM' }, 400)

  const data = await laporanBulan(c.env.DB, month)
  const rows: (string | number | null)[][] = [[
    'No', 'Tanggal', 'Jam', 'Nama Pelanggan', 'ID Pelanggan', 'Alamat', 'Plus Code', 'Koordinat', 'Status', 'Petugas', 'Foto', 'Catatan',
  ]]
  data.forEach((d, i) => {
    const r = d.r
    const ca = String(r.created_at ?? '')
    const lat = r.latitude
    const lng = r.longitude
    rows.push([
      i + 1,
      ca.slice(0, 10),
      ca.length >= 16 ? ca.slice(11, 16) : '',
      (r.nama_pelanggan as string) ?? '',
      (r.id_pelanggan as string) ?? '',
      (r.alamat as string) ?? '',
      (r.plus_code as string) ?? '',
      lat != null && lng != null ? `${Number(lat).toFixed(6)}, ${Number(lng).toFixed(6)}` : '',
      (r.status as string) ?? '',
      (r.petugas as string) ?? '',
      d.foto,
      (r.catatan as string) ?? '',
    ])
  })

  const bytes = buildXlsx(rows, [5, 11, 7, 22, 14, 34, 14, 20, 10, 16, 6, 40])
  return new Response(bytes as unknown as BodyInit, {
    headers: {
      'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'content-disposition': `attachment; filename="Laporan-PLN-${month}.xlsx"`,
      'cache-control': 'no-store',
    },
  })
})

app.get('/api/export/month.pdf', async (c) => {
  if (!(await requireExportKey(c))) return c.json({ ok: false, error: 'unauthorized' }, 401)
  const month = (c.req.query('month') ?? '').trim()
  if (!MONTH_RE.test(month)) return c.json({ ok: false, error: 'month_harus_format_YYYY-MM' }, 400)

  const data = await laporanBulan(c.env.DB, month)
  const generatedAt = new Date().toLocaleString('id-ID', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Jakarta' }) + ' WIB'
  const bytes = buildMonthPdf(monthLabel(month), month, data, generatedAt)
  return new Response(bytes as unknown as BodyInit, {
    headers: {
      'content-type': 'application/pdf',
      'content-disposition': `attachment; filename="Laporan-PLN-${month}.pdf"`,
      'cache-control': 'no-store',
    },
  })
})

// ============================================================
// STATUS SERVER + PEMAKAIAN FREE TIER (v1.9.0-m9)
// Admin melihat: status (lancar/maintenance/down, bisa diubah),
// pemakaian hari ini vs batas free tier, riwayat 7 hari, dan isi
// gudang. ALAMAT/URL server SENGAJA tidak pernah dikirim/tampil.
// ============================================================
const VALID_STATUS = ['lancar', 'maintenance', 'down'] as const
type ServerStatus = (typeof VALID_STATUS)[number]

const LIMITS = {
  requests_per_day: 100_000, // Workers free
  d1_rows_read_per_day: 5_000_000, // D1 free
  d1_rows_written_per_day: 100_000, // D1 free
}

async function getSetting(db: D1Database, key: string, fallback = ''): Promise<string> {
  try {
    const r = await db.prepare('SELECT value FROM settings WHERE key = ?').bind(key).first<{ value: string }>()
    return r?.value ?? fallback
  } catch {
    return fallback
  }
}

async function setSetting(db: D1Database, key: string, value: string) {
  await db
    .prepare(
      'INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value'
    )
    .bind(key, value)
    .run()
}

async function safeFirst<T>(p: Promise<D1Result<T>>): Promise<T | null> {
  try {
    return (await p).results?.[0] ?? null
  } catch {
    return null
  }
}

async function safeAll<T>(p: Promise<D1Result<T>>): Promise<T[]> {
  try {
    return (await p).results ?? []
  } catch {
    return []
  }
}

async function dbFileSize(db: D1Database): Promise<number | null> {
  try {
    const r = await db
      .prepare('SELECT page_count * page_size AS bytes FROM pragma_page_count(), pragma_page_size()')
      .first<{ bytes: number }>()
    return r?.bytes ?? null
  } catch {
    return null
  }
}

app.get('/api/server', async (c) => {
  const db = c.env.DB
  const [status, statusAt, today, days, photoAgg, dbBytes] = await Promise.all([
    getSetting(db, 'server_status', 'lancar'),
    getSetting(db, 'server_status_at', ''),
    safeFirst(
      db
        .prepare('SELECT day, requests, rows_read, rows_written FROM usage_daily WHERE day = ?')
        .bind(usageDay())
        .all<{ day: string; requests: number; rows_read: number; rows_written: number }>()
    ),
    safeAll<Record<string, unknown>>(
      db
        .prepare('SELECT day, requests, rows_read, rows_written FROM usage_daily ORDER BY day DESC LIMIT 7')
        .all<{ day: string; requests: number; rows_read: number; rows_written: number }>()
    ),
    safeFirst(
      db.prepare('SELECT COUNT(*) AS n, COALESCE(SUM(ukuran_kb), 0) AS kb FROM photos').all<{ n: number; kb: number }>()
    ),
    dbFileSize(db),
  ])

  const [reports, messages, petugas] = await Promise.all([
    countSafe(db, 'reports'),
    countSafe(db, 'chat_messages'),
    countSafe(db, 'petugas'),
  ])

  const st: ServerStatus = (VALID_STATUS as readonly string[]).includes(status)
    ? (status as ServerStatus)
    : 'lancar'

  return c.json({
    ok: true,
    version: VERSION,
    time: nowIso(),
    status: st,
    status_updated_at: statusAt,
    limits: LIMITS,
    today: today ?? { day: usageDay(), requests: 0, rows_read: 0, rows_written: 0 },
    last_days: days, // terbaru dulu
    storage: {
      photos: photoAgg?.n ?? 0,
      photos_kb: photoAgg?.kb ?? 0,
      db_bytes: dbBytes,
      reports,
      messages,
      petugas,
    },
  })
})

app.post('/api/server/status', async (c) => {
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  const status = String(body?.status ?? '').toLowerCase()
  if (!(VALID_STATUS as readonly string[]).includes(status))
    return c.json({ ok: false, error: 'status_harus_lancar_maintenance_atau_down' }, 400)

  const t = nowIso()
  await setSetting(c.env.DB, 'server_status', status)
  await setSetting(c.env.DB, 'server_status_at', t)
  return c.json({ ok: true, status, status_updated_at: t })
})

// ============================================================
// LAPORAN — UBAH STATUS (admin web, v1.9.0-m9)
// 2 jenis saja: TERTUNDA <-> SELESAI. PATCH = operasi tulis,
// otomatis wajib X-API-Key lewat middleware.
// ============================================================
app.patch('/api/reports/:id/status', async (c) => {
  const id = c.req.param('id')
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  const status = String(body?.status ?? '').toUpperCase()
  if (status !== 'TERTUNDA' && status !== 'SELESAI')
    return c.json({ ok: false, error: 'status_harus_tertunda_atau_selesai' }, 400)

  const db = c.env.DB
  const res = await db
    .prepare('UPDATE reports SET status = ? WHERE id = ?')
    .bind(status, id)
    .run()
  if (!res.meta.changes) return c.json({ ok: false, error: 'laporan_tidak_ditemukan' }, 404)

  return c.json({ ok: true, report: { id, status } })
})

// ============================================================
// CRON HARIAN (00:10 WIB) — retensi: bulan berjalan + 2 sebelumnya
// ============================================================
function cutoffIso(): string {
  const d = new Date()
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 2, 1)).toISOString()
}

export default {
  fetch: app.fetch,
  async scheduled(_evt: ScheduledController, env: Bindings, _ctx: ExecutionContext) {
    const cutoff = cutoffIso()
    const old = await env.DB
      .prepare('SELECT id FROM reports WHERE created_at < ? LIMIT 200')
      .bind(cutoff)
      .all<{ id: string }>()

    for (const r of old.results ?? []) {
      const ph = await env.DB.prepare('SELECT r2_key FROM photos WHERE report_id = ?').bind(r.id).all<{ r2_key: string }>()
      if (ph.results?.length) {
        await env.PHOTOS.delete(ph.results.map((p) => p.r2_key))
      }
      await env.DB.prepare('DELETE FROM photos WHERE report_id = ?').bind(r.id).run()
      await env.DB.prepare('DELETE FROM reports WHERE id = ?').bind(r.id).run()
    }

    // Riwayat pemakaian: simpan 60 hari terakhir saja
    await env.DB
      .prepare("DELETE FROM usage_daily WHERE day < strftime('%Y-%m-%d', 'now', '-60 days')")
      .run()
      .catch(() => {})
  },
} satisfies ExportedHandler<Bindings>
