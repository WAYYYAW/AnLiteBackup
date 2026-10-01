package com.anlite.backup.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.core.engine.ResticSnapshot
import com.anlite.backup.core.queue.BackupTask
import com.anlite.backup.core.queue.QueueProgress
import com.anlite.backup.core.queue.SequentialBackupQueue
import com.anlite.backup.core.usecase.DeleteSnapshotUseCase
import com.anlite.backup.core.usecase.RestoreDirectoryUseCase
import com.anlite.backup.core.usecase.SyncRepositoryUseCase
import com.anlite.backup.data.dbs.entity.BackupDirectory
import com.anlite.backup.data.preferences.EnginePreferences
import com.anlite.backup.data.repository.DirectoryRepository
import com.anlite.backup.data.repository.DirectoryValidationResult
import com.anlite.backup.data.repository.PresetDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class DirectoryDetailUiState(
    val directory: BackupDirectory,
    val snapshots: List<ResticSnapshot> = emptyList(),
    val isLoadingSnapshots: Boolean = false,
)

/**
 * 目录备份 ViewModel。
 * 负责物理路径探测校验、目录持久化、快照浏览、单项/批量备份投递与目录还原调度。
 */
class DirectoriesViewModel(
    private val directoryRepository: DirectoryRepository,
    private val backupQueue: SequentialBackupQueue,
    private val restoreDirectoryUseCase: RestoreDirectoryUseCase,
    private val deleteSnapshotUseCase: DeleteSnapshotUseCase,
    private val syncRepositoryUseCase: SyncRepositoryUseCase,
    private val preferences: EnginePreferences,
    private val resticDriver: ResticDriver,
) : ViewModel() {

    val directories: StateFlow<List<BackupDirectory>> = directoryRepository.observeDirectories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val queueProgress: StateFlow<QueueProgress> = backupQueue.progress

    val presets: List<PresetDirectory> get() = directoryRepository.presets

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    init {
        syncDirectories()
    }

    fun syncDirectories(onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch {
            _isSyncing.value = true
            android.util.Log.i("AnLiteBackupSync", "DirectoriesViewModel: syncDirectories started")
            try {
                val res = syncRepositoryUseCase.execute()
                android.util.Log.i("AnLiteBackupSync", "DirectoriesViewModel: syncDirectories finished isSuccess=${res.isSuccess}")
                onComplete?.invoke(res.isSuccess)
            } catch (e: Throwable) {
                android.util.Log.e("AnLiteBackupSync", "DirectoriesViewModel: syncDirectories caught exception", e)
                Timber.e(e, "Failed to sync directory snapshots")
                onComplete?.invoke(false)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private val _selectedDirectory = MutableStateFlow<DirectoryDetailUiState?>(null)
    val selectedDirectory: StateFlow<DirectoryDetailUiState?> = _selectedDirectory.asStateFlow()

    private val _pathValidation = MutableStateFlow<DirectoryValidationResult?>(null)
    val pathValidation: StateFlow<DirectoryValidationResult?> = _pathValidation.asStateFlow()

    private val _isValidatingPath = MutableStateFlow(false)
    val isValidatingPath: StateFlow<Boolean> = _isValidatingPath.asStateFlow()

    fun validatePath(path: String) {
        viewModelScope.launch {
            _isValidatingPath.value = true
            try {
                _pathValidation.value = directoryRepository.validatePath(path)
            } finally {
                _isValidatingPath.value = false
            }
        }
    }

    fun clearValidation() {
        _pathValidation.value = null
        _isValidatingPath.value = false
    }

    fun addDirectory(name: String, path: String, onComplete: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val id = directoryRepository.saveDirectory(name, path)
                onComplete?.invoke(id)
            } catch (e: Throwable) {
                Timber.e(e, "Failed to add directory $name ($path)")
            }
        }
    }

    fun deleteDirectory(directory: BackupDirectory) {
        viewModelScope.launch {
            directoryRepository.delete(directory)
            if (_selectedDirectory.value?.directory?.id == directory.id) {
                _selectedDirectory.value = null
            }
        }
    }

    fun backupDirectory(directory: BackupDirectory) {
        val task = BackupTask.DirectoryTask(
            directoryId = directory.id,
            name = directory.name,
            path = directory.path,
        )
        backupQueue.enqueue(task)
    }

    fun backupAllDirectories() {
        val all = directories.value
        if (all.isEmpty()) return
        val tasks = all.map {
            BackupTask.DirectoryTask(
                directoryId = it.id,
                name = it.name,
                path = it.path,
            )
        }
        backupQueue.enqueue(tasks)
    }

    fun selectDirectory(directory: BackupDirectory) {
        _selectedDirectory.value = DirectoryDetailUiState(directory = directory, isLoadingSnapshots = true)
        loadSnapshots(directory)
    }

    fun dismissDetail() {
        _selectedDirectory.value = null
    }

    fun loadSnapshots(directory: BackupDirectory) {
        viewModelScope.launch {
            try {
                val config = preferences.getSnapshotConfig()
                val allSnaps = resticDriver.listSnapshots(config.repoPath, config.repoPassword)
                val snaps = allSnaps.filter { snap ->
                    (snap.tags.contains("dir") || snap.tags.any { it.startsWith("dir:") }) && (
                        snap.tags.any { directoryRepository.isSamePath(it, directory.path) } ||
                        snap.paths.any { directoryRepository.isSamePath(it, directory.path) }
                    )
                }.sortedByDescending { it.time }

                _selectedDirectory.update {
                    if (it?.directory?.id == directory.id) {
                        it.copy(snapshots = snaps, isLoadingSnapshots = false)
                    } else it
                }
            } catch (e: Throwable) {
                Timber.e(e, "Failed to load snapshots for directory ${directory.path}")
                _selectedDirectory.update {
                    if (it?.directory?.id == directory.id) {
                        it.copy(isLoadingSnapshots = false)
                    } else it
                }
            }
        }
    }

    fun restoreDirectory(
        snapshotId: String,
        originalPath: String,
        targetPath: String,
        onComplete: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = restoreDirectoryUseCase.execute(
                snapshotId = snapshotId,
                originalPath = originalPath,
                targetPath = targetPath,
            )
            if (result.isSuccess) {
                onComplete(true, null)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown restore error"
                onComplete(false, err)
            }
        }
    }

    fun deleteSnapshot(
        directory: BackupDirectory,
        snapshotId: String,
        runPrune: Boolean = false,
        onComplete: ((Boolean, String?) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val res = deleteSnapshotUseCase.execute(
                snapshotId = snapshotId,
                directoryId = directory.id,
                runPrune = runPrune,
            )
            loadSnapshots(directory)
            val latestDir = directoryRepository.getById(directory.id)
            if (latestDir != null) {
                _selectedDirectory.update { current ->
                    if (current?.directory?.id == directory.id) {
                        current.copy(directory = latestDir)
                    } else current
                }
            }
            onComplete?.invoke(res.isSuccess, res.exceptionOrNull()?.message)
        }
    }
}
