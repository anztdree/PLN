package com.petugas.pemasangan.ui.form

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.ui.ConfettiOverlay
import com.petugas.pemasangan.ui.fx.ElectricBackdrop
import com.petugas.pemasangan.ui.fx.GlassIconButton
import com.petugas.pemasangan.ui.fx.GlassPanel
import com.petugas.pemasangan.ui.fx.GoldButton
import com.petugas.pemasangan.ui.fx.GoldSpinner
import com.petugas.pemasangan.ui.fx.PlnIcon
import com.petugas.pemasangan.ui.fx.PlnIconKind
import com.petugas.pemasangan.ui.popIn
import com.petugas.pemasangan.util.formatDate
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    recordId: Long,
    onDone: () -> Unit
) {
    val vm: FormViewModel = viewModel(factory = FormViewModel.Factory)
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val e = com.petugas.pemasangan.ui.theme.LocalElectric.current

    LaunchedEffect(recordId) {
        if (recordId > 0) vm.loadRecord(recordId)
    }

    // ---------- Kamera ----------
    var pendingPhotoPath by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val path = pendingPhotoPath
        if (ok && path != null) vm.addPhoto(path)
        pendingPhotoPath = null
    }

    fun launchCamera() {
        val pair = vm.createPhotoFile()
        if (pair == null) {
            Toast.makeText(context, "Gagal menyiapkan kamera", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPhotoPath = pair.second
        cameraLauncher.launch(pair.first)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(context, "Izin kamera diperlukan untuk mengambil foto", Toast.LENGTH_SHORT).show()
    }

    fun takePhoto() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) launchCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ---------- GPS ----------
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            vm.requestLocation()
        } else {
            Toast.makeText(context, "Izin lokasi diperlukan untuk mengambil koordinat", Toast.LENGTH_SHORT).show()
        }
    }

    fun useGps() {
        if (vm.hasLocationPermission()) {
            vm.requestLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // ---------- Date picker ----------
    var showDatePicker by remember { mutableStateOf(false) }
    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = state.installDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { vm.setInstallDate(it) }
                    showDatePicker = false
                }) { Text("Pilih", color = e.yellow) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Batal", color = e.textMid) }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    // ---------- Setelah tersimpan ----------
    LaunchedEffect(state.saved) {
        if (state.saved) {
            delay(900)
            Toast.makeText(context, "Data pemasangan tersimpan", Toast.LENGTH_LONG).show()
            onDone()
        }
    }

    // ---------- Getar saat validasi gagal ----------
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(state.errorTick) {
        if (state.errorTick > 0) {
            listOf(-18f, 18f, -12f, 12f, -6f, 6f, 0f).forEach { v ->
                shakeX.animateTo(v, tween(durationMillis = 45))
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = e.yellow,
        unfocusedBorderColor = e.glassBorderB,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        cursorColor = e.yellow,
        focusedLabelColor = e.yellow,
        unfocusedLabelColor = e.textMid,
        focusedTextColor = e.textHi,
        unfocusedTextColor = e.textHi,
        errorBorderColor = e.red,
        errorLabelColor = e.red,
        errorCursorColor = e.red,
        errorTextColor = e.textHi
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ElectricBackdrop()

        Scaffold(containerColor = Color.Transparent) { padding ->
            if (state.isLoading) {
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
                        .graphicsLayer { translationX = shakeX.value }
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // ---------- App bar kustom ----------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassIconButton(onClick = onDone) {
                            PlnIcon(PlnIconKind.BACK, tint = e.textHi, size = 20.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                if (state.isEdit) "Edit Pemasangan" else "Input Pemasangan",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = e.textHi
                            )
                            Text(
                                "v3.0 • ELECTRIC PREMIUM",
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.5.sp,
                                color = e.yellow
                            )
                        }
                    }

                    // ---------- Section: Data Pelanggan ----------
                    GlassPanel {
                        SectionHeader(PlnIconKind.USER, "DATA PELANGGAN")
                        OutlinedTextField(
                            value = state.customerId,
                            onValueChange = vm::setCustomerId,
                            label = { Text("ID Pelanggan") },
                            placeholder = { Text("Contoh: PLG-00123", color = e.textMid.copy(alpha = 0.6f)) },
                            isError = state.errors.containsKey("customerId"),
                            supportingText = {
                                state.errors["customerId"]?.let {
                                    Text(it, color = e.red)
                                }
                            },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.customerName,
                            onValueChange = vm::setCustomerName,
                            label = { Text("Nama Pelanggan") },
                            isError = state.errors.containsKey("customerName"),
                            supportingText = {
                                state.errors["customerName"]?.let {
                                    Text(it, color = e.red)
                                }
                            },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.address,
                            onValueChange = vm::setAddress,
                            label = { Text("Alamat") },
                            isError = state.errors.containsKey("address"),
                            supportingText = {
                                state.errors["address"]?.let {
                                    Text(it, color = e.red)
                                }
                            },
                            minLines = 2,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // ---------- Section: Lokasi & Peta ----------
                    GlassPanel {
                        SectionHeader(PlnIconKind.MAP, "LOKASI & KOORDINAT")

                        MapPicker(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(230.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            latitude = state.latitude,
                            longitude = state.longitude,
                            onPositionChanged = vm::setPosition
                        )

                        Spacer(Modifier.height(10.dp))

                        val coordText = if (state.latitude != null && state.longitude != null) {
                            String.format(Locale.US, "%.6f, %.6f", state.latitude, state.longitude)
                        } else {
                            "Belum ada titik lokasi"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlnIcon(PlnIconKind.GPS, size = 16.dp, tint = e.cyan)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                coordText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = e.textHi
                            )
                        }

                        Text(
                            "Tekan lama pada peta untuk menandai lokasi, geser marker untuk memperbaiki, atau gunakan GPS.",
                            style = MaterialTheme.typography.bodySmall,
                            color = e.textMid
                        )

                        GoldButton(
                            text = "Ambil Lokasi Saat Ini (GPS)",
                            onClick = { useGps() },
                            modifier = Modifier.fillMaxWidth(),
                            height = 48.dp,
                            leading = { PlnIcon(PlnIconKind.GPS, size = 18.dp, tint = Color(0xFF241A00)) }
                        )

                        state.locationMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = e.red)
                        }
                        state.errors["location"]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = e.red)
                        }
                    }

                    // ---------- Section: Waktu, Petugas & Status ----------
                    GlassPanel {
                        SectionHeader(PlnIconKind.CALENDAR, "JADWAL & PETUGAS")

                        OutlinedTextField(
                            value = formatDate(state.installDate),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Tanggal Pemasangan") },
                            trailingIcon = {
                                GlassIconButton(onClick = { showDatePicker = true }, sizeDp = 34.dp) {
                                    PlnIcon(PlnIconKind.CALENDAR, size = 16.dp, tint = e.yellow)
                                }
                            },
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.officerName,
                            onValueChange = vm::setOfficerName,
                            label = { Text("Nama Petugas") },
                            placeholder = { Text("Nama petugas yang bertugas", color = e.textMid.copy(alpha = 0.6f)) },
                            isError = state.errors.containsKey("officerName"),
                            supportingText = {
                                state.errors["officerName"]?.let {
                                    Text(it, color = e.red)
                                }
                            },
                            singleLine = true,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "STATUS PEKERJAAN",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.5.sp,
                            color = e.textMid
                        )
                        Spacer(Modifier.height(8.dp))
                        StatusSegmented(
                            selected = state.status,
                            onSelect = vm::setStatus
                        )
                    }

                    // ---------- Section: Foto ----------
                    GlassPanel {
                        SectionHeader(PlnIconKind.CAMERA, "DOKUMENTASI FOTO") {
                            Text(
                                "${state.photos.size}/4",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = e.yellow,
                                modifier = Modifier
                                    .background(e.yellow.copy(alpha = 0.12f), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                        Text(
                            "Minimal 2 foto pemasangan (meter, titik sambung, atau papan app).",
                            style = MaterialTheme.typography.bodySmall,
                            color = e.textMid
                        )
                        Spacer(Modifier.height(10.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(state.photos, key = { _, p -> p }) { index, path ->
                                Box(modifier = Modifier.size(112.dp).popIn(index * 70)) {
                                    AsyncImage(
                                        model = File(path),
                                        contentDescription = "Foto ${index + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(18.dp))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(18.dp))
                                            .drawBehind {
                                                drawRoundRect(
                                                    color = e.yellow,
                                                    cornerRadius = CornerRadius(18.dp.toPx()),
                                                    style = Stroke(width = 2.5.dp.toPx())
                                                )
                                            }
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(5.dp)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(e.goldBrush),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            color = Color(0xFF241A00),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    GlassIconButton(
                                        onClick = { vm.removePhoto(index) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp),
                                        sizeDp = 28.dp
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Hapus foto",
                                            tint = e.red,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                            if (state.photos.size < FormViewModel.MAX_PHOTOS) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .size(112.dp)
                                            .popIn()
                                            .drawBehind {
                                                drawRoundRect(
                                                    color = e.yellow.copy(alpha = 0.6f),
                                                    cornerRadius = CornerRadius(18.dp.toPx()),
                                                    style = Stroke(
                                                        width = 2.dp.toPx(),
                                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))
                                                    )
                                                )
                                            }
                                            .background(e.glass, RoundedCornerShape(18.dp))
                                            .clickable { takePhoto() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            PlnIcon(PlnIconKind.CAMERA, size = 28.dp)
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                "Ambil Foto",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = e.textHi
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        state.errors["photos"]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = e.red)
                        }
                    }

                    // ---------- Section: Catatan ----------
                    GlassPanel {
                        SectionHeader(PlnIconKind.NOTE, "CATATAN")
                        OutlinedTextField(
                            value = state.notes,
                            onValueChange = vm::setNotes,
                            label = { Text("Catatan (opsional)") },
                            minLines = 2,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // ---------- Simpan ----------
                    GoldButton(
                        text = if (state.isEdit) "Simpan Perubahan" else "Simpan Data",
                        onClick = { vm.save() },
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                        leading = {
                            if (state.isSaving) {
                                GoldSpinner(size = 20.dp)
                            } else {
                                PlnIcon(PlnIconKind.CHECK, size = 18.dp, tint = Color(0xFF241A00))
                            }
                        }
                    )

                    Spacer(Modifier.height(26.dp))
                }
            }
        }

        // ---------- Overlay saat menyimpan ----------
        AnimatedVisibility(
            visible = state.isSaving || state.saved,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                ConfettiOverlay(visible = state.saved, modifier = Modifier.fillMaxSize())

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(e.glass)
                        .drawBehind { }
                        .padding(horizontal = 36.dp, vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (state.saved) {
                            val checkScale = remember { Animatable(0.3f) }
                            LaunchedEffect(Unit) {
                                checkScale.animateTo(
                                    1f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .graphicsLayer {
                                        scaleX = checkScale.value
                                        scaleY = checkScale.value
                                    }
                                    .clip(CircleShape)
                                    .background(e.goldBrush),
                                contentAlignment = Alignment.Center
                            ) {
                                PlnIcon(PlnIconKind.CHECK, size = 34.dp, tint = Color(0xFF241A00))
                            }
                            Text(
                                "Tersimpan!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = e.textHi
                            )
                        } else {
                            GoldSpinner(size = 32.dp)
                            Text(
                                "Menyimpan data…",
                                style = MaterialTheme.typography.titleSmall,
                                color = e.textHi
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Header section kaca: ikon elektrik + label huruf besar + trailing opsional. */
@Composable
private fun SectionHeader(
    kind: PlnIconKind,
    label: String,
    trailing: (@Composable () -> Unit)? = null
) {
    val e = com.petugas.pemasangan.ui.theme.LocalElectric.current
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

/** Pilihan status pekerjaan dengan pill emas yang berpindah beranimasi. */
@Composable
private fun StatusSegmented(
    selected: String,
    onSelect: (String) -> Unit
) {
    val e = com.petugas.pemasangan.ui.theme.LocalElectric.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (e.dark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f))
    ) {
        val half = maxWidth / 2
        val pillOffset by animateDpAsState(
            targetValue = if (selected == InstallStatus.SELESAI) 0.dp else half,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "statusPill"
        )
        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .width(half)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(e.goldBrush)
        )
        Row(Modifier.fillMaxSize()) {
            SegmentOption(
                label = "Sudah Selesai",
                selected = selected == InstallStatus.SELESAI,
                modifier = Modifier.weight(1f)
            ) { onSelect(InstallStatus.SELESAI) }
            SegmentOption(
                label = "Belum Selesai",
                selected = selected == InstallStatus.PENDING,
                modifier = Modifier.weight(1f)
            ) { onSelect(InstallStatus.PENDING) }
        }
    }
}

@Composable
private fun SegmentOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val e = com.petugas.pemasangan.ui.theme.LocalElectric.current
    Box(
        modifier = modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (selected) Color(0xFF241A00) else e.textMid
        )
    }
}
