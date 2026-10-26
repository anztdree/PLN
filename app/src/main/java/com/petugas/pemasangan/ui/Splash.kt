package com.petugas.pemasangan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val SplashDark1 = Color(0xFF04231A)
private val SplashDark2 = Color(0xFF064E3B)
private val SplashDark3 = Color(0xFF022C22)
private val SplashAccent = Color(0xFF34D399)
private val SplashSoft = Color(0xFF6EE7B7)

/**
 * Splash screen beranimasi v2.0:
 * - latar gradient gelap-hijau (emerald)
 * - logo (ikon Build) di kotak rounded ber-gradient,
 *   scale 0.6f -> 1f dengan spring bouncy + fade in
 * - ring pulse (lingkaran border beranimasi alpha/scale, infinite)
 * - teks "Petugas PLN" + chip "v2.0" fade-in dengan delay
 * - setelah ~1200 ms memanggil onDone()
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    // --- animasi masuk logo (scale bouncy + fade) ---
    var logoVisible by remember { mutableStateOf(false) }
    var textVisible by remember { mutableStateOf(false) }

    val logoScale by animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "logoAlpha"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (textVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "textAlpha"
    )

    // --- ring pulse (infinite transition: scale naik, alpha turun) ---
    val pulse = rememberInfiniteTransition(label = "splashPulse")
    val ringScale by pulse.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringScale"
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )

    // --- urutan waktu: logo masuk -> teks fade -> onDone ~1200ms ---
    LaunchedEffect(Unit) {
        delay(60)
        logoVisible = true
        delay(320)
        textVisible = true
        delay(1200)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SplashDark1, SplashDark2, SplashDark3)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // ring pulse di sekitar logo
                Box(
                    modifier = Modifier
                        .size(148.dp)
                        .scale(ringScale)
                        .alpha(ringAlpha)
                        .border(width = 2.dp, color = SplashAccent, shape = CircleShape)
                )
                // kotak logo rounded ber-gradient
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .scale(logoScale)
                        .alpha(logoAlpha)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF10B981), Color(0xFF047857))
                            ),
                            shape = RoundedCornerShape(28.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = "Logo Petugas PLN",
                        tint = Color.White,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(textAlpha)
            ) {
                Text(
                    text = "Petugas PLN",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .background(
                            color = SplashAccent.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(50)
                        )
                ) {
                    Text(
                        text = "v2.0",
                        color = SplashSoft,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        modifier = Modifier
                            .alpha(textAlpha)
                            .background(Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
