package com.anlite.backup.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.anlite.backup.data.dbs.dao.AppInfoDao
import com.anlite.backup.data.dbs.dao.BackupDao
import com.anlite.backup.data.dbs.entity.AppInfo
import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.preferences.EnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import timber.log.Timber

enum class BackupStatus {
    NOT_BACKED_UP,      // 未备份
    UP_TO_DATE,         // 已备份且为最新版
    UPDATE_AVAILABLE,   // 安装版本高于备份版本
    UNINSTALLED,        // 已在设备上卸载但仍存有快照
}

data class AppItemUiState(
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Int,
    val isSystem: Boolean,
    val isInstalled: Boolean,
    val apkSourceDir: String?,
    val backupStatus: BackupStatus,
    val snapshots: List<Backup> = emptyList(),
    val latestSnapshot: Backup? = null,
)

/**
 * 核心包仓储：
 * 统一聚合已安装应用与 Room 8331 快照索引库，完全摒弃低效的 props.json 磁盘扫描，
 * 通过响应式 Flow 驱动应用列表与状态展示。
 */
class PackageRepository(
    private val context: Context,
    private val backupDao: BackupDao,
    private val preferences: EnginePreferences,
    private val appInfoDao: AppInfoDao? = null,
) {
    private val pm: PackageManager get() = context.packageManager

    /**
     * 响应式聚合已安装应用列表与 Room 中的快照索引
     */
    fun observeAppItems(): Flow<List<AppItemUiState>> = combine(
        backupDao.getAllFlow(),
        preferences.showSystemApps,
    ) { backups, showSystem ->
        val installedMap = loadInstalledPackages(showSystem)
        val backupsByPkg = backups.groupBy { it.packageName }

        val appItems = mutableListOf<AppItemUiState>()

        // 1. 处理已安装应用
        for ((pkgName, pkgInfo) in installedMap) {
            val appInfo = pkgInfo.applicationInfo ?: try {
                pm.getApplicationInfo(pkgName, 0)
            } catch (e: Exception) {
                null
            }
            val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgName
            val isSys = appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
            val installedVCode = @Suppress("DEPRECATION") pkgInfo.versionCode
            val installedVName = pkgInfo.versionName ?: "-"

            val snapshots = backupsByPkg[pkgName]?.sortedByDescending { it.backupDate } ?: emptyList()
            val latest = snapshots.firstOrNull()

            val status = when {
                snapshots.isEmpty() -> BackupStatus.NOT_BACKED_UP
                installedVCode > (latest?.versionCode ?: 0) -> BackupStatus.UPDATE_AVAILABLE
                else -> BackupStatus.UP_TO_DATE
            }

            appItems.add(
                AppItemUiState(
                    packageName = pkgName,
                    appLabel = label,
                    versionName = installedVName,
                    versionCode = installedVCode,
                    isSystem = isSys,
                    isInstalled = true,
                    apkSourceDir = appInfo?.sourceDir,
                    backupStatus = status,
                    snapshots = snapshots,
                    latestSnapshot = latest,
                )
            )
        }

        // 2. 处理已卸载但存有历史快照的应用
        for ((pkgName, snapshots) in backupsByPkg) {
            if (!installedMap.containsKey(pkgName) && snapshots.isNotEmpty()) {
                val sorted = snapshots.sortedByDescending { it.backupDate }
                val latest = sorted.first()
                appItems.add(
                    AppItemUiState(
                        packageName = pkgName,
                        appLabel = latest.packageLabel.ifBlank { pkgName },
                        versionName = latest.versionName ?: "-",
                        versionCode = latest.versionCode,
                        isSystem = latest.isSystem,
                        isInstalled = false,
                        apkSourceDir = null,
                        backupStatus = BackupStatus.UNINSTALLED,
                        snapshots = sorted,
                        latestSnapshot = latest,
                    )
                )
            }
        }

        appItems.sortedBy { it.appLabel.lowercase() }
    }.flowOn(Dispatchers.IO)

    private fun loadInstalledPackages(showSystem: Boolean): Map<String, PackageInfo> {
        return try {
            val flags = PackageManager.GET_META_DATA
            val packages = pm.getInstalledPackages(flags)
            val filtered = if (showSystem) {
                packages
            } else {
                packages.filter {
                    val appInfo = it.applicationInfo ?: try {
                        pm.getApplicationInfo(it.packageName, 0)
                    } catch (e: Exception) {
                        null
                    }
                    val appFlags = appInfo?.flags ?: 0
                    val isSystem = (appFlags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystem = (appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    !isSystem || isUpdatedSystem
                }
            }
            filtered.associateBy { it.packageName }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to load installed packages")
            emptyMap()
        }
    }

    // --- 兼容旧版调用的桥接方法 ---

    fun getBackups(packageName: String): Set<Backup> = runBlocking(Dispatchers.IO) {
        backupDao.get(packageName).toSet()
    }

    fun getBackupsFlow(packageName: String): Flow<List<Backup>> =
        backupDao.getFlow(packageName)

    fun getBackupsList(): List<Backup> = runBlocking(Dispatchers.IO) {
        backupDao.getAll()
    }

    fun getBackupsMap(): Map<String, Set<Backup>> = runBlocking(Dispatchers.IO) {
        backupDao.getAll().groupBy { it.packageName }.mapValues { it.value.toSet() }
    }

    fun getBackupsListFlow(): Flow<List<Backup>> = backupDao.getAllFlow()

    suspend fun updatePackage(packageName: String) {}

    suspend fun replaceAppInfos(vararg appInfos: AppInfo) {
        appInfoDao?.updateList(*appInfos)
    }

    fun replaceAllBackups(backups: List<Backup>) = runBlocking(Dispatchers.IO) {
        backupDao.updateList(*backups.toTypedArray())
    }

    fun deleteBackupsOf(packageNames: List<String>) = runBlocking(Dispatchers.IO) {
        for (pkg in packageNames) {
            backupDao.deleteAllOf(pkg)
        }
    }

    suspend fun enableDisable(packageName: String, users: List<String>, enable: Boolean) {}

    suspend fun uninstall(
        mPackage: Any?,
        users: List<String>,
        onDismiss: () -> Unit,
        showNotification: (String) -> Unit,
    ) {
        onDismiss()
    }

    suspend fun rewriteBackup(pkg: Any?, backup: Backup, changedBackup: Backup) {
        backupDao.delete(backup)
        backupDao.insert(changedBackup)
    }

    suspend fun deleteBackup(pkg: Any?, backup: Backup, onDismiss: () -> Unit = {}) {
        backupDao.delete(backup)
        onDismiss()
    }

    suspend fun deleteAllBackups(pkg: Any?, onDismiss: () -> Unit = {}) {
        onDismiss()
    }

    suspend fun updatePackageBackups(packageName: String, backups: Set<Backup>) {
        backupDao.updateList(packageName, backups)
    }

    suspend fun upsertAppInfo(vararg appInfos: AppInfo) {
        appInfoDao?.upsert(*appInfos)
    }

    suspend fun deleteAppInfoOf(packageName: String) {
        appInfoDao?.deleteAllOf(packageName)
    }
}