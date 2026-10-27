// Uji exporters.js: hasilkan XLSX & PDF contoh lalu tulis ke /tmp
// Jalankan: bun scripts/uji-export.mjs   (dari folder pln-admin)
const src = await Bun.file(new URL('../public/exporters.js', import.meta.url)).text();
(0, eval)(src);
const E = globalThis.PLNExport;
if (!E) { console.error('PLNExport tidak ada'); process.exit(1); }

const headers = ['No', 'Tanggal', 'Nama Pelanggan', 'ID Pelanggan', 'Alamat',
  'Plus Code', 'Koordinat', 'Petugas', 'Status', 'Foto', 'Catatan'];
const widths = [5, 17, 20, 14, 30, 15, 20, 16, 10, 6, 34];

const rows = [];
for (let i = 1; i <= 57; i++) {
  rows.push([
    i,
    new Date(2026, 1, ((i * 3) % 27) + 1, 8 + (i % 9), (i * 7) % 60),
    `Pelanggan ${i} "Kabel" & Co`,          // karakter khusus XML/PDF
    `53210${String(i).padStart(6, '0')}`,
    `Jl. Raya Cicadas No. ${i}, Kabupaten Bandung Barat, Jawa Barat 40581 — (dekat masjid) \\ gang sempit`,
    `8PVH+${String.fromCharCode(65 + (i % 20))}${String.fromCharCode(65 + (i % 26))} Cipeujeuh`,
    `-6.5582${i % 10}, 107.4421${i % 10}`,
    i % 2 ? 'Andri' : 'jarwo',
    i % 3 === 0 ? 'SELESAI' : 'BARU',
    i % 4,
    i === 5
      ? 'Ganti sekring 2A, pemasangan kabel baru sepanjang 12 meter, pembersihan panel budeng yang berdebu tebal sekali dan pemeriksaan tegangan AKD sampai normal kembali.'
      : `Catatan singkat ${i} — tegangan normal 219 V`,
  ]);
}

const meta = {
  title: 'REKAP LAPORAN LAPANGAN — PLN',
  subtitle: 'Periode: Februari 2026 · 57 laporan · Digenerate dari PLN Admin',
  footer: 'PLN Admin — Pusat Kendali Petugas',
};

const x = E.xlsx({ sheet: 'Laporan', headers, widths, rows, meta });
await Bun.write('/tmp/uji.xlsx', x);
console.log('xlsx bytes:', x.size);

const p = E.pdf({ orientation: 'landscape', headers, weights: widths, rows, meta });
await Bun.write('/tmp/uji.pdf', p);
console.log('pdf bytes:', p.size);

// Excel serial check utk tanggal 1 Feb 2026 08:30 lokal
const d = new Date(2026, 1, 1, 8, 30);
const serial = d.getTime() / 86400000 + 25569;
console.log('serial contoh (1 Feb 2026 08:30):', serial.toFixed(5));
