package com.petugas.pemasangan.ui.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.data.remote.ReportUploader
import com.petugas.pemasangan.service.CatatanPoller
import com.petugas.pemasangan.ui.entrance
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.GoldButton
import com.petugas.pemasangan.ui.fx.GoldSpinner
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.fx.PulseDot
import com.petugas.pemasangan.ui.popIn
import com.petugas.pemasangan.ui.theme.LocalElectric
import com.petugas.pemasangan.util.PhotoCodec
import com.petugas.pemasangan.util.formatDate
import com.petugas.pemasangan.util.formatStamp
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    recordId: Long,
    onBack: () -> Unit
) {
    val vm: DetailViewModel = viewModel(factory = DetailViewModel.Factory)
    val record by vm.record.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val e = LocalElectric.current

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSelesaiDialog by remember { mutableStateOf(false) }
    var kirimUlangSedang by remember { mutableStateOf(false) }
    var fullscreenPhotoIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(recordId) { vm.load(recordId) }

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop(modifier = Modifier.fillMaxSize(), dense = true)

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
                    Text(
                        "Detail Pemasangan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = e.textHi,
                        modifier = Modifier.weight(1f)
                    )
                    // v1.16-m17: tombol EDIT DIHAPUS — data yang sudah
                    // dikirim hanya tampil, tidak bisa diubah. Salah isi
                    // dicegah lewat konfirmasi "Mohon pastikan lagi data
                    // sudah benar" sebelum kirim di layar Input.
                    GlassIconButton(onClick = { showDeleteDialog = true }) {
                        PlnIcon(PlnIconKind.TRASH, size = 18.dp, tint = e.red)
                    }
                }
            }
        ) { padding ->
            val entity = record
            if (entity == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    GoldSpinner(size = 34.dp)
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
                    // ---------- CATATAN DARI PUSAT — PALING ATAS ----------
                    // v1.18-m20 (permintaan pemilik): catatan pusat harus
                    // TAMPIL DI BAGIAN ATAS data dan BERBEDA WARNA — kini
                    // jadi kartu siaga merah pertama yang langsung terlihat
                    // begitu data dibuka (bukan panel kaca biasa di bawah).
                    // Munculnya juga menandai "sudah dilihat" (angka merah
                    // di menu turun, tanda merah di daftar padam).
                    if (entity.catatanPusat.isNotBlank()) {
                        val appCtx = context.applicationContext
                        LaunchedEffect(entity.catatanPusat, entity.catatanPusatAt) {
                            CatatanPoller.tandaiDilihat(
                                appCtx,
                                CatatanPoller.clientRefOf(entity),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .entrance(60)
                                .clip(RoundedCornerShape(18.dp))
                                .background(e.red.copy(alpha = 0.10f))
                                .border(1.5.dp, e.red.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(e.red.copy(alpha = 0.18f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    PlnIcon(PlnIconKind.NOTE, size = 17.dp, tint = e.red)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "CATATAN DARI PUSAT",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.4.sp,
                                        color = e.red
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        entity.catatanPusat,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = e.yellow
                                    )
                                    if (entity.catatanPusatAt > 0L) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "Ditulis pusat · " + formatStamp(entity.catatanPusatAt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = e.textMid
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---------- HERO ----------
                    GlassPanel(shape = RoundedCornerShape(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(e.goldBrush),
                                contentAlignment = Alignment.Center
                            ) {
                                PlnIcon(PlnIconKind.USER, size = 26.dp, tint = Color(0xFF241A00))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entity.customerName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = e.textHi
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "ID Pelanggan: ${entity.customerId}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = e.textMid
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (entity.status == InstallStatus.SELESAI) {
                                HeroBadge(text = "SELESAI", icon = PlnIconKind.CHECK, gold = true)
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(e.red.copy(alpha = 0.13f))
                                        .border(1.dp, e.red.copy(alpha = 0.5f), RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    PulseDot(e.red, size = 5.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "BELUM SELESAI",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = e.red
                                    )
                                }
                            }
                            HeroBadge(
                                text = formatDate(entity.installDate),
                                icon = PlnIconKind.CALENDAR,
                                gold = false
                            )
                        }
                    }

                    // ---------- Ubah status cepat ----------
                    // v1.17-m18 (permintaan pemilik): alur status SATU ARAH —
                    // pending -> selesai boleh (dilakukan manual), selesai ->
                    // pending TIDAK bisa. Saat pending, catatan dari pusat
                    // tetap mengalir (pemantau CatatanPoller).
                    val sudahSelesai = entity.status == InstallStatus.SELESAI
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction(
                            label = "Tandai Selesai",
                            icon = PlnIconKind.CHECK,
                            active = sudahSelesai,
                            modifier = Modifier.weight(1f),
                            onClick = { showSelesaiDialog = true }
                        )
                        QuickAction(
                            label = "Tandai Pending",
                            icon = PlnIconKind.GPS,
                            active = entity.status == InstallStatus.PENDING,
                            enabled = !sudahSelesai,
                            modifier = Modifier.weight(1f),
                            onClick = { vm.setStatus(InstallStatus.PENDING) }
                        )
                    }
                    if (sudahSelesai) {
                        Text(
                            "Status selesai bersifat tetap — tidak dapat diubah kembali ke pending.",
                            style = MaterialTheme.typography.labelSmall,
                            color = e.textMid
                        )
                    }

                    // ---------- Informasi pelanggan ----------
                    GlassPanel(modifier = Modifier.entrance(80)) {
                        SectionHeader(PlnIconKind.USER, "INFORMASI PELANGGAN")
                        DetailRow(PlnIconKind.LIST, "ID Pelanggan", entity.customerId)
                        DetailRow(PlnIconKind.USER, "Nama Pelanggan", entity.customerName)
                        DetailRow(PlnIconKind.NOTE, "Alamat", entity.address)
                    }

                    // ---------- Lokasi & waktu ----------
                    GlassPanel(modifier = Modifier.entrance(160)) {
                        SectionHeader(PlnIconKind.MAP, "LOKASI & WAKTU")
                        DetailRow(
                            PlnIconKind.GPS,
                            "Koordinat",
                            String.format(Locale.US, "%.6f, %.6f", entity.latitude, entity.longitude)
                        )
                        DetailRow(PlnIconKind.CALENDAR, "Tanggal Pemasangan", formatDate(entity.installDate))
                        DetailRow(PlnIconKind.EDIT, "Nama Petugas", entity.officerName)
                        Spacer(Modifier.height(6.dp))
                        GoldButton(
                            text = "Buka di Google Maps",
                            onClick = {
                                val geo = "geo:${entity.latitude},${entity.longitude}" +
                                    "?q=${entity.latitude},${entity.longitude}(Pemasangan)"
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo)))
                                }.onFailure {
                                    Toast.makeText(context, "Aplikasi peta tidak ditemukan", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            height = 48.dp,
                            leading = { PlnIcon(PlnIconKind.MAP, size = 18.dp, tint = Color(0xFF241A00)) }
                        )
                    }

                    // ---------- Foto ----------
                    GlassPanel(modifier = Modifier.entrance(240)) {
                        SectionHeader(PlnIconKind.CAMERA, "FOTO PEMASANGAN") {
                            Text(
                                "${PhotoCodec.decode(entity.photos).size} foto",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = e.yellow
                            )
                        }
                        val photos = PhotoCodec.decode(entity.photos)
                        if (photos.isEmpty()) {
                            Text("Tidak ada foto", style = MaterialTheme.typography.bodySmall, color = e.textMid)
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(photos) { index, path ->
                                    Box(
                                        modifier = Modifier
                                            .size(122.dp)
                                            .popIn(index * 60)
                                    ) {
                                        AsyncImage(
                                            model = File(path),
                                            contentDescription = "Foto ${index + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable { fullscreenPhotoIndex = index }
                                        )
                                        // sudut bracket emas
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .drawBehind {
                                                    val len = 22.dp.toPx()
                                                    val w = 3.dp.toPx()
                                                    val g = Brush.linearGradient(
                                                        listOf(e.yellow, e.gold)
                                                    )
                                                    val inset = 6.dp.toPx()
                                                    // 4 sudut
                                                    fun corner(path: Path.() -> Unit) {
                                                        val p = Path().apply(path)
                                                        drawPath(p, g, style = Stroke(w, cap = StrokeCap.Round))
                                                    }
                                                    corner {
                                                        moveTo(inset, inset + len)
                                                        lineTo(inset, inset)
                                                        lineTo(inset + len, inset)
                                                    }
                                                    corner {
                                                        moveTo(size.width - inset - len, inset)
                                                        lineTo(size.width - inset, inset)
                                                        lineTo(size.width - inset, inset + len)
                                                    }
                                                    corner {
                                                        moveTo(size.width - inset, size.height - inset - len)
                                                        lineTo(size.width - inset, size.height - inset)
                                                        lineTo(size.width - inset - len, size.height - inset)
                                                    }
                                                    corner {
                                                        moveTo(inset + len, size.height - inset)
                                                        lineTo(inset, size.height - inset)
                                                        lineTo(inset, size.height - inset - len)
                                                    }
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---------- Kirim ulang bila benar-benar belum sampai ----------
                    // v1.18-m19 (permintaan pemilik): SEBELUM menawarkan
                    // "Kirim Ulang", APK bertanya dulu ke pusat lewat
                    // client_ref. Kalau ternyata SUDAH diterima pusat,
                    // kirim ulang DIKUNCI (data tak mungkin ganda) dan
                    // status lokal otomatis disamakan. Tombol "Kirim Ulang"
                    // hanya muncul kalau pusat membenarkan data belum ada.
                    if (entity.kirimPusatAt == 0L) {
                        var statusCek by remember(entity.id) { mutableStateOf("mengecek") }
                        var cekTick by remember(entity.id) { mutableStateOf(0) }
                        LaunchedEffect(entity.id, cekTick) {
                            statusCek = "mengecek"
                            when (ReportUploader.cekSampai(entity)) {
                                true -> {
                                    // Pusat: "sudah diterima" -> kunci kirim ulang.
                                    vm.tandaiSampai()
                                    Toast.makeText(
                                        context,
                                        "Data sudah sampai pusat ✓ — kirim ulang tidak diperlukan",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                false -> statusCek = "belum"
                                null -> statusCek = "gagal"
                            }
                        }
                        GlassPanel(modifier = Modifier.entrance(280)) {
                            SectionHeader(
                                PlnIconKind.SEND,
                                if (statusCek == "belum") "BELUM SAMPAI KE PUSAT" else "MEMERIKSA KE PUSAT"
                            )
                            Text(
                                when (statusCek) {
                                    "mengecek" ->
                                        "Memastikan dulu ke pusat apakah data ini sudah diterima…"
                                    "belum" ->
                                        "Pusat membenarkan data ini belum diterima " +
                                            "(sinyal sempat putus). Tekan Kirim Ulang — isi data tidak berubah."
                                    else ->
                                        "Tidak bisa memastikan ke pusat (sinyal?). Periksa ulang dulu — " +
                                            "kirim ulang hanya dibuka kalau pusat membenarkan data belum ada."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = e.textMid
                            )
                            Spacer(Modifier.height(10.dp))
                            if (statusCek == "belum") {
                                GoldButton(
                                    text = if (kirimUlangSedang) "Mengirim…" else "Kirim Ulang",
                                    onClick = {
                                        kirimUlangSedang = true
                                        vm.kirimUlang { berhasil ->
                                            kirimUlangSedang = false
                                            Toast.makeText(
                                                context,
                                                if (berhasil) "Data terkirim ke pusat ✓"
                                                else "Masih gagal — cari sinyal lalu coba lagi",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    enabled = !kirimUlangSedang,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (statusCek == "gagal") {
                                GoldButton(
                                    text = "Periksa Ulang",
                                    onClick = { cekTick++ },
                                    enabled = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // ---------- Kendala (dulu "Catatan") ----------
                    // v1.16-m17 (permintaan pemilik): sebutan kolom isi
                    // petugas diselaraskan dengan form — KENDALA.
                    // v1.18-m20: CATATAN PUSAT pindah ke paling atas layar.
                    if (entity.notes.isNotBlank()) {
                        GlassPanel(modifier = Modifier.entrance(300)) {
                            SectionHeader(PlnIconKind.NOTE, "KENDALA")
                            Text(entity.notes, style = MaterialTheme.typography.bodyMedium, color = e.textHi)
                        }
                    }

                    Spacer(Modifier.height(22.dp))
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = e.bg1,
            titleContentColor = e.textHi,
            textContentColor = e.textMid,
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
                    Text("Hapus", color = e.red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Batal", color = e.textMid) }
            }
        )
    }

    // v1.17-m18: konfirmasi singkat sebelum tanda selesai — tindakan ini
    // satu arah (tidak bisa kembali ke pending), jadi dipastikan dulu.
    if (showSelesaiDialog) {
        AlertDialog(
            onDismissRequest = { showSelesaiDialog = false },
            containerColor = e.bg1,
            titleContentColor = e.textHi,
            textContentColor = e.textMid,
            title = { Text("Tandai selesai?") },
            text = { Text("Setelah selesai, status tidak bisa kembali ke pending.") },
            confirmButton = {
                TextButton(onClick = {
                    showSelesaiDialog = false
                    vm.setStatus(InstallStatus.SELESAI)
                }) {
                    Text("Ya, Selesai", color = e.yellow, fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelesaiDialog = false }) { Text("Batal", color = e.textMid) }
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
private fun HeroBadge(text: String, icon: PlnIconKind, gold: Boolean) {
    val e = LocalElectric.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (gold) e.yellow.copy(alpha = 0.14f) else e.cyan.copy(alpha = 0.10f))
            .border(
                1.dp,
                if (gold) e.yellow.copy(alpha = 0.5f) else e.cyan.copy(alpha = 0.4f),
                RoundedCornerShape(50)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        PlnIcon(icon, size = 12.dp, tint = if (gold) e.yellow else e.cyan)
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (gold) e.yellow else e.cyan
        )
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: PlnIconKind,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val e = LocalElectric.current
    val bg = if (active) e.goldBrush else Brush.linearGradient(listOf(e.glass, e.glass))
    val shape = RoundedCornerShape(15.dp)
    val borderModifier = if (active) {
        Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), shape)
    } else {
        Modifier.border(1.dp, e.borderBrush, shape)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .then(borderModifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp)
            // v1.17-m18: tombol terkunci (SELESAI -> Pending) tampak redup.
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlnIcon(
                icon,
                size = 15.dp,
                tint = if (active) Color(0xFF241A00) else e.textMid
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (active) Color(0xFF241A00) else e.textMid
            )
        }
    }
}

@Composable
private fun SectionHeader(kind: PlnIconKind, label: String, trailing: (@Composable () -> Unit)? = null) {
    val e = LocalElectric.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(e.yellow.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            PlnIcon(kind, size = 16.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.6.sp,
            color = e.textHi
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun DetailRow(kind: PlnIconKind, label: String, value: String) {
    val e = LocalElectric.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (e.dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center
        ) {
            PlnIcon(kind, size = 15.dp, tint = e.cyan)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = e.textMid
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = e.textHi
            )
        }
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
            GlassIconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Tutup",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
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
