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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.ui.fx.BoltMark
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.EnergyBar
import com.petugas.pemasangan.ui.theme.LocalElectric
import kotlinx.coroutines.delay

/* =====================================================================
   Splash v3.0 "Electric Premium":
   latar elektrik penuh percikan + sangkar petir bouncy di dalam
   cincin energi berputar + judul + bar energi + chip versi.
   ===================================================================== */

@Composable
fun SplashScreen(onDone: () -> Unit) {
    val e = LocalElectric.current
    var logoVisible by remember { mutableStateOf(false) }
    var textVisible by remember { mutableStateOf(false) }
    var chipVisible by remember { mutableStateOf(false) }

    val logoScale by animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0.55f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0f,
        animationSpec = tween(420),
        label = "logoAlpha"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (textVisible) 1f else 0f,
        animationSpec = tween(520),
        label = "textAlpha"
    )
    val chipAlpha by animateFloatAsState(
        targetValue = if (chipVisible) 1f else 0f,
        animationSpec = tween(420),
        label = "chipAlpha"
    )

    val spin = rememberInfiniteTransition(label = "ringSpin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    val ringAlpha by spin.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse),
        label = "ringAlpha"
    )
    val pulse = rememberInfiniteTransition(label = "splashPulse")
    val ringScale by pulse.animateFloat(
        initialValue = 0.98f, targetValue = 1.5f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "ringScale"
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseAlpha"
    )

    LaunchedEffect(Unit) {
        delay(60)
        logoVisible = true
        delay(300)
        textVisible = true
        delay(220)
        chipVisible = true
        delay(1500)
        onDone()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .scale(ringScale)
                        .alpha(pulseAlpha)
                        .border(2.dp, e.yellow, CircleShape)
                )
                Canvas(
                    modifier = Modifier
                        .size(170.dp)
                        .alpha(ringAlpha)
                ) {
                    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(
                        color = e.yellow,
                        startAngle = angle,
                        sweepAngle = 100f,
                        useCenter = false,
                        style = stroke
                    )
                    drawArc(
                        color = e.cyan,
                        startAngle = angle + 180f,
                        sweepAngle = 60f,
                        useCenter = false,
                        style = stroke
                    )
                }
                Box(
                    modifier = Modifier
                        .size(146.dp)
                        .scale(logoScale)
                        .alpha(logoAlpha)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF1A2342), Color(0xFF0D1428))),
                            shape = RoundedCornerShape(34.dp)
                        )
                        .border(1.dp, e.borderBrush, RoundedCornerShape(34.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    BoltMark(size = 92.dp, glow = true, pulse = true)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(textAlpha)
            ) {
                Text(
                    text = "PETUGAS PLN",
                    color = e.textHi,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Pencatatan Pemasangan Listrik",
                    color = e.textMid,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            EnergyBar()

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "v3.0  •  ELECTRIC PREMIUM",
                color = e.yellow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .alpha(chipAlpha)
                    .background(
                        color = e.yellow.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}
