package com.petugas.pemasangan.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.util.PhotoCodec
import com.petugas.pemasangan.util.formatDate
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    recordId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit
) {
    val vm: DetailViewModel = viewModel(factory = DetailViewModel.Factory)
    val record by vm.record.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var fullscreenPhotoIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(recordId) { vm.load(recordId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detail Pemasangan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { record?.let { onEdit(it.id) } },
                        enabled = record != null
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = record != null
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Hapus")
                    }
                }
            )
        }
    ) { padding ->
        val entity = record
        if (entity == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(title = "Informasi Pelanggan") {
                    DetailRow("ID Pelanggan", entity.customerId)
                    DetailRow("Nama Pelanggan", entity.customerName)
                    DetailRow("Alamat", entity.address)
                }

                SectionCard(title = "Lokasi & Waktu") {
                    DetailRow(
                        "Koordinat",
                        String.format(Locale.US, "%.6f, %.6f", entity.latitude, entity.longitude)
                    )
                    DetailRow("Tanggal Pemasangan", formatDate(entity.installDate))
                    DetailRow("Nama Petugas", entity.officerName)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = {
                            val geo = "geo:${entity.latitude},${entity.longitude}" +
                                "?q=${entity.latitude},${entity.longitude}(Pemasangan)"
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo)))
                            }.onFailure {
                                Toast.makeText(context, "Aplikasi peta tidak ditemukan", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Buka di Google Maps")
                    }
                }

                SectionCard(title = "Foto Pemasangan") {
                    val photos = PhotoCodec.decode(entity.photos)
                    if (photos.isEmpty()) {
                        Text("Tidak ada foto", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(photos) { index, path ->
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = "Foto ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { fullscreenPhotoIndex = index }
                                )
                            }
                        }
                    }
                }

                if (entity.notes.isNotBlank()) {
                    SectionCard(title = "Catatan") {
                        Text(entity.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OutlinedButton(
                    onClick = { shareRecord(context, entity) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Bagikan Data")
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Hapus data?") },
            text = { Text("Data pemasangan beserta foto-fotonya akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    vm.delete {
                        Toast.makeText(context, "Data dihapus", Toast.LENGTH_SHORT).show()
                        onBack()
                    }
                }) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Batal") }
            }
        )
    }

    fullscreenPhotoIndex?.let { startIndex ->
        val photos = PhotoCodec.decode(record?.photos ?: "")
        if (photos.isNotEmpty()) {
            FullscreenPhotoDialog(
                paths = photos,
                initialPage = startIndex,
                onDismiss = { fullscreenPhotoIndex = null }
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FullscreenPhotoDialog(
    paths: List<String>,
    initialPage: Int,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialPage,
            pageCount = { paths.size }
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = File(paths[page]),
                    contentDescription = "Foto ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White)
            }
            Text(
                "${pagerState.currentPage + 1}/${paths.size}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

private fun shareRecord(context: Context, e: InstallationEntity) {
    val text = buildString {
        appendLine("DATA PEMASANGAN")
        appendLine("ID Pelanggan : ${e.customerId}")
        appendLine("Nama Pelanggan : ${e.customerName}")
        appendLine("Alamat : ${e.address}")
        appendLine("Koordinat : ${e.latitude}, ${e.longitude}")
        appendLine("Tanggal : ${formatDate(e.installDate)}")
        appendLine("Petugas : ${e.officerName}")
        appendLine("Jumlah Foto : ${PhotoCodec.decode(e.photos).size}")
        append("Maps: https://maps.google.com/?q=${e.latitude},${e.longitude}")
    }
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan data"))
    }
}
