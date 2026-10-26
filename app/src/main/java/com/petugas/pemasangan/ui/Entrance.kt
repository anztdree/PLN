package com.petugas.pemasangan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entrance halus: muncul dari bawah sambil fade-in.
 * Gunakan dengan delay berbeda untuk efek staggered antar kartu.
 */
@Composable
fun Modifier.entrance(delayMs: Int = 0): Modifier {
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(48f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        launch {
            alpha.animateTo(1f, tween(durationMillis = 420))
        }
        offsetY.animateTo(
            0f,
            tween(durationMillis = 480, easing = FastOutSlowInEasing)
        )
    }
    return graphicsLayer {
        this.alpha = alpha.value
        translationY = offsetY.value
    }
}

/**
 * Pop-in bouncy (scale + fade) — untuk foto, FAB, dan elemen kecil.
 */
@Composable
fun Modifier.popIn(delayMs: Int = 0): Modifier {
    val scale = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        launch {
            alpha.animateTo(1f, tween(durationMillis = 220))
        }
        scale.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }
    return graphicsLayer {
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }
}
