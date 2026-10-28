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
const VERSION = '1.16.0-m17'

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
// OBROLAN — HAPUS SELURUH RIWAYAT (admin web, v1.15.0-m15)
// Menghapus SEMUA pesan satu percakapan sekaligus:
//   ?scope=global            -> seluruh Diskusi Petugas (grup)
//   ?scope=private&a=X&b=Y   -> seluruh DM pasangan tersebut
// Catatan pembaca (chat_reads) percakapan ikut dibersihkan agar
// lencana belum-dibaca & "dilihat oleh" mulai dari nol. Polling
// APK aman: pesan hanya dihapus, urutan id tidak bergeser.
// Tulis otomatis wajib X-API-Key lewat middleware (kunci admin).
// ============================================================
app.delete('/api/chat/history', async (c) => {
  const q = c.req.query('scope')
  const scope = q === 'global' ? 'global' : q === 'private' ? 'private' : null
  if (!scope) return c.json({ ok: false, error: 'scope_harus_global_atau_private' }, 400)

  const db = c.env.DB
  let deleted = 0
  let convKey = 'global'

  if (scope === 'global') {
    const res = await db.prepare("DELETE FROM chat_messages WHERE scope = 'global'").run()
    deleted = res.meta.changes ?? 0
  } else {
    const a = (c.req.query('a') ?? '').trim()
    const b = (c.req.query('b') ?? '').trim()
    if (!a || !b || a === b)
      return c.json({ ok: false, error: 'parameter_a_dan_b_dibutuhkan' }, 400)

    const pair = '((sender_id = ? AND target_id = ?) OR (sender_id = ? AND target_id = ?))'
    const res = await db
      .prepare(`DELETE FROM chat_messages WHERE scope = 'private' AND ${pair}`)
      .bind(a, b, b, a)
      .run()
    deleted = res.meta.changes ?? 0

    // Kunci percakapan sama dengan GET /api/chat/messages
    convKey = [a, b]
      .sort((x, y) => {
        const nx = Number(x)
        const ny = Number(y)
        if (Number.isInteger(nx) && Number.isInteger(ny)) return nx - ny
        return x < y ? -1 : x > y ? 1 : 0
      })
      .join('|')
  }

  await db.prepare('DELETE FROM chat_reads WHERE conv_key = ?').bind(convKey).run()

  return c.json({ ok: true, scope, deleted })
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

  // v1.15.1: global TIDAK PERNAH null — setelah riwayat grup dihapus,
  // baris "Diskusi Petugas" di daftar obrolan APK Admin harus tetap ada
  // (dulu hilang karena objek global hanya dikirim saat masih ada pesan).
  return c.json({
    ok: true,
    global:
      gLastRes ?? {
        id: 0,
        sender_type: '',
        sender_id: '',
        sender_nama: '',
        body: '',
        created_at: '',
      },
    dm,
    unread,
    unread_total: unreadTotal,
    petugas: petugasList,
    server_time: nowIso(),
  })
})

// ============================================================
// LAPORAN — HAPUS SATU LAPORAN (admin web, v1.15.1)
// Menghapus laporan terpilih BESERTA seluruh fotonya di R2.
// Tulis otomatis wajib X-API-Key lewat middleware (kunci admin).
// ============================================================
app.delete('/api/reports/:id', async (c) => {
  const id = (c.req.param('id') ?? '').trim()
  if (!id) return c.json({ ok: false, error: 'id_tidak_valid' }, 400)

  const db = c.env.DB
  const rpt = await db
    .prepare('SELECT id FROM reports WHERE id = ?')
    .bind(id)
    .first<{ id: string }>()
  if (!rpt) return c.json({ ok: false, error: 'laporan_tidak_ditemukan' }, 404)

  const ph = await db
    .prepare('SELECT id, r2_key FROM photos WHERE report_id = ?')
    .bind(id)
    .all<{ id: string; r2_key: string }>()

  let fotos = 0
  for (const p of ph.results ?? []) {
    if (p.r2_key) {
      await c.env.PHOTOS.delete(p.r2_key)
      fotos++
    }
  }
  if (ph.results?.length) {
    await db.prepare('DELETE FROM photos WHERE report_id = ?').bind(id).run()
  }
  await db.prepare('DELETE FROM reports WHERE id = ?').bind(id).run()

  return c.json({ ok: true, deleted_id: id, photos: fotos })
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
// Logo PLN (lingkaran navy + petir kuning, LATAR TRANSPARAN) ditanam di kepala
// Excel — PNG 256x256, sudut halus anti-alias, tidak ada kotak putih.
const LOGO_PLN_PNG_B64 =
  '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'
const logoPlnBytes = (): Uint8Array => Uint8Array.from(atob(LOGO_PLN_PNG_B64), (ch) => ch.charCodeAt(0))

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
  ext: 'png' | 'jpeg'
  fromCol: number // 0-based
  fromRow: number // 0-based
  fromColOffPx: number // px dari tepi kiri sel asal
  fromRowOffPx: number // px dari tepi atas sel asal
  toCol: number // 0-based — sel tujuan
  toRow: number
  toColOffPx: number
  toRowOffPx: number
}

/**
 * Gaya sel Excel (v1.15.0-m15) — indeks cellXfs:
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
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheetPr><pageSetUpPr fitToPage="1"/></sheetPr><sheetViews><sheetView workbookViewId="0"><pane ySplit="${headerRow}" topLeftCell="A${headerRow + 1}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>${cols}</cols><sheetData>${body}</sheetData>${mergeXml}<pageMargins left="0.3" right="0.3" top="0.4" bottom="0.4" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/>${images.length ? '<drawing r:id="rId1"/>' : ''}</worksheet>`

  const files: { name: string; data: Uint8Array }[] = [
    {
      name: '[Content_Types].xml',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${images.length ? '<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>' : ''}</Types>`),
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
    const hasPng = images.some((g) => g.ext === 'png')
    const hasJpeg = images.some((g) => g.ext === 'jpeg')

    // rels lembar kerja -> drawing
    files.push({
      name: 'xl/worksheets/_rels/sheet1.xml.rels',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/></Relationships>`),
    })

    // rels drawing -> berkas gambar
    const drawingRels = images
      .map(
        (g, i) =>
          `<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${i + 1}.${g.ext}"/>`
      )
      .join('')
    files.push({
      name: 'xl/drawings/_rels/drawing1.xml.rels',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${drawingRels}</Relationships>`),
    })

    // drawing: twoCellAnchor per gambar (v1.15.0-m15).
    // Posisi & ukuran dikunci ke GEOMETRI SEL (dari -> ke), bukan
    // <xdr:ext> — beberapa penampil Excel di ponsel salah menghitung
    // oneCellAnchor+ext sehingga logo/foto meluber menimpa kolom lain.
    const EMU_PX = 9525 // 1 px = 9525 EMU
    const anchors = images
      .map((g, i) => {
        const from = `<xdr:col>${g.fromCol}</xdr:col><xdr:colOff>${Math.round(g.fromColOffPx * EMU_PX)}</xdr:colOff><xdr:row>${g.fromRow}</xdr:row><xdr:rowOff>${Math.round(g.fromRowOffPx * EMU_PX)}</xdr:rowOff>`
        const to = `<xdr:col>${g.toCol}</xdr:col><xdr:colOff>${Math.round(g.toColOffPx * EMU_PX)}</xdr:colOff><xdr:row>${g.toRow}</xdr:row><xdr:rowOff>${Math.round(g.toRowOffPx * EMU_PX)}</xdr:rowOff>`
        return `<xdr:twoCellAnchor editAs="oneCell"><xdr:from>${from}</xdr:from><xdr:to>${to}</xdr:to><xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${i + 1}" name="Gambar ${i + 1}"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr><xdr:blipFill><a:blip r:embed="rId${i + 1}"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>`
      })
      .join('')
    files.push({
      name: 'xl/drawings/drawing1.xml',
      data: te.encode(`<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">${anchors}</xdr:wsDr>`),
    })

    images.forEach((g, i) => files.push({ name: `xl/media/image${i + 1}.${g.ext}`, data: g.data }))

    // Tambahkan tipe konten png/jpeg yang terpakai
    const ctStr = new TextDecoder().decode(files[0].data).replace(
      '<Default Extension="rels"',
      `${hasPng ? '<Default Extension="png" ContentType="image/png"/>' : ''}${hasJpeg ? '<Default Extension="jpeg" ContentType="image/jpeg"/>' : ''}<Default Extension="rels"`
    )
    files[0].data = te.encode(ctStr)
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
  // v1.15.0-m15: gambar twoCellAnchor (terkunci ke geometri sel — di ponsel
  // logo/foto tidak lagi meluber menimpa judul/kolom lain) + logo PNG transparan.
  const PRE = 4 // baris sebelum data: judul, periode, kosong, header tabel
  const rows: (string | number | null)[][] = [
    [null, 'LAPORAN PEMASANGAN LAPANGAN PLN'],
    [null, `Periode: ${label}`],
    [null],
    ['No', 'Tanggal', 'Nama Pelanggan', 'Foto', 'ID Pelanggan', 'Alamat', 'Plus Code', 'Koordinat', 'Status', 'Petugas', 'Catatan'],
  ]
  const heights: Record<number, number> = { 1: 42, 2: 17, 3: 6, 4: 22 }
  const images: XlsxGambar[] = []

  // Logo PLN di dalam sel A1 saja — baris 1 setinggi 42pt (≈56px),
  // kolom A selebar 12 (≈89px); logo 48px terpusat, MUSTAHIL menimpa judul di B1.
  const LOGO_PX = 48
  images.push({
    data: logoPlnBytes(),
    ext: 'png',
    fromCol: 0,
    fromRow: 0,
    fromColOffPx: Math.round((89 - LOGO_PX) / 2), // ≈20px — terpusat di kolom A
    fromRowOffPx: Math.round((56 - LOGO_PX) / 2), // ≈4px — terpusat di baris 1
    toCol: 0,
    toRow: 0,
    toColOffPx: Math.round((89 - LOGO_PX) / 2) + LOGO_PX,
    toRowOffPx: Math.round((56 - LOGO_PX) / 2) + LOGO_PX,
  })

  // Gaya per kolom data: tengah utk No/Tanggal/ID/Status, sisanya wrap kiri
  const COL_C = XS.CENTER
  const COL_L = XS.DATA
  const colStyles = [COL_C, COL_C, COL_L, COL_L, COL_C, COL_L, COL_C, COL_C, COL_C, COL_L, COL_L]

  const fotoCol = 3 // kolom "Foto" (0-based) — persis setelah Nama Pelanggan
  const FOTO_W_MAX = 100 // px per foto
  const FOTO_H_MAX = 56 // px per foto
  const FOTO_PITCH_X = 106 // px jarak antar foto sejajar
  const FOTO_PITCH_Y = 61 // px = 46pt jarak antar baris grid foto
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
        const scale = Math.min(FOTO_W_MAX / f.w, FOTO_H_MAX / f.h)
        const wPx = Math.max(8, Math.round(f.w * scale))
        const hPx = Math.max(8, Math.round(f.h * scale))
        const gCol = j % 2
        const gRow = Math.floor(j / 2)
        const x0 = 4 + gCol * FOTO_PITCH_X
        const y0 = 8 + gRow * FOTO_PITCH_Y
        images.push({
          data: f.bytes,
          ext: 'jpeg',
          fromCol: fotoCol,
          fromRow: excelRow - 1,
          fromColOffPx: x0,
          fromRowOffPx: y0,
          toCol: fotoCol, // tetap dalam kolom Foto — tidak meluber ke kolom lain
          toRow: excelRow - 1, // tetap dalam barisnya — tidak meluber ke baris lain
          toColOffPx: x0 + wPx,
          toRowOffPx: y0 + hPx,
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
// v1.16.0-m17: boleh membawa CATATAN PUSAT (opsional) — ditulis admin
// sebelum konfirmasi; tersimpan di reports.catatan_pusat (+ waktu) dan
// sampai ke APK Petugas lewat GET /api/reports/catatan-pusat.
// ============================================================
app.patch('/api/reports/:id/status', async (c) => {
  const id = c.req.param('id')
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  const status = String(body?.status ?? '').toUpperCase()
  if (status !== 'TERTUNDA' && status !== 'SELESAI')
    return c.json({ ok: false, error: 'status_harus_tertunda_atau_selesai' }, 400)

  const db = c.env.DB
  // Catatan pusat opsional: bila kunci "catatan" dikirim, selalu
  // diperbarui (string kosong = hapus catatan). Tidak dikirim = biarkan.
  const catatanAda = body != null && typeof body.catatan === 'string'
  const catatan = catatanAda ? String(body.catatan).trim().slice(0, 1000) : ''
  const stamp = nowIso()

  const res = catatanAda
    ? await db
        .prepare(
          'UPDATE reports SET status = ?, catatan_pusat = ?, catatan_pusat_at = ? WHERE id = ?',
        )
        .bind(status, catatan === '' ? null : catatan, catatan === '' ? null : stamp, id)
        .run()
    : await db.prepare('UPDATE reports SET status = ? WHERE id = ?').bind(status, id).run()
  if (!res.meta.changes) return c.json({ ok: false, error: 'laporan_tidak_ditemukan' }, 404)

  return c.json({ ok: true, report: { id, status } })
})

// ============================================================
// LAPORAN — TULIS CATATAN PUSAT SAJA (v1.16.0-m17)
// Admin menulis/memperbarui catatan untuk satu laporan TANPA mengubah
// statusnya (kejelasan pengerjaan: instruksi/keterangan pusat untuk
// petugas yang datanya sudah terkirim).
// ============================================================
app.patch('/api/reports/:id/catatan', async (c) => {
  const id = c.req.param('id')
  const body = await c.req.json<Record<string, unknown>>().catch(() => null)
  const catatan = String(body?.catatan ?? '').trim().slice(0, 1000)
  if (!catatan)
    return c.json({ ok: false, error: 'catatan_tidak_boleh_kosong' }, 400)

  const db = c.env.DB
  const ada = await db
    .prepare('SELECT id FROM reports WHERE id = ?')
    .bind(id)
    .first<{ id: string }>()
  if (!ada) return c.json({ ok: false, error: 'laporan_tidak_ditemukan' }, 404)

  await db
    .prepare('UPDATE reports SET catatan_pusat = ?, catatan_pusat_at = ? WHERE id = ?')
    .bind(catatan, nowIso(), id)
    .run()
  return c.json({ ok: true })
})

// ============================================================
// LAPORAN — CATATAN PUSAT UNTUK PETUGAS (v1.16.0-m17)
// Dipakai APK Petugas (polling): daftar laporan milik petugas yang
// punya catatan dari pusat. Identitas dari header perangkat — pola yang
// sama dengan /api/chat/summary (X-Device-Id / X-Android-Id).
// Kecocokan baris laporan lewat kolom `petugas` (nama yang terkirim
// saat unggah) — satu-satunya tautan petugas<->laporan di skema ini.
// ============================================================
app.get('/api/reports/catatan-pusat', async (c) => {
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

  const rows = await db
    .prepare(
      `SELECT id, client_ref, nama_pelanggan, id_pelanggan, status, catatan_pusat, catatan_pusat_at, created_at
       FROM reports
       WHERE petugas = ? AND catatan_pusat IS NOT NULL AND catatan_pusat != ''
       ORDER BY catatan_pusat_at DESC LIMIT 50`,
    )
    .bind(me.nama)
    .all<Record<string, string | null>>()

  return c.json({ ok: true, me: me.nama, catatan: rows.results ?? [] })
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
