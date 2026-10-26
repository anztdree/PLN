package com.petugas.pemasangan.ui.list

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ListViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = InstallationRepository(AppDatabase.get(app).installationDao())

    private val queryFlow = MutableStateFlow("")
    val query: StateFlow<String> = queryFlow.asStateFlow()

    /** 0 = Terbaru, 1 = Terlama, 2 = Nama A-Z. */
    private val sortModeFlow = MutableStateFlow(0)
    val sortMode: StateFlow<Int> = sortModeFlow.asStateFlow()

    /** 0 = Semua, 1 = Selesai, 2 = Belum Selesai (v3.0). */
    private val statusFilterFlow = MutableStateFlow(0)
    val statusFilter: StateFlow<Int> = statusFilterFlow.asStateFlow()

    val items: StateFlow<List<InstallationEntity>> =
        combine(repo.observeAll(), queryFlow, sortModeFlow, statusFilterFlow) { list, q, sort, status ->
            var filtered = if (q.isBlank()) {
                list
            } else {
                list.filter {
                    it.customerId.contains(q, ignoreCase = true) ||
                        it.customerName.contains(q, ignoreCase = true) ||
                        it.address.contains(q, ignoreCase = true) ||
                        it.officerName.contains(q, ignoreCase = true)
                }
            }
            filtered = when (status) {
                1 -> filtered.filter { it.status == InstallStatus.SELESAI }
                2 -> filtered.filter { it.status == InstallStatus.PENDING }
                else -> filtered
            }
            when (sort) {
                1 -> filtered.sortedBy { it.installDate }
                2 -> filtered.sortedBy { it.customerName.lowercase() }
                else -> filtered
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        queryFlow.value = q
    }

    fun setSortMode(mode: Int) {
        sortModeFlow.value = mode
    }

    fun setStatusFilter(mode: Int) {
        statusFilterFlow.value = mode
    }

    /** Hapus data dari layar riwayat (swipe-to-delete). */
    fun delete(entity: InstallationEntity) {
        viewModelScope.launch { repo.delete(entity) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ListViewModel(app)
            }
        }
    }
}
