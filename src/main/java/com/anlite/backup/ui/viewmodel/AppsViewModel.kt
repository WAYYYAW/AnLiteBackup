package com.anlite.backup.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.core.engine.RootExecutor
import com.anlite.backup.core.queue.BackupTask
import com.anlite.backup.core.queue.QueueProgress
import com.anlite.backup.core.queue.SequentialBackupQueue
import com.anlite.backup.core.usecase.DeleteSnapshotUseCase
import com.anlite.backup.core.usecase.PruneRepositoryUseCase
import com.anlite.backup.core.usecase.RestoreAppUseCase
import com.anlite.backup.core.usecase.SyncRepositoryUseCase
import com.anlite.backup.data.preferences.EngineConfig
import com.anlite.backup.data.preferences.EnginePreferences
import com.anlite.backup.data.repository.AppItemUiState
import com.anlite.backup.data.repository.BackupStatus
import com.anlite.backup.data.repository.PackageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

enum class FilterMode {
    ALL,
    NEED_BACKUP,
    UP_TO_DATE,
    NOT_BACKED_UP,
}

data class RepoStatsUiState(
    val snapshotCount: Int = 0,
    val totalSizeBytes: Long = 0,
    val isSyncing: Boolean = false,
    val isPruning: Boolean = false,
    val lastSyncMessage: String? = null,
)

/**
 * 现代 Material 3 单事实源 ViewModel。
 * 彻底整合原 3 个重叠 ViewModel，提供纯响应式数据流与全串行队列交互。
 */
class AppsViewModel(
    private val packageRepository: PackageRepository,
    private val backupQueue: SequentialBackupQueue,
    private val restoreAppUseCase: RestoreAppUseCase,
    private val syncRepositoryUseCase: SyncRepositoryUseCase,
    private val pruneRepositoryUseCase: PruneRepositoryUseCase,
    private val deleteSnapshotUseCase: DeleteSnapshotUseCase,
    private val preferences: EnginePreferences,
    private val resticDriver: ResticDriver,
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val filterMode = MutableStateFlow(FilterMode.ALL)
    val selectedPackages = MutableStateFlow<Set<String>>(emptySet())

    val queueProgress: StateFlow<QueueProgress> = backupQueue.progress
    val engineConfig: StateFlow<EngineConfig> = preferences.configFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EngineConfig(
            repoPath = EnginePreferences.DEFAULT_REPO_PATH,
            repoPassword = EnginePreferences.DEFAULT_REPO_PASSWORD,
            backupApk = true,
            backupData = true,
            backupDataDe = true,
            maxSnapshotsPerApp = 3,
            autoPruneAfterBatch = false,
            restoreSelinux = true,
            showSystemApps = false,
            themeMode = "SYSTEM",
            useDynamicColors = true,
        )
    )

    private val _repoStats = MutableStateFlow(RepoStatsUiState())
    val repoStats: StateFlow<RepoStatsUiState> = _repoStats.asStateFlow()

    val selectedAppForSheet = MutableStateFlow<AppItemUiState?>(null)
    val showSettingsDialog = MutableStateFlow(false)
    val isRootGranted = MutableStateFlow(true)

    val appsList: StateFlow<List<AppItemUiState>> = combine(
        packageRepository.observeAppItems(),
        searchQuery,
        filterMode,
    ) { allApps, query, filter ->
        var list = allApps

        // 搜索过滤
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter {
                it.appLabel.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }

        // 状态过滤
        list = when (filter) {
            FilterMode.ALL -> list
            FilterMode.NEED_BACKUP -> list.filter {
                it.backupStatus == BackupStatus.NOT_BACKED_UP || it.backupStatus == BackupStatus.UPDATE_AVAILABLE
            }
            FilterMode.UP_TO_DATE -> list.filter { it.backupStatus == BackupStatus.UP_TO_DATE }
            FilterMode.NOT_BACKED_UP -> list.filter { it.backupStatus == BackupStatus.NOT_BACKED_UP }
        }

        list
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    init {
        checkRoot()
        loadRepoStats()
    }

    fun checkRoot() {
        viewModelScope.launch {
            isRootGranted.value = RootExecutor.isRootAvailable()
        }
    }

    fun loadRepoStats() {
        viewModelScope.launch {
            try {
                val config = preferences.getSnapshotConfig()
                val snaps = resticDriver.listSnapshots(config.repoPath, config.repoPassword)
                val stats = resticDriver.getStats(config.repoPath, config.repoPassword)
                _repoStats.update {
                    it.copy(
                        snapshotCount = snaps.size,
                        totalSizeBytes = stats?.total_size ?: 0L,
                    )
                }
            } catch (e: Throwable) {
                Timber.w(e, "Could not load restic repo stats")
            }
        }
    }

    fun togglePackageSelection(packageName: String) {
        selectedPackages.update { current ->
            if (current.contains(packageName)) current - packageName else current + packageName
        }
    }

    fun selectAll(select: Boolean) {
        if (select) {
            selectedPackages.value = appsList.value.map { it.packageName }.toSet()
        } else {
            selectedPackages.value = emptySet()
        }
    }

    fun clearSelection() {
        selectedPackages.value = emptySet()
    }

    fun backupSelectedApps() {
        val selected = selectedPackages.value
        if (selected.isEmpty()) return

        val appsToBackup = appsList.value.filter { selected.contains(it.packageName) }
        val tasks = appsToBackup.map { app ->
            BackupTask(
                packageName = app.packageName,
                packageLabel = app.appLabel,
                versionName = app.versionName,
                versionCode = app.versionCode,
                apkSourceDir = app.apkSourceDir,
                isSystem = app.isSystem,
            )
        }
        backupQueue.enqueue(tasks)
        clearSelection()
    }

    fun backupSingleApp(app: AppItemUiState) {
        val task = BackupTask(
            packageName = app.packageName,
            packageLabel = app.appLabel,
            versionName = app.versionName,
            versionCode = app.versionCode,
            apkSourceDir = app.apkSourceDir,
            isSystem = app.isSystem,
        )
        backupQueue.enqueue(task)
    }

    fun restoreApp(
        packageName: String,
        snapshotId: String,
        packageLabel: String = "",
        restoreApk: Boolean = true,
        restoreData: Boolean = true,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ) {
        val label = packageLabel.ifBlank {
            appsList.value.find { it.packageName == packageName }?.appLabel ?: packageName
        }
        val task = BackupTask.AppRestoreTask(
            packageName = packageName,
            packageLabel = label,
            snapshotId = snapshotId,
            restoreApk = restoreApk,
            restoreData = restoreData,
            onFinished = onComplete,
        )
        backupQueue.enqueue(task)
    }

    fun restoreSelectedApps(
        restoreApk: Boolean = true,
        restoreData: Boolean = true,
    ) {
        val selected = selectedPackages.value
        if (selected.isEmpty()) return

        val appsToRestore = appsList.value.filter { selected.contains(it.packageName) }
        val tasks = appsToRestore.mapNotNull { app ->
            val latestSnap = app.latestSnapshot ?: app.snapshots.firstOrNull()
            val snapId = latestSnap?.resticSnapshotId
            if (!snapId.isNullOrEmpty()) {
                BackupTask.AppRestoreTask(
                    packageName = app.packageName,
                    packageLabel = app.appLabel,
                    snapshotId = snapId,
                    restoreApk = restoreApk,
                    restoreData = restoreData,
                )
            } else null
        }
        if (tasks.isNotEmpty()) {
            backupQueue.enqueue(tasks)
        }
        clearSelection()
    }

    fun syncRepository() {
        viewModelScope.launch {
            _repoStats.update { it.copy(isSyncing = true) }
            val result = syncRepositoryUseCase.execute()
            _repoStats.update {
                it.copy(
                    isSyncing = false,
                    lastSyncMessage = if (result.isSuccess) "Sync completed: ${result.getOrNull()} snapshots" else "Sync failed",
                )
            }
            loadRepoStats()
        }
    }

    fun pruneRepository(onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            _repoStats.update { it.copy(isPruning = true) }
            val result = pruneRepositoryUseCase.execute()
            _repoStats.update { it.copy(isPruning = false) }
            loadRepoStats()
            onComplete?.invoke(result.isSuccess)
        }
    }

    fun deleteSnapshot(
        snapshotId: String,
        runPrune: Boolean = false,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val res = deleteSnapshotUseCase.execute(
                snapshotId = snapshotId,
                directoryId = null,
                runPrune = runPrune,
            )
            loadRepoStats()
            if (res.isSuccess) {
                selectedAppForSheet.value?.let { currentApp ->
                    val updatedSnapshots = currentApp.snapshots.filterNot { it.resticSnapshotId == snapshotId }
                    selectedAppForSheet.value = currentApp.copy(snapshots = updatedSnapshots)
                }
            }
            onComplete?.invoke(res.isSuccess, res.exceptionOrNull()?.message)
        }
    }

    fun openAppDetail(app: AppItemUiState) {
        selectedAppForSheet.value = app
    }

    fun closeAppDetail() {
        selectedAppForSheet.value = null
    }

    fun openSettings() {
        showSettingsDialog.value = true
    }

    fun closeSettings() {
        showSettingsDialog.value = false
    }

    fun updateConfig(
        repoPath: String? = null,
        repoPassword: String? = null,
        maxSnapshots: Int? = null,
        autoPrune: Boolean? = null,
        restoreSelinux: Boolean? = null,
        showSystemApps: Boolean? = null,
    ) {
        viewModelScope.launch {
            repoPath?.let { preferences.setRepoPath(it) }
            repoPassword?.let { preferences.setRepoPassword(it) }
            maxSnapshots?.let { preferences.setMaxSnapshotsPerApp(it) }
            autoPrune?.let { preferences.setAutoPruneAfterBatch(it) }
            restoreSelinux?.let { preferences.setRestoreSelinux(it) }
            showSystemApps?.let { preferences.setShowSystemApps(it) }
            loadRepoStats()
        }
    }
}
