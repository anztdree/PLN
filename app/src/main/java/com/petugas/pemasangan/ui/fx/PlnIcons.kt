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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlin.math.cos
import kotlin.math.sin

/* =====================================================================
   Ikon kustom v3.0 — digambar manual dengan Canvas (bukan ikon Material),
   bertema listrik/PLN: petir, colokan, kWh meter, panel MCB, dsb.
   ===================================================================== */

enum class PlnIconKind {
    BOLT, PLUG, METER, PANEL, PIN, CAMERA, CALENDAR, USER, SEARCH,
    CHART, GEAR, SHARE, TRASH, EDIT, PLUS, CHECK, BACK, CHEVRON,
    LIST, MAP, GPS, NOTE
}

/**
 * Ikon elektrik kustom aplikasi.
 * @param tint warna solid (opsional); jika null dipakai gradasi emas listrik.
 */
@Composable
fun PlnIcon(
    kind: PlnIconKind,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 22.dp
) {
    val e = LocalElectric.current
    Canvas(modifier = modifier.size(size)) {
        val strokeW = this.size.minDimension * 0.085f
        if (kind == PlnIconKind.BOLT) {
            val brush = tint?.let { Brush.linearGradient(listOf(it, it)) } ?: e.boltBrush
            drawPath(boltPath(this.size), brush)
        } else {
            drawKind(kind, tint ?: e.yellow, strokeW)
        }
    }
}

private fun DrawScope.drawKind(kind: PlnIconKind, c: Color, sw: Float) {
    val scale = size.minDimension / 100f
    fun fx(v: Float) = v * scale

    fun strokeP(p: Path, width: Float = sw, color: Color = c) =
        drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

    fun newPath(): Path = Path()

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = sw, color: Color = c) {
        val p = newPath()
        p.moveTo(fx(x1), fx(y1))
        p.lineTo(fx(x2), fx(y2))
        strokeP(p, width, color)
    }

    fun dot(x: Float, y: Float, r: Float = 3.5f, color: Color = c) =
        drawCircle(color, fx(r), Offset(fx(x), fx(y)))

    fun roundRectStroke(l: Float, t: Float, r: Float, b: Float, corner: Float = 8f) {
        drawRoundRect(
            color = c,
            topLeft = Offset(fx(l), fx(t)),
            size = Size(fx(r) - fx(l), fx(b) - fx(t)),
            cornerRadius = CornerRadius(fx(corner), fx(corner)),
            style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }

    fun roundRectFill(l: Float, t: Float, r: Float, b: Float, corner: Float = 8f, color: Color = c) {
        drawRoundRect(
            color = color,
            topLeft = Offset(fx(l), fx(t)),
            size = Size(fx(r) - fx(l), fx(b) - fx(t)),
            cornerRadius = CornerRadius(fx(corner), fx(corner))
        )
    }

    fun circleStroke(cx: Float, cy: Float, r: Float) =
        drawCircle(c, fx(r), Offset(fx(cx), fx(cy)), style = Stroke(sw, cap = StrokeCap.Round))

    when (kind) {
        PlnIconKind.BOLT -> Unit // ditangani pemanggil

        PlnIconKind.PLUG -> {
            roundRectStroke(32f, 42f, 68f, 74f, 9f)
            line(42f, 42f, 42f, 24f)
            line(58f, 42f, 58f, 24f)
            val p = newPath()
            p.moveTo(fx(50f), fx(74f))
            p.quadraticBezierTo(fx(50f), fx(88f), fx(50f), fx(94f))
            strokeP(p)
        }

        PlnIconKind.METER -> {
            circleStroke(50f, 52f, 30f)
            line(50f, 52f, 66f, 36f)
            dot(50f, 52f, 4f)
            line(30f, 36f, 34f, 40f)
            line(50f, 26f, 50f, 31f)
            line(70f, 36f, 66f, 40f)
            val p = newPath()
            p.moveTo(fx(34f), fx(72f))
            p.quadraticBezierTo(fx(50f), fx(78f), fx(66f), fx(72f))
            strokeP(p)
        }

        PlnIconKind.PANEL -> {
            roundRectStroke(22f, 18f, 78f, 84f, 11f)
            line(34f, 40f, 66f, 40f)
            roundRectFill(42f, 35f, 50f, 45f, 3f)
            line(34f, 62f, 66f, 62f)
            roundRectFill(56f, 57f, 64f, 67f, 3f)
        }

        PlnIconKind.PIN -> {
            circleStroke(50f, 40f, 23f)
            val p = newPath()
            p.moveTo(fx(36f), fx(58f))
            p.lineTo(fx(50f), fx(86f))
            p.lineTo(fx(64f), fx(58f))
            strokeP(p)
            dot(50f, 40f, 7f)
        }

        PlnIconKind.CAMERA -> {
            roundRectStroke(20f, 35f, 80f, 80f, 11f)
            val p = newPath()
            p.moveTo(fx(42f), fx(35f))
            p.lineTo(fx(46f), fx(27f))
            p.lineTo(fx(60f), fx(27f))
            p.lineTo(fx(64f), fx(35f))
            strokeP(p)
            circleStroke(50f, 57f, 12f)
            dot(71f, 44f, 3f)
        }

        PlnIconKind.CALENDAR -> {
            roundRectStroke(22f, 30f, 78f, 82f, 11f)
            line(22f, 47f, 78f, 47f)
            line(36f, 22f, 36f, 34f)
            line(64f, 22f, 64f, 34f)
            dot(39f, 60f, 3f); dot(50f, 60f, 3f); dot(61f, 60f, 3f)
            dot(39f, 71f, 3f); dot(50f, 71f, 3f)
        }

        PlnIconKind.USER -> {
            circleStroke(50f, 35f, 15f)
            val p = newPath()
            p.moveTo(fx(25f), fx(82f))
            p.cubicTo(fx(25f), fx(62f), fx(75f), fx(62f), fx(75f), fx(82f))
            strokeP(p)
        }

        PlnIconKind.SEARCH -> {
            circleStroke(44f, 44f, 20f)
            line(59f, 59f, 77f, 77f)
        }

        PlnIconKind.CHART -> {
            roundRectFill(24f, 54f, 37f, 80f, 4f)
            roundRectFill(43f, 40f, 56f, 80f, 4f)
            roundRectFill(62f, 26f, 75f, 80f, 4f)
            line(18f, 86f, 82f, 86f)
        }

        PlnIconKind.GEAR -> {
            circleStroke(50f, 50f, 17f)
            dot(50f, 50f, 5f)
            for (i in 0 until 8) {
                val ang = Math.toRadians((i * 45).toDouble())
                val x1 = 50f + (24f * cos(ang)).toFloat()
                val y1 = 50f + (24f * sin(ang)).toFloat()
                val x2 = 50f + (31f * cos(ang)).toFloat()
                val y2 = 50f + (31f * sin(ang)).toFloat()
                line(x1, y1, x2, y2)
            }
        }

        PlnIconKind.SHARE -> {
            circleStroke(29f, 50f, 9f)
            circleStroke(71f, 29f, 9f)
            circleStroke(71f, 71f, 9f)
            line(37f, 45f, 63f, 32f)
            line(37f, 55f, 63f, 68f)
        }

        PlnIconKind.TRASH -> {
            line(26f, 32f, 74f, 32f)
            val p = newPath()
            p.moveTo(fx(42f), fx(32f))
            p.lineTo(fx(42f), fx(24f))
            p.lineTo(fx(58f), fx(24f))
            p.lineTo(fx(58f), fx(32f))
            strokeP(p)
            roundRectStroke(31f, 36f, 69f, 82f, 8f)
            line(43f, 48f, 43f, 70f)
            line(57f, 48f, 57f, 70f)
        }

        PlnIconKind.EDIT -> {
            val body = newPath()
            body.moveTo(fx(28f), fx(72f))
            body.lineTo(fx(62f), fx(38f))
            strokeP(body, sw * 1.7f)
            val tip = newPath()
            tip.moveTo(fx(66f), fx(34f))
            tip.lineTo(fx(72f), fx(40f))
            strokeP(tip)
            val tail = newPath()
            tail.moveTo(fx(28f), fx(72f))
            tail.lineTo(fx(25f), fx(80f))
            tail.lineTo(fx(33f), fx(77f))
            strokeP(tail, sw * 1.2f)
        }

        PlnIconKind.PLUS -> {
            line(50f, 27f, 50f, 73f, sw * 1.25f)
            line(27f, 50f, 73f, 50f, sw * 1.25f)
        }

        PlnIconKind.CHECK -> {
            val p = newPath()
            p.moveTo(fx(27f), fx(52f))
            p.lineTo(fx(44f), fx(69f))
            p.lineTo(fx(75f), fx(35f))
            strokeP(p, sw * 1.15f)
        }

        PlnIconKind.BACK -> {
            val p = newPath()
            p.moveTo(fx(57f), fx(26f))
            p.lineTo(fx(33f), fx(50f))
            p.lineTo(fx(57f), fx(74f))
            strokeP(p, sw * 1.15f)
        }

        PlnIconKind.CHEVRON -> {
            val p = newPath()
            p.moveTo(fx(42f), fx(30f))
            p.lineTo(fx(63f), fx(50f))
            p.lineTo(fx(42f), fx(70f))
            strokeP(p, sw * 1.15f)
        }

        PlnIconKind.LIST -> {
            dot(33f, 32f, 4f); dot(33f, 50f, 4f); dot(33f, 68f, 4f)
            line(46f, 32f, 77f, 32f)
            line(46f, 50f, 77f, 50f)
            line(46f, 68f, 77f, 68f)
        }

        PlnIconKind.MAP -> {
            val p = newPath()
            p.moveTo(fx(26f), fx(32f))
            p.lineTo(fx(26f), fx(78f))
            p.lineTo(fx(44f), fx(70f))
            p.lineTo(fx(60f), fx(78f))
            p.lineTo(fx(74f), fx(70f))
            p.lineTo(fx(74f), fx(24f))
            p.lineTo(fx(60f), fx(32f))
            p.lineTo(fx(44f), fx(24f))
            p.close()
            strokeP(p)
            line(44f, 24f, 44f, 70f)
            line(60f, 32f, 60f, 78f)
            dot(50f, 46f, 5f)
        }

        PlnIconKind.GPS -> {
            circleStroke(50f, 50f, 17f)
            line(50f, 24f, 50f, 33f)
            line(50f, 67f, 50f, 76f)
            line(24f, 50f, 33f, 50f)
            line(67f, 50f, 76f, 50f)
            dot(50f, 50f, 4.5f)
        }

        PlnIconKind.NOTE -> {
            roundRectStroke(26f, 22f, 74f, 80f, 9f)
            line(36f, 42f, 64f, 42f)
            line(36f, 54f, 64f, 54f)
            line(36f, 66f, 54f, 66f)
        }
    }
}
