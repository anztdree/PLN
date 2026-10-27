package com.petugas.pemasangan.ui.form

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.petugas.pemasangan.data.InstallationRepository
import com.petugas.pemasangan.data.SettingsRepository
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.util.PhotoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class FormUiState(
    val isEdit: Boolean = false,
    val recordId: Long = 0L,
    val customerId: String = "",
    val customerName: String = "",
    val address: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val installDate: Long = System.currentTimeMillis(),
    val officerName: String = "",
    val photos: List<String> = emptyList(),
    val notes: String = "",
    val status: String = InstallStatus.SELESAI,
    val errors: Map<String, String> = emptyMap(),
    /** Bertambah 1 setiap validasi gagal — pemicu animasi getar di FormScreen. */
    val errorTick: Int = 0,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val isLoading: Boolean = false,
    val loadedFromRecord: Boolean = false,
    val locationMessage: String? = null,
    val createdAt: Long = 0L,
    val photosToDelete: List<String> = emptyList()
)

class FormViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InstallationRepository(AppDatabase.get(app).installationDao())
    private val settings = SettingsRepository(app)

    private val _ui = MutableStateFlow(FormUiState())
    val ui: StateFlow<FormUiState> = _ui.asStateFlow()

    /** Foto milik record lama (mode edit) sebagai pembanding. */
    private var originalPhotos: List<String> = emptyList()

    init {
        viewModelScope.launch {
            settings.officerNameFlow.collect { name ->
                if (!_ui.value.loadedFromRecord) {
                    _ui.update { it.copy(officerName = name) }
                }
            }
        }
    }

    fun loadRecord(id: Long) {
        if (id <= 0L || _ui.value.loadedFromRecord) return
        _ui.update { it.copy(loadedFromRecord = true, isLoading = true) }
        viewModelScope.launch {
            repo.observeById(id).collect { entity ->
                if (entity != null) {
                    originalPhotos = PhotoCodec.decode(entity.photos)
                    _ui.update {
                        it.copy(
                            isEdit = true,
                            recordId = entity.id,
                            customerId = entity.customerId,
                            customerName = entity.customerName,
                            address = entity.address,
                            latitude = entity.latitude,
                            longitude = entity.longitude,
                            installDate = entity.installDate,
                            officerName = entity.officerName,
                            photos = originalPhotos,
                            notes = entity.notes,
                            status = entity.status,
                            createdAt = entity.createdAt,
                            isLoading = false
                        )
                    }
                } else {
                    _ui.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    // ---------- Setter field ----------

    fun setCustomerId(v: String) =
        _ui.update { it.copy(customerId = v, errors = it.errors - "customerId") }

    fun setCustomerName(v: String) =
        _ui.update { it.copy(customerName = v, errors = it.errors - "customerName") }

    fun setAddress(v: String) =
        _ui.update { it.copy(address = v, errors = it.errors - "address") }

    fun setOfficerName(v: String) =
        _ui.update { it.copy(officerName = v, errors = it.errors - "officerName") }

    fun setNotes(v: String) = _ui.update { it.copy(notes = v) }

    /** Status pekerjaan: SELESAI / PENDING (v3.0). */
    fun setStatus(v: String) = _ui.update { it.copy(status = v) }

    fun setInstallDate(millis: Long) = _ui.update { it.copy(installDate = millis) }

    fun setPosition(lat: Double, lng: Double) =
        _ui.update {
            it.copy(latitude = lat, longitude = lng, errors = it.errors - "location")
        }

    // ---------- Foto ----------

    /** Membuat file target foto + content Uri untuk kontrak TakePicture. */
    fun createPhotoFile(): Pair<Uri, String>? {
        val context = getApplication<Application>()
        return runCatching {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "pemasangan"
            )
            dir.mkdirs()
            val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            uri to file.absolutePath
        }.getOrNull()
    }

    fun addPhoto(path: String) = _ui.update {
        if (it.photos.size >= MAX_PHOTOS || it.photos.contains(path)) {
            it
        } else {
            it.copy(photos = it.photos + path, errors = it.errors - "photos")
        }
    }

    fun removePhoto(index: Int) = _ui.update { st ->
        val path = st.photos.getOrNull(index) ?: return@update st
        val newPhotos = st.photos.filterIndexed { i, _ -> i != index }
        val photosToDelete = if (path in originalPhotos) {
            // Foto lama milik record: jangan dihapus sebelum data disimpan.
            st.photosToDelete + path
        } else {
            // Foto baru di sesi ini: langsung hapus file-nya.
            File(path).delete()
            st.photosToDelete
        }
        st.copy(photos = newPhotos, photosToDelete = photosToDelete)
    }

    // ---------- Lokasi ----------

    fun hasLocationPermission(): Boolean {
        val context = getApplication<Application>()
        val fine = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun requestLocation() {
        if (!hasLocationPermission()) return
        val client =
            LocationServices.getFusedLocationProviderClient(getApplication<Application>())
        _ui.update { it.copy(locationMessage = "Mengambil lokasi GPS…") }

        client.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { loc ->
            if (loc != null) {
                _ui.update {
                    it.copy(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        locationMessage = null,
                        errors = it.errors - "location"
                    )
                }
            } else {
                client.lastLocation.addOnSuccessListener { last ->
                    if (last != null) {
                        _ui.update {
                            it.copy(
                                latitude = last.latitude,
                                longitude = last.longitude,
                                locationMessage = null,
                                errors = it.errors - "location"
                            )
                        }
                    } else {
                        _ui.update {
                            it.copy(locationMessage = "Lokasi belum didapat. Pastikan GPS aktif lalu coba lagi.")
                        }
                    }
                }.addOnFailureListener {
                    _ui.update {
                        it.copy(locationMessage = "Lokasi belum didapat. Pastikan GPS aktif lalu coba lagi.")
                    }
                }
            }
        }.addOnFailureListener { e ->
            _ui.update {
                it.copy(locationMessage = "Gagal mengambil lokasi: ${e.message ?: "error"}")
            }
        }
    }

    // ---------- Koordinat -> Alamat (otomatis setelah Oke) ----------

    /**
     * Memasukkan titik koordinat yang sudah dipilih ke dalam field alamat.
     * Dipanggil saat petugas menekan tombol OKE pada seksi Lokasi & Koordinat.
     * - Alamat kosong      -> diisi nama tempat (reverse geocode) + koordinat.
     * - Alamat sudah ada   -> koordinat ditambahkan di baris baru, teks petugas dipertahankan.
     * - Sudah ada GPS lama -> bagian GPS diganti koordinat terbaru (tidak menumpuk).
     */
    fun applyLocationToAddress() {
        val lat = _ui.value.latitude ?: return
        val lng = _ui.value.longitude ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val place = reverseGeocode(lat, lng)
            withContext(Dispatchers.Main) {
                _ui.update { st ->
                    val curLat = st.latitude
                    val curLng = st.longitude
                    if (curLat == null || curLng == null) return@update st
                    val coord = String.format(Locale.US, "GPS: %.6f, %.6f", curLat, curLng)
                    val base0 = st.address.trim()
                    val base = if (base0.contains("GPS:", ignoreCase = true)) {
                        base0
                            .replace(
                                Regex(
                                    "\\s*GPS:\\s*-?[0-9]+(?:\\.[0-9]+)?,\\s*-?[0-9]+(?:\\.[0-9]+)?",
                                    RegexOption.IGNORE_CASE
                                ),
                                ""
                            )
                            .trim()
                            .trimEnd('\n', ' ', ',', '-', '\u2022')
                    } else {
                        base0
                    }
                    val merged = when {
                        base.isEmpty() && place != null -> "$place ($coord)"
                        base.isEmpty() -> coord
                        else -> "$base\n$coord"
                    }
                    st.copy(address = merged, errors = st.errors - "address")
                }
            }
        }
    }

    /** Reverse geocode nama tempat dari koordinat (butuh backend geocoder perangkat; aman gagal). */
    private fun reverseGeocode(lat: Double, lng: Double): String? = runCatching {
        val context = getApplication<Application>()
        if (!Geocoder.isPresent()) return@runCatching null
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val results = geocoder.getFromLocation(lat, lng, 1)
        val a = results?.firstOrNull() ?: return@runCatching null
        val road = listOfNotNull(a.thoroughfare, a.subThoroughfare)
            .joinToString(" ")
            .trim()
        val area = listOfNotNull(a.subLocality, a.locality ?: a.subAdminArea, a.adminArea)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        listOf(road, area)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { null }
    }.getOrNull()

    // ---------- Simpan ----------

    fun save() {
        val s = _ui.value
        val errors = buildMap {
            if (s.customerId.isBlank()) put("customerId", "ID Pelanggan wajib diisi")
            if (s.customerName.isBlank()) put("customerName", "Nama pelanggan wajib diisi")
            if (s.address.isBlank()) put("address", "Alamat wajib diisi")
            if (s.officerName.isBlank()) put("officerName", "Nama petugas wajib diisi")
            if (s.latitude == null || s.longitude == null) {
                put("location", "Tentukan titik lokasi: gunakan GPS atau tekan lama pada peta")
            }
            if (s.photos.size < MIN_PHOTOS) {
                put("photos", "Foto minimal $MIN_PHOTOS (saat ini ${s.photos.size})")
            }
        }
        if (errors.isNotEmpty()) {
            _ui.update { it.copy(errors = errors, errorTick = it.errorTick + 1) }
            return
        }

        val now = System.currentTimeMillis()
        val entity = InstallationEntity(
            id = s.recordId,
            customerId = s.customerId.trim(),
            customerName = s.customerName.trim(),
            address = s.address.trim(),
            latitude = s.latitude ?: return,
            longitude = s.longitude ?: return,
            installDate = s.installDate,
            officerName = s.officerName.trim(),
            photos = PhotoCodec.encode(s.photos),
            notes = s.notes.trim(),
            status = s.status,
            createdAt = if (s.isEdit && s.createdAt > 0L) s.createdAt else now,
            updatedAt = now
        )

        viewModelScope.launch {
            _ui.update { it.copy(isSaving = true) }
            repo.upsert(entity)
            // Nama petugas terakhir menjadi default untuk input berikutnya.
            settings.setOfficerName(entity.officerName)
            // Hapus foto lama yang dilepas pengguna saat mode edit.
            s.photosToDelete.forEach { File(it).delete() }
            _ui.update { it.copy(isSaving = false, saved = true) }
        }
    }

    companion object {
        const val MIN_PHOTOS = 2
        const val MAX_PHOTOS = 4

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                FormViewModel(app)
            }
        }
    }
}
