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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlin.math.cos
import kotlin.math.sin

/* =====================================================================
   Ikon kustom "Electric Premium" — SATU BAHASA VISUAL SINKRON:
   setiap ikon dibangun dari resep yang sama di grid 100x100:
     1. BADAN UTAMA  -> bentuk penuh (filled) gradasi emas
     2. DETAIL FUNGSI-> TEPAT satu elemen aksen cyan
     3. KILAU        -> titik/lelehan putih kecil (glint)
   Tidak ada lagi campuran ikon outline vs filled — semua satu keluarga.
   ===================================================================== */

enum class PlnIconKind {
    BOLT, PLUG, METER, PANEL, PIN, CAMERA, CALENDAR, USER, SEARCH,
    CHART, GEAR, SHARE, TRASH, EDIT, PLUS, CHECK, BACK, CHEVRON,
    LIST, MAP, GPS, NOTE
}

/**
 * Ikon elektrik kustom aplikasi.
 * @param tint warna solid (mode monokrom, dipakai di atas tombol emas dsb.);
 *   jika null -> mode duotone: gradasi emas + aksen cyan + glow + kilau.
 * @param shadow bayangan offset di belakang ikon (mode mono) supaya
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
    val e = LocalElectric.current
    val mono = tint != null
    val mainBrush = tint?.let { SolidColor(it) } ?: e.goldBrush
    val accent = tint ?: e.cyan

    Canvas(modifier = modifier.size(size)) {
        val sw = this.size.minDimension * 0.105f
        // glow lembut di belakang ikon (hanya mode duotone)
        if (!mono) {
            drawCircle(
                Brush.radialGradient(
                    listOf(e.yellow.copy(alpha = 0.36f), Color.Transparent)
                ),
                radius = this.size.minDimension * 0.64f,
                center = center
            )
        }
        if (mono && shadow) {
            // bayangan bentuk ikon: offset turun, gelap lembut -> ikon menonjol
            translate(0f, this.size.minDimension * 0.030f) {
                val sh = SolidColor(Color.Black.copy(alpha = 0.38f))
                if (kind == PlnIconKind.BOLT) {
                    drawPath(boltPath(this.size), sh)
                } else {
                    drawKind(kind, sh, Color.Black.copy(alpha = 0.38f), sw, true)
                }
            }
        }
        if (kind == PlnIconKind.BOLT) {
            // outline gelap tipis di belakang bolt agar tegas di latar apapun
            drawPath(
                boltPath(this.size),
                SolidColor(Color(0xFF1A1206).copy(alpha = if (mono) 0f else 0.60f)),
                style = Stroke(
                    width = this.size.minDimension * 0.045f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(boltPath(this.size), mainBrush)
            if (!mono) {
                // kilau "hot core" di kiri-atas sangkar petir
                scale(0.55f, pivot = Offset(this.size.width * 0.46f, this.size.height * 0.40f)) {
                    drawPath(boltPath(this.size), SolidColor(Color.White.copy(alpha = 0.60f)))
                }
            }
        } else {
            drawKind(kind, mainBrush, accent, sw, mono)
        }
    }
}

private fun DrawScope.drawKind(
    kind: PlnIconKind,
    main: Brush,
    accent: Color,
    sw: Float,
    mono: Boolean
) {
    val scale = size.minDimension / 100f
    val acc = SolidColor(accent)
    // Di mode bayangan (mono) SEMUA detail menjadi siluet seragam gelap.
    val whiteHi = if (mono) accent else Color.White.copy(alpha = 0.90f)
    val whiteSoft = if (mono) accent else Color.White.copy(alpha = 0.55f)

    fun fx(v: Float) = v * scale
    fun newPath(): Path = Path()

    fun strokeP(p: Path, width: Float = sw, brush: Brush = main) =
        drawPath(p, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = sw, brush: Brush = main) {
        val p = newPath()
        p.moveTo(fx(x1), fx(y1))
        p.lineTo(fx(x2), fx(y2))
        strokeP(p, width, brush)
    }

    fun lineC(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = sw) =
        line(x1, y1, x2, y2, width, acc)

    fun arcC(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, width: Float = sw) =
        drawArc(
            brush = acc,
            topLeft = Offset(fx(cx - r), fx(cy - r)),
            size = Size(fx(r) * 2f, fx(r) * 2f),
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width, cap = StrokeCap.Round)
        )

    fun dot(x: Float, y: Float, r: Float = 3.5f, color: Color = accent) =
        drawCircle(color, fx(r), Offset(fx(x), fx(y)))

    fun glint(x: Float, y: Float, r: Float = 2.2f) {
        if (!mono) drawCircle(Color.White.copy(alpha = 0.92f), fx(r), Offset(fx(x), fx(y)))
    }

    fun rrFill(l: Float, t: Float, r: Float, b: Float, corner: Float = 8f, brush: Brush = main) =
        drawRoundRect(
            brush = brush,
            topLeft = Offset(fx(l), fx(t)),
            size = Size(fx(r) - fx(l), fx(b) - fx(t)),
            cornerRadius = CornerRadius(fx(corner), fx(corner))
        )

    fun circleFill(cx: Float, cy: Float, r: Float, brush: Brush = main) =
        drawCircle(brush, fx(r), Offset(fx(cx), fx(cy)))

    fun circleStroke(cx: Float, cy: Float, r: Float, width: Float = sw, brush: Brush = main) =
        drawCircle(brush, fx(r), Offset(fx(cx), fx(cy)), style = Stroke(width, cap = StrokeCap.Round))

    fun fillP(p: Path, brush: Brush = main) = drawPath(p, brush)

    when (kind) {
        PlnIconKind.BOLT -> Unit // ditangani pemanggil

        /* ---- Colokan: badan emas penuh + pin cyan + kabel ---- */
        PlnIconKind.PLUG -> {
            rrFill(28f, 38f, 72f, 70f, 12f)
            rrFill(33f, 43f, 45f, 50f, 4f, SolidColor(whiteSoft))
            lineC(40f, 38f, 40f, 22f, sw * 1.15f)
            lineC(60f, 38f, 60f, 22f, sw * 1.15f)
            val cord = newPath()
            cord.moveTo(fx(50f), fx(70f))
            cord.quadraticTo(fx(50f), fx(84f), fx(50f), fx(90f))
            strokeP(cord)
            dot(50f, 94f, 3.2f)
            glint(38f, 46f, 2.0f)
        }

        /* ---- kWh meter: dial emas penuh + busur ukur cyan + jarum putih ---- */
        PlnIconKind.METER -> {
            rrFill(22f, 24f, 78f, 82f, 14f)
            arcC(50f, 56f, 19f, 195f, 150f, sw * 1.05f)
            line(50f, 56f, 65f, 42f, sw * 1.15f, SolidColor(whiteHi))
            dot(50f, 56f, 4.2f, whiteHi)
            glint(36f, 33f, 2.2f)
        }

        /* ---- Panel MCB: bodi penuh + dua slot saklar cyan ---- */
        PlnIconKind.PANEL -> {
            rrFill(22f, 16f, 78f, 86f, 12f)
            rrFill(31f, 26f, 69f, 40f, 6f, acc)
            rrFill(31f, 50f, 69f, 64f, 6f, acc)
            line(38f, 33f, 58f, 33f, sw * 1.0f, SolidColor(whiteHi))
            line(46f, 57f, 62f, 57f, sw * 1.0f, SolidColor(whiteHi))
            rrFill(31f, 72f, 69f, 77f, 2f, SolidColor(whiteSoft))
            glint(27f, 21f, 2.0f)
        }

        /* ---- Pin lokasi: drop emas penuh + cincin cyan + inti putih ---- */
        PlnIconKind.PIN -> {
            circleFill(50f, 42f, 24f)
            val tail = newPath()
            tail.moveTo(fx(30f), fx(56f))
            tail.lineTo(fx(50f), fx(88f))
            tail.lineTo(fx(70f), fx(56f))
            tail.close()
            fillP(tail)
            circleStroke(50f, 42f, 12f, sw * 1.0f, acc)
            dot(50f, 42f, 4.2f, whiteHi)
            glint(41f, 32f, 2.2f)
        }

        /* ---- Kamera: bodi penuh + ring lensa putih + pupil cyan ---- */
        PlnIconKind.CAMERA -> {
            val hump = newPath()
            hump.moveTo(fx(38f), fx(34f))
            hump.lineTo(fx(44f), fx(24f))
            hump.lineTo(fx(62f), fx(24f))
            hump.lineTo(fx(68f), fx(34f))
            hump.close()
            fillP(hump)
            rrFill(18f, 32f, 82f, 82f, 14f)
            circleStroke(50f, 58f, 15f, sw * 1.05f, SolidColor(whiteHi))
            circleFill(50f, 58f, 7.5f, acc)
            glint(45f, 53f, 2.0f)
            glint(73f, 42f, 2.4f)
        }

        /* ---- Kalender: bodi penuh + header putih + pin cyan + titik jadwal ---- */
        PlnIconKind.CALENDAR -> {
            rrFill(22f, 28f, 78f, 84f, 12f)
            rrFill(22f, 40f, 78f, 47f, 2f, SolidColor(whiteHi))
            lineC(36f, 20f, 36f, 32f, sw * 1.15f)
            lineC(64f, 20f, 64f, 32f, sw * 1.15f)
            val dotsCol = if (mono) accent else whiteHi
            dot(39f, 60f, 3.2f, dotsCol); dot(50f, 60f, 3.2f, dotsCol); dot(61f, 60f, 3.2f, dotsCol)
            dot(39f, 71f, 3.2f, dotsCol); dot(50f, 71f, 3.2f, dotsCol)
            glint(28f, 34f, 2.0f)
        }

        /* ---- Pengguna: kepala + bahu penuh + garis aksen ---- */
        PlnIconKind.USER -> {
            circleFill(50f, 34f, 16f)
            val body = newPath()
            body.moveTo(fx(24f), fx(84f))
            body.cubicTo(fx(24f), fx(60f), fx(76f), fx(60f), fx(76f), fx(84f))
            body.close()
            fillP(body)
            lineC(30f, 92f, 70f, 92f, sw * 0.9f)
            glint(44f, 28f, 2.2f)
        }

        /* ---- Pencarian: lensa emas PENUH + kaca cyan + rim putih ---- */
        PlnIconKind.SEARCH -> {
            circleFill(43f, 43f, 20f)
            if (mono) {
                circleFill(43f, 43f, 20f, acc)
            } else {
                circleFill(43f, 43f, 12f, SolidColor(accent.copy(alpha = 0.30f)))
            }
            circleStroke(43f, 43f, 20f, sw * 1.05f, SolidColor(whiteHi))
            line(59f, 59f, 78f, 78f, sw * 1.35f)
            glint(36f, 36f, 2.2f)
        }

        /* ---- Statistik: 3 batang gradasi + garis tren cyan ---- */
        PlnIconKind.CHART -> {
            rrFill(20f, 54f, 34f, 82f, 5f)
            rrFill(43f, 40f, 57f, 82f, 5f)
            rrFill(66f, 26f, 80f, 82f, 5f)
            rrFill(20f, 54f, 34f, 60f, 5f, SolidColor(whiteSoft))
            rrFill(43f, 40f, 57f, 46f, 5f, SolidColor(whiteSoft))
            rrFill(66f, 26f, 80f, 32f, 5f, SolidColor(whiteSoft))
            lineC(20f, 44f, 42f, 30f, sw * 0.95f)
            lineC(42f, 30f, 62f, 22f, sw * 0.95f)
            dot(70f, 19f, 3.4f)
            rrFill(16f, 88f, 84f, 92f, 2f, acc)
        }

        /* ---- Gir: roda penuh + 8 gigi + lubang aksen ---- */
        PlnIconKind.GEAR -> {
            for (i in 0 until 8) {
                val ang = Math.toRadians((i * 45 + 22.5))
                val x1 = 50f + (23f * cos(ang)).toFloat()
                val y1 = 50f + (23f * sin(ang)).toFloat()
                val x2 = 50f + (31f * cos(ang)).toFloat()
                val y2 = 50f + (31f * sin(ang)).toFloat()
                line(x1, y1, x2, y2, sw * 1.5f)
            }
            circleFill(50f, 50f, 20f)
            circleFill(50f, 50f, 9f, acc)
            dot(50f, 50f, 3.0f, whiteHi)
            glint(42f, 42f, 2.0f)
        }

        /* ---- Bagikan: 3 node penuh + jalur cyan ---- */
        PlnIconKind.SHARE -> {
            lineC(36f, 45f, 64f, 31f, sw * 1.05f)
            lineC(36f, 55f, 64f, 69f, sw * 1.05f)
            circleFill(27f, 50f, 10f)
            circleFill(73f, 26f, 10f)
            circleFill(73f, 74f, 10f)
            glint(24f, 46f, 2.0f)
            glint(70f, 22f, 2.0f)
        }

        /* ---- Tempat sampah: tutup + bodi penuh + gagang cyan ---- */
        PlnIconKind.TRASH -> {
            rrFill(24f, 28f, 76f, 35f, 3.5f)
            val handle = newPath()
            handle.moveTo(fx(42f), fx(28f))
            handle.lineTo(fx(42f), fx(19f))
            handle.lineTo(fx(58f), fx(19f))
            handle.lineTo(fx(58f), fx(28f))
            strokeP(handle, sw * 1.05f, acc)
            rrFill(30f, 41f, 70f, 84f, 10f)
            val slotsCol = if (mono) accent else whiteHi
            line(43f, 50f, 43f, 73f, sw * 1.0f, SolidColor(slotsCol))
            line(57f, 50f, 57f, 73f, sw * 1.0f, SolidColor(slotsCol))
            glint(34f, 45f, 2.0f)
        }

        /* ---- Edit: pensil tebal bergradasi + ujung cyan ---- */
        PlnIconKind.EDIT -> {
            line(32f, 68f, 62f, 38f, sw * 2.0f)
            val tip = newPath()
            tip.moveTo(fx(64f), fx(36f))
            tip.lineTo(fx(74f), fx(26f))
            tip.lineTo(fx(77f), fx(40f))
            tip.close()
            fillP(tip, acc)
            val tail = newPath()
            tail.moveTo(fx(32f), fx(68f))
            tail.lineTo(fx(26f), fx(78f))
            tail.lineTo(fx(36f), fx(75f))
            tail.close()
            fillP(tail, SolidColor(whiteSoft))
        }

        /* ---- Tambah: palang tebal bergradasi + kilau ---- */
        PlnIconKind.PLUS -> {
            line(50f, 26f, 50f, 74f, sw * 1.6f)
            line(26f, 50f, 74f, 50f, sw * 1.6f)
            glint(50f, 50f, 2.4f)
        }

        /* ---- Centang: tebal bergradasi + titik sambung ---- */
        PlnIconKind.CHECK -> {
            val p = newPath()
            p.moveTo(fx(26f), fx(52f))
            p.lineTo(fx(44f), fx(70f))
            p.lineTo(fx(76f), fx(33f))
            strokeP(p, sw * 1.6f)
            glint(44f, 70f, 2.4f)
        }

        /* ---- Kembali: panah + garis aksen modern ---- */
        PlnIconKind.BACK -> {
            val p = newPath()
            p.moveTo(fx(56f), fx(27f))
            p.lineTo(fx(33f), fx(50f))
            p.lineTo(fx(56f), fx(73f))
            strokeP(p, sw * 1.5f)
            lineC(62f, 50f, 74f, 50f, sw * 1.1f)
        }

        /* ---- Lanjut: panah kanan + garis aksen ---- */
        PlnIconKind.CHEVRON -> {
            val p = newPath()
            p.moveTo(fx(42f), fx(29f))
            p.lineTo(fx(63f), fx(50f))
            p.lineTo(fx(42f), fx(71f))
            strokeP(p, sw * 1.5f)
            lineC(70f, 50f, 76f, 50f, sw * 1.1f)
        }

        /* ---- Daftar: bullet cyan penuh + batang emas penuh ---- */
        PlnIconKind.LIST -> {
            dot(31f, 30f, 4.6f); dot(31f, 50f, 4.6f); dot(31f, 70f, 4.6f)
            rrFill(44f, 26f, 80f, 34f, 4f)
            rrFill(44f, 46f, 74f, 54f, 4f)
            rrFill(44f, 66f, 78f, 74f, 4f)
        }

        /* ---- Peta: kertas terlipat 3 + pin aksen ---- */
        PlnIconKind.MAP -> {
            val left = newPath()
            left.moveTo(fx(24f), fx(30f))
            left.lineTo(fx(43f), fx(22f))
            left.lineTo(fx(43f), fx(72f))
            left.lineTo(fx(24f), fx(80f))
            left.close()
            fillP(left)
            val mid = newPath()
            mid.moveTo(fx(43f), fx(22f))
            mid.lineTo(fx(60f), fx(30f))
            mid.lineTo(fx(60f), fx(80f))
            mid.lineTo(fx(43f), fx(72f))
            mid.close()
            fillP(mid, SolidColor(accent.copy(alpha = 0.32f)))
            val right = newPath()
            right.moveTo(fx(60f), fx(30f))
            right.lineTo(fx(78f), fx(22f))
            right.lineTo(fx(78f), fx(72f))
            right.lineTo(fx(60f), fx(80f))
            right.close()
            fillP(right)
            dot(51f, 48f, 5f, whiteHi)
            dot(51f, 48f, 2.2f, accent)
            glint(31f, 34f, 2.0f)
        }

        /* ---- GPS: inti emas penuh + cincin & arah cyan ---- */
        PlnIconKind.GPS -> {
            circleFill(50f, 50f, 15f)
            circleStroke(50f, 50f, 22f, sw * 1.1f, acc)
            line(50f, 20f, 50f, 30f, sw * 1.15f, acc)
            line(50f, 70f, 50f, 80f, sw * 1.15f, acc)
            line(20f, 50f, 30f, 50f, sw * 1.15f, acc)
            line(70f, 50f, 80f, 50f, sw * 1.15f, acc)
            dot(50f, 50f, 4.0f, whiteHi)
        }

        /* ---- Catatan: kertas penuh + lipatan cyan + baris putih ---- */
        PlnIconKind.NOTE -> {
            rrFill(26f, 20f, 74f, 82f, 12f)
            val fold = newPath()
            fold.moveTo(fx(56f), fx(20f))
            fold.lineTo(fx(74f), fx(38f))
            fold.lineTo(fx(56f), fx(38f))
            fold.close()
            fillP(fold, acc)
            val linesCol = if (mono) accent else whiteHi
            line(34f, 52f, 66f, 52f, sw * 0.95f, SolidColor(linesCol))
            line(34f, 63f, 66f, 63f, sw * 0.95f, SolidColor(linesCol))
            line(34f, 74f, 54f, 74f, sw * 0.95f, SolidColor(linesCol))
            glint(32f, 26f, 2.0f)
        }
    }
}
