package com.petugas.pemasangan.ui.menu

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.ui.fx.BoltMark
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.GradientHeadline
import com.petugas.pemasangan.ui.fx.IconTile
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import com.petugas.pemasangan.util.PhotoCodec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun MenuScreen(
    onNewInstallation: () -> Unit,
    onOpenList: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember { SettingsRepository(appContext) }
    val database = remember { AppDatabase.get(appContext) }
    val scope = rememberCoroutineScope()
    val e = LocalElectric.current

    var officerName by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<InstallationEntity>>(emptyList()) }
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings.officerNameFlow.collect { officerName = it }
    }
    LaunchedEffect(Unit) {
        database.installationDao().observeAll().collect { records = it }
    }

    // ---------- Salam & tanggal Indonesia (mengikuti waktu perangkat) ----------
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 4..10 -> "Selamat pagi"
            in 11..14 -> "Selamat siang"
            in 15..17 -> "Selamat sore"
            else -> "Selamat malam"
        }
    }
    val todayText = remember {
        val c = Calendar.getInstance()
        SimpleDateFormat("EEEE, d MMMM yyyy", Locale("id", "ID")).format(c.time)
    }
    val fmtDate = remember { SimpleDateFormat("d MMM", Locale("id", "ID")) }
    val fmtTime = remember { SimpleDateFormat("HH:mm", Locale("id", "ID")) }

    // ---------- Statistik ----------
    val totalData = records.size
    val totalPhotos = records.sumOf { PhotoCodec.decode(it.photos).size }
    val now = Calendar.getInstance()
    val monthCount = records.count { r ->
        val c = Calendar.getInstance()
        c.timeInMillis = r.installDate
        c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }
    val doneCount = records.count { it.status == InstallStatus.SELESAI }

    val animatedTotal by animateIntAsState(totalData, tween(700), label = "animTotal")
    val animatedPhotos by animateIntAsState(totalPhotos, tween(700), label = "animPhotos")
    val animatedMonth by animateIntAsState(monthCount, tween(700), label = "animMonth")
    val animatedDone by animateIntAsState(doneCount, tween(700), label = "animDone")

    // Aktivitas terakhir: 3 data termutakhir
    val recent = remember(records) { records.sortedByDescending { it.updatedAt }.take(3) }

    // Duo palet tile SINKRON: emas & volt-cyan saja (satu keluarga brand)
    val goldTile = Brush.linearGradient(listOf(Color(0xFFFFD95A), Color(0xFFF59B0E)))
    val cyanTile = Brush.linearGradient(listOf(Color(0xFF52E5FF), Color(0xFF0E9BC8)))
    val goldTint = Color(0xFF3A2500)
    val cyanTint = Color(0xFF002430)

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ================= HEADER — salam dinamis =================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BoltMark(size = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        GradientHeadline(
                            text = "PETUGAS PLN",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp
                            )
                        )
                        Text(
                            "Pencatatan Pemasangan Listrik",
                            style = MaterialTheme.typography.bodySmall,
                            color = e.textMid
                        )
                    }
                    Text(
                        "v1.0.0",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = e.yellow,
                        modifier = Modifier
                            .background(e.yellow.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .border(1.dp, e.yellow.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // ================= PROFIL & SALAM =================
                GlassPanel(modifier = Modifier.entrance(60)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                .background(e.goldBrush),
                            contentAlignment = Alignment.Center
                        ) {
                            PlnIcon(PlnIconKind.USER, size = 26.dp, tint = Color(0xFF241A00))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "$greeting,",
                                style = MaterialTheme.typography.bodySmall,
                                color = e.textMid
                            )
                            Text(
                                officerName.ifBlank { "Belum diatur" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = e.textHi,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlnIcon(PlnIconKind.CALENDAR, size = 12.dp, tint = e.yellow)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    todayText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = e.textMid
                                )
                            }
                        }
                        GlassIconButton(onClick = { showEditDialog = true }) {
                            PlnIcon(PlnIconKind.EDIT, size = 17.dp, tint = e.yellow)
                        }
                    }
                }

                // ================= CTA UTAMA: INPUT DATA PELANGGAN =================
                PrimaryGoldCard(
                    title = "Input data pelanggan",
                    onClick = onNewInstallation,
                    modifier = Modifier.entrance(120)
                )

                // ================= JUDUL SEKSI: STATISTIK =================
                SectionTitle(
                    kind = PlnIconKind.CHART,
                    text = "STATISTIK",
                    modifier = Modifier.entrance(180)
                )

                // Statistik grid 2x2 - panel kaca horizontal, angka besar tebal
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        icon = PlnIconKind.LIST,
                        value = animatedTotal,
                        label = "Total Data",
                        tileBrush = goldTile,
                        tileTint = goldTint,
                        modifier = Modifier.weight(1f).entrance(220)
                    )
                    StatCard(
                        icon = PlnIconKind.CAMERA,
                        value = animatedPhotos,
                        label = "Foto",
                        tileBrush = cyanTile,
                        tileTint = cyanTint,
                        modifier = Modifier.weight(1f).entrance(260)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        icon = PlnIconKind.CALENDAR,
                        value = animatedMonth,
                        label = "Bulan Ini",
                        tileBrush = cyanTile,
                        tileTint = cyanTint,
                        modifier = Modifier.weight(1f).entrance(300)
                    )
                    StatCard(
                        icon = PlnIconKind.CHECK,
                        value = animatedDone,
                        label = "Selesai",
                        tileBrush = goldTile,
                        tileTint = goldTint,
                        modifier = Modifier.weight(1f).entrance(340)
                    )
                }

                // ================= JUDUL SEKSI: MENU UTAMA =================
                SectionTitle(
                    kind = PlnIconKind.BOLT,
                    text = "MENU UTAMA",
                    modifier = Modifier.entrance(380)
                )

                // Grid fitur: 2 kolom sinkron emas-cyan
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureTile(
                        icon = PlnIconKind.PANEL,
                        title = "Data & Riwayat",
                        subtitle = "$animatedTotal data",
                        onClick = onOpenList,
                        modifier = Modifier.weight(1f).entrance(420),
                        gradient = goldTile,
                        iconTint = goldTint
                    )
                    FeatureTile(
                        icon = PlnIconKind.CHART,
                        title = "Statistik",
                        subtitle = "Grafik 6 bulan",
                        onClick = onOpenStats,
                        modifier = Modifier.weight(1f).entrance(460),
                        gradient = cyanTile,
                        iconTint = cyanTint
                    )
                }

                FeatureTile(
                    icon = PlnIconKind.GEAR,
                    title = "Pengaturan",
                    subtitle = "Nama petugas & preferensi",
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().entrance(500),
                    wide = true,
                    gradient = Brush.linearGradient(
                        listOf(Color(0xFF2E3D68), Color(0xFF182347))
                    ),
                    iconTint = e.yellow
                )

                // ================= JUDUL SEKSI: AKTIVITAS TERAKHIR =================
                SectionTitle(
                    kind = PlnIconKind.NOTE,
                    text = "AKTIVITAS TERAKHIR",
                    modifier = Modifier.entrance(560)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onOpenList)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "SEMUA",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.4.sp,
                            color = e.yellow
                        )
                        PlnIcon(PlnIconKind.CHEVRON, size = 12.dp, tint = e.yellow)
                    }
                }

                GlassPanel(
                    modifier = Modifier.fillMaxWidth().entrance(620),
                    padding = PaddingValues(14.dp)
                ) {
                    if (recent.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PlnIcon(PlnIconKind.NOTE, size = 30.dp, tint = e.textMid)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Belum ada data pemasangan",
                                style = MaterialTheme.typography.bodySmall,
                                color = e.textMid
                            )
                            Text(
                                "Mulai dari tombol emas di atas",
                                style = MaterialTheme.typography.labelSmall,
                                color = e.textMid.copy(alpha = 0.7f)
                            )
                        }
                    } else {
                        recent.forEachIndexed { i, r ->
                            if (i > 0) {
                                Spacer(Modifier.height(11.dp))
                                MiniDivider()
                                Spacer(Modifier.height(11.dp))
                            }
                            RecentRow(
                                name = r.customerName.ifBlank { r.customerId },
                                address = r.address,
                                whenText = "${fmtDate.format(r.installDate)} • ${fmtTime.format(r.installDate)}",
                                status = r.status
                            )
                        }
                    }
                }

                // ================= FOOTER BRAND =================
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlnIcon(PlnIconKind.BOLT, size = 12.dp, tint = e.yellow)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "PT PLN (PERSERO)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.8.sp,
                            color = e.textMid
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Manager : Imam Sahal F",
                        style = MaterialTheme.typography.labelSmall,
                        color = e.textMid.copy(alpha = 0.65f)
                    )
                }
            }
        }
    }

    if (showEditDialog) {
        var tempName by remember { mutableStateOf(officerName) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            containerColor = e.bg1,
            titleContentColor = e.textHi,
            textContentColor = e.textMid,
            title = { Text("Nama Petugas") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    singleLine = true,
                    label = { Text("Masukkan nama petugas") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = e.yellow,
                        unfocusedBorderColor = e.glassBorderB,
                        cursorColor = e.yellow,
                        focusedLabelColor = e.yellow,
                        unfocusedLabelColor = e.textMid,
                        focusedTextColor = e.textHi,
                        unfocusedTextColor = e.textHi
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.setOfficerName(tempName.trim()) }
                    showEditDialog = false
                }) { Text("Simpan", color = e.yellow) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Batal", color = e.textMid) }
            }
        )
    }
}

/** Judul seksi: ikon mini + teks + garis gradasi emas-cyan + slot aksi kanan. */
@Composable
private fun SectionTitle(
    kind: PlnIconKind,
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val e = LocalElectric.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlnIcon(kind, size = 15.dp, tint = e.yellow)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp,
            color = e.textHi
        )
        Spacer(Modifier.width(12.dp))
        Canvas(modifier = Modifier.weight(1f).height(2.dp)) {
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(
                        e.yellow.copy(alpha = 0.45f),
                        e.cyan.copy(alpha = 0.18f),
                        Color.Transparent
                    )
                ),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = size.height
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** Pembatas tipis bergradasi di dalam panel. */
@Composable
private fun MiniDivider() {
    val e = LocalElectric.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        e.yellow.copy(alpha = 0.28f),
                        e.cyan.copy(alpha = 0.16f),
                        Color.Transparent
                    )
                )
            )
    )
}

/** Satu baris aktivitas terakhir: tile ikon + identitas pelanggan + chip status. */
@Composable
private fun RecentRow(
    name: String,
    address: String,
    whenText: String,
    status: String
) {
    val e = LocalElectric.current
    val selesai = status == InstallStatus.SELESAI
    val chipColor = if (selesai) Color(0xFF58E38A) else Color(0xFFFFB74D)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(
            if (selesai) PlnIconKind.CHECK else PlnIconKind.METER,
            sizeDp = 36.dp,
            gradient = if (selesai) {
                Brush.linearGradient(listOf(Color(0xFFFFD95A), Color(0xFFF59B0E)))
            } else {
                Brush.linearGradient(listOf(Color(0xFF52E5FF), Color(0xFF0E9BC8)))
            },
            iconTint = if (selesai) Color(0xFF3A2500) else Color(0xFF002430),
            iconScale = 0.60f
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = e.textHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                address,
                style = MaterialTheme.typography.bodySmall,
                color = e.textMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = chipColor
            )
            Text(
                whenText,
                style = MaterialTheme.typography.labelSmall,
                color = e.textMid
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: PlnIconKind,
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    tileBrush: Brush,
    tileTint: Color
) {
    val e = LocalElectric.current
    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        padding = PaddingValues(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                icon,
                sizeDp = 44.dp,
                gradient = tileBrush,
                iconTint = tileTint,
                iconScale = 0.60f
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "$value",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = e.yellow,
                    maxLines = 1
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = e.textMid,
                    maxLines = 1
                )
            }
        }
    }
}

/** Kartu utama emas bergradasi + glow. */
@Composable
private fun PrimaryGoldCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "goldCardPress"
    )
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                Brush.radialGradient(
                    listOf(Color(0xFFFFD84D).copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height),
                    radius = size.width * 0.7f
                )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFFFE95A), Color(0xFFFFB300), Color(0xFFFF8F1F))
                    ),
                    shape
                )
                .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(54.dp)) {
                IconTile(
                    PlnIconKind.BOLT,
                    sizeDp = 54.dp,
                    gradient = Brush.linearGradient(
                        listOf(Color.Black.copy(alpha = 0.40f), Color.Black.copy(alpha = 0.16f))
                    ),
                    iconTint = Color.White,
                    iconScale = 0.60f
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 19.sp,
                        letterSpacing = 0.8.sp
                    ),
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF241A00)
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4A3600)
                    )
                }
            }
            PlnIcon(PlnIconKind.CHEVRON, size = 20.dp, tint = Color(0xFF241A00))
        }
    }
}

/**
 * Tile fitur gaya dashboard: IconTile bergradasi di atas (vertikal)
 * atau di kiri (wide = memanjang), judul + subjudul + BUKA.
 */
@Composable
private fun FeatureTile(
    icon: PlnIconKind,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    gradient: Brush? = null,
    iconTint: Color? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "featureTilePress"
    )
    val e = LocalElectric.current
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        // aksen glow di sudut atas tile
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                Brush.radialGradient(
                    listOf(e.glowYellow.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width, 0f),
                    radius = size.width * 0.7f
                ),
                topLeft = Offset.Zero,
                size = size
            )
        }
        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(e.glass, shape)
                    .border(1.dp, e.borderBrush, shape)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(icon, sizeDp = 50.dp, gradient = gradient, iconTint = iconTint)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = e.textHi
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textMid
                    )
                }
                PlnIcon(PlnIconKind.CHEVRON, size = 18.dp, tint = e.yellow)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(e.glass, shape)
                    .border(1.dp, e.borderBrush, shape)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    .padding(16.dp)
            ) {
                IconTile(icon, sizeDp = 46.dp, gradient = gradient, iconTint = iconTint)
                Spacer(Modifier.height(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = e.textHi
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textMid
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "BUKA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        color = e.yellow
                    )
                    Spacer(Modifier.width(4.dp))
                    PlnIcon(PlnIconKind.CHEVRON, size = 14.dp, tint = e.yellow)
                }
            }
        }
    }
}
