/* ============================================================
   PLN ADMIN — exporters.js
   Pembuat berkas Excel (.xlsx) & PDF murni di browser
   TANPA library/CDN apa pun:
   - xlsx : ZIP (metode store) + Open XML minimal
   - pdf  : PDF 1.4 teks (Helvetica bawaan, A4 landscape)
   Dipakai oleh app.js: window.PLNExport.xlsx(...) / .pdf(...)
   ============================================================ */
'use strict';

(function () {

  /* ==========================================================
     UTIL UMUM
     ========================================================== */
  function xmlEsc(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;',
    }[c]));
  }

  function colLetter(n) { // 0-based -> A, B, ... AA
    let s = '';
    n = n + 1;
    while (n > 0) {
      const r = (n - 1) % 26;
      s = String.fromCharCode(65 + r) + s;
      n = Math.floor((n - 1) / 26);
    }
    return s;
  }

  function pad2(n) { return String(n).padStart(2, '0'); }

  function fmtTanggal(d) { // dd/mm/yyyy
    return `${pad2(d.getDate())}/${pad2(d.getMonth() + 1)}/${d.getFullYear()}`;
  }
  function fmtJam(d) { // HH:MM
    return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
  }
  function fmtDateTime(d) { // dd/mm/yyyy HH:MM
    return `${fmtTanggal(d)} ${fmtJam(d)}`;
  }

  /* ==========================================================
     ZIP WRITER (metode STORE, nama UTF-8)
     files: [{ name: string, data: Uint8Array }]
     ========================================================== */
  const CRC_T = (function () {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      t[n] = c >>> 0;
    }
    return t;
  })();

  function crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = CRC_T[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  }

  function zipStore(files) {
    const enc = new TextEncoder();
    const chunks = [];
    const central = [];
    let offset = 0;

    for (const f of files) {
      const nameB = enc.encode(f.name);
      const data = f.data;
      const crc = crc32(data);
      const size = data.length;

      const lfh = new Uint8Array(30);
      const dv = new DataView(lfh.buffer);
      dv.setUint32(0, 0x04034b50, true);   // local file header signature
      dv.setUint16(4, 20, true);           // version needed
      dv.setUint16(6, 0x0800, true);       // flag: nama UTF-8
      dv.setUint16(8, 0, true);            // metode: store
      dv.setUint16(10, 0, true);           // jam DOS
      dv.setUint16(12, 0x21, true);        // tanggal DOS (1980-01-01)
      dv.setUint32(14, crc, true);
      dv.setUint32(18, size, true);
      dv.setUint32(22, size, true);
      dv.setUint16(26, nameB.length, true);
      dv.setUint16(28, 0, true);
      chunks.push(lfh, nameB, data);

      const cd = new Uint8Array(46);
      const cv = new DataView(cd.buffer);
      cv.setUint32(0, 0x02014b50, true);   // central directory signature
      cv.setUint16(4, 20, true);
      cv.setUint16(6, 20, true);
      cv.setUint16(8, 0x0800, true);
      cv.setUint16(10, 0, true);
      cv.setUint16(12, 0, true);
      cv.setUint16(14, 0x21, true);
      cv.setUint32(16, crc, true);
      cv.setUint32(20, size, true);
      cv.setUint32(24, size, true);
      cv.setUint16(28, nameB.length, true);
      cv.setUint32(42, offset, true);
      central.push({ cd, nameB });

      offset += 30 + nameB.length + size;
    }

    const cdStart = offset;
    let cdSize = 0;
    for (const c of central) { chunks.push(c.cd, c.nameB); cdSize += c.cd.length + c.nameB.length; }

    const eocd = new Uint8Array(22);
    const ev = new DataView(eocd.buffer);
    ev.setUint32(0, 0x06054b50, true);
    ev.setUint16(8, files.length, true);
    ev.setUint16(10, files.length, true);
    ev.setUint32(12, cdSize, true);
    ev.setUint32(16, cdStart, true);
    chunks.push(eocd);

    return new Blob(chunks, { type: 'application/octet-stream' });
  }

  /* ==========================================================
     XLSX — Open XML minimal
     Cell: number | Date | string
     Style: 0 default, 1 header (bold putih di atas amber),
            2 tanggal (dd/mm/yyyy hh:mm), 3 judul, 4 subjudul
     ========================================================== */
  function xlsxCellXml(ref, v, style) {
    if (v instanceof Date && !Number.isNaN(v.getTime())) {
      const serial = v.getTime() / 86400000 + 25569; // epoch -> serial Excel
      return `<c r="${ref}" s="${style}"><v>${serial.toFixed(10)}</v></c>`;
    }
    if (typeof v === 'number' && Number.isFinite(v)) {
      return `<c r="${ref}"${style ? ` s="${style}"` : ''}><v>${v}</v></c>`;
    }
    const text = v == null ? '' : String(v);
    if (!text) return `<c r="${ref}"${style ? ` s="${style}"` : ''}/>`;
    return `<c r="${ref}"${style ? ` s="${style}"` : ''} t="inlineStr"><is><t xml:space="preserve">${xmlEsc(text)}</t></is></c>`;
  }

  function buildXlsxBytes({ sheet = 'Data', headers = [], widths = [], rows = [], meta = {} }) {
    const headXml = headers.map((h, i) =>
      xlsxCellXml(`${colLetter(i)}4`, h, 1)).join('');

    const bodyXml = rows.map((row, ri) => {
      const rNum = ri + 5;
      const cells = row.map((v, ci) => {
        // Kolom "Tanggal" (index 1) bertipe Date -> gaya tanggal
        const style = v instanceof Date ? 2 : 0;
        return xlsxCellXml(`${colLetter(ci)}${rNum}`, v, style);
      }).join('');
      return `<row r="${rNum}">${cells}</row>`;
    }).join('');

    const colsXml = widths.length
      ? `<cols>${widths.map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`).join('')}</cols>`
      : '';

    const sheetXml =
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
      `<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
      (colsXml ? colsXml : '') +
      `<sheetData>` +
      `<row r="1">${xlsxCellXml('A1', meta.title || '', 3)}</row>` +
      `<row r="2">${xlsxCellXml('A2', meta.subtitle || '', 4)}</row>` +
      `<row r="4">${headXml}</row>` +
      bodyXml +
      `</sheetData></worksheet>`;

    const files = [
      {
        name: '[Content_Types].xml',
        data: new TextEncoder().encode(
          `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
          `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">` +
          `<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
          `<Default Extension="xml" ContentType="application/xml"/>` +
          `<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>` +
          `<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>` +
          `<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>` +
          `</Types>`),
      },
      {
        name: '_rels/.rels',
        data: new TextEncoder().encode(
          `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
          `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
          `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>` +
          `</Relationships>`),
      },
      {
        name: 'xl/workbook.xml',
        data: new TextEncoder().encode(
          `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
          `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
          `<sheets><sheet name="${xmlEsc(sheet)}" sheetId="1" r:id="rId1"/></sheets>` +
          `</workbook>`),
      },
      {
        name: 'xl/_rels/workbook.xml.rels',
        data: new TextEncoder().encode(
          `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
          `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
          `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>` +
          `<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>` +
          `</Relationships>`),
      },
      {
        name: 'xl/styles.xml',
        data: new TextEncoder().encode(
          `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
          `<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
          `<numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy hh:mm"/></numFmts>` +
          `<fonts count="4">` +
          `<font><sz val="10"/><name val="Calibri"/></font>` +
          `<font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>` +
          `<font><b/><sz val="14"/><color rgb="FF1A1205"/><name val="Calibri"/></font>` +
          `<font><i/><sz val="9"/><color rgb="FF98A2B3"/><name val="Calibri"/></font>` +
          `</fonts>` +
          `<fills count="3">` +
          `<fill><patternFill patternType="none"/></fill>` +
          `<fill><patternFill patternType="gray125"/></fill>` +
          `<fill><patternFill patternType="solid"><fgColor rgb="FFF79009"/><bgColor indexed="64"/></patternFill></fill>` +
          `</fills>` +
          `<borders count="1"><border/></borders>` +
          `<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>` +
          `<cellXfs count="5">` +
          `<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>` +
          `<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>` +
          `<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>` +
          `<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>` +
          `<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>` +
          `</cellXfs>` +
          `<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>` +
          `</styleSheet>`),
      },
      {
        name: 'xl/worksheets/sheet1.xml',
        data: new TextEncoder().encode(sheetXml),
      },
    ];

    return zipStore(files);
  }

  /* ==========================================================
     PDF 1.4 murni — teks Helvetica (font bawaan), A4 landscape
     ========================================================== */
  const PDF_CHAR_MAP = {
    '\u2014': '-', '\u2013': '-', '\u2018': "'", '\u2019': "'",
    '\u201c': '"', '\u201d': '"', '\u2026': '...', '\u00a0': ' ',
    '\u2022': '-', '\u2192': '->', '\u2264': '<=', '\u2265': '>=',
  };

  function pdfStr(s) {
    // sanitasi ke WinAnsi/Latin-1 + escape ()\
    let out = '';
    for (const ch of String(s ?? '')) {
      if (PDF_CHAR_MAP[ch] !== undefined) { out += PDF_CHAR_MAP[ch]; continue; }
      const c = ch.codePointAt(0);
      if (ch === '\\') out += '\\\\';
      else if (ch === '(') out += '\\(';
      else if (ch === ')') out += '\\)';
      else if (c < 32 || c === 127) out += ' ';
      else if (c <= 255) out += ch;
      else out += '?';
    }
    return out;
  }

  function wrapPdf(text, maxW, fontSize, bold) {
    // perkiraan lebar karakter Helvetica: ~0.50em reguler, ~0.54em bold
    const k = bold ? 0.54 : 0.50;
    const maxChars = Math.max(3, Math.floor(maxW / (fontSize * k)));
    const words = String(text ?? '').split(/\s+/).filter(Boolean);
    if (!words.length) return [''];
    const lines = [];
    let line = '';
    for (let w of words) {
      while (w.length > maxChars) {          // kata super panjang: pecah paksa
        if (line) { lines.push(line); line = ''; }
        lines.push(w.slice(0, maxChars));
        w = w.slice(maxChars);
      }
      const cand = line ? line + ' ' + w : w;
      if (cand.length <= maxChars) line = cand;
      else { if (line) lines.push(line); line = w; }
    }
    if (line) lines.push(line);
    return lines.slice(0, 8); // batas 8 baris per sel
  }

  function buildPdfBytes({ orientation = 'landscape', headers = [], weights = [], rows = [], meta = {} }) {
    const W = 842, H = 595; // A4 landscape poin
    const M = 28;           // margin
    const FS = 7.2;         // ukuran font isi
    const LH = 9.2;         // tinggi baris teks
    const PADX = 4;

    const usable = W - M * 2;
    const wsum = weights.reduce((a, b) => a + (b || 1), 0) || 1;
    const colW = weights.map((w) => Math.max(18, (usable * (w || 1)) / wsum));

    const ink = '0.10 0.09 0.08';
    const inkSoft = '0.35 0.38 0.42';
    const amberFill = '0.97 0.63 0.07';
    const zebra = '0.955 0.962 0.972';
    const lineCol = '0.88 0.90 0.92';

    // Susun konten tiap halaman (string) dulu, footer menyusul
    const pageBodies = [];
    let cur = [];
    let y = 0;
    let rowIdx = 0;

    const topFirst = H - 74;   // ruang judul di halaman 1
    const topNext = H - 40;

    function push(t) { cur.push(t); }

    function headerRow(topY) {
      push(`${amberFill} rg ${M} ${topY - 20} ${usable} 20 re f`);
      let x = M;
      headers.forEach((h, i) => {
        const lines = wrapPdf(h, colW[i] - PADX * 2, FS, true);
        push(`BT /F2 ${FS} Tf ${ink} rg ${x + PADX} ${topY - 14} Td (${pdfStr(lines[0])}) Tj ET`);
        x += colW[i];
      });
      return topY - 20;
    }

    function drawTitle(first) {
      if (!first) return topNext;
      push(`BT /F2 13 Tf ${ink} rg ${M} ${H - 34} Td (${pdfStr(meta.title || '')}) Tj ET`);
      push(`BT /F1 8 Tf ${inkSoft} rg ${M} ${H - 48} Td (${pdfStr(meta.subtitle || '')}) Tj ET`);
      return topFirst;
    }

    // ---- mulai halaman 1
    y = drawTitle(true);
    y = headerRow(y);

    for (const row of rows) {
      const cellLines = row.map((v, i) => {
        const text = v instanceof Date ? fmtDateTime(v) : (v == null ? '' : String(v));
        return wrapPdf(text, colW[i] - PADX * 2, FS, false);
      });
      const maxLines = Math.max(1, ...cellLines.map((l) => l.length));
      const rowH = Math.max(17, maxLines * LH + 7);

      if (y - rowH < 34) {           // halaman penuh -> lanjut halaman baru
        pageBodies.push(cur);
        cur = [];
        y = drawTitle(false);
        y = headerRow(y);
      }

      // zebra + garis bawah
      if (rowIdx % 2 === 1) push(`${zebra} rg ${M} ${y - rowH} ${usable} ${rowH} re f`);
      push(`${lineCol} RG 0.5 w ${M} ${y - rowH} m ${M + usable} ${y - rowH} l S`);
      rowIdx++;

      let x = M;
      cellLines.forEach((lines, i) => {
        lines.forEach((ln, li) => {
          const ty = y - 12 - li * LH;
          if (ty > y - rowH) push(`BT /F1 ${FS} Tf ${ink} rg ${x + PADX} ${ty} Td (${pdfStr(ln)}) Tj ET`);
        });
        x += colW[i];
      });

      y -= rowH;
    }
    pageBodies.push(cur);

    const total = pageBodies.length;

    // ---- rakit dokumen: 1 catalog, 2 pages, 3 F1, 4 F2, 5.. halaman+stream
    const pageAndStreams = [];
    const kids = [];
    pageBodies.forEach((body, i) => {
      const foot = `Halaman ${i + 1} dari ${total}  \u00b7  ${meta.footer || 'PLN Admin'}`;
      const stream =
        `${body.join('\n')}\n` +
        `BT /F1 7 Tf ${inkSoft} rg ${W / 2 - 60} 18 Td (${pdfStr(foot)}) Tj ET\n`;
      const pageNum = 5 + i * 2;
      kids.push(`${pageNum} 0 R`);
      pageAndStreams.push(
        `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${W} ${H}] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${pageNum + 1} 0 R >>`
      );
      pageAndStreams.push(
        `<< /Length ${stream.length} >>\nstream\n${stream}endstream`
      );
    });

    const finalObjects = [
      `<< /Type /Catalog /Pages 2 0 R >>`,
      `<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${total} >>`,
      `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>`,
      `<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>`,
      ...pageAndStreams,
    ];

    let out = '%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n';
    const offsets = [0];
    finalObjects.forEach((o, i) => {
      offsets[i + 1] = out.length;
      out += `${i + 1} 0 obj\n${o}\nendobj\n`;
    });
    const xrefStart = out.length;
    let xref = `xref\n0 ${finalObjects.length + 1}\n0000000000 65535 f \n`;
    for (let i = 1; i <= finalObjects.length; i++) {
      xref += `${String(offsets[i]).padStart(10, '0')} 00000 n \n`;
    }
    out += xref;
    out += `trailer\n<< /Size ${finalObjects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF`;

    const bytes = new Uint8Array(out.length);
    for (let i = 0; i < out.length; i++) bytes[i] = out.charCodeAt(i) & 0xFF;
    return bytes;
  }

  /* ==========================================================
     API PUBLIK
     ========================================================== */
  globalThis.PLNExport = {
    xlsx(opts) { return buildXlsxBytes(opts); },
    pdf(opts) {
      const bytes = buildPdfBytes(opts);
      return new Blob([bytes], { type: 'application/pdf' });
    },
  };

})();
