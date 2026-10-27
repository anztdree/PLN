package com.petugas.pemasangan.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import com.petugas.pemasangan.util.PhotoCodec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private data class MonthBar(val label: String, val count: Int)

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val database = remember { AppDatabase.get(appContext) }
    val e = LocalElectric.current

    var records by remember { mutableStateOf<List<InstallationEntity>>(emptyList()) }
    LaunchedEffect(Unit) {
        database.installationDao().observeAll().collect { records = it }
    }

    val total = records.size
    val done = records.count { it.status == InstallStatus.SELESAI }
    val pending = total - done
    val photos = records.sumOf { PhotoCodec.decode(it.photos).size }
    val ratio = if (total > 0) done.toFloat() / total else 0f

    // 6 bulan terakhir
    val monthly: List<MonthBar> = remember(records) {
        val out = ArrayList<MonthBar>(6)
        val fmt = SimpleDateFormat("MMM", Locale("id", "ID"))
        for (i in 5 downTo 0) {
            val c = Calendar.getInstance()
            c.add(Calendar.MONTH, -i)
            val y = c.get(Calendar.YEAR)
            val m = c.get(Calendar.MONTH)
            val count = records.count { r ->
                val rc = Calendar.getInstance()
                rc.timeInMillis = r.installDate
                rc.get(Calendar.YEAR) == y && rc.get(Calendar.MONTH) == m
            }
            out.add(MonthBar(fmt.format(c.time), count))
        }
        out
    }

    // animasi chart
    val barProgress = remember { Animatable(0f) }
    LaunchedEffect(monthly) {
        barProgress.snapTo(0f)
        barProgress.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
    }
    val donutProgress = remember { Animatable(0f) }
    LaunchedEffect(ratio) {
        donutProgress.snapTo(0f)
        donutProgress.animateTo(ratio, tween(1100, easing = FastOutSlowInEasing))
    }

    val maxBar = (monthly.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(onClick = onBack) {
                    PlnIcon(PlnIconKind.BACK, tint = e.textHi, size = 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Statistik Pemasangan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = e.textHi
                    )
                    Text(
                        "Ringkasan performa lapangan",
                        style = MaterialTheme.typography.labelSmall,
                        color = e.textMid
                    )
                }
            }

            // 2x2 tile
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTileBig(PlnIconKind.LIST, total, "Total Data", e.yellow, Modifier.weight(1f).entrance(80))
                StatTileBig(PlnIconKind.CALENDAR, monthly.lastOrNull()?.count ?: 0, "Bulan Ini", e.cyan, Modifier.weight(1f).entrance(140))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTileBig(PlnIconKind.CAMERA, photos, "Total Foto", e.amber, Modifier.weight(1f).entrance(200))
                StatTileBig(PlnIconKind.CHECK, done, "Selesai", e.yellow, Modifier.weight(1f).entrance(260))
            }

            // Grafik batang 6 bulan
            GlassPanel(modifier = Modifier.entrance(320)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlnIcon(PlnIconKind.CHART, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "TREN 6 BULAN TERAKHIR",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                        color = e.textHi
                    )
                }
                Spacer(Modifier.height(14.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    val slot = size.width / monthly.size
                    val barW = slot * 0.5f
                    monthly.forEachIndexed { i, m ->
                        val local = ((barProgress.value - i * 0.07f) / (1f - i * 0.07f)).coerceIn(0f, 1f)
                        val h = (m.count.toFloat() / maxBar) * size.height * 0.92f * local
                        if (h > 1f) {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(e.yellow, e.gold.copy(alpha = 0.55f), Color.Transparent),
                                    startY = size.height - h,
                                    endY = size.height
                                ),
                                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                                size = Size(barW, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3f, barW / 3f)
                            )
                        }
                        // garis dasar
                        drawLine(
                            e.glassBorderB,
                            Offset(i * slot + slot * 0.1f, size.height),
                            Offset((i + 1) * slot - slot * 0.1f, size.height),
                            strokeWidth = 1.5f
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    monthly.forEach { m ->
                        Text(
                            m.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.count > 0) e.textHi else e.textMid,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Donut rasio selesai
            GlassPanel(modifier = Modifier.entrance(400)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlnIcon(PlnIconKind.METER, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "RASIO PEKERJAAN SELESAI",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.6.sp,
                        color = e.textHi
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(130.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(130.dp)) {
                            val stroke = 20.dp.toPx()
                            val inset = stroke / 2f + 2f
                            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                            drawArc(
                                color = if (e.dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f),
                                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                topLeft = Offset(inset, inset), size = arcSize,
                                style = Stroke(stroke, cap = StrokeCap.Round)
                            )
                            if (total > 0) {
                                drawArc(
                                    brush = Brush.sweepGradient(listOf(e.yellow, e.gold, e.amber, e.yellow)),
                                    startAngle = -90f,
                                    sweepAngle = 360f * donutProgress.value,
                                    useCenter = false,
                                    topLeft = Offset(inset, inset), size = arcSize,
                                    style = Stroke(stroke, cap = StrokeCap.Round)
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${(donutProgress.value * 100).toInt()}%",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = e.yellow
                            )
                            Text(
                                "selesai",
                                style = MaterialTheme.typography.labelSmall,
                                color = e.textMid
                            )
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegendRow(e.yellow, "Selesai", done, Modifier)
                        LegendRow(e.red, "Belum Selesai", pending, Modifier)
                        LegendRow(e.cyan, "Total Foto", photos, Modifier)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun StatTileBig(
    icon: PlnIconKind,
    value: Int,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val e = LocalElectric.current
    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        padding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp, horizontal = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                PlnIcon(icon, size = 19.dp, tint = accent)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "$value",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = e.textHi
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = e.textMid)
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: Int, modifier: Modifier = Modifier) {
    val e = LocalElectric.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$label",
            style = MaterialTheme.typography.bodySmall,
            color = e.textMid,
            modifier = Modifier.width(100.dp)
        )
        Text(
            "$value",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = e.textHi
        )
    }
}
