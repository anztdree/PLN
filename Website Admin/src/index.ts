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

// ============================================================
// MIDDLEWARE — semua operasi TULIS wajib X-API-Key (anti spam).
// Operasi BACA (GET) terbuka untuk sementara; dipertatut saat
// web admin jadi (M3).
// ============================================================
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
    version: '1.7.0-m7',
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
      field('status') || 'BARU',
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
  },
} satisfies ExportedHandler<Bindings>
