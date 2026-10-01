package com.anlite.backup.core.usecase

import android.content.Context
import android.content.pm.ApplicationInfo
import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.data.dbs.dao.BackupDao
import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.preferences.EnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SyncRepositoryUseCase(
    private val context: Context,
    private val resticDriver: ResticDriver,
    private val backupDao: BackupDao,
    private val preferences: EnginePreferences,
) {
    suspend fun execute(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val config = preferences.getSnapshotConfig()
            val snapshots = resticDriver.listSnapshots(config.repoPath, config.repoPassword)
            Timber.i("Found ${snapshots.size} snapshot(s) in restic repository: ${config.repoPath}")

            val snapshotIds = snapshots.map { it.id }.toSet()
            val pm = context.packageManager

            for (snap in snapshots) {
                val existing = backupDao.getBySnapshotId(snap.id)
                if (existing != null) {
                    continue
                }

                // 目录快照由 Directories 模块管理，跳过应用备份同步
                if (snap.tags.contains("dir") || snap.tags.any { it.startsWith("dir:") }) {
                    continue
                }

                // 提取包名 tag (排除时间戳 tag)
                val packageName = snap.tags.firstOrNull { tag ->
                    tag.contains(".") && !tag.contains(":")
                } ?: snap.tags.firstOrNull() ?: continue

                // 提取时间
                val backupDate = try {
                    ZonedDateTime.parse(snap.time).toLocalDateTime()
                } catch (_: Throwable) {
                    try {
                        LocalDateTime.parse(snap.time, DateTimeFormatter.ISO_DATE_TIME)
                    } catch (_: Throwable) {
                        LocalDateTime.now()
                    }
                }

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

            // 清理已从 restic 仓库物理删除但在本地 Room 残留的孤儿快照记录
            val dbBackups = backupDao.getAll()
            for (b in dbBackups) {
                val sid = b.resticSnapshotId
                if (b.compressionType == "restic" && !sid.isNullOrEmpty() && !snapshotIds.contains(sid)) {
                    backupDao.deleteBySnapshotId(sid)
                    Timber.d("Pruned orphaned db record for snapshot $sid")
                }
            }

            snapshots.size
        }
    }
}
