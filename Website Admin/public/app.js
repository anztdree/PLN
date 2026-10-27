/* ============================================================
   PLN ADMIN — app.js (vanilla, tanpa framework)
   Desain: "Onyx Luxe"
   Polling: data utama 10 dtk, percakapan aktif 5 dtk,
            preview daftar obrolan 30 dtk.
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

const AVATAR_GRADIENTS = [
  'linear-gradient(135deg,#8a6d2f 0%,#c9a24b 50%,#f0d38a 100%)',
  'linear-gradient(135deg,#7a5c22 0%,#b08a3e 55%,#e6c476 100%)',
  'linear-gradient(135deg,#5f6b3c 0%,#8fa05a 55%,#cfd9a0 100%)',
  'linear-gradient(135deg,#7d4a2a 0%,#b97a45 55%,#eab98a 100%)',
  'linear-gradient(135deg,#4f5d52 0%,#7d8f7e 55%,#bccbbd 100%)',
];

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

function avatarHtml(nama, cls, online) {
  const dot = online === undefined
    ? ''
    : `<span class="st-dot ${online ? 'on dot-online' : ''}"></span>`;
  return `<span class="avatar ${cls || ''}" style="background:${avatarGradient(nama)}">${esc(initials(nama))}${dot}</span>`;
}

const SVG = {
  pin: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"/></svg>',
  clock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>',
  pinMap: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/></svg>',
  camera: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z"/><circle cx="12" cy="13" r="3"/></svg>',
  hat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 18a1 1 0 0 0 1 1h18a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1H3a1 1 0 0 0-1 1z"/><path d="M4 15v-3a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v3"/><path d="M19 15V9a2 2 0 0 0-2-2"/></svg>',
  chatEmpty: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/><path d="M8 12h.01"/><path d="M12 12h.01"/><path d="M16 12h.01"/></svg>',
  users: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
  file: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v5h6"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/></svg>',
};

let toastTimer = null;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
}

async function api(path, opts) {
  // X-API-Key pada GET chat -> bacaan admin tercatat utk status "dilihat oleh"
  const res = await fetch(path, { cache: 'no-store', headers: { 'X-API-Key': API_KEY }, ...opts });
  if (!res.ok && res.status !== 409 && res.status !== 404) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

/* ============================================================
   STATE
   ============================================================ */
const state = {
  tab: 'petugas',
  health: null,
  petugas: null,     // null = masih memuat
  reports: null,
  qPetugas: '',
  qLaporan: '',
  filter: 'SEMUA',
  previews: {},      // { global: {body,created_at,sender_nama}, p3: {...} }
  conv: null,        // {kind:'global'} | {kind:'private', id, nama, key}
  msgs: [],
  lastId: 0,
  msgsLoading: false,
  sending: false,
  lastSync: null,
  tick: 0,
};

let errShownAt = 0;
function errToast() {
  if (Date.now() - errShownAt > 30000) {
    errShownAt = Date.now();
    toast('Koneksi bermasalah, mencoba lagi…');
  }
}

/* ============================================================
   TAB & NAVIGASI
   ============================================================ */
document.querySelectorAll('[data-tab]').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
});
document.querySelectorAll('.stat[data-goto]').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(btn.dataset.goto));
});

function switchTab(tab) {
  state.tab = tab;
  /* Statistik tidak tampil di tab Obrolan — kolom chat fokus penuh */
  document.body.classList.toggle('tab-obrolan', tab === 'obrolan');
  document.querySelectorAll('[data-tab]').forEach((b) =>
    b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
  $(`view-${tab}`).classList.add('active');
  if (tab === 'petugas') { renderPetugas(); }
  if (tab === 'laporan') { renderLaporan(); }
  if (tab === 'obrolan') { renderChatList(); loadPreviews(); }
  if (tab !== 'obrolan') closeConv(false);
}

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
   DATA UTAMA (health + petugas + laporan)
   ============================================================ */
async function loadAll() {
  const btn = $('btn-refresh');
  btn.classList.add('spin');
  try {
    const [h, p, r] = await Promise.allSettled([
      api('/api/health'),
      api('/api/petugas'),
      api('/api/reports'),
    ]);
    if (h.status === 'fulfilled') state.health = h.value;
    if (p.status === 'fulfilled') state.petugas = p.value.petugas ?? [];
    if (r.status === 'fulfilled') state.reports = r.value.reports ?? [];

    const allFailed = h.status === 'rejected' && p.status === 'rejected' && r.status === 'rejected';
    if (allFailed) errToast();

    state.lastSync = Date.now();
    renderStats();
    if (state.tab === 'petugas') renderPetugas();
    if (state.tab === 'laporan') renderLaporan();
    if (state.tab === 'obrolan') renderChatList();
    renderSync();
  } catch { errToast(); }
  finally { btn.classList.remove('spin'); }
}

function renderSync() {
  $('sync-text').textContent = state.lastSync
    ? `Tersinkron ${relTime(new Date(state.lastSync).toISOString())}`
    : 'Menyambungkan…';
}

function renderStats() {
  $('st-petugas').textContent = state.health ? state.health.statistik.petugas : '–';
  $('st-reports').textContent = state.health ? state.health.statistik.reports : '–';
  $('st-messages').textContent = state.health ? state.health.statistik.messages : '–';
  $('st-online').textContent = state.petugas
    ? state.petugas.filter((x) => x.online).length
    : '–';
}
$('btn-refresh').addEventListener('click', loadAll);

/* ============================================================
   PETUGAS
   ============================================================ */
$('petugas-q').addEventListener('input', (e) => {
  state.qPetugas = e.target.value;
  renderPetugas();
});

function renderPetugas() {
  const sub = $('petugas-sub');
  const el = $('petugas-list');

  if (state.petugas === null) {
    sub.textContent = 'Memuat…';
    el.innerHTML = Array(3).fill('<div class="skeleton pg-skel"></div>').join('');
    return;
  }

  const adm = state.petugas.find((x) => x.admin);
  const petugasOnly = state.petugas.filter((x) => !x.admin);
  const total = petugasOnly.length;
  const on = state.petugas.filter((x) => x.online).length; // termasuk admin
  sub.textContent = `${total} petugas terdaftar · ${on} online`;

  const q = state.qPetugas.trim().toLowerCase();
  const list = q ? state.petugas.filter((x) => x.nama.toLowerCase().includes(q)) : state.petugas;

  if (!list.length) {
    el.innerHTML = `<div class="empty">
      <span class="empty-ic">${SVG.users}</span>
      <p class="empty-title display">${state.petugas.length ? 'Tidak ditemukan' : 'Belum ada petugas terdaftar'}</p>
      <p class="empty-sub">${state.petugas.length
        ? `Tidak ada petugas bernama “${esc(state.qPetugas.trim())}”.`
        : 'Petugas akan tampil di sini setelah mendaftar lewat aplikasi.'}</p>
    </div>`;
    return;
  }

  el.innerHTML = list.map((p, i) => `
    <article class="pg-card" style="animation-delay:${Math.min(i * 0.05, 0.4)}s">
      <div class="pg-top">
        ${avatarHtml(p.nama, p.admin ? 'adm' : '', p.online)}
        <div class="pg-main">
          <b>${esc(p.nama)}${p.admin ? ' <span class="badge admin">ADMIN</span>' : ''}</b>
          <small>${p.admin ? 'Admin · Pusat kendali' : `Petugas · ID #${p.id}`}</small>
        </div>
        <span class="status-pill ${p.online ? 'on' : 'off'}">
          <i class="dot ${p.online ? 'on dot-online' : ''}"></i>${p.online ? 'ONLINE' : 'OFFLINE'}
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
      await loadAll();
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
  renderLaporan();
});
$('laporan-chips').addEventListener('click', (e) => {
  const b = e.target.closest('.chip-f[data-f]');
  if (!b) return;
  state.filter = b.dataset.f;
  document.querySelectorAll('#laporan-chips .chip-f').forEach((c) =>
    c.classList.toggle('active', c === b));
  renderLaporan();
});

function mapsQuery(r) {
  if (r.latitude != null && r.longitude != null) return `${r.latitude},${r.longitude}`;
  if (r.plus_code) return r.plus_code;
  if (r.alamat) return r.alamat;
  return null;
}

function reportCard(r) {
  const badge = r.status === 'BARU' ? '<span class="badge baru">BARU</span>'
    : r.status === 'SELESAI' ? '<span class="badge selesai">SELESAI</span>'
    : `<span class="badge lain">${esc(r.status || '—')}</span>`;

  const n = (r.photos ?? []).length;
  const cols = n === 1 ? 'cols-1' : n === 2 ? 'cols-2' : 'cols-4';
  const photos = n
    ? `<div class="rp-photos ${cols}">${r.photos.map((ph) => {
        const url = `/api/photos/${encodeURIComponent(ph.id)}`;
        return `<button class="rp-ph" data-full="${esc(url)}" data-cap="Foto ${ph.urutan} — ${esc(r.nama_pelanggan || 'Pelanggan')}" aria-label="Perbesar foto ${ph.urutan}">
          <img src="${esc(url)}" alt="Foto ${ph.urutan} laporan ${esc(r.nama_pelanggan || '')}" loading="lazy">
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
  const mapLink = q
    ? `<div class="rp-actions"><a class="map-link" target="_blank" rel="noopener"
        href="https://maps.google.com/?q=${encodeURIComponent(q)}">${SVG.pinMap} Buka di Google Maps</a></div>`
    : '';

  return `<article class="rp-card">
    ${photos}
    <div class="rp-body">
      <div class="rp-toprow">
        <div style="min-width:0">
          <h3 class="rp-name display">${esc(r.nama_pelanggan || 'Tanpa Nama Pelanggan')}</h3>
          <p class="rp-time">${esc(fmtClock(r.created_at))} · ${esc(relTime(r.created_at))}</p>
        </div>
        ${badge}
      </div>
      <div class="rp-meta">${meta.join('')}</div>
      ${r.catatan ? `<div class="catatan">${esc(r.catatan)}</div>` : ''}
      ${mapLink}
    </div>
  </article>`;
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

function renderLaporan() {
  const sub = $('laporan-sub');
  const el = $('laporan-list');

  if (state.reports === null) {
    sub.textContent = 'Memuat…';
    el.innerHTML = '<div class="skeleton rp-skel"></div><div class="skeleton rp-skel"></div>';
    return;
  }

  sub.textContent = `${state.reports.length} laporan terbaru`;

  const q = state.qLaporan.trim().toLowerCase();
  const list = state.reports.filter((r) => {
    if (state.filter === 'BARU' && r.status !== 'BARU') return false;
    if (state.filter === 'SELESAI' && r.status !== 'SELESAI') return false;
    if (state.filter === 'LAIN' && (r.status === 'BARU' || r.status === 'SELESAI')) return false;
    if (!q) return true;
    return [r.nama_pelanggan, r.id_pelanggan, r.alamat, r.petugas]
      .filter(Boolean).some((v) => String(v).toLowerCase().includes(q));
  });

  if (!list.length) {
    el.innerHTML = `<div class="empty">
      <span class="empty-ic">${SVG.file}</span>
      <p class="empty-title display">${state.reports.length ? 'Tidak ada hasil' : 'Belum ada laporan masuk'}</p>
      <p class="empty-sub">${state.reports.length
        ? 'Coba ubah kata kunci atau filter status.'
        : 'Laporan dari Aplikasi Petugas (foto + lokasi + data pelanggan) akan tampil otomatis di sini.'}</p>
    </div>`;
    return;
  }

  el.innerHTML = list.map((r, i) =>
    reportCard(r).replace('class="rp-card"', `class="rp-card" style="animation-delay:${Math.min(i * 0.04, 0.3)}s"`)).join('');
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

/* ============================================================
   OBROLAN — daftar (dengan preview pesan terakhir)
   ============================================================ */
async function loadPreviews() {
  try {
    const d = await api('/api/chat/messages?scope=global&after=0&limit=1');
    const last = (d.messages ?? [])[0];
    if (last) state.previews.global = { body: last.body, created_at: last.created_at, sender_nama: last.sender_nama };
  } catch { /* diamkan */ }
  if (!state.petugas) return;
  await Promise.allSettled(state.petugas.filter((p) => !p.admin).map(async (p) => {
    try {
      const d = await api(`/api/chat/messages?scope=private&a=${p.id}&b=admin&after=0&limit=1`);
      const last = (d.messages ?? [])[0];
      if (last) state.previews[`p${p.id}`] = { body: last.body, created_at: last.created_at, sender_nama: last.sender_nama };
    } catch { /* diamkan */ }
  }));
  renderChatList();
}

function previewLine(key, fallback) {
  const pv = state.previews[key];
  if (!pv) return fallback;
  const who = pv.sender_nama ? `${pv.sender_nama}: ` : '';
  return `${who}${pv.body}`;
}

function renderChatList() {
  const el = $('chat-list');
  if (!state.petugas) {
    el.innerHTML = '<div class="skeleton ci-skel"></div><div class="skeleton ci-skel"></div>';
    return;
  }

  const selCls = (k) => {
    if (!state.conv) return '';
    if (state.conv.kind === 'global' && k === 'global') return ' sel';
    if (state.conv.kind === 'private' && k === state.conv.key) return ' sel';
    return '';
  };

  const pvG = state.previews.global;
  const items = [`<button class="chat-item${selCls('global')}" data-conv="global">
    <span class="pinned-av">${SVG.pin}</span>
    <span class="cmain">
      <span class="cname"><b>Diskusi Petugas</b><span class="grup-chip">GRUP</span></span>
      <span class="cprev">${esc(previewLine('global', 'Semua petugas & admin'))}</span>
    </span>
    <span class="ctime">${pvG ? esc(fmtClock(pvG.created_at)) : ''}</span>
  </button>`];

  items.push('<p class="list-label">PESAN PRIBADI</p>');

  const dmList = state.petugas.filter((x) => !x.admin);
  if (!dmList.length) {
    items.push('<p class="cempty">Belum ada petugas terdaftar. Pesan pribadi tampil di sini setelah petugas mendaftar lewat aplikasi.</p>');
  }
  for (const p of dmList) {
    const key = `p${p.id}`;
    const pv = state.previews[key];
    items.push(`<button class="chat-item${selCls(key)}" data-conv="${p.id}">
      ${avatarHtml(p.nama, 'sm', p.online)}
      <span class="cmain">
        <span class="cname"><b>${esc(p.nama)}</b></span>
        <span class="cprev">${esc(pv ? pv.body : (p.online ? 'Online' : `Aktif ${relTime(p.last_seen_at)}`))}</span>
      </span>
      <span class="ctime">${pv ? esc(fmtClock(pv.created_at)) : ''}</span>
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
    const p = (state.petugas ?? []).find((x) => String(x.id) === v);
    openConv({ kind: 'private', id: Number(v), nama: p ? p.nama : 'Petugas', key: `p${v}` });
  }
});

/* ============================================================
   OBROLAN — percakapan
   ============================================================ */
function convSubtitle() {
  if (!state.conv) return '';
  if (state.conv.kind === 'global') {
    const n = (state.petugas?.filter((x) => !x.admin).length ?? 0) + 1; // + admin
    const on = state.petugas?.filter((x) => x.online).length ?? 0;
    return `${n} anggota · ${on} online`;
  }
  const p = (state.petugas ?? []).find((x) => x.id === state.conv.id);
  if (!p) return 'Petugas';
  return p.online ? 'Online' : `Aktif ${relTime(p.last_seen_at)}`;
}

function renderConvHeader() {
  const c = state.conv;
  if (!c) return;
  if (c.kind === 'global') {
    $('conv-title').textContent = 'Diskusi Petugas';
    $('conv-avatar').className = 'pinned-av sm-pinned';
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

  $('conv-empty').style.display = 'none';
  $('conv-active').hidden = false;
  $('chat-wrap').classList.add('show-conv');
  document.body.classList.add('conv-open');
  $('chat-wrap').classList.add('tall');
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
  $('chat-wrap').classList.remove('tall');
  document.body.classList.remove('conv-open');
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
      <div class="bubble">${esc(m.body)}<span class="bubble-time">${esc(fmtClock(m.created_at))}</span></div>
    </div></div>`;
  }
  box.innerHTML = html;
  if (stick || nearBottom) box.scrollTop = box.scrollHeight;
}

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
    state.previews[state.conv.key] = {
      body: d.message.body, created_at: d.message.created_at, sender_nama: 'Imam Sahal F.',
    };
    renderMsgs(true);
    renderChatList();
  } catch (err) {
    toast('Pesan gagal terkirim. Coba lagi.');
  } finally {
    state.sending = false;
    $('btn-send').disabled = false;
    input.focus();
  }
});

/* ============================================================
   LOOP & MULAI
   ============================================================ */
setInterval(() => {
  state.tick++;
  if (state.tick % 2 === 0) loadAll();               // 10 dtk
  if (state.tab === 'obrolan') {
    pollConv(false);                                  // 5 dtk
    if (state.tick % 6 === 0) loadPreviews();         // 30 dtk
  }
  if (state.tab === 'petugas' || state.tab === 'laporan') renderSync();
}, 5000);

switchTab('petugas');
loadAll();
