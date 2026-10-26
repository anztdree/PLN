package com.petugas.pemasangan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val ConfettiColors = listOf(
    Color(0xFF34D399),
    Color(0xFFFBBF24),
    Color(0xFFA3E635),
    Color(0xFF2DD4BF),
    Color(0xFFF87171),
    Color(0xFFFFFFFF)
)

private class ConfettiPiece(
    val angle: Float,
    val speed: Float,
    val delay: Float,
    val size: Float,
    val color: Color,
    val spin: Float
)

/**
 * Ledakan confetti beranimasi (Canvas) — ditampilkan saat data berhasil disimpan.
 * Partikel menyebar dari tengah layar lalu jatuh dengan efek gravitasi dan rotasi.
 */
@Composable
fun ConfettiOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = 120)),
        exit = fadeOut(tween(durationMillis = 320)),
        modifier = modifier
    ) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 1500, easing = LinearEasing))
        }

        val pieces = remember {
            List(56) { i ->
                ConfettiPiece(
                    angle = (i / 56f) * (2f * Math.PI.toFloat()) + Random.nextFloat() * 0.22f,
                    speed = 0.20f + Random.nextFloat() * 0.32f,
                    delay = Random.nextFloat() * 0.16f,
                    size = 7f + Random.nextFloat() * 9f,
                    color = ConfettiColors[i % ConfettiColors.size],
                    spin = Random.nextFloat() * 720f - 360f
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progress.value
            if (t <= 0f || t >= 1f) return@Canvas

            val cx0 = size.width / 2f
            val cy0 = size.height * 0.40f
            val gravity = size.height * 0.95f

            pieces.forEach { p ->
                val local = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
                if (local <= 0f || local >= 1f) return@forEach

                val cx = cx0 + cos(p.angle) * p.speed * local * size.width
                val cy = cy0 +
                    sin(p.angle) * p.speed * local * size.height * 0.6f +
                    gravity * local * local
                val alpha = (1f - local).coerceIn(0f, 1f)

                val w = p.size
                val h = p.size * 0.62f
                rotate(degrees = p.spin * local, pivot = Offset(cx, cy)) {
                    drawRect(
                        color = p.color.copy(alpha = alpha),
                        topLeft = Offset(cx - w / 2f, cy - h / 2f),
                        size = Size(w, h)
                    )
                }
            }
        }
    }
}
