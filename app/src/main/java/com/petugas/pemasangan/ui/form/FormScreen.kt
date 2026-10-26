package com.petugas.pemasangan.ui.form

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.petugas.pemasangan.util.formatDate
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    recordId: Long,
    onDone: () -> Unit
) {
    val vm: FormViewModel = viewModel(factory = FormViewModel.Factory)
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                }) { Text("Pilih") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Batal") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    // ---------- Setelah tersimpan ----------
    LaunchedEffect(state.saved) {
        if (state.saved) {
            Toast.makeText(context, "Data pemasangan tersimpan", Toast.LENGTH_LONG).show()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.isEdit) "Edit Data Pemasangan" else "Input Data Pemasangan")
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
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
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ID Pelanggan
                OutlinedTextField(
                    value = state.customerId,
                    onValueChange = vm::setCustomerId,
                    label = { Text("ID Pelanggan") },
                    placeholder = { Text("Contoh: PLG-00123") },
                    isError = state.errors.containsKey("customerId"),
                    supportingText = {
                        state.errors["customerId"]?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Nama Pelanggan
                OutlinedTextField(
                    value = state.customerName,
                    onValueChange = vm::setCustomerName,
                    label = { Text("Nama Pelanggan") },
                    isError = state.errors.containsKey("customerName"),
                    supportingText = {
                        state.errors["customerName"]?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Alamat
                OutlinedTextField(
                    value = state.address,
                    onValueChange = vm::setAddress,
                    label = { Text("Alamat") },
                    isError = state.errors.containsKey("address"),
                    supportingText = {
                        state.errors["address"]?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Koordinat & peta
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Map,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Koordinat Lokasi",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        MapPicker(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            latitude = state.latitude,
                            longitude = state.longitude,
                            onPositionChanged = vm::setPosition
                        )

                        Text(
                            "Tekan lama pada peta untuk menandai lokasi, geser marker untuk memperbaiki, atau gunakan GPS.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val coordText = if (state.latitude != null && state.longitude != null) {
                            String.format(Locale.US, "%.6f, %.6f", state.latitude, state.longitude)
                        } else {
                            "Belum ada titik lokasi"
                        }
                        Text(
                            coordText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        Button(
                            onClick = { useGps() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Ambil Lokasi Saat Ini (GPS)")
                        }

                        state.locationMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        state.errors["location"]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Tanggal pemasangan
                OutlinedTextField(
                    value = formatDate(state.installDate),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Tanggal Pemasangan") },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "Pilih tanggal")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Nama petugas (bisa diedit)
                OutlinedTextField(
                    value = state.officerName,
                    onValueChange = vm::setOfficerName,
                    label = { Text("Nama Petugas") },
                    placeholder = { Text("Nama petugas yang bertugas") },
                    isError = state.errors.containsKey("officerName"),
                    supportingText = {
                        state.errors["officerName"]?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Foto pemasangan
                Text(
                    "Foto Pemasangan (minimal 2, maksimal 4)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(state.photos, key = { _, p -> p }) { index, path ->
                        Box(modifier = Modifier.size(110.dp)) {
                            AsyncImage(
                                model = File(path),
                                contentDescription = "Foto ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${index + 1}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { vm.removePhoto(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Hapus foto",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    if (state.photos.size < FormViewModel.MAX_PHOTOS) {
                        item {
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { takePhoto() },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.PhotoCamera,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Ambil Foto",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                state.errors["photos"]?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                // Catatan opsional
                OutlinedTextField(
                    value = state.notes,
                    onValueChange = vm::setNotes,
                    label = { Text("Catatan (opsional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                // Tombol simpan
                Button(
                    onClick = { vm.save() },
                    enabled = !state.isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.isEdit) "Simpan Perubahan" else "Simpan Data")
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
