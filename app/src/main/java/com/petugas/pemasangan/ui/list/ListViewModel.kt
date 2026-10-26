package com.petugas.pemasangan.ui.list

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.petugas.pemasangan.data.InstallationRepository
import com.petugas.pemasangan.data.db.AppDatabase
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

    /** 0 = Terbaru, 1 = Terlama, 2 = Nama A-Z (baru di v2.0). */
    private val sortModeFlow = MutableStateFlow(0)
    val sortMode: StateFlow<Int> = sortModeFlow.asStateFlow()

    val items: StateFlow<List<InstallationEntity>> =
        combine(repo.observeAll(), queryFlow, sortModeFlow) { list, q, sort ->
            val filtered = if (q.isBlank()) {
                list
            } else {
                list.filter {
                    it.customerId.contains(q, ignoreCase = true) ||
                        it.customerName.contains(q, ignoreCase = true) ||
                        it.address.contains(q, ignoreCase = true) ||
                        it.officerName.contains(q, ignoreCase = true)
                }
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

    /** Hapus data dari layar riwayat (swipe-to-delete, v2.0). */
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
