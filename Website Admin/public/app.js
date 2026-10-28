/* ============================================================
   PLN ADMIN — app.js (vanilla, tanpa framework)
   Tema v1.8 "Navy Teknis" — 4 tab: Dasbor / Petugas / Laporan / Obrolan

   Kecepatan:
   - 1 permintaan gabungan /api/dashboard (sebelumnya 3)
   - preview obrolan 1 permintaan /api/chat/previews (sebelumnya 1+N)
   - hidrasi instan dari localStorage sebelum jaringan
   - render hanya saat data benar-benar berubah (signature guard)
   - tanpa font eksternal (system stack) — first paint cepat
   Polling: dasbor 10 dtk, percakapan aktif 5 dtk, preview 30 dtk.
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

function dayKey(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '?';
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}

function dayLabel(iso) {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const today = new Date();
  const yest = new Date();
  yest.setDate(today.getDate() - 1);
  const same = (a, b) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (same(d, today)) return 'Hari Ini';
  if (same(d, yest)) return 'Kemarin';
  return d.toLocaleDateString('id-ID', { day: 'numeric', month: 'long', year: 'numeric' });
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
  pin: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"/></svg>',
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>',
  pinMap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/></svg>',
  camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/></svg>',
  check: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
  undo: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/></svg>',
  hat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 18a1 1 0 0 0 1 1h18a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1H3a1 1 0 0 0-1 1z"/><path d="M4 15v-3a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v3"/><path d="M19 15V9a2 2 0 0 0-2-2"/></svg>',
  chatEmpty: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/><path d="M8 12h.01"/><path d="M12 12h.01"/><path d="M16 12h.01"/></svg>',
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
  previews: {},      // { global: {...}, dm: { '<id>': {...} }, unread: {...} }
  unreadTotal: 0,
  conv: null,        // {kind:'global'} | {kind:'private', id, nama, key}
  msgs: [],
  lastId: 0,
  msgsLoading: false,
  sending: false,
  lastSync: null,
  tick: 0,
  srv: null,        // respons /api/server (status + pemakaian free tier)
  sig: { petugas: '', laporan: '', chatlist: '', stats: '', srv: '' },
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
   ============================================================ */
document.querySelectorAll('[data-tab]').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
});
document.querySelectorAll('.stat[data-goto]').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(btn.dataset.goto));
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
  if (tab === 'obrolan') { renderChatList(true); loadPreviews(); }
  renderTabBadge();
  beriKabarAplikasi();
}

/* ============================================================
   OBROLAN — TIDAK ADA TOMBOL DI WEB (permintaan user): tombol
   obrolan melayang hanya ada di Aplikasi Admin (native FAB).
   Web hanya menyediakan view obrolan + __plnOpenChat yang
   dipanggil FAB native, lalu FAB disembunyikan selama
   obrolan terbuka dan muncul lagi saat obrolan ditutup.
   ============================================================ */
function renderTabBadge() {
  /* tidak ada lagi tombol obrolan di web — lencana ditangani
     FAB native di aplikasi. Fungsi dibiarkan sebagai no-op. */
}

function recomputeBadge() {
  const u = state.previews.unread ?? {};
  let total = Number(u.global) || 0;
  for (const [k, v] of Object.entries(u)) if (k !== 'global') total += Number(v) || 0;
  state.unreadTotal = total;
  renderTabBadge();
}

/* Jembatan aplikasi Android (PLN Admin APK): sembunyikan FAB native
   saat obrolan terbuka di dalam WebView, tampilkan lagi di tab lain. */
function beriKabarAplikasi() {
  if (!IS_APP || !window.PlnApp || typeof window.PlnApp.fab !== 'function') return;
  try { window.PlnApp.fab(state.tab === 'obrolan' ? 'hide' : 'show'); } catch { /* diamkan */ }
}

/* Dipanggil oleh FAB native di aplikasi Android */
window.__plnOpenChat = () => switchTab('obrolan');

/* Tutup obrolan (tombol X) — kembali ke Dasbor; FAB native muncul lagi
   otomatis lewat beriKabarAplikasi() yang dipanggil switchTab. */
$('chat-close').addEventListener('click', () => {
  closeConv();
  switchTab('dasbor');
});

/* ============================================================
   HERO — sambutan & jam
   ============================================================ */
function renderHero() {
  $('salam').textContent = salamWaktu();
  $('hero-date').textContent = tanggalPanjang();
}
renderHero();
setInterval(renderHero, 30000);

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

async function loadDashboard() {
  const btn = $('btn-refresh');
  btn.classList.add('spin');
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
  finally { btn.classList.remove('spin'); }
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
  if (state.tab === 'petugas') renderPetugas();
  if (state.tab === 'laporan') renderLaporan();
  if (state.tab === 'obrolan') renderChatList();
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

$('btn-refresh').addEventListener('click', loadDashboard);

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
        : 'Petugas akan tampil di sini setelah mendaftar lewat aplikasi.'}</p>
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
  if (!confirm(`Hapus petugas "${nama}"?\n\nPetugas bisa mendaftar ulang kapan saja lewat aplikasi. Riwayat laporan & pesan tetap tersimpan.`)) return;
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
  const actions = toggleBtn || mapLink
    ? `<div class="rp-actions">${toggleBtn}${mapLink}</div>`
    : '';

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
      ${actions}
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
        : 'Laporan dari Aplikasi Petugas (foto + lokasi + data pelanggan) akan tampil otomatis di sini.'}</p>
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
    ? `${fmtInt(n)} laporan bulan ini siap diunduh, lengkap dengan foto lapangan.`
    : 'Belum ada laporan bulan ini — berkas tetap bisa diunduh.';
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

/* Ganti status laporan: TERTUNDA <-> SELESAI (admin web) */
$('laporan-list').addEventListener('click', async (e) => {
  const btn = e.target.closest('.rp-toggle[data-to]');
  if (!btn) return;
  const id = btn.dataset.id;
  const to = btn.dataset.to;
  const label = to === 'SELESAI' ? 'Selesai' : 'Tertunda';
  btn.disabled = true;
  try {
    const d = await api(`/api/reports/${encodeURIComponent(id)}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: to }),
    });
    if (!d.ok) throw new Error(d.error || 'gagal');
    const r = (state.db?.reports ?? []).find((x) => String(x.id) === String(id));
    if (r) r.status = to;
    state.sig.laporan = '';
    state.sig.stats = '';
    if (state.tab === 'laporan') renderLaporan(true);
    renderStats();
    toast(`Laporan ditandai ${label}`);
  } catch {
    btn.disabled = false;
    toast('Gagal mengubah status laporan — coba lagi');
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

/* Judul bergairah — berputar tiap jam agar terasa hidup, tetap informatif */
const MOODS = {
  prima: ['Ngebut! Semua nyala penuh', 'Kilat! Kondisi terbaik', 'Mantap — respons kilat'],
  lancar: ['Aman terkendali', 'Semua sistem menyala', 'Lancar jaya'],
  lambat: ['Masih jalan, agak lelet', 'Hari ini bebannya berat'],
  gangguan: ['Ada gangguan di jalur', 'Lagi rewel — kami pantau'],
};

function moodFor(s) {
  const h = s.health ?? {};
  if (h.uptime_pct == null && h.last_ok == null) return { tier: 'tunggu', word: 'Menyala…', ic: MOOD_IC.lancar };
  const ms = h.avg_ms ?? 0;
  let tier = 'lancar';
  if (s.status === 'gangguan') tier = 'gangguan';
  else if (s.status === 'lambat') tier = 'lambat';
  else if (h.uptime_pct != null && h.uptime_pct >= 99.5 && ms > 0 && ms <= 600) tier = 'prima';
  const list = MOODS[tier] ?? MOODS.lancar;
  const seed = new Date().getHours() + new Date().getDate();
  return { tier, word: list[seed % list.length], ic: MOOD_IC[tier] ?? MOOD_IC.lancar };
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
    b.textContent = 'Server sedang mengalami gangguan — beberapa fitur mungkin lambat merespons. Coba lagi sebentar lagi.';
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
   OBROLAN — daftar (1 permintaan /api/chat/previews)
   ============================================================ */
async function loadPreviews() {
  try {
    const d = await api('/api/chat/previews');
    if (d.ok) {
      state.previews = { global: d.global, dm: d.dm ?? {}, unread: d.unread ?? {} };
      recomputeBadge();
      renderChatList();
    }
  } catch { /* diamkan */ }
}

function previewLine(id, fallback) {
  const pv = id === 'global' ? state.previews.global : state.previews.dm?.[id];
  if (!pv) return fallback;
  const who = pv.sender_nama ? `${pv.sender_nama}: ` : '';
  return `${who}${pv.body}`;
}

function previewTime(id) {
  const pv = id === 'global' ? state.previews.global : state.previews.dm?.[id];
  return pv ? esc(fmtClock(pv.created_at)) : '';
}

function renderChatList(force) {
  const el = $('chat-list');
  if (!state.db) {
    el.innerHTML = '<div class="skeleton ci-skel"></div><div class="skeleton ci-skel"></div>';
    return;
  }

  const all = state.db.petugas ?? [];
  const dmList = all.filter((x) => !x.admin);
  const sig = `${all.map((x) => `${x.id}:${x.online ? 1 : 0}`).join(',')}|` +
    `${state.previews.global ? state.previews.global.id : 'g0'}|` +
    Object.entries(state.previews.dm ?? {}).map(([k, v]) => `${k}:${v.id}`).join(',');

  if (!force && sig === state.sig.chatlist) return;
  state.sig.chatlist = sig;

  const selCls = (k) => {
    if (!state.conv) return '';
    if (state.conv.kind === 'global' && k === 'global') return ' sel';
    if (state.conv.kind === 'private' && k === state.conv.key) return ' sel';
    return '';
  };

  const unread = state.previews.unread ?? {};
  const uChip = (n) =>
    Number(n) > 0
      ? `<span class="unread-chip">${Number(n) > 99 ? '99+' : Number(n)}</span>`
      : '<span class="unread-chip none"></span>';

  const items = [`<button class="chat-item${selCls('global')}" data-conv="global">
    <span class="pinned-av">${SVG.pin}</span>
    <span class="cmain">
      <span class="cname"><b>Diskusi Petugas</b><span class="grup-chip">GRUP</span></span>
      <span class="cprev">${esc(previewLine('global', 'Semua petugas & admin'))}</span>
    </span>
    <span class="cside"><span class="ctime">${previewTime('global')}</span>${uChip(unread.global)}</span>
  </button>`];

  items.push('<p class="list-label">Pesan Pribadi</p>');

  if (!dmList.length) {
    items.push('<p class="cempty">Belum ada petugas terdaftar. Pesan pribadi tampil di sini setelah petugas mendaftar lewat aplikasi.</p>');
  }
  for (const p of dmList) {
    const key = `p${p.id}`;
    items.push(`<button class="chat-item${selCls(key)}" data-conv="${p.id}">
      ${avatarHtml(p.nama, 'sm', p.online)}
      <span class="cmain">
        <span class="cname"><b>${esc(p.nama)}</b></span>
        <span class="cprev">${esc(previewLine(String(p.id), p.online ? 'Online' : `Aktif ${relTime(p.last_seen_at)}`))}</span>
      </span>
      <span class="cside"><span class="ctime">${previewTime(String(p.id))}</span>${uChip(unread[String(p.id)])}</span>
    </button>`);
  }
  el.innerHTML = items.join('');
}

$('chat-list').addEventListener('click', (e) => {
  const btn = e.target.closest('.chat-item[data-conv]');
  if (!btn) return;
  const v = btn.dataset.conv;
  if (v === 'global') openConv({ kind: 'global', key: 'global' });
  else {
    const p = (state.db?.petugas ?? []).find((x) => String(x.id) === v);
    openConv({ kind: 'private', id: Number(v), nama: p ? p.nama : 'Petugas', key: `p${v}` });
  }
});

/* Kembali dari percakapan ke daftar obrolan (tutup percakapan aktif) */
function backToList() {
  closeConv();
}

/* ============================================================
   OBROLAN — percakapan
   ============================================================ */
function convSubtitle() {
  if (!state.conv) return '';
  if (state.conv.kind === 'global') {
    const n = (state.db?.petugas?.filter((x) => !x.admin).length ?? 0) + 1; // + admin
    const on = state.db?.petugas?.filter((x) => x.online).length ?? 0;
    return `${n} anggota · ${on} online`;
  }
  const p = (state.db?.petugas ?? []).find((x) => x.id === state.conv.id);
  if (!p) return 'Petugas';
  return p.online ? 'Online' : `Aktif ${relTime(p.last_seen_at)}`;
}

function renderConvHeader() {
  const c = state.conv;
  if (!c) return;
  if (c.kind === 'global') {
    $('conv-title').textContent = 'Diskusi Petugas';
    $('conv-avatar').className = 'pinned-av sm-pinned';
    $('conv-avatar').style.background = '';
    $('conv-avatar').innerHTML = SVG.pin;
    $('conv-input').placeholder = 'Tulis pesan ke semua petugas…';
  } else {
    $('conv-title').textContent = c.nama;
    $('conv-avatar').className = 'avatar sm';
    $('conv-avatar').style.background = avatarGradient(c.nama);
    $('conv-avatar').innerHTML = esc(initials(c.nama));
    $('conv-input').placeholder = `Pesan untuk ${c.nama}…`;
  }
  $('conv-sub').textContent = convSubtitle();
}

async function openConv(conv) {
  state.conv = conv;
  state.msgs = [];
  state.lastId = 0;
  state.msgsLoading = true;

  // Percakapan ini dianggap terbaca sekarang (server mencatat saat GET)
  if (state.previews.unread) {
    state.previews.unread[conv.kind === 'global' ? 'global' : String(conv.id)] = 0;
    recomputeBadge();
    renderChatList();
  }

  $('conv-empty').style.display = 'none';
  $('conv-active').hidden = false;
  $('chat-wrap').classList.add('show-conv');
  renderConvHeader();

  document.querySelectorAll('.chat-item').forEach((el) => {
    el.classList.toggle('sel',
      (conv.kind === 'global' && el.dataset.conv === 'global') ||
      (conv.kind === 'private' && el.dataset.conv === String(conv.id)));
  });

  $('conv-msgs').innerHTML = '<div class="loading-msgs"><span class="spinner"></span></div>';
  await pollConv(true);
  $('conv-input').focus();
}

function closeConv(clearSel = true) {
  state.conv = null;
  state.msgs = [];
  state.lastId = 0;
  $('conv-active').hidden = true;
  $('conv-empty').style.display = '';
  $('chat-wrap').classList.remove('show-conv');
  if (clearSel) {
    document.querySelectorAll('.chat-item').forEach((el) => el.classList.remove('sel'));
  }
}

$('btn-back').addEventListener('click', () => closeConv());

async function pollConv(full) {
  if (!state.conv || state.sending) return;
  try {
    const after = full ? 0 : state.lastId;
    let path;
    if (state.conv.kind === 'global') {
      path = `/api/chat/messages?scope=global&after=${after}&limit=200`;
    } else {
      path = `/api/chat/messages?scope=private&a=${state.conv.id}&b=admin&after=${after}&limit=200`;
    }
    const d = await api(path);
    const incoming = d.messages ?? [];

    if (full) {
      state.msgs = incoming;
    } else if (incoming.length) {
      const known = new Set(state.msgs.map((m) => m.id));
      for (const m of incoming) if (!known.has(m.id)) state.msgs.push(m);
    }
    state.lastId = state.msgs.length ? Math.max(...state.msgs.map((m) => m.id)) : 0;
    state.msgsLoading = false;
    renderMsgs(incoming.length > 0);
    renderConvHeader();
  } catch { /* polling: diamkan error sesaat */ }
}

function renderMsgs(stick) {
  const box = $('conv-msgs');
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 90;

  if (!state.msgs.length) {
    box.innerHTML = `<div class="chat-empty">${SVG.chatEmpty}
      <span>Belum ada pesan. Mulai percakapan pertama!</span></div>`;
    return;
  }

  const global = state.conv && state.conv.kind === 'global';
  let html = '';
  let prevKey = null;
  for (const m of state.msgs) {
    const k = dayKey(m.created_at);
    if (k !== prevKey) {
      html += `<div class="day-chip"><span>${esc(dayLabel(m.created_at))}</span></div>`;
      prevKey = k;
    }
    const own = m.sender_type === 'admin';
    const name = !own && global ? `<span class="msg-name">${esc(m.sender_nama)}</span>` : '';
    html += `<div class="msg ${own ? 'own' : 'other'}"><div class="msg-inner">${name}
      <div class="bubble" data-id="${m.id}" title="Ketuk untuk menghapus pesan">${esc(m.body)}<span class="bubble-time">${esc(fmtClock(m.created_at))}</span></div>
    </div></div>`;
  }
  box.innerHTML = html;
  if (stick || nearBottom) box.scrollTop = box.scrollHeight;
}

/* ============================================================
   OBROLAN — HAPUS PESAN (hak admin)
   Ketuk gelembung pesan -> konfirmasi -> pesan hilang untuk
   semua pengguna. Pesan lama juga dibersihkan otomatis (30 hari).
   ============================================================ */
$('conv-msgs').addEventListener('click', async (e) => {
  const bubble = e.target.closest('.bubble[data-id]');
  if (!bubble || !state.conv || state.sending) return;
  const id = Number(bubble.dataset.id);
  if (!Number.isInteger(id) || id <= 0) return;
  if (!confirm('Hapus pesan ini?\n\nPesan akan hilang untuk semua pengguna.')) return;
  try {
    await api(`/api/chat/messages/${id}`, { method: 'DELETE' });
    state.msgs = state.msgs.filter((m) => Number(m.id) !== id);
    renderMsgs(false);
    loadPreviews();
    toast('Pesan dihapus');
  } catch {
    toast('Gagal menghapus pesan — coba lagi');
  }
});

$('conv-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const input = $('conv-input');
  const body = input.value.trim();
  if (!body || !state.conv || state.sending) return;
  state.sending = true;
  $('btn-send').disabled = true;
  try {
    const payload = state.conv.kind === 'global'
      ? { scope: 'global', sender_type: 'admin', body }
      : { scope: 'private', sender_type: 'admin', target_id: String(state.conv.id), body };
    const d = await api('/api/chat/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY },
      body: JSON.stringify(payload),
    });
    if (!d.ok) throw new Error(d.message || 'gagal');
    input.value = '';
    state.msgs.push(d.message);
    state.lastId = Math.max(state.lastId, d.message.id);
    if (state.conv.kind === 'global') {
      state.previews.global = d.message;
    } else {
      state.previews.dm ??= {};
      state.previews.dm[String(state.conv.id)] = d.message;
    }
    state.sig.chatlist = '';
    renderMsgs(true);
    renderChatList(true);
  } catch (err) {
    toast('Pesan gagal terkirim. Coba lagi.');
  } finally {
    state.sending = false;
    $('btn-send').disabled = false;
    input.focus();
  }
});

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
  if (state.tick % 6 === 0) loadPreviews();           // 30 dtk — lencana tab obrolan
  if (state.tab === 'obrolan') {
    pollConv(false);                                   // 5 dtk saat tab obrolan terbuka
  }
  renderSync();
}, 5000);

hydrateCache();          // catatan terakhir -> layar terisi instan
switchTab('dasbor');
loadDashboard();         // lalu segarkan dari jaringan
loadServer();            // status server + pemakaian free tier
loadPreviews();          // lencana belum-terbaca tab obrolan
