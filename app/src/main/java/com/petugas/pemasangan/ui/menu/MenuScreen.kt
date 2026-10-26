package com.petugas.pemasangan.ui.menu

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.ui.fx.BoltMark
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.GoldButton
import com.petugas.pemasangan.ui.fx.GoldSpinner
import com.petugas.pemasangan.ui.fx.GradientHeadline
import com.petugas.pemasangan.ui.fx.IconTile
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.theme.LocalElectric
import com.petugas.pemasangan.util.PhotoCodec
import java.util.Calendar
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
    var isDark by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        settings.officerNameFlow.collect { officerName = it }
    }
    LaunchedEffect(Unit) {
        settings.themeFlow.collect { isDark = it }
    }
    LaunchedEffect(Unit) {
        database.installationDao().observeAll().collect { records = it }
    }

    // ---------- Statistik ----------
    val totalData = records.size
    val totalPhotos = records.sumOf { PhotoCodec.decode(it.photos).size }
    val now = Calendar.getInstance()
    val nowYear = now.get(Calendar.YEAR)
    val nowMonth = now.get(Calendar.MONTH)
    val monthCount = records.count { r ->
        val c = Calendar.getInstance()
        c.timeInMillis = r.installDate
        c.get(Calendar.YEAR) == nowYear && c.get(Calendar.MONTH) == nowMonth
    }
    val doneCount = records.count { it.status == InstallStatus.SELESAI }

    val animatedTotal by animateIntAsState(totalData, tween(700), label = "animTotal")
    val animatedPhotos by animateIntAsState(totalPhotos, tween(700), label = "animPhotos")
    val animatedMonth by animateIntAsState(monthCount, tween(700), label = "animMonth")
    val animatedDone by animateIntAsState(doneCount, tween(700), label = "animDone")

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(dense = true)

        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ================= HEADER =================
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

                // ================= PROFIL =================
                GlassPanel(modifier = Modifier.entrance(60)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(e.goldBrush),
                            contentAlignment = Alignment.Center
                        ) {
                            PlnIcon(PlnIconKind.USER, size = 24.dp, tint = Color(0xFF241A00))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "PETUGAS LAPANGAN",
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.2.sp,
                                color = e.textMid
                            )
                            Text(
                                officerName.ifBlank { "Belum diatur" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = e.textHi
                            )
                        }
                        GlassIconButton(onClick = { showEditDialog = true }) {
                            PlnIcon(PlnIconKind.EDIT, size = 17.dp, tint = e.yellow)
                        }
                    }
                }

                // ================= STATISTIK =================
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        icon = PlnIconKind.LIST,
                        value = animatedTotal,
                        label = "Total Data",
                        modifier = Modifier.weight(1f).entrance(120)
                    )
                    StatTile(
                        icon = PlnIconKind.CAMERA,
                        value = animatedPhotos,
                        label = "Foto",
                        modifier = Modifier.weight(1f).entrance(180)
                    )
                    StatTile(
                        icon = PlnIconKind.CALENDAR,
                        value = animatedMonth,
                        label = "Bulan Ini",
                        modifier = Modifier.weight(1f).entrance(240)
                    )
                    StatTile(
                        icon = PlnIconKind.CHECK,
                        value = animatedDone,
                        label = "Selesai",
                        modifier = Modifier.weight(1f).entrance(300)
                    )
                }

                // ================= MENU UTAMA =================
                Text(
                    "MENU UTAMA",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = e.textMid,
                    modifier = Modifier.entrance(340)
                )

                // Kartu utama emas — Input Pemasangan
                PrimaryGoldCard(
                    title = "Input Pemasangan Baru",
                    subtitle = "Foto minimal 2 + koordinat GPS + peta",
                    onClick = onNewInstallation,
                    modifier = Modifier.entrance(400)
                )

                // Grid fitur modern: 2 kolom + 1 lebar
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureTile(
                        icon = PlnIconKind.PANEL,
                        title = "Data & Riwayat",
                        subtitle = "$animatedTotal data",
                        onClick = onOpenList,
                        modifier = Modifier.weight(1f).entrance(460)
                    )
                    FeatureTile(
                        icon = PlnIconKind.CHART,
                        title = "Statistik",
                        subtitle = "Grafik 6 bulan",
                        onClick = onOpenStats,
                        modifier = Modifier.weight(1f).entrance(520),
                        gradient = Brush.linearGradient(
                            listOf(Color(0xFF3BE0FF), Color(0xFF0FB4E8))
                        ),
                        iconTint = Color(0xFF00212B)
                    )
                }

                FeatureTile(
                    icon = PlnIconKind.GEAR,
                    title = "Pengaturan",
                    subtitle = "Nama petugas • tema gelap/terang",
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth().entrance(580),
                    wide = true,
                    gradient = Brush.linearGradient(
                        listOf(Color(0xFFA9B6E0), Color(0xFF7C8AB8))
                    ),
                    iconTint = Color(0xFF1C2440)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "Peta © OpenStreetMap — Data tersimpan lokal di perangkat",
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textMid,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(640)
                )
                Spacer(Modifier.height(14.dp))
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

@Composable
private fun StatTile(
    icon: PlnIconKind,
    value: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    val e = LocalElectric.current
    GlassPanel(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        padding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp, horizontal = 6.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconTile(icon, sizeDp = 30.dp, iconScale = 0.58f)
            Spacer(Modifier.height(8.dp))
            Text(
                "$value",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = e.yellow
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = e.textMid,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Kartu utama emas bergradasi + kilau berjalan + glow. */
@Composable
private fun PrimaryGoldCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
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
                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
                    radius = size.width * 0.7f
                )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFFFFE95A), Color(0xFFFFB300), Color(0xFFFF8F1F))), shape)
                .border(1.dp, Color.White.copy(alpha = 0.35f), shape)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(54.dp)
            ) {
                IconTile(
                    PlnIconKind.BOLT,
                    sizeDp = 54.dp,
                    gradient = Brush.linearGradient(
                        listOf(Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.10f))
                    ),
                    iconTint = Color.White,
                    iconScale = 0.60f
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF241A00)
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4A3600)
                )
            }
            PlnIcon(PlnIconKind.CHEVRON, size = 20.dp, tint = Color(0xFF241A00))
        }
    }
}

/**
 * Tile fitur gaya dashboard modern: IconTile bergradasi di atas,
 * judul + subjudul + label BUKA. [wide] = tata letak baris memanjang.
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
