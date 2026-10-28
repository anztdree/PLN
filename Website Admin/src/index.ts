import { Hono } from 'hono'

type Bindings = {
  DB: D1Database
  PHOTOS: R2Bucket
  API_KEY: string
  ADMIN_NAME: string
  ACCOUNT_ID: string
  DB_ID: string
  HEALTH_URL: string
  SELF?: Fetcher
  CLOUDFLARE_ANALYTICS_TOKEN?: string
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
const VERSION = '1.14.0-m14'

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
// PEMAKAIAN NYATA — ANALITIK CLOUDFLARE (v1.10.0-m10)
// Angka pemakaian diambil langsung dari analitik Cloudflare
// (GraphQL): permintaan worker + baris D1 dibaca/ditulis per hari
// — persis jendela limit yang direset 00:00 UTC. Di-cache 2 menit
// per isolate agar hemat; bila analitik tak terjangkau, jatuh ke
// pelacak internal (usage_daily) sebagai cadangan.
// ============================================================
type UsageDay = { day: string; requests: number; rows_read: number; rows_written: number }
type UsageSnapshot = { days: UsageDay[]; source: 'analitik-cloudflare' | 'pelacak-lokal'; at: number }

let gUsageCache: UsageSnapshot | null = null
const USAGE_TTL_MS = 120_000

async function fetchAnalyticsUsage(env: Bindings): Promise<UsageSnapshot | null> {
  const token = env.CLOUDFLARE_ANALYTICS_TOKEN
  if (!token || !env.ACCOUNT_ID || !env.DB_ID) return null
  const from = new Date(Date.now() - 6 * 86_400_000)
  from.setUTCHours(0, 0, 0, 0)
  const to = new Date()
  const query = `query {
    viewer {
      accounts(filter: { accountTag: "${env.ACCOUNT_ID}" }) {
        workersInvocationsAdaptive(limit: 10000, filter: { scriptName: "pln-admin", datetime_geq: "${from.toISOString()}", datetime_leq: "${to.toISOString()}" }, orderBy: [date_ASC]) {
          dimensions { date }
          sum { requests }
        }
        d1AnalyticsAdaptiveGroups(limit: 10000, filter: { databaseId: "${env.DB_ID}", datetime_geq: "${from.toISOString()}", datetime_leq: "${to.toISOString()}" }, orderBy: [date_ASC]) {
          dimensions { date }
          sum { rowsRead rowsWritten }
        }
      }
    }
  }`
  try {
    const res = await fetch('https://api.cloudflare.com/client/v4/graphql', {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ query }),
      signal: AbortSignal.timeout(8_000),
    })
    if (!res.ok) return null
    const j = (await res.json()) as {
      data?: {
        viewer?: {
          accounts?: {
            workersInvocationsAdaptive?: { dimensions: { date: string }; sum: { requests: number } }[]
            d1AnalyticsAdaptiveGroups?: { dimensions: { date: string }; sum: { rowsRead: number; rowsWritten: number } }[]
          }[]
        }
      }
    }
    const acc = j.data?.viewer?.accounts?.[0]
    const map = new Map<string, UsageDay>()
    for (const r of acc?.workersInvocationsAdaptive ?? []) {
      const day = String(r?.dimensions?.date ?? '')
      if (day) map.set(day, { day, requests: Number(r?.sum?.requests) || 0, rows_read: 0, rows_written: 0 })
    }
    for (const r of acc?.d1AnalyticsAdaptiveGroups ?? []) {
      const day = String(r?.dimensions?.date ?? '')
      if (!day) continue
      const cur = map.get(day) ?? { day, requests: 0, rows_read: 0, rows_written: 0 }
      cur.rows_read = Number(r?.sum?.rowsRead) || 0
      cur.rows_written = Number(r?.sum?.rowsWritten) || 0
      map.set(day, cur)
    }
    if (!map.size) return null
    const days = [...map.values()].sort((a, b) => (a.day < b.day ? -1 : 1))
    return { days, source: 'analitik-cloudflare', at: Date.now() }
  } catch {
    return null
  }
}

async function getUsageSnapshot(db: D1Database, env: Bindings): Promise<UsageSnapshot> {
  if (gUsageCache && Date.now() - gUsageCache.at < USAGE_TTL_MS) return gUsageCache
  const live = await fetchAnalyticsUsage(env)
  if (live && live.days.length) {
    gUsageCache = live
    return live
  }
  // Cadangan: pelacak internal bila analitik sedang tak terjangkau
  const rows = await safeAll<UsageDay>(
    db.prepare('SELECT day, requests, rows_read, rows_written FROM usage_daily ORDER BY day ASC LIMIT 7').all<UsageDay>()
  )
  const snap: UsageSnapshot = { days: rows, source: 'pelacak-lokal', at: Date.now() }
  gUsageCache = snap
  return snap
}

// ============================================================
// KESEHATAN SERVER (v1.10.0-m10)
// Cron tiap 5 menit memanggil ulang /api/health lewat jaringan
// publik, mencatat waktu tanggap (ms) & keberhasilannya ke tabel
// server_health. /api/server merangkum 24 jam terakhir menjadi
// persen kelancaran + ms rata-rata — status dihitung dari hasil
// nyata, BUKAN pengaturan manual.
// ============================================================
let gLastSelfCheckAt = 0

/**
 * Pemeriksaan ketersediaan: panggil ulang /api/health lewat service
 * binding SELF (fetch URL publik sendiri diblokir Cloudflare) atau,
 * sebagai cadangan di lokal, fetch HEALTH_URL. ms = waktu proses
 * worker + basis data. sumber = 'cron'.
 */
async function recordSelfCheck(env: Bindings) {
  const t0 = Date.now()
  let ok = false
  try {
    let res: Response
    if (env.SELF) {
      res = await env.SELF.fetch('https://pemeriksaan-internal/api/health', { signal: AbortSignal.timeout(8_000) })
    } else {
      res = await fetch(`${env.HEALTH_URL}${env.HEALTH_URL.includes('?') ? '&' : '?'}_pemeriksaan=${t0}`, {
        signal: AbortSignal.timeout(8_000),
      })
    }
    ok = res.ok
    if (!res.ok) console.error('pemeriksaan-mandiri: status HTTP', res.status)
  } catch (e) {
    ok = false
    console.error('pemeriksaan-mandiri: gagal fetch:', String(e))
  }
  const ms = Math.min(59_999, Date.now() - t0)
  await env.DB
    .prepare("INSERT INTO server_health (ts, ms, ok, sumber) VALUES (?, ?, ?, 'cron')")
    .bind(new Date().toISOString(), ms, ok ? 1 : 0)
    .run()
}

/** Jaga agar pemeriksaan tetap mengalir walau cron terlewat (maks 1x per 4 menit per isolate). */
function maybeSelfCheck(env: Bindings, waitUntil: (p: Promise<unknown>) => void) {
  const now = Date.now()
  if (now - gLastSelfCheckAt < 4 * 60_000) return
  gLastSelfCheckAt = now
  waitUntil(recordSelfCheck(env).catch(() => {}))
}

type HealthSummary = {
  checks_24h: number
  uptime_pct: number | null
  avg_ms: number | null
  last_ms: number | null
  last_ok: boolean | null
  last_at: string | null
  status: 'lancar' | 'lambat' | 'gangguan'
}

/**
 * Kelancaran (%) dihitung dari pemeriksaan 'cron' tiap 5 menit.
 * Waktu tanggap (ms) diutamakan dari laporan 'web' — latensi nyata
 * yang dirasakan dasbor di browser (termasuk jaringan ponsel);
 * bila belum ada, pakai ms pemeriksaan internal.
 */
async function healthSummary(db: D1Database): Promise<HealthSummary> {
  const since = new Date(Date.now() - 86_400_000).toISOString()
  const [cronAgg, cronLast, webAgg, lastAny] = await Promise.all([
    safeFirst(
      db
        .prepare("SELECT COUNT(*) AS n, SUM(ok) AS okN FROM server_health WHERE ts >= ? AND sumber = 'cron'")
        .bind(since)
        .all<{ n: number; okN: number | null }>()
    ),
    safeFirst(
      db
        .prepare("SELECT ts, ms, ok FROM server_health WHERE sumber = 'cron' ORDER BY ts DESC LIMIT 1")
        .all<{ ts: string; ms: number; ok: number }>()
    ),
    safeFirst(
      db
        .prepare("SELECT AVG(ms) AS avgMs, COUNT(*) AS n FROM server_health WHERE ts >= ? AND sumber = 'web'")
        .bind(since)
        .all<{ avgMs: number | null; n: number }>()
    ),
    safeFirst(
      db.prepare('SELECT ts, ms, ok FROM server_health ORDER BY ts DESC LIMIT 1').all<{ ts: string; ms: number; ok: number }>()
    ),
  ])

  const total = cronAgg?.n ?? 0
  const uptime = total > 0 && cronAgg?.okN != null ? (cronAgg.okN / total) * 100 : null
  const lastCron = cronLast ?? null
  const webN = webAgg?.n ?? 0
  const avgMsWeb = webAgg?.avgMs != null ? Math.round(webAgg.avgMs) : null
  const avgMsCron = lastCron?.ms ?? null
  const avgMs = avgMsWeb ?? avgMsCron
  const lastRow = lastAny ?? null

  let status: HealthSummary['status'] = 'lancar'
  if ((lastCron && !lastCron.ok) || (uptime != null && uptime < 90)) status = 'gangguan'
  else if (avgMs != null && avgMs > (webN > 0 ? 2_000 : 800)) status = 'lambat'

  return {
    checks_24h: total,
    uptime_pct: uptime != null ? Math.round(uptime * 10) / 10 : null,
    avg_ms: avgMs,
    last_ms: lastRow?.ms ?? null,
    last_ok: lastRow ? !!lastRow.ok : null,
    last_at: lastRow?.ts ?? null,
    status,
  }
}

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
// OBROLAN — HAPUS PESAN (admin web, v1.10.0-m10)
// Pesan yang dihapus hilang untuk semua pengguna. Polling APK
// tetap aman karena hanya meminta pesan dengan id LEBIH BESAR
// dari yang sudah dikenalnya (pesanan id tidak bergeser).
// Tulis otomatis wajib X-API-Key lewat middleware (kunci admin).
// ============================================================
app.delete('/api/chat/messages/:id', async (c) => {
  const id = Number(c.req.param('id'))
  if (!Number.isInteger(id) || id <= 0)
    return c.json({ ok: false, error: 'id_tidak_valid' }, 400)

  const res = await c.env.DB.prepare('DELETE FROM chat_messages WHERE id = ?').bind(id).run()
  if (!res.meta.changes) return c.json({ ok: false, error: 'pesan_tidak_ditemukan' }, 404)

  return c.json({ ok: true, deleted_id: id })
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
    db
      .prepare('SELECT id, nama, last_seen_at FROM petugas ORDER BY id ASC LIMIT 500')
      .all<{ id: number; nama: string; last_seen_at: string | null }>(),
  ])

  const list = petugasRes.results ?? []
  // v1.14.0-m14: daftar petugas + status online ikut dikirim — dipakai
  // OBROLAN NATIVE di Aplikasi Admin (daftar percakapan seperti WhatsApp).
  const now = Date.now()
  const petugasList = list.map((p) => ({
    id: p.id,
    nama: p.nama,
    online: !!p.last_seen_at && now - Date.parse(p.last_seen_at) < ONLINE_WINDOW_MS,
  }))
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

  // ============================================================
  // BELUM-TERBACA UNTUK ADMIN (lencana FAB obrolan, v1.11.0-m11)
  // Sumber = chat_reads reader 'admin' (tercatat tiap web membuka
  // percakapan). Pesan dihitung belum-terbaca bila pengirim bukan
  // admin dan id-nya lebih besar dari bacaan terakhir admin.
  // ============================================================
  const readsRes = await db
    .prepare("SELECT conv_key, last_read_id FROM chat_reads WHERE reader_id = 'admin'")
    .all<{ conv_key: string; last_read_id: number }>()
  const bacaAdmin = new Map<string, number>()
  for (const r of readsRes.results ?? []) bacaAdmin.set(r.conv_key, r.last_read_id)

  const gUnreadAdmin = await db
    .prepare(
      "SELECT COUNT(*) AS n FROM chat_messages WHERE scope = 'global' AND sender_id != 'admin' AND id > ?"
    )
    .bind(bacaAdmin.get('global') ?? 0)
    .first<{ n: number }>()

  const unreadStmts = list.map((p) => {
    const key = `${p.id}|admin` // admin (bukan angka) selalu di belakang
    return db
      .prepare(
        `SELECT COUNT(*) AS n FROM chat_messages
         WHERE scope = 'private'
           AND ((sender_id = ? AND target_id = 'admin') OR (sender_id = 'admin' AND target_id = ?))
           AND sender_id != 'admin' AND id > ?`
      )
      .bind(String(p.id), String(p.id), bacaAdmin.get(key) ?? 0)
  })
  const unreadRes = unreadStmts.length ? await db.batch(unreadStmts) : []

  const unread: Record<string, number> = { global: gUnreadAdmin?.n ?? 0 }
  let unreadTotal = gUnreadAdmin?.n ?? 0
  ;(unreadRes as D1Result<{ n: number }>[]).forEach((r, i) => {
    const n = r.results?.[0]?.n ?? 0
    if (n > 0) unreadTotal += n
    unread[String(list[i].id)] = n
  })

  return c.json({
    ok: true,
    global: gLastRes ?? null,
    dm,
    unread,
    unread_total: unreadTotal,
    petugas: petugasList,
    server_time: nowIso(),
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
// EXPORT LAPORAN BULANAN (v1.13.0-m13) — Excel (.xlsx) & PDF
// Dibuat TANPA dependensi: xlsx = ZIP store + SpreadsheetML +
// gambar (xl/media + drawing); PDF = berkas PDF 1.4 ditulis
// manual dengan foto JPEG (DCTDecode) inline di baris tabel.
// Aturan isi berkas (permintaan user — BERSIH):
//   - Logo PLN + judul + periode di kepala, TANPA keterangan
//   - FOTO WAJIB ikut (dibaca langsung dari R2, JPEG asli)
//   - JAM tidak dicantumkan — hanya tanggal (zona WIB)
//   - TIDAK ADA narasi/catatan otomatis/penutup setelah tabel
// Autentikasi: X-API-Key (header) ATAU ?key= (unduh langsung).
// ============================================================
// Logo PLN (lingkaran navy + petir kuning) ditanam di kepala Excel —
// JPEG 256x256, geometri sama dengan logo vektor PDF (pdfLogo).
const LOGO_PLN_JPEG_B64 =
  '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'
const logoPlnBytes = (): Uint8Array => Uint8Array.from(atob(LOGO_PLN_JPEG_B64), (ch) => ch.charCodeAt(0))

const BULAN_ID = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember']

const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/

function monthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number)
  return `${BULAN_ID[m - 1]} ${y}`
}

/** Tanggal saja (tanpa jam), zona WIB — mis. "12 Feb 2026". */
function tanggalWib(iso: string): string {
  const d = Date.parse(iso)
  if (Number.isNaN(d)) return ''
  return new Intl.DateTimeFormat('id-ID', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'Asia/Jakarta' }).format(d)
}

/** Baca dimensi JPEG dari byte (marker SOF0/1/2) — tanpa dependensi. */
function jpegSize(bytes: Uint8Array): { w: number; h: number } | null {
  if (bytes.length < 9 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null
  let i = 2
  while (i + 9 < bytes.length) {
    if (bytes[i] !== 0xff) {
      i++
      continue
    }
    const marker = bytes[i + 1]
    if (marker === 0xff) {
      i++
      continue
    }
    if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd9)) {
      i += 2
      continue
    }
    const len = (bytes[i + 2] << 8) | bytes[i + 3]
    const isSof =
      (marker >= 0xc0 && marker <= 0xc3) ||
      (marker >= 0xc5 && marker <= 0xc7) ||
      (marker >= 0xc9 && marker <= 0xcb) ||
      (marker >= 0xcd && marker <= 0xcf)
    if (isSof) {
      const h = (bytes[i + 5] << 8) | bytes[i + 6]
      const w = (bytes[i + 7] << 8) | bytes[i + 8]
      return w > 0 && h > 0 ? { w, h } : null
    }
    if (len < 2) return null
    i += 2 + len
  }
  return null
}

type FotoTerlampir = { reportId: string; urutan: number; bytes: Uint8Array; w: number; h: number }

/** Baris laporan satu bulan + seluruh fotonya dari R2 (JPEG siap semat). */
async function laporanBulan(env: Bindings, month: string): Promise<{ r: Record<string, unknown>; fotos: FotoTerlampir[] }[]> {
  const [y, m] = month.split('-').map(Number)
  const start = `${month}-01`
  const end = new Date(Date.UTC(y, m, 1)).toISOString().slice(0, 10) // awal bulan berikutnya
  const { results } = await env.DB
    .prepare('SELECT * FROM reports WHERE created_at >= ? AND created_at < ? ORDER BY created_at ASC')
    .bind(start, end)
    .all<Record<string, unknown>>()
  const rows = results ?? []
  if (!rows.length) return rows.map((r) => ({ r, fotos: [] as FotoTerlampir[] }))

  const marks = rows.map(() => '?').join(',')
  const ph = await env.DB
    .prepare(`SELECT report_id, r2_key, urutan FROM photos WHERE report_id IN (${marks}) ORDER BY report_id ASC, urutan ASC`)
    .bind(...rows.map((r) => String(r.id)))
    .all<{ report_id: string; r2_key: string; urutan: number }>()

  const byReport = new Map<string, FotoTerlampir[]>()
  for (const p of ph.results ?? []) {
    try {
      const obj = await env.PHOTOS.get(p.r2_key)
      if (!obj) continue
      const bytes = new Uint8Array(await obj.arrayBuffer())
      if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) continue // bukan JPEG — lewati
      const dim = jpegSize(bytes)
      if (!dim) continue
      const foto: FotoTerlampir = { reportId: p.report_id, urutan: p.urutan, bytes, w: dim.w, h: dim.h }
      let arr = byReport.get(p.report_id)
      if (!arr) {
        arr = []
        byReport.set(p.report_id, arr)
      }
      arr.push(foto)
    } catch {
      /* foto satu berkas gagal — lanjutkan sisanya */
    }
  }
  return rows.map((r) => ({ r, fotos: byReport.get(String(r.id)) ?? [] }))
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

// ---------- XLSX (dengan foto tertanam) ----------
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

type XlsxGambar = {
  data: Uint8Array
  col: number // 0-based
  row: number // 0-based
  wPx: number
  hPx: number
  rowOffEmu: number
  colOffEmu?: number // geser horizontal di dalam sel (untuk grid 2 kolom foto)
}

/**
 * Gaya sel Excel (v1.14.0-m14) — indeks cellXfs:
 *  0 default | 1 header kuning | 2 judul | 3 periode | 4 data wrap |
 *  5 data tengah | 6 SELESAI (hijau) | 7 TERTUNDA (merah) |
 *  8-11 versi zebra (baris genap berlatar biru muda) dari 4-7.
 */
const XS = {
  DATA: 4,
  CENTER: 5,
  SELESAI: 6,
  TERTUNDA: 7,
  Z_DATA: 8,
  Z_CENTER: 9,
  Z_SELESAI: 10,
  Z_TERTUNDA: 11,
} as const

function buildXlsx(
  rows: (string | number | null)[][],
  widths: number[],
  rowHeights: Record<number, number>, // nomor baris Excel (1-based) -> tinggi pt
  images: XlsxGambar[],
  merges: string[] = [], // rentang sel digabung, mis. "B1:K1"
  headerRow = 0, // 0-based baris judul tabel (bold + freeze di bawahnya)
  styleCells: Record<string, number> = {}, // "r:c" -> indeks cellXfs
  colStyles: number[] = [], // gaya per KOLOM untuk baris data (border rapi di seluruh tabel)
): Uint8Array {
  const te = new TextEncoder()
  const cols = widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('')
  const body = rows
    .map((row, r) => {
      const ht = rowHeights[r + 1]
      const cells = row
        .map((v, c) => {
          const isHeader = r === headerRow
          const style = isHeader
            ? 1
            : r > headerRow
              ? (styleCells[`${r}:${c}`] ?? colStyles[c] ?? XS.DATA)
              : styleCells[`${r}:${c}`]
          const stAttr = style ? ` s="${style}"` : ''
          const ref = `${colRef(c + 1)}${r + 1}`
          if (v === null || v === undefined || v === '') {
            // Sel kosong tetap digambar (gaya membawa border) agar tabel utuh.
            return stAttr ? `<c r="${ref}"${stAttr}/>` : ''
          }
          if (typeof v === 'number' && Number.isFinite(v)) return `<c r="${ref}"${stAttr}><v>${v}</v></c>`
          return `<c r="${ref}" t="inlineStr"${stAttr}><is><t xml:space="preserve">${xmlEsc(String(v))}</t></is></c>`
        })
        .join('')
      return `<row r="${r + 1}"${ht ? ` ht="${ht}" customHeight="1"` : ''}>${cells}</row>`
    })
    .join('')
  const mergeXml = merges.length
    ? `<mergeCells count="${merges.length}">${merges.map((m) => `<mergeCell ref="${m}"/>`).join('')}</mergeCells>`
    : ''

  const sheet = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetViews><sheetView workbookViewId="0"><pane ySplit="${headerRow}" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${cols}</cols><sheetData>${body}</sheetData>${mergeXml}${images.length ? '<drawing r:id="rId1"/>' : ''}</worksheet>`

  const files: { name: string; data: Uint8Array }[] = [
    {
      name: '[Content_Types].xml',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>${images.length ? '<Default Extension="jpeg" ContentType="image/jpeg"/>' : ''}<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${images.length ? '<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' : ''}</Types>`),
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
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="7"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FF1A2433"/><name val="Calibri"/></font><font><b/><sz val="16"/><color rgb="FF0D1B2E"/><name val="Calibri"/></font><font><i/><sz val="10"/><color rgb="FF6B7A90"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FF166534"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFB91C1C"/><name val="Calibri"/></font><font><sz val="11"/><color rgb="FF3D4C5E"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD84D"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF2F6FB"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFC9D3E0"/></left><right style="thin"><color rgb="FFC9D3E0"/></right><top style="thin"><color rgb="FFC9D3E0"/></top><bottom style="thin"><color rgb="FFC9D3E0"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="12"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf><xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="4" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top"/></xf><xf numFmtId="0" fontId="5" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="4" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top"/></xf><xf numFmtId="0" fontId="5" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="top"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`),
    },
    { name: 'xl/worksheets/sheet1.xml', data: te.encode(sheet) },
  ]

  if (images.length) {
    // rels lembar kerja -> drawing
    files.push({
      name: 'xl/worksheets/_rels/sheet1.xml.rels',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/></Relationships>`),
    })

    // rels drawing -> berkas gambar
    const drawingRels = images
      .map(
        (_, i) =>
          `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${i + 1}.jpeg"/>`
      )
      .join('')
    files.push({
      name: 'xl/drawings/_rels/drawing1.xml.rels',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${drawingRels}</Relationships>`),
    })

    // drawing: satu oneCellAnchor per foto (mengunci ke sel, aspek dipertahankan)
    const EMU = 9525 // per piksel
    const anchors = images
      .map((g, i) => {
        const cx = Math.round(g.wPx * EMU)
        const cy = Math.round(g.hPx * EMU)
        return `<xdr:oneCellAnchor><xdr:from><xdr:col>${g.col}</xdr:col><xdr:colOff>${g.colOffEmu ?? 19050}</xdr:colOff><xdr:row>${g.row}</xdr:row><xdr:rowOff>${g.rowOffEmu}</xdr:rowOff></xdr:from><xdr:ext cx="${cx}" cy="${cy}"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i + 1}" name="Foto ${i + 1}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>`
      })
      .join('')
    files.push({
      name: 'xl/drawings/drawing1.xml',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">${anchors}</xdr:wsDr>`),
    })

    images.forEach((g, i) => files.push({ name: `xl/media/image${i + 1}.jpeg`, data: g.data }))
  }

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

/** Logo PLN vektor: lingkaran kuning + petir navy — dipakai di pita judul PDF. */
function pdfLogo(cmds: string[], cx: number, cy: number, r: number) {
  const f = (n: number) => n.toFixed(2)
  const k = 0.5523 * r
  cmds.push('1 0.824 0.247 rg')
  cmds.push(
    `${f(cx - r)} ${f(cy)} m`,
    `${f(cx - r)} ${f(cy + k)} ${f(cx - k)} ${f(cy + r)} ${f(cx)} ${f(cy + r)} c`,
    `${f(cx + k)} ${f(cy + r)} ${f(cx + r)} ${f(cy + k)} ${f(cx + r)} ${f(cy)} c`,
    `${f(cx + r)} ${f(cy - k)} ${f(cx + k)} ${f(cy - r)} ${f(cx)} ${f(cy - r)} c`,
    `${f(cx - k)} ${f(cy - r)} ${f(cx - r)} ${f(cy - k)} ${f(cx - r)} ${f(cy)} c f`,
  )
  // petir: path SVG 24x24 (M13 2 L4.5 13.5 H11 L9.5 22 L19 9.8 H12.4 Z), Y PDF ke atas
  const s = (r * 1.55) / 20
  const pts: [number, number][] = [[13, 2], [4.5, 13.5], [11, 13.5], [9.5, 22], [19, 9.8], [12.4, 9.8]]
  const px = (x: number) => f(cx + (x - 11.75) * s)
  const py = (y: number) => f(cy - (y - 12) * s)
  cmds.push('0.051 0.106 0.180 rg')
  cmds.push(
    `${px(pts[0][0])} ${py(pts[0][1])} m ${pts
      .slice(1)
      .map(([x, y]) => `${px(x)} ${py(y)} l`)
      .join(' ')} h f`,
  )
}

function buildMonthPdf(
  label: string,
  data: { r: Record<string, unknown>; fotos: FotoTerlampir[] }[],
): Uint8Array {
  const W = 842, H = 595, M = 32
  // Kolom FOTO tepat setelah PELANGGAN — foto tampil INLINE di barisnya.
  // Laporan BERSIH (permintaan user): logo + judul + periode + tabel, selesai.
  const COLS = [22, 52, 88, 176, 62, 190, 54, 66, 68]
  const HEAD = ['No', 'Tanggal', 'Pelanggan', 'Foto', 'ID Pelanggan', 'Alamat', 'Status', 'Petugas', 'Catatan']
  const FOTO_IDX = 3
  const tableW = COLS.reduce((a, b) => a + b, 0)
  const fs = 7.6
  const headH = 20
  const leading = 9
  const titleH = 64 // pita judul ramping: logo PLN + judul + periode
  const contH = 30  // pita lanjutan
  const footH = 24  // bawah: hanya nomor halaman

  // Grid foto di dalam sel: 3 foto sejajar, kotak maksimal 64x54 pt.
  const TH_W = 64, TH_H = 54, PITCH_X = 70, PITCH_Y = 62, GRID_COLS = 3

  const total = data.length
  const nSelesai = data.filter((d) => String(d.r.status ?? '').toUpperCase() === 'SELESAI').length
  const nTertunda = total - nSelesai
  const summary = `Total ${total} laporan  ·  SELESAI ${nSelesai}  ·  TERTUNDA ${nTertunda}`

  const rowHOf = (d: { fotos: FotoTerlampir[] }): number =>
    d.fotos.length ? Math.ceil(d.fotos.length / GRID_COLS) * PITCH_Y + 10 : 26

  // ---- daftar foto unik (objek XObject) + kunci identitas ----
  const objIdx = new Map<string, number>()
  const flat: FotoTerlampir[] = []
  data.forEach((d) => {
    d.fotos.forEach((f, j) => {
      const key = `${String(d.r.id)}:${j}`
      if (!objIdx.has(key)) {
        objIdx.set(key, flat.length)
        flat.push(f)
      }
    })
  })

  // ---- paginasi dinamis: tinggi baris mengikuti jumlah fotonya ----
  type Block = { d: { r: Record<string, unknown>; fotos: FotoTerlampir[] }; no: number; h: number }
  const blocks: Block[] = data.map((d, i) => ({ d, no: i + 1, h: rowHOf(d) }))
  const firstTop = H - titleH
  const nextTop = H - contH
  type Page = { rows: Block[]; top: number; used: number }
  const pages: Page[] = []
  let curRows: Block[] = []
  let curTop = firstTop
  let used = 0
  for (const b of blocks) {
    const avail = curTop - headH - footH - 8 - used
    if (avail < b.h && curRows.length) {
      pages.push({ rows: curRows, top: curTop, used })
      curRows = []
      curTop = nextTop
      used = 0
    }
    curRows.push(b)
    used += b.h
  }
  if (curRows.length) pages.push({ rows: curRows, top: curTop, used })

  const cellXOf = (col: number): number => M + COLS.slice(0, col).reduce((a, b) => a + b, 0)

  type Stream = { body: string; imgs: { name: string; obj: number }[] }
  const streams: Stream[] = []

  for (let p = 0; p < pages.length; p++) {
    const page = pages[p]
    const cmds: string[] = []
    const pageImgs: { name: string; obj: number }[] = []
    const bandTop = page.top

    if (p === 0) {
      // pita judul: logo PLN vektor + judul + periode + ringkasan angka
      cmds.push('0.051 0.106 0.180 rg', `0 ${H - titleH} ${W} ${titleH} re f`)
      cmds.push('1 0.824 0.247 rg', `0 ${H - titleH} ${W} 3 re f`)
      pdfLogo(cmds, M + 15, H - 33, 13)
      cmds.push('1 1 1 rg', `BT /F2 16 Tf 1 0 0 1 ${M + 38} ${H - 27} Tm (${pdfEsc('LAPORAN PEMASANGAN LAPANGAN')}) Tj ET`)
      cmds.push('0.71 0.78 0.87 rg', `BT /F1 10 Tf 1 0 0 1 ${M + 38} ${H - 46} Tm (${pdfEsc(`Periode: ${label}`)}) Tj ET`)
      const sw = Math.round(summary.length * 5.0)
      cmds.push('1 0.824 0.247 rg', `BT /F2 9 Tf 1 0 0 1 ${W - M - sw} ${H - 33} Tm (${pdfEsc(summary)}) Tj ET`)
    } else {
      cmds.push('0.051 0.106 0.180 rg', `0 ${H - contH} ${W} ${contH} re f`)
      cmds.push('1 0.824 0.247 rg', `0 ${H - contH} ${W} 2.5 re f`)
      pdfLogo(cmds, M + 10, H - 15, 8)
      cmds.push('1 1 1 rg', `BT /F2 10.5 Tf 1 0 0 1 ${M + 25} ${H - 19.5} Tm (${pdfEsc(`Laporan Pemasangan Lapangan — Periode: ${label}`)}) Tj ET`)
    }

    // header tabel
    cmds.push('0.051 0.106 0.180 rg', `${M} ${bandTop - headH} ${tableW} ${headH} re f`)
    let hx = M
    HEAD.forEach((h, i) => {
      cmds.push('1 1 1 rg', `BT /F2 ${fs + 0.4} Tf 1 0 0 1 ${hx + 5} ${bandTop - headH + 6} Tm (${pdfEsc(h)}) Tj ET`)
      hx += COLS[i]
    })

    // baris data (tinggi mengikuti foto)
    let yCursor = bandTop - headH
    page.rows.forEach((b, ri) => {
      const top = yCursor - b.h
      if (ri % 2 === 1) cmds.push('0.953 0.961 0.973 rg', `${M} ${top} ${tableW} ${b.h} re f`)
      const r = b.d.r
      const vals = [
        String(b.no),
        tanggalWib(String(r.created_at ?? '')),
        String(r.nama_pelanggan ?? ''),
        b.d.fotos.length ? `${b.d.fotos.length} foto` : '-',
        String(r.id_pelanggan ?? ''),
        String(r.alamat ?? ''),
        String(r.status ?? ''),
        String(r.petugas ?? ''),
        String(r.catatan ?? ''),
      ]
      let cx = M
      vals.forEach((v, ci) => {
        if (ci === FOTO_IDX) {
          cx += COLS[ci]
          return
        }
        const lines = wrapCell(v, COLS[ci] - 10, fs, ci === 5 ? 4 : 3)
        cmds.push('0.13 0.18 0.26 rg')
        lines.forEach((ln, li) => {
          cmds.push(`BT /F1 ${fs} Tf 1 0 0 1 ${cx + 5} ${top + b.h - 12 - li * leading} Tm (${pdfEsc(ln)}) Tj ET`)
        })
        cx += COLS[ci]
      })

      // --- FOTO INLINE: grid 3 sejajar di kolom Foto ---
      const cellX = cellXOf(FOTO_IDX)
      const rowTop = top + b.h // tepi ATAS baris (variabel top = tepi bawah)
      b.d.fotos.forEach((f, j) => {
        const c = j % GRID_COLS
        const gr = Math.floor(j / GRID_COLS)
        const scale = Math.min(TH_W / f.w, TH_H / f.h)
        const dw = f.w * scale
        const dh = f.h * scale
        const boxTop = rowTop - 8 - gr * PITCH_Y
        const boxLeft = cellX + 6 + c * PITCH_X
        const dx = boxLeft + (TH_W - dw) / 2
        const dy = boxTop - TH_H + (TH_H - dh) / 2
        // chip nomor urut foto
        cmds.push('0.051 0.106 0.180 rg', `${boxLeft} ${boxTop - 12} 11 11 re f`)
        cmds.push('1 1 1 rg', `BT /F2 6 Tf 1 0 0 1 ${boxLeft + (j + 1 < 10 ? 3.8 : 2)} ${boxTop - 9.6} Tm (${j + 1}) Tj ET`)
        // gambar + bingkai tipis
        const k = objIdx.get(`${String(b.d.r.id)}:${j}`)!
        const name = `/I${k}`
        pageImgs.push({ name, obj: k })
        cmds.push(`q ${dw.toFixed(2)} 0 0 ${dh.toFixed(2)} ${dx.toFixed(2)} ${dy.toFixed(2)} cm ${name} Do Q`)
        cmds.push('0.84 0.87 0.90 RG', '0.5 w', `${dx.toFixed(2)} ${dy.toFixed(2)} ${dw.toFixed(2)} ${dh.toFixed(2)} re S`)
      })

      yCursor = top
    })

    // garis kolom & batas baris
    cmds.push('0.84 0.87 0.90 RG', '0.6 w')
    const rowsTop = bandTop - headH
    const rowsBottom = yCursor
    let gx = M
    for (let i = 0; i <= COLS.length; i++) {
      cmds.push(`${gx} ${rowsBottom} m ${gx} ${rowsTop} l S`)
      if (i < COLS.length) gx += COLS[i]
    }
    let ly = rowsTop
    for (const b of page.rows) {
      cmds.push(`${M} ${ly} m ${M + tableW} ${ly} l S`)
      ly -= b.h
    }

    // footer — hanya nomor halaman (tanpa keterangan apa pun)
    cmds.push('0.45 0.51 0.60 rg')
    cmds.push(`BT /F1 7.5 Tf 1 0 0 1 ${W - M - 44} 12 Tm (${pdfEsc(`Hal. ${p + 1}/${pages.length}`)}) Tj ET`)

    streams.push({ body: cmds.join('\n'), imgs: pageImgs })
  }

  // ---- rakit berkas PDF (aman biner: objek foto = byte JPEG asli) ----
  const te = new TextEncoder()
  type PdfObj = { kind: 'text'; body: string } | { kind: 'stream'; dict: string; data: Uint8Array }
  const objects: (PdfObj | null)[] = [null] // indeks 0 tak dipakai

  const firstPageObj = 5
  const firstImgObj = firstPageObj + streams.length * 2
  const kids = streams.map((_, i) => `${firstPageObj + i * 2} 0 R`).join(' ')
  objects[1] = { kind: 'text', body: '<< /Type /Catalog /Pages 2 0 R >>' }
  objects[2] = { kind: 'text', body: `<< /Type /Pages /Kids [${kids}] /Count ${streams.length} >>` }
  objects[3] = { kind: 'text', body: '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>' }
  objects[4] = { kind: 'text', body: '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>' }

  streams.forEach((s, i) => {
    const pageNum = firstPageObj + i * 2
    const xobj = s.imgs.length
      ? ` /XObject << ${s.imgs.map((im) => `${im.name} ${firstImgObj + im.obj} 0 R`).join(' ')} >>`
      : ''
    objects[pageNum] = {
      kind: 'text',
      body: `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${W} ${H}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >>${xobj} >> /Contents ${pageNum + 1} 0 R >>`,
    }
    objects[pageNum + 1] = {
      kind: 'stream',
      dict: `<< /Length ${te.encode(s.body).length} >>`,
      data: te.encode(s.body), // tanda "stream"/"endstream" ditambahkan saat perakitan
    }
  })

  flat.forEach((foto, k) => {
    objects[firstImgObj + k] = {
      kind: 'stream',
      dict: `<< /Type /XObject /Subtype /Image /Width ${foto.w} /Height ${foto.h} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${foto.bytes.length} >>`,
      data: foto.bytes,
    }
  })

  const parts: Uint8Array[] = [te.encode('%PDF-1.4\n')]
  const offsets: number[] = []
  let pos = parts[0].length
  for (let i = 1; i < objects.length; i++) {
    const o = objects[i]!
    const head = te.encode(`${i} 0 obj\n`)
    let body: Uint8Array
    if (o.kind === 'text') {
      body = te.encode(`${o.body}\nendobj\n`)
    } else {
      const d = te.encode(`${o.dict}\nstream\n`)
      const tail = te.encode('\nendstream\nendobj\n')
      body = concatBytes([d, o.data, tail])
    }
    offsets[i] = pos
    parts.push(head, body)
    pos += head.length + body.length
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

  const data = await laporanBulan(c.env, month)
  const label = monthLabel(month)

  // Struktur BERSIH (permintaan user): LOGO + JUDUL → periode → tabel → selesai.
  // Tidak ada narasi, catatan otomatis, penutup, atau keterangan apa pun.
  // Kolom FOTO tepat setelah Nama Pelanggan agar foto tampil di samping data pelanggan.
  // v1.14.0-m14: tabel RAPI — border tiap sel, baris zebra, teks wrap,
  // lebar kolom pas, status berwarna, foto seragam 2 sejajar dalam sel.
  const PRE = 4 // baris sebelum data: judul, periode, kosong, header tabel
  const rows: (string | number | null)[][] = [
    [null, 'LAPORAN PEMASANGAN LAPANGAN PLN'],
    [null, `Periode: ${label}`],
    [null],
    ['No', 'Tanggal', 'Nama Pelanggan', 'Foto', 'ID Pelanggan', 'Alamat', 'Plus Code', 'Koordinat', 'Status', 'Petugas', 'Catatan'],
  ]
  const heights: Record<number, number> = { 1: 42, 2: 17, 3: 6, 4: 22 }
  const images: XlsxGambar[] = []

  // Logo PLN menempel di sel A1 (kolom No dilebar agar logo muat)
  images.push({
    data: logoPlnBytes(),
    col: 0,
    row: 0,
    wPx: 52,
    hPx: 52,
    rowOffEmu: Math.round(4 * 9525),
    colOffEmu: Math.round(6 * 9525),
  })

  // Gaya per kolom data: tengah utk No/Tanggal/ID/Status, sisanya wrap kiri
  const COL_C = XS.CENTER
  const COL_L = XS.DATA
  const colStyles = [COL_C, COL_C, COL_L, COL_L, COL_C, COL_L, COL_C, COL_C, COL_C, COL_L, COL_L]

  const fotoCol = 3 // kolom "Foto" (0-based) — persis setelah Nama Pelanggan
  const styleCellsExport: Record<string, number> = {} // sel Status berwarna per baris
  data.forEach((d, i) => {
    const r = d.r
    const lat = r.latitude
    const lng = r.longitude
    const status = String(r.status ?? '').toUpperCase() === 'SELESAI' ? 'SELESAI' : 'TERTUNDA'
    const zebra = i % 2 === 1
    rows.push([
      i + 1,
      tanggalWib(String(r.created_at ?? '')),
      (r.nama_pelanggan as string) ?? '',
      '', // fotonya tampil sendiri di kolom ini — teks "n foto" hanya jadi sampah di balik gambar
      (r.id_pelanggan as string) ?? '',
      (r.alamat as string) ?? '',
      (r.plus_code as string) ?? '',
      lat != null && lng != null ? `${Number(lat).toFixed(6)}, ${Number(lng).toFixed(6)}` : '',
      status,
      (r.petugas as string) ?? '',
      (r.catatan as string) ?? '',
    ])
    const excelRow = PRE + i + 1 // 1-based baris Excel baris data ini

    // Status berwarna (hijau SELESAI / merah TERTUNDA) + zebra biru muda
    const stKey = `${excelRow - 1}:8`
    styleCellsExport[stKey] = zebra
      ? status === 'SELESAI'
        ? XS.Z_SELESAI
        : XS.Z_TERTUNDA
      : status === 'SELESAI'
        ? XS.SELESAI
        : XS.TERTUNDA

    if (d.fotos.length) {
      // Grid rapi dalam sel: 2 foto sejajar, ukuran sama (100x56 px), selalu muat kolom.
      const gridRows = Math.ceil(d.fotos.length / 2)
      heights[excelRow] = Math.min(409, 10 + gridRows * 46)
      d.fotos.forEach((f, j) => {
        const scale = Math.min(100 / f.w, 56 / f.h)
        images.push({
          data: f.bytes,
          col: fotoCol,
          row: excelRow - 1,
          wPx: Math.max(8, Math.round(f.w * scale)),
          hPx: Math.max(8, Math.round(f.h * scale)),
          rowOffEmu: Math.round((6 + Math.floor(j / 2) * 46) * 12700), // 1 pt = 12.700 EMU
          colOffEmu: Math.round((4 + (j % 2) * 106) * 9525), // 1 px = 9.525 EMU — 2 foto sejajar
        })
      })
    }
  })

  // Tabel selesai = dokumen selesai. Tidak ada baris apa pun setelah data.
  const lastCol = colRef(11) // K — kolom terakhir tabel
  const merges = [`B1:${lastCol}1`, `B2:${lastCol}2`]
  const styleCells: Record<string, number> = {
    '0:1': 2, // judul besar (B1, di samping logo)
    '1:1': 3, // periode (B2, italic abu)
    ...styleCellsExport,
  }

  const bytes = buildXlsx(
    rows,
    // Kolom A dilebar (≈89px) agar logo 52px muat UTUH tanpa menimpa judul di B1
    [12, 11.5, 20, 30, 13.5, 34, 14.5, 19, 11.5, 13, 30],
    heights,
    images,
    merges,
    PRE - 1, // baris header tabel (0-based) — bold + freeze di bawahnya
    styleCells,
    colStyles,
  )
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

  const data = await laporanBulan(c.env, month)
  const bytes = buildMonthPdf(monthLabel(month), data)
  return new Response(bytes as unknown as BodyInit, {
    headers: {
      'content-type': 'application/pdf',
      'content-disposition': `attachment; filename="Laporan-PLN-${month}.pdf"`,
      'cache-control': 'no-store',
    },
  })
})

// ============================================================
// KONDISI SERVER + PEMAKAIAN (v1.10.0-m10)
// Admin melihat: kualitas server TERUKUR (persen kelancaran &
// ms rata-rata dari pemeriksaan otomatis — bukan pengaturan
// manual), pemakaian hari ini vs batas paket gratis dari
// ANALITIK Cloudflare (angka nyata), riwayat 7 hari, dan isi
// gudang. ALAMAT/URL server tidak pernah dikirim/tampil.
// ============================================================
const LIMITS = {
  requests_per_day: 100_000, // Workers free
  d1_rows_read_per_day: 5_000_000, // D1 free
  d1_rows_written_per_day: 100_000, // D1 free
  r2_storage_bytes: 10 * 1024 * 1024 * 1024, // R2 free: 10 GB penyimpanan per bulan
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

/**
 * Ukuran penyimpanan R2 NYATA — dihitung dari daftar objek di bucket
 * (bukan perkiraan). Kuota gratis R2 = 10 GB per bulan; angka ini yang
 * membandingkan pemakaian dengan batas tersebut di kartu Server.
 */
async function r2Storage(env: Bindings): Promise<{ objects: number; bytes: number } | null> {
  try {
    let cursor: string | undefined
    let objects = 0
    let bytes = 0
    for (let i = 0; i < 40; i++) {
      const page = await env.PHOTOS.list({ limit: 1000, cursor })
      for (const o of page.objects) {
        objects++
        bytes += o.size
      }
      if (page.truncated && page.cursor) {
        cursor = page.cursor
        continue
      }
      break
    }
    return { objects, bytes }
  } catch {
    return null
  }
}

app.get('/api/server', async (c) => {
  const db = c.env.DB
  const [health, usage, photoAgg, dbBytes, r2] = await Promise.all([
    healthSummary(db),
    getUsageSnapshot(db, c.env),
    safeFirst(
      db.prepare('SELECT COUNT(*) AS n, COALESCE(SUM(ukuran_kb), 0) AS kb FROM photos').all<{ n: number; kb: number }>()
    ),
    dbFileSize(db),
    r2Storage(c.env),
  ])

  // Jaga pemeriksaan kesehatan tetap mengalir walau cron terlewat
  maybeSelfCheck(c.env, (p) => c.executionCtx.waitUntil(p))

  const [reports, messages, petugas] = await Promise.all([
    countSafe(db, 'reports'),
    countSafe(db, 'chat_messages'),
    countSafe(db, 'petugas'),
  ])

  const todayDay = usageDay()
  const today =
    usage.days.find((d) => d.day === todayDay) ??
    { day: todayDay, requests: 0, rows_read: 0, rows_written: 0 }

  return c.json({
    ok: true,
    version: VERSION,
    time: nowIso(),
    status: health.status,
    health,
    usage_source: usage.source,
    limits: LIMITS,
    today,
    last_days: [...usage.days].slice(-7).reverse(), // terbaru dulu
    storage: {
      photos: photoAgg?.n ?? 0,
      photos_kb: photoAgg?.kb ?? 0,
      r2_objects: r2?.objects ?? photoAgg?.n ?? 0,
      r2_bytes: r2?.bytes ?? (photoAgg?.kb ?? 0) * 1024,
      db_bytes: dbBytes,
      reports,
      messages,
      petugas,
    },
  })
})

// ============================================================
// LAPORAN LATENSI DASBOR (v1.10.0-m10) — dari browser admin
// Dasbor mengukur waktu tanggap nyata (termasuk jaringan ponsel)
// lalu melaporkannya ke sini. Dibatasi: cukup 1 catatan per 60
// detik agar tulisan D1 tetap hemat.
// ============================================================
app.post('/api/server/ping', async (c) => {
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  const ms = Math.round(Number(body?.ms ?? 0))
  if (!Number.isFinite(ms) || ms <= 0 || ms > 60_000)
    return c.json({ ok: false, error: 'ms_tidak_valid' }, 400)

  const db = c.env.DB
  const last = await safeFirst(
    db
      .prepare("SELECT ts FROM server_health WHERE sumber = 'web' ORDER BY ts DESC LIMIT 1")
      .all<{ ts: string }>()
  )
  if (last?.ts && Date.now() - Date.parse(last.ts) < 60_000) {
    return c.json({ ok: true, throttled: true })
  }
  await db
    .prepare("INSERT INTO server_health (ts, ms, ok, sumber) VALUES (?, ?, 1, 'web')")
    .bind(nowIso(), ms)
    .run()
  return c.json({ ok: true })
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
// CRON — TIAP 5 MENIT + HARIAN (00:10 WIB)
// Tiap 5 menit : pemeriksaan kesehatan server (ms + berhasil),
//                pembersih obrolan lebih tua dari 30 hari, dan
//                pembersih riwayat kesehatan > 3 hari.
// Harian       : retensi laporan (bulan berjalan + 2 sebelumnya)
//                dan riwayat pemakaian > 60 hari.
// ============================================================
function cutoffIso(): string {
  const d = new Date()
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() - 2, 1)).toISOString()
}

export default {
  fetch: app.fetch,
  async scheduled(_evt: ScheduledController, env: Bindings, _ctx: ExecutionContext) {
    // --- pemeriksaan kesehatan (lewat jaringan publik, hasil nyata) ---
    await recordSelfCheck(env).catch(() => {})

    // --- obrolan: hapus otomatis yang lebih tua dari 30 hari ---
    const chatCutoff = new Date(Date.now() - 30 * 86_400_000).toISOString()
    await env.DB
      .prepare('DELETE FROM chat_messages WHERE created_at < ?')
      .bind(chatCutoff)
      .run()
      .catch(() => {})

    // --- riwayat kesehatan: simpan 3 hari terakhir saja ---
    const healthCutoff = new Date(Date.now() - 3 * 86_400_000).toISOString()
    await env.DB
      .prepare('DELETE FROM server_health WHERE ts < ?')
      .bind(healthCutoff)
      .run()
      .catch(() => {})

    if (_evt.cron === '*/5 * * * *') return // sisa pekerjaan hanya untuk cron harian

    // --- retensi laporan: bulan berjalan + 2 sebelumnya ---
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
