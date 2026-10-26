package com.petugas.pemasangan.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.petugas.pemasangan.data.InstallationRepository
import com.petugas.pemasangan.data.db.AppDatabase
import com.petugas.pemasangan.data.db.InstallationEntity
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
