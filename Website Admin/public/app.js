/* ============================================================
   PLN ADMIN — app.js (vanilla, tanpa framework)
   Tema v1.8 "Navy Teknis" — 3 tab: Dasbor / Petugas / Laporan

   CATATAN PENTING (keputusan pemilik): TIDAK ADA fungsi chat di
   web admin. Obrolan admin HANYA di Aplikasi Admin native (APK)
   lewat FAB native -> layar obrolan native. Web tidak memuat
   UI chat, tidak memanggil /api/chat/*, dan tidak menampilkan
   lencana pesan.

   Kecepatan:
   - 1 permintaan gabungan /api/dashboard (sebelumnya 3)
   - hidrasi instan dari localStorage sebelum jaringan
   - render hanya saat data benar-benar berubah (signature guard)
   - tanpa font eksternal (system stack) — first paint cepat
   Polling: dasbor 10 dtk, status server 30 dtk.
   ============================================================ */
'use strict';

const $ = (id) => document.getElementById(id);

/* Kunci anti-spam yang sama dengan APK (bukan rahasia, dideploy terbuka sesuai desain) */
const API_KEY = 'PLN-KLTEN-2026-KABEL-9f4a2c';

/* ============================================================
   UTIL
   ============================================================ */
function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function relTime(iso) {
  if (!iso) return '—';
  const d = Date.parse(iso);
  if (Number.isNaN(d)) return '—';
  const s = Math.max(0, (Date.now() - d) / 1000);
  if (s < 60) return 'baru saja';
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} menit lalu`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h} jam lalu`;
  const dd = Math.floor(h / 24);
  if (dd < 7) return `${dd} hari lalu`;
  return new Date(d).toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' });
}

function fmtClock(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' });
}

function initials(nama) {
  const parts = String(nama ?? '?').trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return '?';
  if (parts.length === 1) return parts[0].charAt(0).toUpperCase();
  return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
}

/* Palet avatar selaras tema navy/kuning */
const AVATAR_GRADIENTS = [
  'linear-gradient(135deg,#12365e 0%,#1f5487 60%,#3d7ab5 100%)',
  'linear-gradient(135deg,#7a5c14 0%,#b08a2e 55%,#e0bd62 100%)',
  'linear-gradient(135deg,#14532d 0%,#1e7a44 55%,#4fae76 100%)',
  'linear-gradient(135deg,#5b2333 0%,#8f3a50 55%,#c06880 100%)',
  'linear-gradient(135deg,#262d3a 0%,#45506388 55%,#7c8aa0 100%)',
  'linear-gradient(135deg,#233247 0%,#3a5678 55%,#6d89ad 100%)',
];

/* Mode aplikasi Android (WebView PLN Admin) — unduhan lewat tautan langsung
   agar diterima DownloadManager sistem (blob: tidak didukung WebView). */
const IS_APP = /PLNAdminApp/i.test(navigator.userAgent);

function avatarGradient(nama) {
  const s = String(nama ?? '?');
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return AVATAR_GRADIENTS[h % AVATAR_GRADIENTS.length];
}

function salamWaktu(d = new Date()) {
  const h = d.getHours();
  if (h >= 4 && h < 11) return 'pagi';
  if (h < 15) return 'siang';
  if (h < 19) return 'sore';
  return 'malam';
}

function tanggalPanjang(d = new Date()) {
  return d.toLocaleDateString('id-ID', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
}

function currentMonth() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

/* 2 jenis laporan saja: TERTUNDA & SELESAI (nilai lama otomatis dianggap TERTUNDA) */
function normStatus(s) {
  return String(s ?? '').toUpperCase() === 'SELESAI' ? 'SELESAI' : 'TERTUNDA';
}

function fmtInt(n) {
  return new Intl.NumberFormat('id-ID').format(Math.round(Number(n) || 0));
}

function fmtPct(n) {
  return (Number(n) || 0).toLocaleString('id-ID', { minimumFractionDigits: 0, maximumFractionDigits: 1 });
}

function fmtKb(kb) {
  kb = Number(kb) || 0;
  if (kb >= 1024 * 1024) return `${(kb / 1024 / 1024).toFixed(2)} GB`;
  if (kb >= 1024) return `${(kb / 1024).toFixed(1)} MB`;
  return `${fmtInt(kb)} KB`;
}

function fmtBytes(b) {
  b = Number(b) || 0;
  if (b >= 1024 ** 3) return `${(b / 1024 ** 3).toFixed(2)} GB`;
  if (b >= 1024 ** 2) return `${(b / 1024 ** 2).toFixed(1)} MB`;
  if (b >= 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${fmtInt(b)} B`;
}

function avatarHtml(nama, cls, online) {
  const dot = online === undefined
    ? ''
    : `<span class="st-dot ${online ? 'on' : ''}"></span>`;
  return `<span class="avatar ${cls || ''}" style="background:${avatarGradient(nama)}">${esc(initials(nama))}${dot}</span>`;
}

const SVG = {
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>',
  pinMap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/></svg>',
  camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
  pen: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/></svg>',
  undo: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/></svg>',
  hat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 18a1 1 0 0 0 1 1h18a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1H3a1 1 0 0 0-1 1z"/><path d="M4 15v-3a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v3"/><path d="M19 15V9a2 2 0 0 0-2-2"/></svg>',
  users: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
  file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v5h6"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/></svg>',
  trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M10 11v6"/><path d="M14 11v6"/></svg>',
};

let toastTimer = null;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
}

/* Dialog konfirmasi buatan sendiri (v1.14.0-m14) — pengganti confirm()
   bawaan browser yang TIDAK JALAN di WebView Aplikasi Admin (tanpa
   WebChromeClient dulu) sehingga tombol hapus petugas/pesan diam saja.
   Selalu aman di semua peramban & WebView. */
function uiConfirm(msg, opts = {}) {
  return new Promise((resolve) => {
    const box = $('confirm');
    const ic = $('cf-ic');
    $('confirm-title').textContent = opts.title || 'Konfirmasi';
    $('confirm-msg').textContent = msg;
    const yes = $('cf-yes');
    yes.textContent = opts.okText || 'Ya, lanjut';
    yes.classList.toggle('safe', !!opts.safe);
    ic.classList.toggle('safe', !!opts.safe);
    box.hidden = false;
    const done = (v) => {
      box.hidden = true;
      document.removeEventListener('keydown', onKey);
      resolve(v);
    };
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    document.addEventListener('keydown', onKey);
    $('cf-no').onclick = () => done(false);
    yes.onclick = () => done(true);
    box.onclick = (e) => { if (e.target === box) done(false); };
    yes.focus();
  });
}

/* Dialog STATUS + CATATAN PUSAT (v1.16.0-m17) — kejelasan sebelum
   konfirmasi: admin menulis catatan untuk laporan terpilih, status baru
   baru saja diterapkan setelah tombol Konfirmasi. Catatan (opsional)
   ikut tersimpan dan sampai ke APK Petugas (badge + notifikasi).
   opts: { title, msg, to, note } — to = 'SELESAI'|'TERTUNDA'|null
   (null = catatan saja, status tetap). Return: { ok, catatan } */
function uiCatatan(opts) {
  return new Promise((resolve) => {
    const box = $('cm');
    const note = $('cm-note');
    $('cm-title').textContent = opts.title || 'Ubah Status Laporan';
    $('cm-msg').textContent = opts.msg || '';
    note.value = opts.note || '';
    const yes = $('cm-yes');
    yes.textContent = opts.to ? 'Konfirmasi' : 'Simpan Catatan';
    box.hidden = false;
    const done = (v) => {
      box.hidden = true;
      document.removeEventListener('keydown', onKey);
      resolve(v);
    };
    const onKey = (e) => { if (e.key === 'Escape') done({ ok: false }); };
    document.addEventListener('keydown', onKey);
    $('cm-no').onclick = () => done({ ok: false });
    yes.onclick = () => done({ ok: true, catatan: note.value.trim() });
    box.onclick = (e) => { if (e.target === box) done({ ok: false }); };
    note.focus();
  });
}

/** Simpan catatan pusat tanpa mengubah status (tombol pensil di kartu). */
async function simpanCatatanSaja(id, catatan) {
  const d = await api(`/api/reports/${encodeURIComponent(id)}/catatan`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ catatan }),
  });
  if (!d.ok) throw new Error(d.error || 'gagal');
  const r = (state.db?.reports ?? []).find((x) => String(x.id) === String(id));
  if (r) {
    // Kosong = catatan dihapus (kolom kembali null, tombol "Catatan
    // pusat" muncul lagi di kartu).
    r.catatan_pusat = catatan === '' ? null : catatan;
    r.catatan_pusat_at = catatan === '' ? null : new Date().toISOString();
  }
  state.sig.laporan = '';
  if (state.tab === 'laporan') renderLaporan(true);
}

async function api(path, opts = {}) {
  // X-API-Key pada GET chat -> bacaan admin tercatat utk status "dilihat oleh"
  // Header selalu digabung agar X-API-Key tak hilang saat pemanggil
  // mengirim headers tambahan (Content-Type, dll).
  const res = await fetch(path, {
    cache: 'no-store',
    ...opts,
    headers: { 'X-API-Key': API_KEY, ...(opts.headers ?? {}) },
  });
  if (!res.ok && res.status !== 409 && res.status !== 404) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

/* ============================================================
   STATE
   ============================================================ */
const state = {
  tab: 'dasbor',
  db: null,          // { version, statistik, petugas, reports } dari /api/dashboard
  fromCache: false,
  qPetugas: '',
  qLaporan: '',
  filter: 'SEMUA',
  lastSync: null,
  tick: 0,
  srv: null,        // respons /api/server (status + pemakaian free tier)
  sig: { petugas: '', laporan: '', stats: '', srv: '', aktivitas: '', grafik: '' },
};

let errShownAt = 0;
function errToast() {
  if (Date.now() - errShownAt > 30000) {
    errShownAt = Date.now();
    toast('Koneksi bermasalah, mencoba lagi…');
  }
}

/* ============================================================
   TAB & NAVIGASI (instan, tanpa muat ulang)
   Delegasi klik: [data-tab] = tab navigasi (bawah/atas),
   [data-goto] = lompat ke tab (tile statistik, "Lihat semua",
   baris aktivitas terbaru).
   ============================================================ */
document.addEventListener('click', (e) => {
  const goto = e.target.closest('[data-goto]');
  if (goto) { switchTab(goto.dataset.goto); return; }
  const tab = e.target.closest('[data-tab]');
  if (tab) switchTab(tab.dataset.tab);
});

function switchTab(tab) {
  state.tab = tab;
  document.querySelectorAll('[data-tab]').forEach((b) =>
    b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
  $(`view-${tab}`).classList.add('active');
  if (tab === 'petugas') renderPetugas(true);
  if (tab === 'laporan') { renderLaporan(true); renderExportHint(); }
  if (tab === 'dasbor') renderExportHint();
}

/* ============================================================
   HERO — sambutan, tanggal & JAM HIDUP
   ============================================================ */
function renderHero() {
  $('salam').textContent = salamWaktu();
  $('hero-date').textContent = tanggalPanjang();
}
renderHero();
setInterval(renderHero, 30000);

/* Zona waktu jujur: WIB/WITA/WIT sesuai perangkat, bukan anggapan */
function zonaWaktuLabel() {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    if (tz === 'Asia/Jakarta') return 'WIB';
    if (tz === 'Asia/Makassar') return 'WITA';
    if (tz === 'Asia/Jayapura') return 'WIT';
    const off = -new Date().getTimezoneOffset() / 60;
    const rounded = Math.round(off * 100) / 100;
    return `GMT${rounded >= 0 ? '+' : ''}${rounded}`;
  } catch { return 'WIB'; }
}

function renderClock() {
  const el = $('hero-clock-time');
  if (!el) return;
  el.textContent = new Date().toLocaleTimeString('id-ID', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  });
}
renderClock();
setInterval(renderClock, 1000);
(function () {
  const z = $('hero-clock-zone');
  if (z) z.textContent = zonaWaktuLabel();
})();

/* ============================================================
   DATA UTAMA — 1 permintaan /api/dashboard
   ============================================================ */
const CACHE_KEY = 'pln-admin-cache-v2';

function hydrateCache() {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return;
    const d = JSON.parse(raw);
    if (d && d.petugas && d.reports) {
      state.db = d;
      state.fromCache = true;
      renderAll();
    }
  } catch { /* cache rusak -> abaikan */ }
}

/** Simpan salinan state ke localStorage agar muat ulang halaman tetap
 *  menampilkan data terakhir (pola yang sama dengan loadDashboard). */
function simpanDbLokal() {
  try { localStorage.setItem(CACHE_KEY, JSON.stringify(state.db)); } catch { /* penuh -> abaikan */ }
}

async function loadDashboard() {
  try {
    const d = await api('/api/dashboard');
    if (!d.ok) throw new Error('gagal');
    state.db = {
      version: d.version,
      statistik: d.statistik,
      petugas: d.petugas ?? [],
      reports: d.reports ?? [],
    };
    state.fromCache = false;
    try { localStorage.setItem(CACHE_KEY, JSON.stringify(state.db)); } catch { /* penuh -> abaikan */ }

    state.lastSync = Date.now();
    renderAll();
    renderSync();
  } catch { errToast(); }
}

function renderSync() {
  $('sync-text').textContent = state.lastSync
    ? `Tersinkron ${relTime(new Date(state.lastSync).toISOString())}`
    : 'Menyambungkan…';
}

function renderAll() {
  renderStats();
  renderServerCard();
  renderExportHint();
  renderAktivitas();
  renderGrafikMingguan();
  if (state.tab === 'petugas') renderPetugas();
  if (state.tab === 'laporan') renderLaporan();
}

/* ---------- ringkasan (signature guard mencegah render ulang sia-sia) ---------- */
function renderStats() {
  if (!state.db) return;
  const statistik = state.db.statistik ?? {};
  const reports = state.db.reports ?? [];
  const onPetugas = (state.db.petugas ?? []).filter((x) => x.online && !x.admin).length;
  const tertunda = reports.filter((r) => normStatus(r.status) === 'TERTUNDA').length;
  const selesai = reports.length - tertunda;
  const sig = `${statistik.petugas}|${statistik.reports}|${onPetugas}|${tertunda}`;
  if (sig === state.sig.stats) return;
  state.sig.stats = sig;

  $('st-petugas').textContent = statistik.petugas ?? '–';
  $('st-reports').textContent = statistik.reports ?? '–';
  $('st-reports-sub').textContent = reports.length
    ? `${tertunda} tertunda · ${selesai} selesai`
    : '\u00a0';
  $('st-aktif').textContent = onPetugas;
}

function renderServerCard() {
  if (!state.db?.version) return;
  $('srv-ver').textContent = state.db.version;
}

/* ============================================================
   AKTIVITAS TERBARU — 3 laporan termutakhir di dasbor
   ============================================================ */
function renderAktivitas() {
  const el = $('act-list');
  if (!el || !state.db) return;
  const reports = state.db.reports ?? [];
  const sig = reports.slice(0, 3).map((r) => `${String(r.id).slice(0, 8)}:${r.status}:${(r.photos ?? []).length}`).join(',');
  if (sig === state.sig.aktivitas) return;
  state.sig.aktivitas = sig;

  if (!reports.length) {
    el.innerHTML = '<p class="act-empty">Belum ada laporan masuk.</p>';
    return;
  }
  el.innerHTML = reports.slice(0, 3).map((r) => {
    const selesai = normStatus(r.status) === 'SELESAI';
    const foto = (r.photos ?? []).length;
    return `<button class="act-item" type="button" data-goto="laporan">
      <span class="act-ic">${foto ? SVG.camera : SVG.file}</span>
      <span class="act-main">
        <b>${esc(r.nama_pelanggan || 'Tanpa nama pelanggan')}</b>
        <small>${esc(r.petugas || 'Petugas')} · ${esc(r.alamat || 'Alamat tidak tercatat')}</small>
      </span>
      <span class="act-side">
        <span class="mini-badge ${selesai ? 'selesai' : 'tertunda'}">${selesai ? 'SELESAI' : 'TERTUNDA'}</span>
        <span class="act-time">${esc(relTime(r.created_at))}</span>
      </span>
    </button>`;
  }).join('');
}

/* ============================================================
   GRAFIK LAPORAN 7 HARI — batang emas (hari ini cyan)
   ============================================================ */
const HARI_SINGKAT = ['Min', 'Sen', 'Sel', 'Rab', 'Kam', 'Jum', 'Sab'];

function renderGrafikMingguan() {
  const el = $('wk-chart');
  if (!el || !state.db) return;
  const reports = state.db.reports ?? [];

  const now = new Date();
  const days = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(now);
    d.setDate(now.getDate() - i);
    d.setHours(0, 0, 0, 0);
    days.push({ date: d, n: 0 });
  }
  for (const r of reports) {
    if (!r.created_at) continue;
    const t = Date.parse(r.created_at);
    if (Number.isNaN(t)) continue;
    const d = new Date(t);
    d.setHours(0, 0, 0, 0);
    const hit = days.find((x) => x.date.getTime() === d.getTime());
    if (hit) hit.n++;
  }

  const total = days.reduce((a, b) => a + b.n, 0);
  const sig = days.map((d) => d.n).join(',');
  if (sig === state.sig.grafik) return;
  state.sig.grafik = sig;

  const max = Math.max(1, ...days.map((d) => d.n));
  el.innerHTML = days.map((d, i) => {
    const last = i === days.length - 1;
    const pct = d.n ? Math.max(8, Math.round((d.n / max) * 100)) : 3;
    return `<div class="wk-col ${last ? 'today' : (d.n ? '' : 'dim')}">
      <span class="wk-num">${d.n || ''}</span>
      <span class="wk-bar" style="height:${pct}%"></span>
      <span class="wk-day">${last ? 'Hari ini' : HARI_SINGKAT[d.date.getDay()]}</span>
    </div>`;
  }).join('');

  $('wk-total').textContent = fmtInt(total);
  const sub = $('wk-sub');
  if (sub) {
    const delta = total - days[5].n;
    sub.textContent = delta > 0 ? `+${delta} dari kemarin` : '\u00a0';
  }
}

/* Data disegarkan otomatis tiap 10 detik — tombol refresh dihapus (permintaan user) */

/* ============================================================
   PETUGAS
   ============================================================ */
$('petugas-q').addEventListener('input', (e) => {
  state.qPetugas = e.target.value;
  renderPetugas(true);
});

function renderPetugas(force) {
  const sub = $('petugas-sub');
  const el = $('petugas-list');

  if (!state.db) {
    sub.textContent = 'Memuat…';
    el.innerHTML = Array(3).fill('<div class="skeleton pg-skel"></div>').join('');
    return;
  }

  const all = state.db.petugas ?? [];
  const adm = all.find((x) => x.admin);
  const petugasOnly = all.filter((x) => !x.admin);
  const total = petugasOnly.length;
  const on = all.filter((x) => x.online).length; // termasuk admin
  const subText = `${total} petugas terdaftar · ${on} online`;

  const q = state.qPetugas.trim().toLowerCase();
  const list = q ? all.filter((x) => x.nama.toLowerCase().includes(q)) : all;

  const sig = `${subText}|${q}|${list.map((p) => `${p.id}:${p.admin ? 1 : 0}:${p.online ? 1 : 0}:${String(p.last_seen_at || '').slice(0, 16)}`).join(',')}`;
  if (!force && sig === state.sig.petugas) return;
  state.sig.petugas = sig;

  sub.textContent = subText;

  if (!list.length) {
    el.innerHTML = `<div class="empty">
      <span class="empty-ic">${SVG.users}</span>
      <p class="empty-title">${all.length ? 'Tidak ditemukan' : 'Belum ada petugas terdaftar'}</p>
      <p class="empty-sub">${all.length
        ? `Tidak ada petugas bernama “${esc(state.qPetugas.trim())}”.`
        : 'Pendaftaran lewat aplikasi.'}</p>
    </div>`;
    return;
  }

  el.innerHTML = list.map((p) => `
    <article class="pg-card">
      <div class="pg-top">
        ${avatarHtml(p.nama, p.admin ? 'adm' : '', p.online)}
        <div class="pg-main">
          <b>${esc(p.nama)}${p.admin ? ' <span class="badge admin">ADMIN</span>' : ''}</b>
          <small>${p.admin ? 'Admin · Pusat kendali' : `Petugas · ID #${p.id}`}</small>
        </div>
        <span class="status-pill ${p.online ? 'on' : 'off'}">
          <i class="dot ${p.online ? 'dot-online' : ''}"></i>${p.online ? 'ONLINE' : 'OFFLINE'}
        </span>
        ${p.admin ? '' : `<button class="pg-del" data-id="${p.id}" data-nama="${esc(p.nama)}" aria-label="Hapus petugas ${esc(p.nama)}" title="Hapus petugas">${SVG.trash}</button>`}
      </div>
      <div class="pg-foot">
        ${SVG.clock}<span>${p.admin
          ? (p.online ? 'Dasbor admin sedang terbuka' : 'Dasbor admin tidak terbuka')
          : `Aktif ${esc(relTime(p.last_seen_at))}`}</span>${p.admin ? '' : `<span class="sep">·</span><span>Terdaftar ${esc(relTime(p.created_at))}</span>`}
      </div>
    </article>`).join('');
}

/* Hapus petugas (admin) — konfirmasi dulu, lalu segarkan data */
$('petugas-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('.pg-del');
  if (!btn) return;
  const id = btn.dataset.id;
  const nama = btn.dataset.nama || 'petugas ini';
  if (!(await uiConfirm(`Hapus petugas "${nama}"?\n\nRiwayat laporan & pesan tetap tersimpan.`, { title: 'Hapus petugas', okText: 'Ya, hapus' }))) return;
  btn.disabled = true;
  btn.classList.add('busy');
  try {
    const d = await api(`/api/petugas/${id}`, { method: 'DELETE' });
    if (d.ok) {
      toast(`Petugas "${nama}" dihapus`);
      await loadDashboard();
    } else {
      toast(d.message || d.error || 'Gagal menghapus petugas');
      btn.disabled = false;
      btn.classList.remove('busy');
    }
  } catch {
    toast('Gagal menghapus — periksa koneksi lalu coba lagi');
    btn.disabled = false;
    btn.classList.remove('busy');
  }
});

/* ============================================================
   LAPORAN
   ============================================================ */
$('laporan-q').addEventListener('input', (e) => {
  state.qLaporan = e.target.value;
  renderLaporan(true);
});
$('laporan-chips').addEventListener('click', (e) => {
  const b = e.target.closest('.chip-f[data-f]');
  if (!b) return;
  state.filter = b.dataset.f;
  document.querySelectorAll('#laporan-chips .chip-f').forEach((c) =>
    c.classList.toggle('active', c === b));
  renderLaporan(true);
});

function mapsQuery(r) {
  if (r.latitude != null && r.longitude != null) return `${r.latitude},${r.longitude}`;
  if (r.plus_code) return r.plus_code;
  if (r.alamat) return r.alamat;
  return null;
}

function micId() {
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 10h2v2"/><path d="M12 18h.01"/><path d="M8 18h.01"/><path d="M2 6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z"/><path d="M6 8h2v2H6z"/><path d="M11 8h4"/><path d="M6 12h2v2H6z"/><path d="M11 12h4"/></svg>';
}
function micHome() {
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"/><path d="M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>';
}
function micCompass() {
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/></svg>';
}

function reportCard(r) {
  const st = normStatus(r.status);
  const badge = st === 'SELESAI'
    ? '<span class="badge selesai">SELESAI</span>'
    : '<span class="badge tertunda">TERTUNDA</span>';

  const n = (r.photos ?? []).length;
  const cols = n === 1 ? 'cols-1' : n === 2 ? 'cols-2' : 'cols-4';
  const photos = n
    ? `<div class="rp-photos ${cols}">${r.photos.map((ph) => {
        const url = `/api/photos/${encodeURIComponent(ph.id)}`;
        return `<button class="rp-ph" data-full="${esc(url)}" data-cap="Foto ${ph.urutan} — ${esc(r.nama_pelanggan || 'Pelanggan')}" aria-label="Perbesar foto ${ph.urutan}">
          <img src="${esc(url)}" alt="Foto ${ph.urutan} laporan ${esc(r.nama_pelanggan || '')}" loading="lazy" decoding="async">
          <span class="zoom">${SVG.camera}</span>
        </button>`;
      }).join('')}</div>`
    : '';

  const meta = [];
  if (r.id_pelanggan) meta.push(`<p><span class="mic-ic">${micId()}</span><span class="mono">${esc(r.id_pelanggan)}</span></p>`);
  if (r.alamat) meta.push(`<p><span class="mic-ic">${micHome()}</span><span>${esc(r.alamat)}</span></p>`);
  if (r.latitude != null && r.longitude != null)
    meta.push(`<p><span class="mic-ic">${SVG.pinMap}</span><span class="mono">${Number(r.latitude).toFixed(5)}, ${Number(r.longitude).toFixed(5)}</span></p>`);
  if (r.plus_code)
    meta.push(`<p><span class="mic-ic">${micCompass()}</span><span class="mono">${esc(r.plus_code)}</span></p>`);
  meta.push(`<p class="who"><span class="mic-ic">${SVG.hat}</span><b>${esc(r.petugas || '—')}</b>
    <span class="sep">·</span>${SVG.clock}<span>${esc(relTime(r.created_at))}</span>
    ${n ? `<span class="sep">·</span>${SVG.camera}<span>${n} foto</span>` : ''}</p>`);

  const q = mapsQuery(r);
  const isDone = st === 'SELESAI';
  const toggleBtn = `<button class="rp-toggle ${isDone ? 'to-pending' : 'to-done'}" data-id="${esc(String(r.id))}" data-to="${isDone ? 'TERTUNDA' : 'SELESAI'}" type="button" aria-label="${isDone ? 'Tandai kembali tertunda' : 'Tandai selesai'}">
      ${isDone ? SVG.undo : SVG.check} ${isDone ? 'Tandai Tertunda' : 'Tandai Selesai'}
    </button>`;
  const mapLink = q
    ? `<a class="map-link" target="_blank" rel="noopener"
        href="https://maps.google.com/?q=${encodeURIComponent(q)}">${SVG.pinMap} Buka di Google Maps</a>`
    : '';
  const delBtn = `<button class="rp-del" data-id="${esc(String(r.id))}" data-nama="${esc(r.nama_pelanggan || 'laporan ini')}" type="button" aria-label="Hapus laporan ${esc(r.nama_pelanggan || '')}" title="Hapus laporan">${SVG.trash}</button>`;
  const actions = `<div class="rp-actions">${toggleBtn}${mapLink}${delBtn}</div>`;

  // CATATAN PUSAT (v1.16.0-m17) — kejelasan pengerjaan: instruksi/
  // keterangan pusat untuk petugas atas data yang sudah terkirim.
  // Ada  -> blok kuning berbingkai + tombol pensil (sunting/hapus).
  // Belum -> tombol kecil "Catatan pusat" di baris aksi.
  const cpAda = r.catatan_pusat
    ? `<div class="catatan-pusat">
        <div class="cp-head">
          <span class="cp-tag">${SVG.hat} Catatan Pusat</span>
          <button class="cp-edit" data-id="${esc(String(r.id))}" type="button" aria-label="Sunting catatan pusat" title="Sunting catatan pusat">${SVG.pen}</button>
        </div>
        <p class="cp-isi">${esc(r.catatan_pusat)}</p>
        ${r.catatan_pusat_at ? `<p class="cp-time">${SVG.clock} ${esc(fmtClock(r.catatan_pusat_at))} · ${esc(relTime(r.catatan_pusat_at))}</p>` : ''}
      </div>`
    : '';
  const cpAdd = r.catatan_pusat
    ? ''
    : `<button class="cp-add" data-id="${esc(String(r.id))}" type="button" title="Tulis catatan pusat untuk petugas">${SVG.pen} Catatan pusat</button>`;

  return `<article class="rp-card">
    ${photos}
    <div class="rp-body">
      <div class="rp-toprow">
        <div style="min-width:0">
          <h3 class="rp-name">${esc(r.nama_pelanggan || 'Tanpa Nama Pelanggan')}</h3>
          <p class="rp-time">${esc(fmtClock(r.created_at))} · ${esc(relTime(r.created_at))}</p>
        </div>
        ${badge}
      </div>
      <div class="rp-meta">${meta.join('')}</div>
      ${r.catatan ? `<div class="catatan">${esc(r.catatan)}</div>` : ''}
      ${cpAda}
      ${actions}
      ${cpAdd}
    </div>
  </article>`;
}

function renderLaporan(force) {
  const sub = $('laporan-sub');
  const el = $('laporan-list');

  if (!state.db) {
    sub.textContent = 'Memuat…';
    el.innerHTML = '<div class="skeleton rp-skel"></div><div class="skeleton rp-skel"></div>';
    return;
  }

  const reports = state.db.reports ?? [];
  sub.textContent = `${reports.length} laporan terbaru`;

  const nTertunda = reports.filter((r) => normStatus(r.status) === 'TERTUNDA').length;
  $('cnt-tertunda').textContent = nTertunda;
  $('cnt-selesai').textContent = reports.length - nTertunda;

  const q = state.qLaporan.trim().toLowerCase();
  const list = reports.filter((r) => {
    if (state.filter === 'TERTUNDA' && normStatus(r.status) !== 'TERTUNDA') return false;
    if (state.filter === 'SELESAI' && normStatus(r.status) !== 'SELESAI') return false;
    if (!q) return true;
    return [r.nama_pelanggan, r.id_pelanggan, r.alamat, r.petugas]
      .filter(Boolean).some((v) => String(v).toLowerCase().includes(q));
  });

  const sig = `${state.filter}|${q}|${list.map((r) => `${String(r.id).slice(0, 8)}:${r.status}:${(r.photos ?? []).length}`).join(',')}`;
  if (!force && sig === state.sig.laporan) return;
  state.sig.laporan = sig;

  if (!list.length) {
    el.innerHTML = `<div class="empty">
      <span class="empty-ic">${SVG.file}</span>
      <p class="empty-title">${reports.length ? 'Tidak ada hasil' : 'Belum ada laporan masuk'}</p>
      <p class="empty-sub">${reports.length
        ? 'Coba ubah kata kunci atau filter status.'
        : 'Laporan dari aplikasi petugas tampil di sini.'}</p>
    </div>`;
    return;
  }

  el.innerHTML = list.map((r) => reportCard(r)).join('');
}

/* Hint export di dasbor: ringkasan bulan terpilih */
function renderExportHint() {
  const hint = $('export-hint');
  if (!hint || !state.db) return;
  const month = currentMonth();
  const n = (state.db.reports ?? []).filter((r) => String(r.created_at ?? '').startsWith(month)).length;
  hint.textContent = n
    ? `${fmtInt(n)} laporan siap diunduh.`
    : 'Belum ada laporan bulan ini.';
}

/* lightbox */
$('laporan-list').addEventListener('click', (e) => {
  const btn = e.target.closest('.rp-ph[data-full]');
  if (!btn) return;
  $('lightbox-img').src = btn.dataset.full;
  $('lightbox-cap').textContent = btn.dataset.cap || '';
  $('lightbox').hidden = false;
});
$('lightbox').addEventListener('click', () => {
  $('lightbox').hidden = true;
  $('lightbox-img').src = '';
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !$('lightbox').hidden) {
    $('lightbox').hidden = true;
    $('lightbox-img').src = '';
  }
});

/* Ganti status laporan: TERTUNDA <-> SELESAI (admin web)
   v1.16.0-m17: TIDAK lagi sekali-klik — dialog catatan dulu (kejelasan
   sebelum konfirmasi), status baru diterapkan setelah Konfirmasi. */
$('laporan-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('.rp-toggle[data-to]');
  if (!btn) return;
  const id = btn.dataset.id;
  const to = btn.dataset.to;
  const label = to === 'SELESAI' ? 'Selesai' : 'Tertunda';
  const r = (state.db?.reports ?? []).find((x) => String(x.id) === String(id));
  const nama = r?.nama_pelanggan || 'laporan ini';
  const catatanLama = r?.catatan_pusat || '';

  const pilih = await uiCatatan({
    title: `Tandai ${label}`,
    msg: `${nama}\nStatus laporan akan diubah menjadi ${label.toUpperCase()}.`,
    to,
    note: catatanLama,
  });
  if (!pilih.ok) return;

  btn.disabled = true;
  try {
    const d = await api(`/api/reports/${encodeURIComponent(id)}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: to, catatan: pilih.catatan }),
    });
    if (!d.ok) throw new Error(d.error || 'gagal');
    if (r) {
      r.status = to;
      if (pilih.catatan === '') {
        r.catatan_pusat = null;
        r.catatan_pusat_at = null;
      } else {
        r.catatan_pusat = pilih.catatan;
        r.catatan_pusat_at = new Date().toISOString();
      }
    }
    state.sig.laporan = '';
    state.sig.stats = '';
    if (state.tab === 'laporan') renderLaporan(true);
    renderStats();
    simpanDbLokal();
    toast(pilih.catatan
      ? `Status ${label} + catatan pusat tersimpan`
      : `Laporan ditandai ${label}`);
  } catch {
    btn.disabled = false;
    toast('Gagal mengubah status laporan — coba lagi');
  }
});

/* Tulis / sunting catatan pusat TANPA mengubah status (v1.16.0-m17)
   Dua pintu: pensil pada blok catatan (.cp-edit) & tombol "Catatan
   pusat" pada kartu yang belum punya catatan (.cp-add). */
$('laporan-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('.cp-edit[data-id], .cp-add[data-id]');
  if (!btn) return;
  const id = btn.dataset.id;
  const r = (state.db?.reports ?? []).find((x) => String(x.id) === String(id));
  const nama = r?.nama_pelanggan || 'laporan ini';
  const pilih = await uiCatatan({
    title: 'Catatan Pusat',
    msg: `${nama}\nCatatan untuk petugas atas data yang sudah terkirim.`,
    to: null,
    note: r?.catatan_pusat || '',
  });
  if (!pilih.ok) return;
  btn.disabled = true;
  try {
    await simpanCatatanSaja(id, pilih.catatan);
    toast(pilih.catatan ? 'Catatan pusat tersimpan' : 'Catatan pusat dihapus');
  } catch {
    toast('Gagal menyimpan catatan — coba lagi');
  }
});

/* Hapus satu laporan (admin web): laporan terpilih + seluruh fotonya */
$('laporan-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('.rp-del[data-id]');
  if (!btn) return;
  const id = btn.dataset.id;
  const nama = btn.dataset.nama || 'laporan ini';
  if (!(await uiConfirm(`Hapus laporan "${nama}"?\n\nFoto lapangan ikut terhapus.`, { title: 'Hapus laporan', okText: 'Ya, hapus' }))) return;
  btn.disabled = true;
  try {
    const d = await api(`/api/reports/${encodeURIComponent(id)}`, { method: 'DELETE' });
    if (!d.ok) throw new Error(d.error || 'gagal');
    if (state.db) state.db.reports = (state.db.reports ?? []).filter((x) => String(x.id) !== String(id));
    state.sig.laporan = '';
    state.sig.stats = '';
    renderLaporan(true);
    renderStats();
    renderExportHint();
    toast('Laporan dihapus');
  } catch {
    btn.disabled = false;
    toast('Gagal menghapus laporan — coba lagi');
  }
});

/* ============================================================
   EXPORT — Excel & PDF per bulan
   ============================================================ */
async function downloadExport(ext, btn) {
  const month = (document.querySelector('.export-month')?.value) || currentMonth();
  const label = ext.toUpperCase();
  const url = `/api/export/month.${ext}?month=${encodeURIComponent(month)}&key=${encodeURIComponent(API_KEY)}`;
  const prev = btn.disabled;
  btn.disabled = true;
  try {
    // Di aplikasi Android (WebView), blob: tak didukung — pakai tautan
    // langsung agar berkas diterima DownloadManager sistem.
    if (IS_APP) {
      window.location.href = url;
      toast(`Mengunduh laporan ${month} (${label})…`);
      return;
    }
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) {
      let msg = `Gagal membuat ${label}`;
      try { const j = await res.json(); if (j.error) msg = j.error; } catch { /* bukan json */ }
      throw new Error(msg);
    }
    const blob = await res.blob();
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = `Laporan-PLN-${month}.${ext}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(blobUrl), 4000);
    toast(`Laporan ${month} (${label}) terunduh`);
  } catch (err) {
    toast(err.message || `Gagal mengunduh ${label}`);
  } finally {
    btn.disabled = prev;
  }
}

document.querySelectorAll('.dl-xlsx, .dl-pdf').forEach((btn) => {
  btn.addEventListener('click', () => downloadExport(btn.dataset.ext, btn));
});

/* Sinkronkan dua pilihan bulan (dasbor & laporan) */
document.querySelectorAll('.export-month').forEach((inp) => {
  inp.value = currentMonth();
  inp.addEventListener('input', () => {
    document.querySelectorAll('.export-month').forEach((o) => { if (o !== inp) o.value = inp.value; });
  });
});

/* ============================================================
   SERVER — kualitas terukur (kelancaran % + ms) & pemakaian nyata.
   Status dihitung dari pemeriksaan otomatis, BUKAN pengaturan.
   Tampilan ikonik: gauge kelancaran + judul bergairah + chip angka.
   ============================================================ */
const STATUS_LABEL = { lancar: 'Lancar', lambat: 'Lambat', gangguan: 'Gangguan' };

const MOOD_IC = {
  prima: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M13 2 4.5 13.5H11L9.5 22 19 9.8h-6.6L13 2Z"/></svg>',
  lancar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
  lambat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>',
  gangguan: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>',
};

/* Kata kondisi server — singkat, tanpa hiasan */
const MOODS = {
  prima: ['Kondisi prima'],
  lancar: ['Kondisi lancar'],
  lambat: ['Respons lambat'],
  gangguan: ['Ada gangguan'],
};

function moodFor(s) {
  const h = s.health ?? {};
  if (h.uptime_pct == null && h.last_ok == null) return { tier: 'tunggu', word: 'Memeriksa…', ic: MOOD_IC.lancar };
  const ms = h.avg_ms ?? 0;
  let tier = 'lancar';
  if (s.status === 'gangguan') tier = 'gangguan';
  else if (s.status === 'lambat') tier = 'lambat';
  else if (h.uptime_pct != null && h.uptime_pct >= 99.5 && ms > 0 && ms <= 600) tier = 'prima';
  const list = MOODS[tier] ?? MOODS.lancar;
  return { tier, word: list[0], ic: MOOD_IC[tier] ?? MOOD_IC.lancar };
}

const GAUGE_C = 2 * Math.PI * 34; // keliling lingkaran r=34

function setGauge(pct, tier) {
  const arc = $('srv-gauge-arc');
  const val = pct != null ? Math.max(0, Math.min(100, pct)) : 0;
  arc.style.strokeDasharray = `${GAUGE_C.toFixed(1)}`;
  arc.style.strokeDashoffset = `${(GAUGE_C * (1 - val / 100)).toFixed(1)}`;
  arc.setAttribute('class', `gauge-val g-${tier}`); // SVG: className read-only
  $('srv-gauge-pct').textContent = pct != null ? fmtPct(pct) : '–';
}

let lastPingAt = 0;

async function loadServer() {
  const t0 = performance.now();
  try {
    const d = await api('/api/server');
    if (d.ok) {
      // Lapor latensi nyata ke server (maks 1x per 2 menit) —
      // inilah sumber "waktu tanggap rata-rata" di kartu Server.
      const rtt = Math.round(performance.now() - t0);
      if (rtt > 0 && Date.now() - lastPingAt > 120000) {
        lastPingAt = Date.now();
        api('/api/server/ping', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ ms: rtt }),
        }).catch(() => {});
      }
      state.srv = d;
      renderServer();
    }
  } catch { /* diamkan — coba lagi di tick berikutnya */ }
}

function setBar(idBar, idVal, used, limit) {
  const pct = limit > 0 ? Math.min(100, (Number(used) || 0) / limit * 100) : 0;
  const bar = $(idBar);
  bar.style.width = `${Math.max(pct, (Number(used) > 0 ? 1.5 : 0)).toFixed(1)}%`;
  bar.className = pct >= 85 ? 'danger' : pct >= 60 ? 'warn' : '';
  $(idVal).textContent = fmtInt(used);
}

function renderServer() {
  const s = state.srv;
  if (!s) return;
  const h = s.health ?? {};
  const sig = [
    s.version, s.status, h.uptime_pct, h.avg_ms, h.last_ok, h.checks_24h,
    s.today?.requests, s.today?.rows_read, s.today?.rows_written,
    s.storage?.photos, s.storage?.photos_kb, s.storage?.db_bytes, s.storage?.r2_bytes, s.usage_source,
  ].join('|');
  if (sig === state.sig.srv) return;
  state.sig.srv = sig;

  const badge = $('srv-badge');
  badge.className = `srv-badge st-${s.status}`;
  $('srv-badge-txt').textContent = STATUS_LABEL[s.status] ?? s.status;

  // --- tampilan ikonik: gauge + mood + chip ---
  const mood = moodFor(s);
  setGauge(h.uptime_pct, mood.tier);
  $('srv-mood-ic').innerHTML = mood.ic;
  $('srv-mood-txt').textContent = mood.word;
  const moodBox = $('srv-mood');
  moodBox.className = `srv-mood m-${mood.tier}`;

  const ms = h.avg_ms != null ? `${fmtInt(h.avg_ms)} ms` : null;
  const pct = h.uptime_pct != null ? `${fmtPct(h.uptime_pct)}%` : null;
  const chipMs = $('srv-chip-ms');
  if (ms) { chipMs.hidden = false; chipMs.textContent = `⚡ Tanggap ${ms}`; } else chipMs.hidden = true;
  const chipChecks = $('srv-chip-checks');
  if (h.checks_24h > 0) {
    chipChecks.hidden = false;
    chipChecks.textContent = `${pct ?? '–'} pemeriksaan berhasil · ${fmtInt(h.checks_24h)}× dicek`;
  } else chipChecks.hidden = true;

  setBar('ub-req', 'ub-req-val', s.today?.requests, s.limits?.requests_per_day);
  setBar('ub-read', 'ub-read-val', s.today?.rows_read, s.limits?.d1_rows_read_per_day);
  setBar('ub-write', 'ub-write-val', s.today?.rows_written, s.limits?.d1_rows_written_per_day);

  // Penyimpanan foto (R2) — kuota gratis 10 GB
  const storePct = s.limits?.r2_storage_bytes > 0
    ? Math.min(100, (Number(s.storage?.r2_bytes) || 0) / s.limits.r2_storage_bytes * 100)
    : 0;
  const storeBar = $('ub-store');
  storeBar.style.width = `${Math.max(storePct, (Number(s.storage?.r2_bytes) > 0 ? 1.5 : 0)).toFixed(2)}%`;
  storeBar.className = storePct >= 85 ? 'danger' : storePct >= 60 ? 'warn' : '';
  $('ub-store-val').textContent = fmtBytes(s.storage?.r2_bytes);

  $('srv-ver').textContent = s.version || '–';
  $('srv-r2').textContent = `${fmtInt(s.storage?.r2_objects ?? 0)} foto · ${fmtBytes(s.storage?.r2_bytes)}`;
  $('srv-db').textContent = s.storage?.db_bytes
    ? `${(s.storage.db_bytes / 1024 / 1024).toFixed(2)} MB`
    : 'Sehat';
  $('srv-updated').textContent = s.time ? relTime(s.time) : '–';

  const b = $('srv-banner');
  if (s.status === 'gangguan') {
    b.hidden = false;
    b.className = 'srv-banner down';
    b.textContent = 'Server gangguan — coba lagi sebentar.';
  } else {
    b.hidden = true;
  }
}

/* ============================================================
   RESET KUOTA HARIAN CLOUDFLARE — hitung mundur ke 00.00 UTC
   (07.00 WIB). Limit gratis (permintaan API & baris D1) diisi
   ulang otomatis setiap tengah malam UTC.
   ============================================================ */
let resetTxt = '';
function renderResetCountdown() {
  const el = $('srv-reset-txt');
  if (!el) return;
  const now = new Date();
  const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  const sisa = Math.max(0, Math.floor((next - now.getTime()) / 1000));
  const j = Math.floor(sisa / 3600);
  const m = Math.floor((sisa % 3600) / 60);
  const dtk = sisa % 60;
  const txt = j > 0 ? `${j} jam ${m} menit`
    : m > 0 ? `${m} menit ${dtk} detik`
    : `${dtk} detik`;
  if (txt !== resetTxt) {
    resetTxt = txt;
    el.textContent = txt;
  }
}
renderResetCountdown();
setInterval(renderResetCountdown, 1000);

/* ============================================================
   PWA — tombol Install
   ============================================================ */
let deferredPrompt = null;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPrompt = e;
  $('btn-install').hidden = false;
});
$('btn-install').addEventListener('click', async () => {
  if (!deferredPrompt) return;
  deferredPrompt.prompt();
  const { outcome } = await deferredPrompt.userChoice.catch(() => ({ outcome: 'dismissed' }));
  if (outcome === 'accepted') toast('PLN Admin terpasang di layar utama');
  deferredPrompt = null;
  $('btn-install').hidden = true;
});
window.addEventListener('appinstalled', () => {
  $('btn-install').hidden = true;
});

/* ============================================================
   LOOP & MULAI
   ============================================================ */
setInterval(() => {
  state.tick++;
  if (state.tick % 2 === 0) loadDashboard();          // 10 dtk
  if (state.tick % 6 === 0) loadServer();             // 30 dtk — status & pemakaian
  renderSync();
}, 5000);

hydrateCache();          // catatan terakhir -> layar terisi instan
switchTab('dasbor');
loadDashboard();         // lalu segarkan dari jaringan
loadServer();            // status server + pemakaian free tier
