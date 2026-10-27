package com.petugas.pemasangan.ui.fx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlin.math.cos
import kotlin.math.sin

/* =====================================================================
   Ikon "NEON ELECTRIC LINE" — dibuat khusus untuk tema malam aplikasi.
   Setiap ikon adalah tanda neon di atas kaca navy. SATU resep seragam
   untuk SEMUA ikon pada grid 100x100:
     1. GARIS BADAN  -> stroke emas + halo neon 2 lapis (glow)
     2. AKSEN FUNGSI -> TEPAT satu elemen cyan (juga ber-neon)
     3. TITIK PANAS  -> maksimal satu titik putih kecil (spark)
   Tidak ada campuran gaya, tidak ada isi kartun — semuanya garis cahaya.
   ===================================================================== */

enum class PlnIconKind {
    BOLT, PLUG, METER, PANEL, PIN, CAMERA, CALENDAR, USER, SEARCH,
    CHART, GEAR, SHARE, TRASH, EDIT, PLUS, CHECK, SEND, BACK, CHEVRON,
    LIST, MAP, GPS, NOTE, CHAT, CLOCK
}

/**
 * Ikon neon elektrik kustom aplikasi.
 * @param tint warna solid (mode monokrom, dipakai di atas tile emas/cyan dsb.);
 *   jika null -> mode neon duotone: garis emas berhalo + aksen cyan + spark.
 * @param shadow bayangan offset di belakang ikon (mode monokrom) supaya
 *   gambar selalu terbaca jelas di atas tile/latar berwarna apapun.
 */
@Composable
fun PlnIcon(
    kind: PlnIconKind,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 22.dp,
    shadow: Boolean = false
) {
    val env = LocalElectric.current
    val solidMode = tint != null
    val goldB = tint?.let { SolidColor(it) } ?: env.goldBrush
    val cyanC = tint ?: env.cyan

    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension * 0.105f
        // aura lembut di belakang seluruh ikon (hanya mode neon)
        if (!solidMode) {
            drawCircle(
                Brush.radialGradient(
                    listOf(env.yellow.copy(alpha = 0.30f), Color.Transparent)
                ),
                radius = this.size.minDimension * 0.66f,
                center = center
            )
        }
        if (solidMode && shadow) {
            // bayangan bentuk ikon: offset turun, gelap lembut -> ikon menonjol
            translate(0f, this.size.minDimension * 0.030f) {
                val ghost = SolidColor(Color.Black.copy(alpha = 0.38f))
                if (kind == PlnIconKind.BOLT) {
                    drawPath(boltPath(this.size), ghost)
                } else {
                    drawGlyph(kind, ghost, Color.Black.copy(alpha = 0.38f), unit, true)
                }
            }
        }
        if (kind == PlnIconKind.BOLT) {
            val bolt = boltPath(this.size)
            if (solidMode) {
                // mode tile: isi penuh agar tegas di atas tile berwarna
                drawPath(bolt, goldB)
            } else {
                // neon bolt: halo 2 lapis + isi energi lembut + garis inti
                drawPath(bolt, goldB, alpha = 0.20f, style = Stroke(unit * 2.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(bolt, goldB, alpha = 0.38f, style = Stroke(unit * 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(bolt, goldB, alpha = 0.16f)
                drawPath(bolt, goldB, style = Stroke(unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        } else {
            drawGlyph(kind, goldB, cyanC, unit, solidMode)
        }
    }
}

private fun DrawScope.drawGlyph(
    kind: PlnIconKind,
    goldB: Brush,
    cyanC: Color,
    unit: Float,
    solidMode: Boolean
) {
    val cyanB = SolidColor(cyanC)
    val scale = size.minDimension / 100f

    fun px(v: Float) = v * scale

    fun strokeP(p: Path, w: Float, brush: Brush, alpha: Float = 1f) =
        drawPath(p, brush, alpha = alpha, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))

    /** path neon: halo luar + halo dalam + garis inti (mode solid: inti saja) */
    fun glowP(p: Path, w: Float, brush: Brush) {
        if (!solidMode) {
            strokeP(p, w * 2.9f, brush, 0.16f)
            strokeP(p, w * 1.7f, brush, 0.32f)
        }
        strokeP(p, w, brush)
    }

    fun lineGold(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = unit) {
        val p = Path()
        p.moveTo(px(x1), px(y1))
        p.lineTo(px(x2), px(y2))
        glowP(p, w, goldB)
    }

    fun lineCyan(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = unit) {
        val p = Path()
        p.moveTo(px(x1), px(y1))
        p.lineTo(px(x2), px(y2))
        glowP(p, w, cyanB)
    }

    fun pathOpen(points: List<Pair<Float, Float>>, w: Float = unit, brush: Brush = goldB) {
        val p = Path()
        p.moveTo(px(points[0].first), px(points[0].second))
        points.drop(1).forEach { p.lineTo(px(it.first), px(it.second)) }
        glowP(p, w, brush)
    }

    fun pathClosed(points: List<Pair<Float, Float>>, w: Float = unit, brush: Brush = goldB) {
        val p = Path()
        p.moveTo(px(points[0].first), px(points[0].second))
        points.drop(1).forEach { p.lineTo(px(it.first), px(it.second)) }
        p.close()
        glowP(p, w, brush)
    }

    fun curveQuad(x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, w: Float = unit, brush: Brush = goldB) {
        val p = Path()
        p.moveTo(px(x1), px(y1))
        p.quadraticTo(px(cx), px(cy), px(x2), px(y2))
        glowP(p, w, brush)
    }

    fun ringGold(cx: Float, cy: Float, r: Float, w: Float = unit) {
        val c = Offset(px(cx), px(cy))
        val rr = px(r)
        if (!solidMode) {
            drawCircle(goldB, rr, c, 0.16f, style = Stroke(w * 2.9f, cap = StrokeCap.Round))
            drawCircle(goldB, rr, c, 0.32f, style = Stroke(w * 1.7f, cap = StrokeCap.Round))
        }
        drawCircle(goldB, rr, c, style = Stroke(w, cap = StrokeCap.Round))
    }

    fun ringCyan(cx: Float, cy: Float, r: Float, w: Float = unit) {
        val c = Offset(px(cx), px(cy))
        val rr = px(r)
        if (!solidMode) {
            drawCircle(cyanB, rr, c, 0.16f, style = Stroke(w * 2.9f, cap = StrokeCap.Round))
            drawCircle(cyanB, rr, c, 0.32f, style = Stroke(w * 1.7f, cap = StrokeCap.Round))
        }
        drawCircle(cyanB, rr, c, style = Stroke(w, cap = StrokeCap.Round))
    }

    fun boxGold(l: Float, t: Float, r: Float, b: Float, corner: Float = 8f, w: Float = unit) {
        val tl = Offset(px(l), px(t))
        val sz = Size(px(r) - px(l), px(b) - px(t))
        val cr = CornerRadius(px(corner), px(corner))
        if (!solidMode) {
            drawRoundRect(goldB, tl, sz, cr, 0.16f, Stroke(w * 2.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawRoundRect(goldB, tl, sz, cr, 0.32f, Stroke(w * 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawRoundRect(goldB, tl, sz, cr, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    fun boxCyan(l: Float, t: Float, r: Float, b: Float, corner: Float = 8f, w: Float = unit) {
        val tl = Offset(px(l), px(t))
        val sz = Size(px(r) - px(l), px(b) - px(t))
        val cr = CornerRadius(px(corner), px(corner))
        if (!solidMode) {
            drawRoundRect(cyanB, tl, sz, cr, 0.16f, Stroke(w * 2.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawRoundRect(cyanB, tl, sz, cr, 0.32f, Stroke(w * 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawRoundRect(cyanB, tl, sz, cr, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    fun sweepCyan(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, w: Float = unit) {
        val tl = Offset(px(cx - r), px(cy - r))
        val sz = Size(px(r) * 2f, px(r) * 2f)
        if (!solidMode) {
            drawArc(cyanB, start, sweep, false, topLeft = tl, size = sz, alpha = 0.16f, style = Stroke(w * 2.9f, cap = StrokeCap.Round))
            drawArc(cyanB, start, sweep, false, topLeft = tl, size = sz, alpha = 0.32f, style = Stroke(w * 1.7f, cap = StrokeCap.Round))
        }
        drawArc(cyanB, start, sweep, false, topLeft = tl, size = sz, style = Stroke(w, cap = StrokeCap.Round))
    }

    /** titik aksen cyan dengan halo kecil */
    fun dotCyan(x: Float, y: Float, r: Float = 2.8f) {
        val c = Offset(px(x), px(y))
        val rr = px(r)
        if (!solidMode) drawCircle(cyanB, rr * 2.4f, c, 0.22f)
        drawCircle(cyanB, rr, c)
    }

    /** titik panas putih kecil — maksimal satu per ikon (hanya mode neon) */
    fun spark(x: Float, y: Float, r: Float = 1.8f) {
        if (!solidMode) drawCircle(Color.White.copy(alpha = 0.92f), px(r), Offset(px(x), px(y)))
    }

    when (kind) {
        PlnIconKind.BOLT -> Unit // ditangani pemanggil

        /* ---- Colokan: bodi outline + pin cyan + kabel ---- */
        PlnIconKind.PLUG -> {
            boxGold(28f, 38f, 72f, 70f, 12f)
            lineCyan(40f, 38f, 40f, 22f, unit * 1.1f)
            lineCyan(60f, 38f, 60f, 22f, unit * 1.1f)
            curveQuad(50f, 70f, 50f, 84f, 50f, 90f)
            dotCyan(50f, 93f, 3.0f)
            spark(37f, 45f)
        }

        /* ---- kWh meter: dial outline + busur ukur cyan + jarum ---- */
        PlnIconKind.METER -> {
            ringGold(50f, 54f, 26f)
            sweepCyan(50f, 54f, 17f, 200f, 120f, unit)
            lineGold(50f, 54f, 63f, 42f, unit * 1.05f)
            dotCyan(50f, 54f, 2.6f)
            spark(63f, 42f)
        }

        /* ---- Panel MCB: bodi outline + dua saklar cyan + ventilasi ---- */
        PlnIconKind.PANEL -> {
            boxGold(22f, 16f, 78f, 86f, 12f)
            boxCyan(31f, 26f, 69f, 40f, 6f, unit * 0.95f)
            boxCyan(31f, 50f, 69f, 64f, 6f, unit * 0.95f)
            lineGold(38f, 33f, 58f, 33f, unit * 0.95f)
            lineGold(46f, 57f, 62f, 57f, unit * 0.95f)
            lineCyan(31f, 74f, 69f, 74f, unit * 0.9f)
            spark(27f, 22f)
        }

        /* ---- Pin lokasi: drop outline + cincin cyan + inti panas ---- */
        PlnIconKind.PIN -> {
            ringGold(50f, 42f, 22f)
            pathOpen(listOf(32f to 57f, 50f to 86f, 68f to 57f))
            ringCyan(50f, 42f, 8f, unit * 0.95f)
            spark(50f, 42f)
        }

        /* ---- Kamera: bodi outline + lensa emas + pupil cyan ---- */
        PlnIconKind.CAMERA -> {
            pathOpen(listOf(38f to 34f, 44f to 24f, 62f to 24f, 68f to 34f))
            boxGold(18f, 32f, 82f, 82f, 14f)
            ringGold(50f, 58f, 15f)
            dotCyan(50f, 58f, 4.2f)
            spark(72f, 41f)
        }

        /* ---- Kalender: bodi outline + pin cyan + titik jadwal cyan ---- */
        PlnIconKind.CALENDAR -> {
            boxGold(22f, 28f, 78f, 84f, 12f)
            lineGold(22f, 44f, 78f, 44f, unit * 0.95f)
            lineCyan(36f, 20f, 36f, 32f)
            lineCyan(64f, 20f, 64f, 32f)
            dotCyan(38f, 58f, 2.4f); dotCyan(50f, 58f, 2.4f); dotCyan(62f, 58f, 2.4f)
            dotCyan(38f, 70f, 2.4f); dotCyan(50f, 70f, 2.4f)
            spark(28f, 35f)
        }

        /* ---- Pengguna: kepala + bahu outline + garis dasar cyan ---- */
        PlnIconKind.USER -> {
            ringGold(50f, 33f, 15f)
            val shoulders = Path()
            shoulders.moveTo(px(26f), px(82f))
            shoulders.cubicTo(px(26f), px(58f), px(74f), px(58f), px(74f), px(82f))
            glowP(shoulders, unit, goldB)
            lineCyan(30f, 91f, 70f, 91f)
            spark(45f, 29f)
        }

        /* ---- Pencarian: lensa outline + gagang + kilau kaca cyan ---- */
        PlnIconKind.SEARCH -> {
            ringGold(44f, 42f, 20f)
            lineGold(59f, 57f, 77f, 75f, unit * 1.25f)
            sweepCyan(44f, 42f, 12f, 140f, 90f, unit * 0.9f)
            spark(37f, 35f)
        }

        /* ---- Statistik: 3 batang outline + tren cyan + garis dasar ---- */
        PlnIconKind.CHART -> {
            boxGold(20f, 54f, 34f, 82f, 4f)
            boxGold(43f, 40f, 57f, 82f, 4f)
            boxGold(66f, 26f, 80f, 82f, 4f)
            pathOpen(listOf(20f to 44f, 42f to 30f, 62f to 22f), unit * 0.95f, cyanB)
            dotCyan(68f, 20f, 2.8f)
            lineCyan(16f, 88f, 84f, 88f, unit * 0.95f)
            spark(24f, 59f)
        }

        /* ---- Gir: roda outline + 8 gigi + poros cyan ---- */
        PlnIconKind.GEAR -> {
            for (i in 0 until 8) {
                val ang = Math.toRadians(i * 45.0 + 22.5)
                lineGold(
                    50f + (19f * cos(ang)).toFloat(),
                    50f + (19f * sin(ang)).toFloat(),
                    50f + (27f * cos(ang)).toFloat(),
                    50f + (27f * sin(ang)).toFloat(),
                    unit * 1.2f
                )
            }
            ringGold(50f, 50f, 19f)
            ringCyan(50f, 50f, 8f, unit * 0.95f)
            spark(50f, 50f)
        }

        /* ---- Bagikan: 3 node outline + jalur cyan ---- */
        PlnIconKind.SHARE -> {
            ringGold(27f, 50f, 8f)
            ringGold(73f, 26f, 8f)
            ringGold(73f, 74f, 8f)
            lineCyan(35f, 46f, 64f, 31f)
            lineCyan(35f, 54f, 64f, 69f)
            spark(27f, 50f)
        }

        /* ---- Tempat sampah: tutup + gagang cyan + bodi outline ---- */
        PlnIconKind.TRASH -> {
            lineGold(24f, 28f, 76f, 28f, unit * 1.05f)
            pathOpen(listOf(42f to 28f, 42f to 19f, 58f to 19f, 58f to 28f), unit * 0.95f, cyanB)
            boxGold(30f, 34f, 70f, 84f, 10f)
            lineCyan(43f, 46f, 43f, 72f, unit * 0.95f)
            lineCyan(57f, 46f, 57f, 72f, unit * 0.95f)
            spark(34f, 40f)
        }

        /* ---- Edit: pensil diagonal + ujung cyan ---- */
        PlnIconKind.EDIT -> {
            lineGold(31f, 69f, 63f, 37f, unit * 1.5f)
            pathClosed(listOf(64f to 36f, 74f to 26f, 78f to 40f, 68f to 44f), unit * 0.95f, cyanB)
            spark(33f, 67f)
        }

        /* ---- Tambah: palang tebal + titik pusat cyan ---- */
        PlnIconKind.PLUS -> {
            lineGold(50f, 28f, 50f, 72f, unit * 1.5f)
            lineGold(28f, 50f, 72f, 50f, unit * 1.5f)
            dotCyan(50f, 50f, 2.6f)
        }

        /* ---- Centang: tebal + sambungan cyan ---- */
        PlnIconKind.CHECK -> {
            pathOpen(listOf(27f to 52f, 44f to 69f, 75f to 32f), unit * 1.5f)
            dotCyan(44f, 69f, 2.6f)
        }

        /* ---- Kirim: pesawat kertas + jejak cyan ---- */
        PlnIconKind.SEND -> {
            pathOpen(listOf(26f to 48f, 76f to 30f, 58f to 72f, 45f to 53f, 26f to 48f), unit * 1.5f)
            lineCyan(45f, 53f, 63f, 45f, unit * 1.1f)
        }

        /* ---- Kembali: panah + ekor cyan ---- */
        PlnIconKind.BACK -> {
            pathOpen(listOf(55f to 27f, 33f to 50f, 55f to 73f), unit * 1.5f)
            lineCyan(61f, 50f, 74f, 50f, unit * 1.1f)
        }

        /* ---- Lanjut: panah kanan + ekor cyan ---- */
        PlnIconKind.CHEVRON -> {
            pathOpen(listOf(42f to 29f, 63f to 50f, 42f to 71f), unit * 1.5f)
            lineCyan(69f, 50f, 76f, 50f, unit * 1.1f)
        }

        /* ---- Daftar: bullet cyan + garis emas ---- */
        PlnIconKind.LIST -> {
            dotCyan(31f, 30f, 3.4f)
            dotCyan(31f, 50f, 3.4f)
            dotCyan(31f, 70f, 3.4f)
            lineGold(45f, 30f, 78f, 30f, unit * 1.15f)
            lineGold(45f, 50f, 70f, 50f, unit * 1.15f)
            lineGold(45f, 70f, 74f, 70f, unit * 1.15f)
        }

        /* ---- Peta: kertas terlipat outline + pin cyan ---- */
        PlnIconKind.MAP -> {
            pathClosed(
                listOf(
                    24f to 30f, 43f to 22f, 60f to 30f, 78f to 22f,
                    78f to 72f, 60f to 80f, 43f to 72f, 24f to 80f
                )
            )
            lineGold(43f, 22f, 43f, 72f, unit * 0.9f)
            lineGold(60f, 30f, 60f, 80f, unit * 0.9f)
            ringCyan(51f, 48f, 7f, unit * 0.85f)
            dotCyan(51f, 48f, 2.2f)
            spark(28f, 36f)
        }

        /* ---- GPS: inti outline + cincin cyan + 4 arah ---- */
        PlnIconKind.GPS -> {
            ringGold(50f, 50f, 14f)
            ringCyan(50f, 50f, 21f, unit * 0.95f)
            lineCyan(50f, 20f, 50f, 29f)
            lineCyan(50f, 71f, 50f, 80f)
            lineCyan(20f, 50f, 29f, 50f)
            lineCyan(71f, 50f, 80f, 50f)
            dotCyan(50f, 50f, 2.6f)
        }

        /* ---- Catatan: kertas outline + lipatan cyan + baris ---- */
        PlnIconKind.NOTE -> {
            boxGold(26f, 20f, 74f, 82f, 12f)
            pathClosed(listOf(56f to 20f, 74f to 38f, 56f to 38f), unit * 0.95f, cyanB)
            lineGold(34f, 52f, 64f, 52f, unit * 0.9f)
            lineGold(34f, 63f, 64f, 63f, unit * 0.9f)
            lineGold(34f, 74f, 52f, 74f, unit * 0.9f)
            spark(32f, 27f)
        }

        /* ---- Obrolan: gelembung chat dengan ekor + tiga titik cyan ---- */
        PlnIconKind.CHAT -> {
            pathClosed(
                listOf(
                    26f to 28f, 74f to 28f, 74f to 62f, 50f to 62f,
                    36f to 76f, 38f to 62f, 26f to 62f
                ),
                unit * 1.35f
            )
            dotCyan(40f, 45f, 3.2f)
            dotCyan(50f, 45f, 3.2f)
            dotCyan(60f, 45f, 3.2f)
            spark(30f, 32f)
        }

        /* ---- Jam: pesan sedang dikirim (pending) ---- */
        PlnIconKind.CLOCK -> {
            ringGold(50f, 50f, 25f, unit * 1.4f)
            lineGold(50f, 50f, 50f, 33f, unit * 1.35f)
            lineCyan(50f, 50f, 62f, 58f, unit * 1.35f)
        }
    }
}
