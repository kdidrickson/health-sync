package com.healthsync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.healthsync.data.SyncWorker
import com.healthsync.util.PreferencesHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncUiState(
    val healthConnectAvailable: Boolean = false,
    val lastSyncTime: String = "Never",
    val lastSyncResult: String = "—",
    val isSyncing: Boolean = false,
    val permissionsGranted: Map<String, Boolean> = emptyMap(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app)
    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        observeWorkManager()
        loadPersistedState()
    }

    private fun observeWorkManager() {
        viewModelScope.launch {
            workManager
                .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME)
                .collect { infos ->
                    val syncing = infos.any { it.state == WorkInfo.State.RUNNING }
                    _uiState.update { it.copy(isSyncing = syncing) }
                    if (!syncing) loadPersistedState()
                }
        }
    }

    private fun loadPersistedState() {
        val ctx = getApplication<Application>()
        val timeMs = PreferencesHelper.getLastSyncTime(ctx)
        val result = PreferencesHelper.getLastSyncResult(ctx)
        val timeStr = if (timeMs == 0L) "Never"
        else SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()).format(Date(timeMs))
        _uiState.update { it.copy(lastSyncTime = timeStr, lastSyncResult = result) }
    }

    fun updateHealthConnectAvailability(available: Boolean) {
        _uiState.update { it.copy(healthConnectAvailable = available) }
    }

    fun updatePermissions(permissions: Map<String, Boolean>) {
        _uiState.update { it.copy(permissionsGranted = permissions) }
    }

    fun triggerImmediateSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
        workManager.enqueue(request)
    }
}
