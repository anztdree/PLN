package com.petugas.pemasangan.ui.list

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.ui.fx.BoltMark
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.fx.PulseDot
import com.petugas.pemasangan.ui.theme.LocalElectric
import com.petugas.pemasangan.util.PhotoCodec
import com.petugas.pemasangan.util.formatDate
import java.io.File
import kotlinx.coroutines.delay

private val SortOptions = listOf("Terbaru" to 0, "Terlama" to 1, "Nama A-Z" to 2)
private val StatusOptions = listOf("Semua" to 0, "Selesai" to 1, "Belum Selesai" to 2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onBack: () -> Unit,
    onItemClick: (Long) -> Unit,
    onAddClick: () -> Unit
) {
    val vm: ListViewModel = viewModel(factory = ListViewModel.Factory)
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sortMode by vm.sortMode.collectAsStateWithLifecycle()
    val statusFilter by vm.statusFilter.collectAsStateWithLifecycle()
    val e = LocalElectric.current
    val context = LocalContext.current

    var pendingDelete by remember { mutableStateOf<InstallationEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(dense = true)

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassIconButton(onClick = onBack) {
                        PlnIcon(PlnIconKind.BACK, tint = e.textHi, size = 20.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Data Pemasangan",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = e.textHi
                        )
                        Text(
                            "${items.size} data ditampilkan",
                            style = MaterialTheme.typography.labelSmall,
                            color = e.textMid
                        )
                    }
                    GlassIconButton(
                        onClick = { shareRekap(context, items) }
                    ) {
                        PlnIcon(PlnIconKind.SHARE, size = 18.dp, tint = e.cyan)
                    }
                }
            },
            floatingActionButton = {
                val fabScale = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(250)
                    fabScale.animateTo(
                        1f,
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .graphicsLayer {
                            scaleX = fabScale.value
                            scaleY = fabScale.value
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(e.glowYellow.copy(alpha = 0.55f), Color.Transparent),
                                radius = size.minDimension * 0.72f
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(e.goldBrush, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable(onClick = onAddClick),
                        contentAlignment = Alignment.Center
                    ) {
                        PlnIcon(PlnIconKind.PLUS, size = 24.dp, tint = Color(0xFF241A00))
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ---------- Pencarian kaca ----------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(e.glass, RoundedCornerShape(15.dp))
                        .border(1.dp, e.borderBrush, RoundedCornerShape(15.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlnIcon(PlnIconKind.SEARCH, size = 18.dp, tint = e.textMid)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = e.textHi,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(e.yellow),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        "Cari ID, nama, alamat, petugas...",
                                        color = e.textMid.copy(alpha = 0.7f),
                                        fontSize = 15.sp
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (query.isNotEmpty()) {
                        GlassIconButton(onClick = { vm.setQuery("") }, sizeDp = 28.dp) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Bersihkan pencarian",
                                tint = e.textMid,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // ---------- Filter status + sortir ----------
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusOptions.forEach { (label, mode) ->
                        FilterChip(
                            selected = statusFilter == mode,
                            onClick = { vm.setStatusFilter(mode) },
                            label = {
                                Text(
                                    label,
                                    fontWeight = if (statusFilter == mode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = chipColors(e),
                            border = if (statusFilter == mode) null else FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = false,
                                borderColor = e.glassBorderB
                            )
                        )
                    }
                    SortOptions.forEach { (label, mode) ->
                        FilterChip(
                            selected = sortMode == mode,
                            onClick = { vm.setSortMode(mode) },
                            label = {
                                Text(
                                    label,
                                    fontWeight = if (sortMode == mode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = chipColors(e),
                            border = if (sortMode == mode) null else FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = false,
                                borderColor = e.glassBorderB
                            )
                        )
                    }
                }

                if (items.isEmpty()) {
                    EmptyState(hasQuery = query.isNotBlank(), modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items, key = { it.id }) { entity ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value == SwipeToDismissBoxValue.EndToStart) {
                                        pendingDelete = entity
                                    }
                                    false
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = false,
                                modifier = Modifier.animateItem(),
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(e.red.copy(alpha = 0.25f), e.red)
                                                )
                                            )
                                    ) {
                                        PlnIcon(
                                            PlnIconKind.TRASH,
                                            tint = Color.White,
                                            size = 26.dp,
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .padding(end = 24.dp)
                                        )
                                    }
                                }
                            ) {
                                InstallationItem(
                                    entity = entity,
                                    onClick = { onItemClick(entity.id) }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(84.dp)) }
                    }
                }
            }
        }
    }

    // Dialog konfirmasi hapus
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = e.bg1,
            titleContentColor = e.textHi,
            textContentColor = e.textMid,
            title = { Text("Hapus data?") },
            text = {
                Text("Data \"${target.customerName}\" akan dihapus permanen dari perangkat.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    pendingDelete = null
                }) {
                    Text("Hapus", color = e.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Batal", color = e.textMid) }
            }
        )
    }
}

@Composable
private fun chipColors(e: com.petugas.pemasangan.ui.theme.ElectricColors) =
    FilterChipDefaults.filterChipColors(
        containerColor = Color.Transparent,
        selectedContainerColor = e.yellow.copy(alpha = 0.16f),
        labelColor = e.textMid,
        selectedLabelColor = e.yellow,
        iconColor = e.textMid,
        selectedLeadingIconColor = e.yellow
    )

@Composable
private fun EmptyState(hasQuery: Boolean, modifier: Modifier = Modifier) {
    val float = rememberInfiniteTransition(label = "emptyFloat")
    val dy by float.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dy"
    )
    val e = LocalElectric.current

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.graphicsLayer { translationY = dy },
            ) {
                BoltMark(size = 96.dp)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (hasQuery) "Data tidak ditemukan" else "Belum ada data pemasangan",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = e.textHi
            )
            Text(
                if (hasQuery) "Coba kata kunci lain" else "Tekan tombol + untuk mencatat pemasangan baru",
                style = MaterialTheme.typography.bodySmall,
                color = e.textMid
            )
        }
    }
}

@Composable
private fun InstallationItem(
    entity: InstallationEntity,
    onClick: () -> Unit
) {
    val e = LocalElectric.current
    val photos = PhotoCodec.decode(entity.photos)
    val isDone = entity.status == InstallStatus.SELESAI

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(e.glass, RoundedCornerShape(18.dp))
            .border(1.dp, e.borderBrush, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photos.isNotEmpty()) {
                AsyncImage(
                    model = File(photos.first()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.5.dp, e.goldBrush, RoundedCornerShape(13.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(e.glassBorderB.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    PlnIcon(PlnIconKind.CAMERA, size = 24.dp, tint = e.textMid)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entity.customerName,
                        fontWeight = FontWeight.Bold,
                        color = e.textHi,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    if (isDone) {
                        StatusBadge(text = "Selesai", icon = PlnIconKind.CHECK, gold = true)
                    } else {
                        StatusBadge(text = "Belum Selesai", icon = PlnIconKind.GPS, gold = false)
                    }
                }
                Text(
                    "ID: ${entity.customerId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textMid
                )
                Text(
                    formatDate(entity.installDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = e.textMid
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlnIcon(PlnIconKind.CAMERA, size = 11.dp, tint = e.cyan)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${photos.size} foto · ${entity.officerName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = e.textMid
                    )
                }
            }
            PlnIcon(
                PlnIconKind.CHEVRON,
                size = 18.dp,
                tint = e.textMid,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, icon: PlnIconKind, gold: Boolean) {
    val e = LocalElectric.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (gold) e.yellow.copy(alpha = 0.14f) else e.red.copy(alpha = 0.14f)
            )
            .border(
                1.dp,
                if (gold) e.yellow.copy(alpha = 0.5f) else e.red.copy(alpha = 0.5f),
                RoundedCornerShape(50)
            )
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        if (gold) {
            PlnIcon(icon, size = 10.dp, tint = e.yellow)
        } else {
            PulseDot(e.red, size = 5.dp)
            Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (gold) e.yellow else e.red
        )
    }
}

/** Ekspor rekap seluruh data yang tampil menjadi teks lalu dibagikan. */
private fun shareRekap(context: Context, items: List<InstallationEntity>) {
    val text = buildString {
        appendLine("=== REKAP DATA PEMASANGAN ===")
        appendLine("Aplikasi Petugas PLN — v1.0.0")
        appendLine("Jumlah data: ${items.size}")
        appendLine()
        items.forEachIndexed { i, e ->
            appendLine("${i + 1}. ${e.customerName} (${e.customerId})")
            appendLine("   Alamat : ${e.address}")
            appendLine("   Tanggal: ${formatDate(e.installDate)}")
            appendLine("   Petugas: ${e.officerName}")
            appendLine("   Status : ${if (e.status == InstallStatus.SELESAI) "Selesai" else "Belum Selesai"}")
            appendLine("   Foto   : ${PhotoCodec.decode(e.photos).size}")
            appendLine()
        }
    }
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Ekspor rekap data"))
    }
}
