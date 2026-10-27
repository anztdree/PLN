/* ============================================================
   PLN ADMIN — app.js (vanilla, tanpa framework)
   Polling: petugas/laporan 10 dtk, percakapan aktif 5 dtk.
   ============================================================ */
'use strict';

const $ = (id) => document.getElementById(id);

/* Kunci anti-spam yang sama dengan APK (bukan rahasia, dideploy terbuka sesuai desain) */
const API_KEY = 'PLN-KLTEN-2026-KABEL-9f4a2c';

/* ---------- util ---------- */
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

let toastTimer = null;
function toast(msg) {
  const t = $('toast');
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
}

async function api(path, opts) {
  const res = await fetch(path, { cache: 'no-store', ...opts });
  if (!res.ok && res.status !== 409 && res.status !== 404) {
    throw new Error(`HTTP ${res.status}`);
  }
  return res.json();
}

/* ---------- state ---------- */
const state = {
  tab: 'petugas',
  petugas: [],
  reports: [],
  conv: null,        // {kind:'global'} | {kind:'private', id:'5', nama:'…'}
  msgs: [],
  lastId: 0,
  sending: false,
  tick: 0,
};

/* ============================================================
   TAB
   ============================================================ */
document.querySelectorAll('.bottombar .tab').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
});

function switchTab(tab) {
  state.tab = tab;
  document.querySelectorAll('.bottombar .tab').forEach((b) =>
    b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
  $(`view-${tab}`).classList.add('active');
  if (tab === 'petugas') loadPetugas();
  if (tab === 'laporan') loadReports();
  if (tab === 'obrolan') { loadChatList(); }
  if (tab !== 'obrolan') $('chat-wrap').classList.remove('show-conv');
}

/* ============================================================
   PETUGAS
   ============================================================ */
async function loadPetugas() {
  try {
    const d = await api('/api/petugas');
    state.petugas = d.petugas ?? [];
    const on = d.online_count ?? 0;
    $('petugas-chips').innerHTML = `
      <span class="chip">👥 Total <b>${d.count ?? 0}</b> petugas</span>
      <span class="chip"><span class="dot pulse" style="color:var(--green)"></span> <b>${on}</b> online</span>`;

    const el = $('petugas-list');
    if (!state.petugas.length) {
      el.innerHTML = `<div class="empty"><span class="big">🧑‍🔧</span>
        Belum ada petugas terdaftar.<br>Petugas mendaftar lewat aplikasi di perangkatnya.</div>`;
      return;
    }
    el.innerHTML = state.petugas.map((p) => {
      const init = esc((p.nama || '?').trim().charAt(0).toUpperCase());
      const st = p.online
        ? '<span class="status on"><span class="dot pulse"></span>Online</span>'
        : '<span class="status off"><span class="dot"></span>Offline</span>';
      return `<div class="row-card">
        <div class="avatar">${init}</div>
        <div class="row-main">
          <b>${esc(p.nama)}</b>
          <small>Terdaftar ${relTime(p.created_at)} · Aktif ${relTime(p.last_seen_at)}</small>
        </div>
        ${st}
      </div>`;
    }).join('');
  } catch { errToast(); }
}

/* ============================================================
   LAPORAN
   ============================================================ */
async function loadReports() {
  try {
    const d = await api('/api/reports');
    state.reports = d.reports ?? [];
    $('laporan-chips').innerHTML = `<span class="chip">📋 <b>${d.count ?? 0}</b> laporan (100 terakhir)</span>`;

    const el = $('laporan-list');
    if (!state.reports.length) {
      el.innerHTML = `<div class="empty"><span class="big">📭</span>
        Belum ada laporan masuk.<br>Laporan dari aplikasi petugas akan tampil di sini.</div>`;
      return;
    }

    el.innerHTML = state.reports.map((r) => {
      const badge = r.status === 'BARU' ? '<span class="badge baru">BARU</span>'
        : r.status === 'SELESAI' ? '<span class="badge selesai">SELESAI</span>'
        : `<span class="badge lain">${esc(r.status || '—')}</span>`;

      const photos = (r.photos ?? []).map((ph) =>
        `<img src="/api/photos/${encodeURIComponent(ph.id)}" alt="Foto ${ph.urutan} laporan ${esc(r.nama_pelanggan)}"
              loading="lazy" data-full="/api/photos/${encodeURIComponent(ph.id)}">`).join('');

      let lokasi = '';
      if (r.latitude != null && r.longitude != null && r.latitude !== '' && r.longitude !== '') {
        lokasi = `<div><span class="ico">🧭</span>Koordinat: <b>${Number(r.latitude).toFixed(5)}, ${Number(r.longitude).toFixed(5)}</b></div>`;
      } else if (r.plus_code) {
        lokasi = `<div><span class="ico">🧭</span>Plus Code: <b>${esc(r.plus_code)}</b></div>`;
      }
      const q = r.latitude != null && r.longitude != null && r.latitude !== '' && r.longitude !== ''
        ? `${r.latitude},${r.longitude}` : (r.plus_code || r.alamat || '');
      const mapLink = q ? `<a class="map-link" target="_blank" rel="noopener"
        href="https://maps.google.com/?q=${encodeURIComponent(q)}">📍 Buka di Google Maps</a>` : '';

      return `<article class="report-card">
        <div class="report-top"><b>${esc(r.nama_pelanggan || 'Tanpa nama')}</b>${badge}</div>
        <div class="report-body">
          <div class="report-meta">
            ${r.id_pelanggan ? `<div><span class="ico">🆔</span>ID Pelanggan: <b>${esc(r.id_pelanggan)}</b></div>` : ''}
            ${r.alamat ? `<div><span class="ico">🏠</span>${esc(r.alamat)}</div>` : ''}
            ${lokasi}
            <div><span class="ico">🧑‍🔧</span>Petugas: <b>${esc(r.petugas || '—')}</b> · ${relTime(r.created_at)} (${fmtClock(r.created_at)})</div>
          </div>
          ${r.catatan ? `<div class="catatan">📝 ${esc(r.catatan)}</div>` : ''}
          ${photos ? `<div class="photo-strip">${photos}</div>` : ''}
          ${mapLink}
        </div>
      </article>`;
    }).join('');
  } catch { errToast(); }
}

/* lightbox */
$('laporan-list').addEventListener('click', (e) => {
  const img = e.target.closest('img[data-full]');
  if (!img) return;
  $('lightbox-img').src = img.dataset.full;
  $('lightbox').hidden = false;
});
$('lightbox').addEventListener('click', () => {
  $('lightbox').hidden = true;
  $('lightbox-img').src = '';
});

/* ============================================================
   OBROLAN
   ============================================================ */
async function loadChatList() {
  try {
    const d = await api('/api/petugas');
    state.petugas = d.petugas ?? [];

    const sel = (k) => {
      if (!state.conv) return '';
      if (state.conv.kind === 'global' && k === 'global') return ' sel';
      if (state.conv.kind === 'private' && k === state.conv.id) return ' sel';
      return '';
    };

    const items = [`<button class="chat-item${sel('global')}" data-conv="global">
      <div class="avatar pin">📌</div>
      <div class="chat-item-txt"><b>Obrolan Umum</b><small>Semua petugas &amp; admin</small></div>
      <span class="pinned-tag">GRUP</span>
    </button>`];

    if (!state.petugas.length) {
      items.push(`<div class="empty" style="margin:6px"><span class="big">🧑‍🔧</span>
        Belum ada petugas.<br>Obrolan pribadi tampil di sini setelah ada petugas terdaftar.</div>`);
    }
    for (const p of state.petugas) {
      const init = esc((p.nama || '?').trim().charAt(0).toUpperCase());
      const dot = p.online ? '<span class="dot pulse" style="color:var(--green)"></span>'
                           : '<span class="dot" style="color:var(--muted)"></span>';
      items.push(`<button class="chat-item${sel(String(p.id))}" data-conv="${p.id}">
        <div class="avatar">${init}</div>
        <div class="chat-item-txt"><b>${esc(p.nama)}</b><small>${p.online ? 'Online' : `Aktif ${relTime(p.last_seen_at)}`}</small></div>
        ${dot}
      </button>`);
    }
    $('chat-list').innerHTML = items.join('');
  } catch { errToast(); }
}

$('chat-list').addEventListener('click', (e) => {
  const btn = e.target.closest('.chat-item[data-conv]');
  if (!btn) return;
  const v = btn.dataset.conv;
  if (v === 'global') openConv({ kind: 'global' });
  else {
    const p = state.petugas.find((x) => String(x.id) === v);
    openConv({ kind: 'private', id: v, nama: p ? p.nama : 'Petugas' });
  }
});

async function openConv(conv) {
  state.conv = conv;
  state.msgs = [];
  state.lastId = 0;
  $('conv-empty').style.display = 'none';
  $('conv-active').hidden = false;
  $('chat-wrap').classList.add('show-conv');

  if (conv.kind === 'global') {
    $('conv-title').textContent = '📌 Obrolan Umum';
    $('conv-sub').textContent = `👥 ${(state.petugas.length || 0) + 1} anggota · semua petugas & admin`;
    $('conv-input').placeholder = 'Tulis pesan ke grup…';
  } else {
    $('conv-title').textContent = conv.nama;
    $('conv-sub').textContent = '…';
    $('conv-input').placeholder = `Pesan untuk ${conv.nama}…`;
    updateConvSub();
  }

  document.querySelectorAll('.chat-item').forEach((el) => {
    el.classList.toggle('sel',
      (conv.kind === 'global' && el.dataset.conv === 'global') ||
      (conv.kind === 'private' && el.dataset.conv === conv.id));
  });

  $('conv-msgs').innerHTML = '<div class="loading">Memuat pesan…</div>';
  await pollConv(true);
  $('conv-input').focus();
}

function updateConvSub() {
  if (!state.conv || state.conv.kind !== 'private') return;
  const p = state.petugas.find((x) => String(x.id) === state.conv.id);
  if (p) {
    state.conv.nama = p.nama;
    $('conv-title').textContent = p.nama;
    $('conv-sub').textContent = p.online ? '🟢 Online' : `⚪ Aktif ${relTime(p.last_seen_at)}`;
  }
}

async function pollConv(full) {
  if (!state.conv || state.sending) return;
  try {
    const after = full ? 0 : state.lastId;
    let path;
    if (state.conv.kind === 'global') {
      path = `/api/chat/messages?scope=global&after=${after}&limit=200`;
    } else {
      path = `/api/chat/messages?scope=private&a=${encodeURIComponent(state.conv.id)}&b=admin&after=${after}&limit=200`;
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
    renderMsgs(incoming.length > 0);
    updateConvSub();
  } catch { /* polling: diamkan error sesaat */ }
}

function renderMsgs(stick) {
  const box = $('conv-msgs');
  const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 90;
  if (!state.msgs.length) {
    box.innerHTML = `<div class="empty" style="margin:auto"><span class="big">✨</span>
      Belum ada pesan. Mulai percakapan pertama!</div>`;
    return;
  }
  const global = state.conv.kind === 'global';
  box.innerHTML = state.msgs.map((m) => {
    const own = m.sender_type === 'admin';
    const name = !own && global ? `<div class="msg-name">${esc(m.sender_nama)}</div>` : '';
    return `<div class="msg ${own ? 'own' : 'other'}">${name}
      <div class="bubble">${esc(m.body)}<span class="bubble-time">${fmtClock(m.created_at)}</span></div>
    </div>`;
  }).join('');
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
    renderMsgs(true);
  } catch (err) {
    toast('Pesan gagal terkirim. Coba lagi.');
  } finally {
    state.sending = false;
    $('btn-send').disabled = false;
    input.focus();
  }
});

$('btn-back').addEventListener('click', () => {
  $('chat-wrap').classList.remove('show-conv');
  state.conv = null;
  $('conv-active').hidden = true;
  $('conv-empty').style.display = '';
});

/* ============================================================
   LOOP & MULAI
   ============================================================ */
let errShown = false;
function errToast() {
  if (!errShown) {
    toast('Koneksi bermasalah, mencoba lagi…');
    errShown = true;
    setTimeout(() => { errShown = false; }, 8000);
  }
}

setInterval(() => {
  state.tick++;
  if (state.tab === 'petugas' && state.tick % 2 === 0) loadPetugas();
  if (state.tab === 'laporan' && state.tick % 2 === 0) loadReports();
  if (state.tab === 'obrolan') {
    if (state.tick % 2 === 0) loadChatList();
    pollConv(false);
  }
}, 5000);

switchTab('petugas');
