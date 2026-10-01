package com.machiav3lli.backup.core.usecase

import com.machiav3lli.backup.core.engine.ResticDriver
import com.machiav3lli.backup.data.dbs.dao.BackupDao
import com.machiav3lli.backup.data.preferences.EnginePreferences
import com.machiav3lli.backup.data.repository.DirectoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DeleteSnapshotUseCase(
    private val resticDriver: ResticDriver,
    private val backupDao: BackupDao,
    private val directoryRepository: DirectoryRepository,
    private val pruneRepositoryUseCase: PruneRepositoryUseCase,
    private val preferences: EnginePreferences,
) {
    /**
     * 删除指定快照。
     *
     * @param snapshotId 快照 ID
     * @param directoryId 若删除的是自定义目录快照，传入对应的目录 ID；若为应用快照则为 null
     * @param runPrune 是否同时执行底层碎片整理 (Prune) 物理回收存储块
     * @param onPruneProgress 碎片整理流式日志回调
     */
    suspend fun execute(
        snapshotId: String,
        directoryId: Long? = null,
        runPrune: Boolean = false,
        onPruneProgress: ((String) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = preferences.getSnapshotConfig()
            Timber.i("Starting snapshot deletion for ID $snapshotId (directoryId=$directoryId, runPrune=$runPrune)...")

            // 1. 调用底层 restic forget
            val forgetResult = resticDriver.forget(
                repoPath = config.repoPath,
                password = config.repoPassword,
                snapshotId = snapshotId,
            )
            if (!forgetResult.isSuccess) {
                val errMsg = (forgetResult.err + forgetResult.out).joinToString("\n")
                throw IllegalStateException("Restic forget failed: $errMsg")
            }
            Timber.i("Restic forget completed for snapshot $snapshotId")

            // 2. 清理/更新数据库
            if (directoryId != null) {
                val dir = directoryRepository.getById(directoryId)
                if (dir != null) {
                    val remainingSnaps = resticDriver.listSnapshots(
                        repoPath = config.repoPath,
                        password = config.repoPassword,
                        tag = dir.path,
                    ).sortedByDescending { it.time }

                    val latest = remainingSnaps.firstOrNull()
                    val latestTime = latest?.let {
                        try {
                            ZonedDateTime.parse(it.time).toLocalDateTime()
                        } catch (_: Throwable) {
                            try {
                                LocalDateTime.parse(it.time, DateTimeFormatter.ISO_DATE_TIME)
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    }

                    val updated = dir.copy(
                        snapshotCount = remainingSnaps.size,
                        lastSnapshotId = latest?.id,
                        lastBackupTime = latestTime,
                        sizeBytes = if (remainingSnaps.isEmpty()) 0L else dir.sizeBytes,
                    )
                    directoryRepository.update(updated)
                    Timber.i("Updated directory ${dir.name} metadata: ${remainingSnaps.size} snapshot(s) remaining")
                }
            } else {
                // 应用快照：直接从 Backup 表中删除该快照条目
                backupDao.deleteBySnapshotId(snapshotId)
                Timber.i("Deleted snapshot $snapshotId from Backup database table")
            }

            // 3. 若用户勾选了碎片整理，则执行 prune 物理释放磁盘空间
            if (runPrune) {
                Timber.i("Executing requested prune after snapshot deletion...")
                val pruneResult = pruneRepositoryUseCase.execute(onLine = onPruneProgress)
                if (pruneResult.isFailure) {
                    Timber.w("Prune after delete failed: ${pruneResult.exceptionOrNull()?.message}")
                }
            }
        }
    }
}
