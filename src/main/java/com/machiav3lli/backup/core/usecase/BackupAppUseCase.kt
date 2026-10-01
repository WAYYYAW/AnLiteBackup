package com.machiav3lli.backup.core.usecase

import android.content.Context
import android.content.pm.PackageManager
import com.machiav3lli.backup.core.engine.BindMountManager
import com.machiav3lli.backup.core.engine.ResticDriver
import com.machiav3lli.backup.data.dbs.dao.BackupDao
import com.machiav3lli.backup.data.dbs.entity.Backup
import com.machiav3lli.backup.data.preferences.EnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class BackupAppUseCase(
    private val context: Context,
    private val resticDriver: ResticDriver,
    private val backupDao: BackupDao,
    private val preferences: EnginePreferences,
) {
    suspend fun execute(
        packageName: String,
        packageLabel: String,
        versionName: String? = "-",
        versionCode: Int = 0,
        apkSourceDir: String? = null,
        isSystem: Boolean = false,
        onProgress: ((percent: Float, message: String) -> Unit)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val config = preferences.getSnapshotConfig()
            val mountManager = BindMountManager(packageName)
            try {
                // 1. 设置虚拟挂载点
                val staging = mountManager.setupStaging(
                    apkSourceDir = if (config.backupApk) apkSourceDir else null,
                    includeData = config.backupData,
                    includeDataDe = config.backupDataDe,
                )

                val now = LocalDateTime.now()
                val dateTag = now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                val tags = listOf(packageName, dateTag)

                // 2. 调用 restic 引擎备份
                val backupResult = resticDriver.backup(
                    repoPath = config.repoPath,
                    password = config.repoPassword,
                    stagingDir = staging.stagingDir,
                    tags = tags,
                    onProgress = onProgress,
                )

                if (!backupResult.isSuccess || backupResult.snapshotId.isNullOrEmpty()) {
                    throw IllegalStateException(backupResult.error ?: "Restic backup failed")
                }

                val snapshotId = backupResult.snapshotId

                // 3. 写入 Room 数据库快照索引
                val backupEntity = Backup(
                    backupVersionCode = 8331,
                    packageName = packageName,
                    packageLabel = packageLabel,
                    versionName = versionName ?: "-",
                    versionCode = versionCode,
                    profileId = 0,
                    sourceDir = apkSourceDir,
                    splitSourceDirs = emptyArray(),
                    isSystem = isSystem,
                    backupDate = now,
                    hasApk = staging.hasApk,
                    hasAppData = staging.hasData,
                    hasDevicesProtectedData = staging.hasDataDe,
                    hasExternalData = false,
                    hasObbData = false,
                    hasMediaData = false,
                    compressionType = "restic",
                    cipherType = null,
                    iv = byteArrayOf(),
                    cpuArch = null,
                    permissions = emptyList(),
                    size = backupResult.dataAddedPacked.takeIf { it > 0 } ?: backupResult.totalBytesProcessed,
                    note = "",
                    persistent = false,
                    resticSnapshotId = snapshotId,
                )

                backupDao.insert(backupEntity)
                Timber.i("Saved snapshot $snapshotId for $packageName into database")

                // 4. 版本保留策略控制 (forget 旧版本)
                if (config.maxSnapshotsPerApp > 0) {
                    val allBackups = backupDao.get(packageName).sortedByDescending { it.backupDate }
                    if (allBackups.size > config.maxSnapshotsPerApp) {
                        val toRemove = allBackups.drop(config.maxSnapshotsPerApp)
                        for (old in toRemove) {
                            val oldId = old.resticSnapshotId
                            if (!oldId.isNullOrEmpty()) {
                                resticDriver.forget(config.repoPath, config.repoPassword, oldId)
                                backupDao.deleteBySnapshotId(oldId)
                                Timber.d("Forgot excess snapshot $oldId for $packageName")
                            }
                        }
                    }
                }

                snapshotId
            } finally {
                // 5. 逆序卸载挂载点并删除暂存目录
                mountManager.close()
            }
        }
    }
}
