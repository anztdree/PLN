package com.petugas.pemasangan.ui.menu

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.util.PhotoCodec
import java.util.Calendar
import kotlinx.coroutines.launch

private val HeaderA = Color(0xFF065F46)
private val HeaderB = Color(0xFF0F766E)
private val HeaderC = Color(0xFF15803D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    onNewInstallation: () -> Unit,
    onOpenList: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember { SettingsRepository(appContext) }
    val database = remember { AppDatabase.get(appContext) }
    val scope = rememberCoroutineScope()

    var officerName by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<InstallationEntity>>(emptyList()) }
    var showEditDialog by remember { mutableStateOf(false) }
    var isDark by remember { mutableStateOf(false) }

    val onToggleTheme: (Boolean) -> Unit = { dark ->
        scope.launch { settings.setTheme(dark) }
    }

    LaunchedEffect(Unit) {
        settings.officerNameFlow.collect { officerName = it }
    }
    LaunchedEffect(Unit) {
        settings.themeFlow.collect { isDark = it }
    }
    LaunchedEffect(Unit) {
        database.installationDao().observeAll().collect { records = it }
    }

    // ---------- Statistik (dianimasikan dari nilai sebelumnya) ----------
    val totalData = records.size
    val totalPhotos = records.sumOf { PhotoCodec.decode(it.photos).size }
    val now = Calendar.getInstance()
    val nowYear = now.get(Calendar.YEAR)
    val nowMonth = now.get(Calendar.MONTH)
    val monthCount = records.count { e ->
        val c = Calendar.getInstance()
        c.timeInMillis = e.installDate
        c.get(Calendar.YEAR) == nowYear && c.get(Calendar.MONTH) == nowMonth
    }

    val animatedTotal by animateIntAsState(totalData, tween(700), label = "animTotal")
    val animatedPhotos by animateIntAsState(totalPhotos, tween(700), label = "animPhotos")
    val animatedMonth by animateIntAsState(monthCount, tween(700), label = "animMonth")

    // ---------- Gradient header beranimasi (bergeser pelan tanpa henti) ----------
    val headerTransition = rememberInfiniteTransition(label = "headerGrad")
    val shift by headerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shift"
    )
    val headerBrush = Brush.horizontalGradient(
        colors = listOf(
            lerp(HeaderA, HeaderB, shift),
            lerp(HeaderB, HeaderC, shift),
            lerp(HeaderC, HeaderA, shift)
        )
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ================= HEADER GRADIENT BERANIMASI =================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBrush)
            ) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Aplikasi Petugas",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = Color.White.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(50)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "v2.0",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        "Pencatatan Pemasangan — PLN",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )

                    Spacer(Modifier.height(16.dp))

                    // Kartu profil (nama petugas bisa diedit)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "PETUGAS LAPANGAN",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                            Text(
                                officerName.ifBlank { "Belum diatur" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit nama petugas",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // ================= ISI =================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Statistik ringkas
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatChip(
                        icon = Icons.Filled.Assignment,
                        value = animatedTotal,
                        label = "Total Data",
                        modifier = Modifier.weight(1f).entrance(140)
                    )
                    StatChip(
                        icon = Icons.Filled.PhotoCamera,
                        value = animatedPhotos,
                        label = "Foto Tersimpan",
                        modifier = Modifier.weight(1f).entrance(210)
                    )
                    StatChip(
                        icon = Icons.Filled.EventAvailable,
                        value = animatedMonth,
                        label = "Bulan Ini",
                        modifier = Modifier.weight(1f).entrance(280)
                    )
                }

                // Toggle tema gelap / terang
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(340),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Tema Gelap",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Lebih nyaman saat patroli malam",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isDark,
                            onCheckedChange = onToggleTheme
                        )
                    }
                }

                Text(
                    "Menu Utama",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.entrance(400)
                )

                MenuCard(
                    icon = Icons.Default.AddLocationAlt,
                    title = "Input Data Pemasangan",
                    subtitle = "Catat pemasangan baru + foto",
                    onClick = onNewInstallation,
                    modifier = Modifier.entrance(460)
                )

                MenuCard(
                    icon = Icons.Default.Assignment,
                    title = "Data Pemasangan",
                    subtitle = "Lihat riwayat ($animatedTotal data)",
                    onClick = onOpenList,
                    modifier = Modifier.entrance(540)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    "Peta © OpenStreetMap — Data tersimpan lokal di perangkat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(620)
                )
            }
        }
    }

    if (showEditDialog) {
        var tempName by remember { mutableStateOf(officerName) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Nama Petugas") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    singleLine = true,
                    label = { Text("Masukkan nama petugas") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.setOfficerName(tempName.trim()) }
                    showEditDialog = false
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun StatChip(
    icon: ImageVector,
    value: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$value",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Kartu menu dengan efek tekan (mengecil bouncy saat ditekan). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "menuCardPress"
    )

    ElevatedCard(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
