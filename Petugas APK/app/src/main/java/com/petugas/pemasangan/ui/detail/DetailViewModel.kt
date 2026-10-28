package com.petugas.pemasangan.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.petugas.pemasangan.data.InstallationRepository
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallStatus
import com.petugas.pemasangan.data.db.InstallationEntity
import com.petugas.pemasangan.data.remote.ReportUploader
import com.petugas.pemasangan.util.PhotoCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class DetailViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InstallationRepository(AppDatabase.get(app).installationDao())

    private val _record = MutableStateFlow<InstallationEntity?>(null)
    val record: StateFlow<InstallationEntity?> = _record.asStateFlow()

    private var loadedId: Long = 0L

    fun load(id: Long) {
        if (id <= 0L || loadedId == id) return
        loadedId = id
        viewModelScope.launch {
            repo.observeById(id).collect { _record.value = it }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val entity = _record.value ?: return
        viewModelScope.launch {
            PhotoCodec.decode(entity.photos).forEach { path -> File(path).delete() }
            repo.delete(entity)
            onDeleted()
        }
    }

    /**
     * Ubah status pekerjaan (SELESAI / PENDING) langsung dari layar detail (v3.0).
     * v1.17-m18 (permintaan pemilik): alur SATU ARAH — begitu SELESAI,
     * status terkunci dan tidak bisa kembali ke PENDING. Pending tetap
     * bisa menerima catatan pusat & ditandai selesai manual.
     */
    fun setStatus(status: String) {
        val entity = _record.value ?: return
        if (entity.status == InstallStatus.SELESAI && status == InstallStatus.PENDING) return
        viewModelScope.launch {
            repo.upsert(entity.copy(status = status, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * v1.16-m17 — kirim ulang data yang GAGAL sampai ke pusat (jaringan
     * buruk saat pertama kirim). Bukan edit: isi data persis sama, server
     * mengenali lewat client_ref sehingga tidak jadi laporan ganda.
     * Berhasil -> kirimPusatAt tercatat, banner "Kirim Ulang" hilang.
     *
     * v1.18-m19 (permintaan pemilik): SEBELUM mengirim apa pun, tanya
     * dulu ke pusat lewat client_ref. Kalau ternyata SUDAH sampai,
     * kirim ulang DIBATALKAN total — cukup catat waktu sampai. Kalau
     * pusat tak bisa dihubungi untuk memastikan, jangan kirim juga
     * (petugas mencari sinyal lalu menekan tombol lagi).
     */
    fun kirimUlang(onResult: (Boolean) -> Unit) {
        val entity = _record.value ?: return
        viewModelScope.launch {
            // 1) Pastikan dulu: sudah sampai atau belum.
            val cek = ReportUploader.cekSampai(entity)
            when (cek) {
                true -> {
                    // Sudah diterima pusat -> LARANG kirim ulang.
                    repo.upsert(
                        entity.copy(
                            kirimPusatAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    onResult(true)
                    return@launch
                }
                null -> {
                    // Tidak bisa memastikan -> jangan kirim apa-apa.
                    onResult(false)
                    return@launch
                }
                false -> Unit // benar-benar belum ada di pusat -> boleh kirim
            }
            // 2) Baru kirim.
            val hasil = ReportUploader.kirim(entity)
            if (hasil.isSuccess) {
                repo.upsert(
                    entity.copy(
                        kirimPusatAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            onResult(hasil.isSuccess)
        }
    }

    /**
     * v1.18-m19 — pusat mengonfirmasi data SUDAH diterima (cek client_ref),
     * jadi status lokal disamakan tanpa mengunggah ulang apa pun. Efeknya
     * banner "Kirim Ulang" menghilang dan data tidak bisa dikirim ganda.
     */
    fun tandaiSampai() {
        val entity = _record.value ?: return
        if (entity.kirimPusatAt > 0L) return
        viewModelScope.launch {
            repo.upsert(
                entity.copy(
                    kirimPusatAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                DetailViewModel(app)
            }
        }
    }
}
