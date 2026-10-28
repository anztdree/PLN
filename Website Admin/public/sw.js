/* ============================================================
   PLN ADMIN — Service Worker (PWA v1.8)
   - App shell (HTML/CSS/JS/ikon) di-cache -> buka ulang nyaris instan.
   - /api/* SELALU jaringan (data laporan tidak pernah di-cache).
   - Navigasi: network-first, fallback ke cache saat offline.
   ============================================================ */
'use strict';

const CACHE = 'pln-admin-v21';
const SHELL = [
  '/',
  '/index.html',
  '/style.css?v=125',
  '/app.js?v=125',
  '/manifest.webmanifest',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => c.addAll(SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/')) return; // API tidak pernah di-cache

  // Navigasi halaman: network-first, fallback cache
  if (req.mode === 'navigate') {
    e.respondWith(
      fetch(req)
        .then((res) => {
          const cp = res.clone();
          caches.open(CACHE).then((c) => c.put('/', cp));
          return res;
        })
        .catch(() => caches.match('/'))
    );
    return;
  }

  // Aset statis: cache-first, isi ulang diam-diam
  e.respondWith(
    caches.match(req).then((hit) => {
      if (hit) {
        // revalidate di belakang
        fetch(req).then((res) => {
          if (res && res.ok) {
            const cp = res.clone();
            caches.open(CACHE).then((c) => c.put(req, cp));
          }
        }).catch(() => {});
        return hit;
      }
      return fetch(req).then((res) => {
        if (res && res.ok) {
          const cp = res.clone();
          caches.open(CACHE).then((c) => c.put(req, cp));
        }
        return res;
      });
    })
  );
});
