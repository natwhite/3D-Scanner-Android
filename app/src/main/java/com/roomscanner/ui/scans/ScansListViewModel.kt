package com.roomscanner.ui.scans

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.roomscanner.data.Scan
import com.roomscanner.data.ScanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScansListViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScanRepository.getInstance(application)

    private val _scans = MutableStateFlow<List<Scan>>(emptyList())
    val scans: StateFlow<List<Scan>> = _scans.asStateFlow()

    fun loadScans() {
        viewModelScope.launch {
            _scans.value = repository.listScans()
        }
    }

    fun deleteScan(scan: Scan) {
        viewModelScope.launch {
            repository.deleteScan(scan)
            loadScans()
        }
    }
}
