package com.anlite.backup.core.usecase

import android.content.Context
import android.content.pm.ApplicationInfo
import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.data.dbs.dao.BackupDao
import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.preferences.EnginePreferences
import com.anlite.backup.data.repository.DirectoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SyncRepositoryUseCase(
    private val context: Context,
    private val resticDriver: ResticDriver,
    private val backupDao: BackupDao,
    private val directoryRepository: DirectoryRepository,
    private val preferences: EnginePreferences,
) {
    suspend fun execute(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val config = preferences.getSnapshotConfig()
            android.util.Log.i("AnLiteBackupSync", "Starting sync on repo: ${config.repoPath}")
            val snapshots = resticDriver.listSnapshots(config.repoPath, config.repoPassword)
            android.util.Log.i("AnLiteBackupSync", "Found ${snapshots.size} snapshot(s) in restic repository")
            Timber.i("Found ${snapshots.size} snapshot(s) in restic repository: ${config.repoPath}")

            val snapshotIds = snapshots.map { it.id }.toSet()
            val pm = context.packageManager

            // 分流应用快照与自定义目录快照
            val appSnapshots = snapshots.filter { snap ->
                !snap.tags.contains("dir") && !snap.tags.any { it.startsWith("dir:") }
            }
            val dirSnapshots = snapshots.filter { snap ->
                snap.tags.contains("dir") || snap.tags.any { it.startsWith("dir:") }
            }
            android.util.Log.i("AnLiteBackupSync", "appSnapshots=${appSnapshots.size}, dirSnapshots=${dirSnapshots.size}")

            // 1. 同步应用快照
            for (snap in appSnapshots) {
                val existing = backupDao.getBySnapshotId(snap.id)
                if (existing != null) {
                    continue
                }

                // 提取包名 tag (排除时间戳 tag)
                val packageName = snap.tags.firstOrNull { tag ->
                    tag.contains(".") && !tag.contains(":")
                } ?: snap.tags.firstOrNull() ?: continue

                // 提取时间
                val backupDate = parseDateTime(snap.time)

                // 查询本地安装信息填充 label
                var label = packageName
                var versionName = "-"
                var versionCode = 0
                var isSystem = false

                try {
                    val pi = pm.getPackageInfo(packageName, 0)
                    val appInfo = pi.applicationInfo
                    if (appInfo != null) {
                        label = pm.getApplicationLabel(appInfo).toString()
                        isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    }
                    versionName = pi.versionName ?: "-"
                    @Suppress("DEPRECATION")
                    versionCode = pi.versionCode
                } catch (_: Throwable) {
                    // 应用可能已卸载，保留缺省包名
                }

                val entity = Backup(
                    backupVersionCode = 8331,
                    packageName = packageName,
                    packageLabel = label,
                    versionName = versionName,
                    versionCode = versionCode,
                    profileId = 0,
                    sourceDir = null,
                    splitSourceDirs = emptyArray(),
                    isSystem = isSystem,
                    backupDate = backupDate,
                    hasApk = true,
                    hasAppData = true,
                    hasDevicesProtectedData = false,
                    hasExternalData = false,
                    hasObbData = false,
                    hasMediaData = false,
                    compressionType = "restic",
                    cipherType = null,
                    iv = byteArrayOf(),
                    cpuArch = null,
                    permissions = emptyList(),
                    size = 0L,
                    note = "",
                    persistent = false,
                    resticSnapshotId = snap.id,
                )
                backupDao.insert(entity)
                Timber.d("Imported snapshot ${snap.id} for $packageName into Room database")
            }

            // 清理已从 restic 仓库物理删除但在本地 Room 残留的孤儿应用快照记录
            val dbBackups = backupDao.getAll()
            for (b in dbBackups) {
                val sid = b.resticSnapshotId
                if (b.compressionType == "restic" && !sid.isNullOrEmpty() && !snapshotIds.contains(sid)) {
                    backupDao.deleteBySnapshotId(sid)
                    Timber.d("Pruned orphaned db record for snapshot $sid")
                }
            }

            // 2. 同步自定义目录快照
            val dirSnapshotsByPath = dirSnapshots.groupBy { snap ->
                val rawPath = snap.tags.firstOrNull { it != "dir" && !it.startsWith("dir:") && (it.startsWith("/") || it.contains("/")) }
                    ?: snap.paths.firstOrNull()
                    ?: ""
                directoryRepository.normalizePath(rawPath)
            }.filterKeys { it.isNotEmpty() }

            for ((path, snaps) in dirSnapshotsByPath) {
                val sortedSnaps = snaps.sortedByDescending { it.time }
                val latestSnap = sortedSnaps.firstOrNull()
                val snapshotCount = snaps.size
                val latestSnapshotId = latestSnap?.id
                val latestBackupTime = latestSnap?.time?.let { parseDateTime(it) }
                val sizeBytes = latestSnap?.summary?.data_added_packed?.takeIf { it > 0L }
                    ?: latestSnap?.summary?.total_bytes_processed
                    ?: 0L

                val existing = directoryRepository.getByPath(path)
                if (existing != null) {
                    directoryRepository.update(
                        existing.copy(
                            lastBackupTime = latestBackupTime ?: existing.lastBackupTime,
                            lastSnapshotId = latestSnapshotId ?: existing.lastSnapshotId,
                            sizeBytes = if (sizeBytes > 0L) sizeBytes else existing.sizeBytes,
                            snapshotCount = snapshotCount,
                        )
                    )
                    Timber.d("Updated directory metadata in db: $path (snapshots: $snapshotCount)")
                } else {
                    val preset = directoryRepository.presets.firstOrNull {
                        directoryRepository.isSamePath(it.path, path)
                    }
                    val name = preset?.name ?: File(path).name.ifEmpty { path }
                    val id = directoryRepository.saveDirectory(
                        name = name,
                        rawPath = path,
                    )
                    val inserted = directoryRepository.getById(id) ?: directoryRepository.getByPath(path)
                    if (inserted != null) {
                        directoryRepository.update(
                            inserted.copy(
                                createdAt = latestBackupTime ?: LocalDateTime.now(),
                                lastBackupTime = latestBackupTime,
                                lastSnapshotId = latestSnapshotId,
                                sizeBytes = sizeBytes,
                                snapshotCount = snapshotCount,
                            )
                        )
                    }
                    Timber.i("Auto-imported directory backup: $name ($path) with $snapshotCount snapshot(s)")
                }
            }

            // 处理仓库中已无快照但本地数据库仍存留的目录快照计数
            val allDbDirs = directoryRepository.getAll()
            for (dbDir in allDbDirs) {
                val matchedSnaps = dirSnapshots.filter { snap ->
                    snap.tags.any { directoryRepository.isSamePath(it, dbDir.path) } ||
                    snap.paths.any { directoryRepository.isSamePath(it, dbDir.path) }
                }
                if (matchedSnaps.isEmpty() && (dbDir.snapshotCount > 0 || dbDir.lastSnapshotId != null)) {
                    directoryRepository.update(
                        dbDir.copy(
                            snapshotCount = 0,
                            lastSnapshotId = null,
                            sizeBytes = 0L,
                        )
                    )
                }
            }

            Timber.i("Completed sync: ${snapshots.size} snapshot(s) processed")
            snapshots.size
        }.onFailure {
            android.util.Log.e("AnLiteBackupSync", "Sync execution error", it)
            Timber.e(it, "Sync execution error")
        }
    }

    private fun parseDateTime(isoString: String): LocalDateTime {
        return try {
            ZonedDateTime.parse(isoString).toLocalDateTime()
        } catch (_: Throwable) {
            try {
                LocalDateTime.parse(isoString, DateTimeFormatter.ISO_DATE_TIME)
            } catch (_: Throwable) {
                LocalDateTime.now()
            }
        }
    }
}
