package com.anlite.backup.core.usecase

import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.core.engine.RootExecutor
import com.anlite.backup.data.preferences.EnginePreferences
import com.anlite.backup.data.repository.DirectoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BackupDirectoryUseCase(
    private val resticDriver: ResticDriver,
    private val directoryRepository: DirectoryRepository,
    private val preferences: EnginePreferences,
) {
    suspend fun execute(
        directoryId: Long,
        onProgress: ((percent: Float, message: String) -> Unit)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = directoryRepository.getById(directoryId)
                ?: throw IllegalArgumentException("Directory with ID $directoryId not found")

            val targetFile = File(dir.path)
            val exists = RootExecutor.execute("test -d ${RootExecutor.quote(dir.path)}").isSuccess
            if (!exists) {
                throw IllegalStateException("Directory path does not exist: ${dir.path}")
            }

            val config = preferences.getSnapshotConfig()
            val now = LocalDateTime.now()
            val dateTag = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val tags = listOf("dir", dir.path, dateTag)

            Timber.i("Starting backup for directory: ${dir.name} (${dir.path})")
            val backupResult = resticDriver.backup(
                repoPath = config.repoPath,
                password = config.repoPassword,
                stagingDir = targetFile,
                tags = tags,
                onProgress = onProgress,
            )

            if (!backupResult.isSuccess || backupResult.snapshotId.isNullOrEmpty()) {
                throw IllegalStateException(backupResult.error ?: "Restic backup failed for directory ${dir.path}")
            }

            val snapshotId = backupResult.snapshotId
            val size = backupResult.dataAddedPacked.takeIf { it > 0 } ?: backupResult.totalBytesProcessed

            // 获取当前该目录快照总数
            val snapshots = resticDriver.listSnapshots(config.repoPath, config.repoPassword, tag = dir.path)
            val snapshotCount = snapshots.size.coerceAtLeast(1)

            val updated = dir.copy(
                lastBackupTime = now,
                lastSnapshotId = snapshotId,
                sizeBytes = size,
                snapshotCount = snapshotCount,
            )
            directoryRepository.update(updated)
            Timber.i("Directory backup completed successfully. Snapshot ID: $snapshotId")

            snapshotId
        }
    }
}
