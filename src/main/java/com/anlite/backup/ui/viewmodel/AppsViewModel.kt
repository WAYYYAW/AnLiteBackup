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
import com.anlite.backup.core.usecase.RestoreDirectoryUseCase
import com.anlite.backup.core.usecase.SyncRepositoryUseCase
import com.anlite.backup.data.preferences.EngineConfig
import com.anlite.backup.data.preferences.EnginePreferences
import com.anlite.backup.data.repository.AppItemUiState
import com.anlite.backup.data.repository.BackupStatus
import com.anlite.backup.data.repository.DirectoryRepository
import com.anlite.backup.data.repository.PackageRepository
import com.anlite.backup.data.repository.SnapshotFilterType
import com.anlite.backup.data.repository.UnifiedSnapshotUiItem
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
    val appSnapshotCount: Int = 0,
    val dirSnapshotCount: Int = 0,
    val isSyncing: Boolean = false,
    val isPruning: Boolean = false,
    val isChecking: Boolean = false,
    val isUnlocking: Boolean = false,
    val lastSyncMessage: String? = null,
    val lastCheckResult: String? = null,
)

/**
 * 现代 Material 3 单事实源 ViewModel。
 * 彻底整合原 3 个重叠 ViewModel，提供纯响应式数据流与全串行队列交互。
 */
class AppsViewModel(
    private val packageRepository: PackageRepository,
    private val directoryRepository: DirectoryRepository,
    private val backupQueue: SequentialBackupQueue,
    private val restoreAppUseCase: RestoreAppUseCase,
    private val restoreDirectoryUseCase: RestoreDirectoryUseCase,
    private val syncRepositoryUseCase: SyncRepositoryUseCase,
    private val pruneRepositoryUseCase: PruneRepositoryUseCase,
    private val deleteSnapshotUseCase: DeleteSnapshotUseCase,
    private val preferences: EnginePreferences,
    private val resticDriver: ResticDriver,
) : ViewModel() {

    val searchQuery = MutableStateFlow("")
    val filterMode = MutableStateFlow(FilterMode.ALL)
    val selectedPackages = MutableStateFlow<Set<String>>(emptySet())

    // 存储库快照页检索与筛选状态
    val snapshotSearchQuery = MutableStateFlow("")
    val snapshotFilterType = MutableStateFlow(SnapshotFilterType.ALL)

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

    /**
     * 响应式统合快照列表（应用快照 + 目录快照混合时间轴）
     */
    val unifiedSnapshots: StateFlow<List<UnifiedSnapshotUiItem>> = combine(
        packageRepository.observeAppItems(),
        directoryRepository.observeDirectories(),
        snapshotSearchQuery,
        snapshotFilterType,
    ) { apps, directories, query, filter ->
        val appSnapshots = apps.flatMap { app ->
            app.snapshots.map { snap ->
                UnifiedSnapshotUiItem.AppSnapshot(
                    snapshotId = snap.resticSnapshotId ?: "",
                    shortId = snap.resticSnapshotId?.take(8) ?: "-",
                    backupDate = snap.backupDate,
                    sizeBytes = snap.size,
                    displayTitle = app.appLabel,
                    subtitle = app.packageName,
                    packageName = app.packageName,
                    versionName = snap.versionName ?: "-",
                    versionCode = snap.versionCode,
                    isSystem = app.isSystem,
                    isInstalled = app.isInstalled,
                    hasApk = snap.hasApk,
                    hasAppData = snap.hasAppData,
                    hasDataDe = snap.hasDevicesProtectedData,
                    backupEntity = snap,
                    appUiState = app,
                )
            }
        }

        val dirSnapshots = directories.mapNotNull { dir ->
            if (dir.lastSnapshotId.isNullOrEmpty() || dir.lastBackupTime == null) {
                null
            } else {
                UnifiedSnapshotUiItem.DirectorySnapshot(
                    snapshotId = dir.lastSnapshotId,
                    shortId = dir.lastSnapshotId.take(8),
                    backupDate = dir.lastBackupTime,
                    sizeBytes = dir.sizeBytes,
                    displayTitle = dir.name,
                    subtitle = dir.path,
                    path = dir.path,
                    directoryEntity = dir,
                )
            }
        }

        var list: List<UnifiedSnapshotUiItem> = (appSnapshots + dirSnapshots).sortedByDescending { it.backupDate }

        // 搜索过滤
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            list = list.filter { item ->
                item.displayTitle.lowercase().contains(q) ||
                item.subtitle.lowercase().contains(q) ||
                item.snapshotId.lowercase().contains(q) ||
                item.shortId.lowercase().contains(q)
            }
        }

        // 类型分类过滤
        list = when (filter) {
            SnapshotFilterType.ALL -> list
            SnapshotFilterType.APPS_ONLY -> list.filterIsInstance<UnifiedSnapshotUiItem.AppSnapshot>()
            SnapshotFilterType.DIRECTORIES_ONLY -> list.filterIsInstance<UnifiedSnapshotUiItem.DirectorySnapshot>()
            SnapshotFilterType.UNINSTALLED_ONLY -> list.filter { it is UnifiedSnapshotUiItem.AppSnapshot && !it.isInstalled }
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
                val appCount = snaps.count { !it.tags.contains("dir") && !it.tags.any { tag -> tag.startsWith("dir:") } }
                val dirCount = snaps.size - appCount
                _repoStats.update {
                    it.copy(
                        snapshotCount = snaps.size,
                        totalSizeBytes = stats?.total_size ?: 0L,
                        appSnapshotCount = appCount,
                        dirSnapshotCount = dirCount,
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

    fun cancelPendingTasks() {
        backupQueue.clearPendingTasks()
    }

    fun resetQueueState() {
        backupQueue.resetState()
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

    fun unlockRepository(onComplete: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            _repoStats.update { it.copy(isUnlocking = true) }
            val config = preferences.getSnapshotConfig()
            val result = resticDriver.unlock(config.repoPath, config.repoPassword)
            _repoStats.update { it.copy(isUnlocking = false) }
            loadRepoStats()
            val msg = (result.out + result.err).joinToString("\n").ifBlank { if (result.isSuccess) "已清除死锁" else "解锁失败" }
            onComplete?.invoke(result.isSuccess, msg)
        }
    }

    fun checkRepository(onComplete: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            _repoStats.update { it.copy(isChecking = true) }
            val config = preferences.getSnapshotConfig()
            val result = resticDriver.check(config.repoPath, config.repoPassword)
            val msg = (result.out + result.err).joinToString("\n").ifBlank { if (result.isSuccess) "体检通过，数据完整" else "体检发现错误" }
            _repoStats.update {
                it.copy(
                    isChecking = false,
                    lastCheckResult = msg,
                )
            }
            loadRepoStats()
            onComplete?.invoke(result.isSuccess, msg)
        }
    }

    fun restoreDirectory(
        snapshotId: String,
        originalPath: String,
        targetPath: String,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val result = restoreDirectoryUseCase.execute(
                snapshotId = snapshotId,
                originalPath = originalPath,
                targetPath = targetPath,
            )
            onComplete?.invoke(result.isSuccess, result.exceptionOrNull()?.message)
        }
    }

    fun deleteSnapshot(
        snapshotId: String,
        directoryId: Long? = null,
        runPrune: Boolean = false,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val res = deleteSnapshotUseCase.execute(
                snapshotId = snapshotId,
                directoryId = directoryId,
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
