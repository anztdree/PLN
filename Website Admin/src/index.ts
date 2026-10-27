import { Hono } from 'hono'

type Bindings = {
  DB: D1Database
  PHOTOS: R2Bucket
}

const app = new Hono<{ Bindings: Bindings }>()

// ============ HEALTH CHECK ============
app.get('/api/health', (c) =>
  c.json({
    ok: true,
    service: 'PLN Admin API',
    version: '1.0.0-m1',
    time: new Date().toISOString(),
  })
)

// ============ DAFTAR LAPORAN (pondasi M1) ============
app.get('/api/reports', async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT id, status, nama_pelanggan, alamat, created_at
     FROM reports
     ORDER BY created_at DESC
     LIMIT 100`
  ).all()
  return c.json({ ok: true, count: results.length, data: results })
})

export default {
  fetch: app.fetch,
  // Cron retensi — logika penuh dikerjakan di M5
  async scheduled(_evt: ScheduledController, _env: Bindings, ctx: ExecutionContext) {
    ctx.waitUntil(Promise.resolve())
  },
} satisfies ExportedHandler<Bindings>
