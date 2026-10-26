package com.petugas.pemasangan.ui.fx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/* =====================================================================
   Efek "Electric Premium" v3.0 — latar hidup, kaca elegan, glow energi.
   ===================================================================== */

private class Spark {
    val x = Random.nextFloat()
    val speed = 0.55f + Random.nextFloat() * 0.9f
    val phase = Random.nextFloat()
    val r = 1.8f + Random.nextFloat() * 3.4f
    val base = 0.32f + Random.nextFloat() * 0.55f
    val cyan = Random.nextFloat() < 0.35f
    val big = Random.nextFloat() < 0.22f
    val wob = 0.6f + Random.nextFloat() * 1.4f
}

/** Jejak sirkuit Manhattan (kiri-kanan/atas-bawah) + denyut energi yang merambat. */
private class TracePath {
    val cyan = Random.nextFloat() < 0.45f
    val speed = 0.20f + Random.nextFloat() * 0.35f
    val phase = Random.nextFloat()
    val pts: List<Pair<Float, Float>>

    init {
        val p = mutableListOf<Pair<Float, Float>>()
        var x = if (Random.nextBoolean()) 0.04f + Random.nextFloat() * 0.25f else 0.72f + Random.nextFloat() * 0.24f
        var y = 0.04f + Random.nextFloat() * 0.90f
        p += x.coerceIn(0.03f, 0.97f) to y.coerceIn(0.03f, 0.97f)
        var horizontal = Random.nextBoolean()
        repeat(3 + Random.nextInt(3)) {
            val len = 0.15f + Random.nextFloat() * 0.45f
            if (horizontal) x += if (Random.nextBoolean()) len else -len
            else y += if (Random.nextBoolean()) len * 0.75f else -len * 0.75f
            x = x.coerceIn(0.03f, 0.97f)
            y = y.coerceIn(0.03f, 0.97f)
            p += x to y
            horizontal = !horizontal
        }
        pts = p
    }

    private fun segLen(i: Int): Float {
        val (x0, y0) = pts[i]
        val (x1, y1) = pts[i + 1]
        return hypot(x1 - x0, y1 - y0)
    }

    private fun totalLen(): Float {
        var s = 0f
        for (i in 0 until pts.size - 1) s += segLen(i)
        return s
    }

    /** Posisi titik pada fraksi t sepanjang polyline. */
    fun pointAt(t: Float): Pair<Float, Float> {
        val total = totalLen().coerceAtLeast(1e-4f)
        var d = (t % 1f) * total
        for (i in 0 until pts.size - 1) {
            val l = segLen(i)
            if (d <= l) {
                val k = d / l
                val (x0, y0) = pts[i]
                val (x1, y1) = pts[i + 1]
                return (x0 + (x1 - x0) * k) to (y0 + (y1 - y0) * k)
            }
            d -= l
        }
        return pts.last()
    }
}

/**
 * Latar "Electric Premium" v1.0.0 — berlapis, anti-polos:
 * gradasi navy, pita aurora energi miring yang merambat, dua glow besar,
 * grid blueprint (gelap & terang), jejak sirkuit dengan denyut energi
 * yang berjalan di jalurnya, percikan bintang, dan vignette sinematik.
 * [dense] menambah jumlah percikan & sirkuit (dipakai di splash).
 */
@Composable
fun ElectricBackdrop(modifier: Modifier = Modifier, dense: Boolean = false) {
    val e = LocalElectric.current
    val t = rememberInfiniteTransition(label = "backdrop")
    val drift by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift"
    )
    val drift2 by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift2"
    )
    val aur by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart),
        label = "aur"
    )
    val sparks = remember(dense) { List(if (dense) 48 else 34) { Spark() } }
    val traces = remember { List(if (dense) 9 else 7) { TracePath() } }

    Canvas(modifier = modifier) {
        // dasar bergradasi 3 lapis — perbedaan warna terlihat jelas
        drawRect(
            Brush.verticalGradient(
                listOf(
                    e.bg0,
                    if (e.dark) Color(0xFF0D1834) else Color(0xFFFBF6E6),
                    e.bg1
                )
            )
        )

        val r = size.minDimension
        val unit = r / 480f

        // ===== 3 blob glow BESAR & terlihat (emas / cyan / amber) =====
        drawCircle(
            Brush.radialGradient(
                listOf(e.glowYellow.copy(alpha = if (e.dark) 0.32f else 0.38f), Color.Transparent)
            ),
            radius = r * 0.85f,
            center = Offset(
                size.width * (0.08f + 0.16f * drift),
                size.height * (0.04f + 0.05f * drift2)
            )
        )
        drawCircle(
            Brush.radialGradient(
                listOf(e.glowCyan.copy(alpha = if (e.dark) 0.26f else 0.32f), Color.Transparent)
            ),
            radius = r * 0.9f,
            center = Offset(
                size.width * (0.94f - 0.12f * drift2),
                size.height * (0.98f - 0.07f * drift)
            )
        )
        drawCircle(
            Brush.radialGradient(
                listOf(e.amber.copy(alpha = if (e.dark) 0.17f else 0.20f), Color.Transparent)
            ),
            radius = r * 0.8f,
            center = Offset(
                size.width * (0.5f + sin(aur * 2f * PI.toFloat()) * 0.09f),
                size.height * 0.40f
            )
        )

        // ===== aurora: 3 pita miring yang merambat =====
        rotate(degrees = -16f, pivot = center) {
            val ay = size.height * (-0.4f + 1.8f * aur)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        e.glowYellow.copy(alpha = if (e.dark) 0.14f else 0.17f),
                        Color.Transparent
                    )
                ),
                topLeft = Offset(-size.width * 0.3f, ay),
                size = Size(size.width * 1.6f, size.height * 0.34f)
            )
        }
        rotate(degrees = 12f, pivot = center) {
            val ay = size.height * (1.45f - 1.8f * aur)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        e.glowCyan.copy(alpha = if (e.dark) 0.12f else 0.15f),
                        Color.Transparent
                    )
                ),
                topLeft = Offset(-size.width * 0.3f, ay - size.height * 0.30f),
                size = Size(size.width * 1.6f, size.height * 0.30f)
            )
        }
        rotate(degrees = -4f, pivot = center) {
            val ay = size.height * (0.1f + 0.9f * drift2)
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        e.amber.copy(alpha = if (e.dark) 0.09f else 0.11f),
                        Color.Transparent
                    )
                ),
                topLeft = Offset(-size.width * 0.3f, ay - size.height * 0.16f),
                size = Size(size.width * 1.6f, size.height * 0.16f)
            )
        }

        // ===== cincin energi berputar =====
        val ringC1 = Offset(size.width * 0.88f, size.height * 0.12f)
        val rc = size.minDimension * 0.30f
        rotate(degrees = drift * 360f, pivot = ringC1) {
            drawCircle(
                e.yellow.copy(alpha = if (e.dark) 0.16f else 0.20f),
                radius = rc,
                center = ringC1,
                style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f)))
            )
            drawArc(
                e.cyan.copy(alpha = if (e.dark) 0.28f else 0.32f),
                startAngle = 0f, sweepAngle = 75f, useCenter = false,
                topLeft = Offset(ringC1.x - rc, ringC1.y - rc),
                size = Size(rc * 2f, rc * 2f),
                style = Stroke(width = 3.4f, cap = StrokeCap.Round)
            )
        }
        val ringC2 = Offset(size.width * 0.08f, size.height * 0.86f)
        val rc2 = size.minDimension * 0.22f
        rotate(degrees = -drift * 252f, pivot = ringC2) {
            drawArc(
                e.yellow.copy(alpha = if (e.dark) 0.20f else 0.24f),
                startAngle = 40f, sweepAngle = 60f, useCenter = false,
                topLeft = Offset(ringC2.x - rc2, ringC2.y - rc2),
                size = Size(rc2 * 2f, rc2 * 2f),
                style = Stroke(width = 2.8f, cap = StrokeCap.Round)
            )
        }

        // ===== watermark petir raksasa (glow + isi bergradasi) =====
        translate(size.width * 0.50f, size.height * 0.52f) {
            val wp = Size(size.width * 0.46f, size.height * 0.46f)
            drawPath(
                boltPath(wp),
                e.yellow.copy(alpha = if (e.dark) 0.06f else 0.07f),
                style = Stroke(width = 14f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                boltPath(wp),
                Brush.linearGradient(
                    listOf(
                        e.yellow.copy(alpha = if (e.dark) 0.12f else 0.14f),
                        e.amber.copy(alpha = 0.03f)
                    )
                )
            )
        }

        // ===== grid blueprint + tanda + di persimpangan =====
        val step = size.width / 6f
        val gridC = if (e.dark) Color.White.copy(alpha = 0.055f) else Color(0xFF1B2A4A).copy(alpha = 0.075f)
        val plusC = if (e.dark) Color.White.copy(alpha = 0.11f) else Color(0xFF1B2A4A).copy(alpha = 0.14f)
        var gx = step
        while (gx < size.width) {
            drawLine(gridC, Offset(gx, 0f), Offset(gx, size.height), 1f)
            gx += step
        }
        var gy = step
        while (gy < size.height) {
            drawLine(gridC, Offset(0f, gy), Offset(size.width, gy), 1f)
            gy += step
        }
        var px = step
        var pi = 0
        while (px < size.width) {
            var py = step
            var pj = 0
            while (py < size.height) {
                if ((pi + pj) % 2 == 0) {
                    val l = 4f * unit
                    drawLine(plusC, Offset(px - l, py), Offset(px + l, py), 1.2f)
                    drawLine(plusC, Offset(px, py - l), Offset(px, py + l), 1.2f)
                }
                py += step
                pj++
            }
            px += step
            pi++
        }

        // ===== jejak sirkuit + denyut energi merambat (dengan halo) =====
        traces.forEach { tr ->
            val col = if (tr.cyan) e.cyan else e.yellow
            val alpha = if (e.dark) 0.26f else 0.22f
            var prev: Offset? = null
            tr.pts.forEach { (ux, uy) ->
                val pt = Offset(ux * size.width, uy * size.height)
                prev?.let { pv -> drawLine(col.copy(alpha = alpha), pv, pt, 1.8f) }
                drawCircle(col.copy(alpha = alpha * 1.9f), 3.2f * unit, pt)
                prev = pt
            }
            val pos = tr.pointAt((drift * tr.speed + tr.phase) % 1f)
            val pc = Offset(pos.first * size.width, pos.second * size.height)
            drawCircle(col.copy(alpha = 0.14f), 16f * unit, pc)
            drawCircle(col.copy(alpha = 0.45f), 8.5f * unit, pc)
            drawCircle(Color.White.copy(alpha = 0.95f), 3f * unit, pc)
        }

        // ===== percikan bintang dengan halo =====
        sparks.forEach { s ->
            val p = (drift * s.speed + s.phase) % 1f
            val sy = size.height * (1.1f - p * 1.2f)
            val sx = size.width * s.x + sin(p * 2f * PI.toFloat() * s.wob) * size.width * 0.02f
            val a = sin(p * 2f * PI.toFloat() * 2f + s.phase * 6f)
                .coerceIn(0f, 1f) * s.base * if (e.dark) 1.15f else 0.85f
            val c = if (s.cyan) e.cyan else e.yellow
            drawCircle(c.copy(alpha = a * 0.28f), s.r * 2.8f * unit, Offset(sx, sy))
            drawCircle(c.copy(alpha = a), s.r * unit, Offset(sx, sy))
            if (s.big && a > 0.25f) {
                val l = s.r * 3.2f * unit
                drawLine(c.copy(alpha = a * 0.85f), Offset(sx - l, sy), Offset(sx + l, sy), 1.4f)
                drawLine(c.copy(alpha = a * 0.85f), Offset(sx, sy - l), Offset(sx, sy + l), 1.4f)
            }
        }

        // ===== gelombang energi di dasar layar (2 lapis, beranimasi) =====
        for (w in 0..1) {
            val amp = (14f + w * 9f) * unit
            val yBase = size.height * (0.895f + w * 0.05f)
            val ph = drift * 2f * PI.toFloat() * (if (w == 0) 1f else -1.4f)
            val colW = if (w == 0) e.yellow else e.cyan
            val wave = Path()
            var wx = 0f
            while (wx <= size.width) {
                val wy = yBase + sin(wx / size.width * 4f * PI.toFloat() + ph + w * 1.3f) * amp
                if (wx == 0f) wave.moveTo(wx, wy) else wave.lineTo(wx, wy)
                wx += size.width / 48f
            }
            drawPath(
                wave,
                colW.copy(alpha = if (e.dark) 0.32f else 0.36f),
                style = Stroke(width = 2.2f, cap = StrokeCap.Round)
            )
        }

        // ---- vignette sinematik: pinggir gelap, fokus ke tengah ----
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    if (e.dark) Color(0xFF020409).copy(alpha = 0.50f)
                    else Color(0xFFD8CB9E).copy(alpha = 0.30f)
                ),
                center = center,
                radius = size.minDimension * 0.95f
            )
        )
    }
}

/** Sangkar petir (bolt) bergradasi emas dengan glow berdenyut. */
@Composable
fun BoltMark(size: Dp, modifier: Modifier = Modifier, glow: Boolean = true, pulse: Boolean = true) {
    val e = LocalElectric.current
    val t = rememberInfiniteTransition(label = "boltMark")
    val flash by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "flash"
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (glow) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val g = if (pulse) 0.26f + 0.18f * flash else 0.34f
                drawCircle(
                    Brush.radialGradient(listOf(e.glowYellow.copy(alpha = g), Color.Transparent)),
                    radius = this.size.minDimension * 0.72f
                )
            }
        }
        Canvas(modifier = Modifier.size(size * 0.62f)) {
            drawPath(boltPath(this.size), e.boltBrush)
        }
    }
}

/** Sangkar petir — dipakai juga oleh PlnIcons. */
fun boltPath(s: Size) = Path().apply {
    val w = s.width / 100f
    val h = s.height / 100f
    moveTo(58 * w, 3 * h)
    lineTo(16 * w, 57 * h)
    lineTo(41 * w, 57 * h)
    lineTo(33 * w, 97 * h)
    lineTo(84 * w, 39 * h)
    lineTo(53 * w, 39 * h)
    lineTo(66 * w, 3 * h)
    close()
}

/** Panel kaca: latar translucent + border gradasi emas-cyan. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val e = LocalElectric.current
    Box(modifier = modifier.clip(shape)) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                Brush.radialGradient(
                    colors = listOf(e.glowYellow.copy(alpha = 0.055f), Color.Transparent),
                    center = Offset(size.width * 0.12f, 0f),
                    radius = size.width * 0.95f
                )
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(e.glass, shape)
                .border(1.dp, e.borderBrush, shape)
        )
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/**
 * Judul gradasi emas + kilau berjalan (shimmer) — kontras aman di tema
 * gelap maupun terang: gradasi adaptif + bayangan lembut di bawah teks,
 * jadi teks tidak pernah "tenggelam"/bertabrakan dengan latar.
 */
@Composable
fun GradientHeadline(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    shimmer: Boolean = true
) {
    val e = LocalElectric.current
    val t = rememberInfiniteTransition(label = "headline")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    // Gradasi adaptif tema: terang & berkilau di gelap, cokelat-emas pekat
    // di tema terang agar tetap terbaca di latar ivory.
    val baseColors = if (e.dark)
        listOf(Color(0xFFFFE95A), Color(0xFFFFFFFF), Color(0xFFFFC93C))
    else
        listOf(Color(0xFFB9820B), Color(0xFF6E4600), Color(0xFF9C6E06))
    val bandAlpha = if (e.dark) 0.65f else 0.32f

    Box(modifier = modifier) {
        // bayangan lembut — memisahkan teks dari latar apapun
        Text(
            text = text,
            style = style.copy(
                color = if (e.dark) Color.Black.copy(alpha = 0.50f) else Color(0x5C6B4A08)
            ),
            modifier = Modifier.offset(y = 1.5.dp)
        )
        Text(
            text = text,
            style = style,
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.linearGradient(baseColors),
                        blendMode = BlendMode.SrcAtop
                    )
                    if (shimmer) {
                        val band = p * 2.4f - 0.7f
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = bandAlpha),
                                    Color.Transparent
                                ),
                                start = Offset(size.width * (band - 0.18f), 0f),
                                end = Offset(size.width * (band + 0.18f), size.height)
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
                }
        )
    }
}

/** Titik status berdenyut (badge "Belum Selesai"). */
@Composable
fun PulseDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulseDot")
    val a by t.animateFloat(
        initialValue = 0.95f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "a"
    )
    Box(
        modifier = modifier
            .size(size * 2.2f)
            .drawBehind {
                drawCircle(color.copy(alpha = 0.22f))
                drawCircle(color.copy(alpha = a), radius = this.size.minDimension / 4.4f)
            },
        contentAlignment = Alignment.Center
    ) {}
}

/**
 * Tombol utama emas: gradasi kuning-emas, glow di belakang, kilau berjalan,
 * efek tekan bouncy. Dipakai untuk CTA penting (Simpan, Bagikan, GPS).
 */
@Composable
fun GoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 54.dp,
    leading: (@Composable () -> Unit)? = null
) {
    val e = LocalElectric.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "goldBtnPress"
    )
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                Brush.radialGradient(
                    listOf(e.glowYellow.copy(alpha = if (enabled) 0.45f else 0f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height),
                    radius = size.width * 0.62f
                ),
                topLeft = Offset(0f, size.height * 0.18f),
                size = Size(size.width, size.height * 0.92f)
            )
        }
        val bg = if (enabled) {
            e.goldBrush
        } else {
            Brush.linearGradient(listOf(Color(0xFF6B6455), Color(0xFF4A463C)))
        }
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(bg, shape)
                .border(
                    1.2.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.75f),
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.55f)
                        )
                    ),
                    shape
                )
                .then(if (enabled) Modifier.shineBand() else Modifier)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.invoke()
            if (leading != null) Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = Color(0xFF241A00),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
        // gloss gel modern: pantulan cahaya di paruh atas tombol
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.34f),
                        0.5f to Color.Transparent
                    )
                )
        )
    }
}

/** Overlay kilau berjalan untuk tombol/panel berwarna. */
@Composable
fun Modifier.shineBand(): Modifier {
    val t = rememberInfiniteTransition(label = "shineBand")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart),
        label = "p"
    )
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val band = p * 2.6f - 0.8f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    start = Offset(size.width * (band - 0.16f), 0f),
                    end = Offset(size.width * (band + 0.16f), size.height)
                ),
                blendMode = BlendMode.SrcAtop
            )
        }
}

/** Lingkaran aksi kaca di app bar (back / share / edit / delete) — glow + efek tekan bouncy. */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 42.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val e = LocalElectric.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "glassBtnPress"
    )
    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        // glow lembut di belakang kaca
        Canvas(modifier = Modifier.size(sizeDp * 1.5f)) {
            drawCircle(
                Brush.radialGradient(
                    listOf(e.glowYellow.copy(alpha = 0.16f), Color.Transparent)
                ),
                radius = this.size.minDimension * 0.5f
            )
        }
        Box(
            modifier = Modifier
                .size(sizeDp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(e.glass, CircleShape)
                .border(1.dp, e.borderBrush, CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

/**
 * Wadah ikon premium: squircle bergradasi + gloss + ring gradasi + glow luar.
 * Dipakai di menu, statistik, form, dan pengaturan agar ikon terlihat modern.
 */
@Composable
fun IconTile(
    icon: PlnIconKind,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 46.dp,
    gradient: Brush? = null,
    iconTint: Color? = null,
    iconScale: Float = 0.56f
) {
    val e = LocalElectric.current
    val shape = RoundedCornerShape(sizeDp * 0.32f)
    val bg = gradient ?: e.goldBrush
    val tint = iconTint ?: Color(0xFF241A00)
    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        // glow luar
        Canvas(modifier = Modifier.size(sizeDp * 1.7f)) {
            drawCircle(
                Brush.radialGradient(
                    listOf(e.glowYellow.copy(alpha = 0.30f), Color.Transparent)
                ),
                radius = this.size.minDimension * 0.5f
            )
        }
        Box(
            modifier = Modifier
                .size(sizeDp)
                .clip(shape)
                .background(bg, shape)
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.70f), Color.White.copy(alpha = 0.15f))
                    ),
                    shape
                )
        )
        // gloss gel
        Box(
            modifier = Modifier
                .size(sizeDp)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.35f),
                        0.55f to Color.Transparent
                    )
                )
        )
        PlnIcon(icon, size = sizeDp * iconScale, tint = tint)
    }
}

/** Bar energi memanjang (splash) — terisi dari 0 ke 1. */
@Composable
fun EnergyBar(modifier: Modifier = Modifier, durationMs: Int = 1500) {
    val e = LocalElectric.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = durationMs, easing = LinearEasing))
    }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .width(170.dp)
            .height(6.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f), shape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = progress.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .height(6.dp)
                .clip(shape)
                .background(e.goldBrush, shape)
        )
    }
}

/** Loading kecil warna emas. */
@Composable
fun GoldSpinner(size: Dp = 22.dp, modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = 2.4.dp,
        color = LocalElectric.current.yellow
    )
}
