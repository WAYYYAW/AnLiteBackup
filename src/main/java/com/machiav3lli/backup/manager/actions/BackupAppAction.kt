/*
 * Neo Backup: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.manager.actions

import android.content.Context
import com.machiav3lli.backup.MODE_APK
import com.machiav3lli.backup.MODE_DATA
import com.machiav3lli.backup.MODE_DATA_DE
import com.machiav3lli.backup.MODE_DATA_EXT
import com.machiav3lli.backup.MODE_DATA_MEDIA
import com.machiav3lli.backup.MODE_DATA_OBB
import com.machiav3lli.backup.NeoApp
import com.machiav3lli.backup.batchModes
import com.machiav3lli.backup.batchOperations
import com.machiav3lli.backup.data.dbs.entity.AppInfo
import com.machiav3lli.backup.data.dbs.entity.Backup
import com.machiav3lli.backup.data.entity.ActionResult
import com.machiav3lli.backup.data.entity.Package
import com.machiav3lli.backup.data.entity.RootFile
import com.machiav3lli.backup.data.entity.StorageFile
import com.machiav3lli.backup.manager.handler.BackupBuilder
import com.machiav3lli.backup.manager.handler.BindMountManager
import com.machiav3lli.backup.manager.handler.LogsHandler
import com.machiav3lli.backup.manager.handler.ResticBackupResult
import com.machiav3lli.backup.manager.handler.ResticHandler
import com.machiav3lli.backup.manager.handler.ShellHandler
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.quote
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.utilBoxQ
import com.machiav3lli.backup.manager.tasks.AppActionWork
import com.machiav3lli.backup.ui.pages.pref_backupPauseApps
import com.machiav3lli.backup.ui.pages.pref_enableStorageCheck
import com.machiav3lli.backup.ui.pages.pref_fakeBackupSeconds
import com.machiav3lli.backup.utils.BACKUP_DATE_TIME_FORMATTER
import com.machiav3lli.backup.utils.CryptoSetupException
import com.machiav3lli.backup.utils.DATE_TIME_AS_VERSION_CODE_FORMATTER
import com.machiav3lli.backup.utils.FileUtils.BackupLocationInAccessibleException
import com.machiav3lli.backup.utils.FileUtils.checkAvailableStorage
import com.machiav3lli.backup.utils.StorageLocationNotConfiguredException
import com.machiav3lli.backup.utils.SystemUtils
import com.machiav3lli.backup.utils.extensions.Android
import com.machiav3lli.backup.utils.getEncryptionPassword
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.LocalDateTime

open class BackupAppAction(context: Context, work: AppActionWork?, shell: ShellHandler) :
    BaseAppAction(context, work, shell) {

    open fun run(app: Package, backupMode: Int): ActionResult {
        var backup: Backup? = null
        var ok = false
        val fakeSeconds = pref_fakeBackupSeconds.value

        fun handleException(e: Throwable): ActionResult {
            val message =
                "${e::class.simpleName}: ${e.message}${e.cause?.message?.let { " - $it" } ?: ""}"
            Timber.e("Backup failed: $message")
            return ActionResult(app, null, message, false)
        }

        try {
            Timber.i("Backing up: ${app.packageName} (${app.packageLabel})")
            work?.setOperation("B")

            if (fakeSeconds > 0) {
                val step = 1000L * 1
                val startTime = SystemUtils.msSinceBoot
                do {
                    val now = SystemUtils.msSinceBoot
                    val seconds = (now - startTime) / 1000.0
                    work?.setOperation((seconds / 10).toInt().toString().padStart(3, '0'))
                    Thread.sleep(step)
                } while (seconds < fakeSeconds)

                val succeeded = true

                return if (succeeded) {
                    Timber.w("package: ${app.packageName} faking success")
                    ActionResult(app, null, "faked backup succeeded", true)
                } else {
                    Timber.w("package: ${app.packageName} faking failure")
                    ActionResult(app, null, "faked backup failed", false)
                }
            }

            val backupRoot = NeoApp.backupRoot
                ?: return handleException(BackupFailedException(STORAGE_LOCATION_INACCESSIBLE, null))

            val appBackupBaseDir: StorageFile = try {
                app.getAppBackupBaseDir(create = true)!!
            } catch (e: BackupLocationInAccessibleException) {
                return handleException(BackupFailedException(STORAGE_LOCATION_INACCESSIBLE, e))
            } catch (e: StorageLocationNotConfiguredException) {
                return handleException(BackupFailedException(STORAGE_LOCATION_INACCESSIBLE, e))
            } catch (e: Throwable) {
                LogsHandler.unexpectedException(e, app)
                return handleException(BackupFailedException(STORAGE_LOCATION_INACCESSIBLE, e))
            }

            if (pref_enableStorageCheck.value) try {
                checkAvailableStorage(context, app, backupMode)
            } catch (e: BackupFailedException) {
                return handleException(e)
            }

            val backupBuilder = try {
                BackupBuilder(app.packageInfo, appBackupBaseDir)
            } catch (e: Throwable) {
                return handleException(BackupFailedException(STORAGE_LOCATION_INACCESSIBLE, e))
            }

            val backupInstanceDir = backupBuilder.backupDir
            val pauseApp = pref_backupPauseApps.value
            if (pauseApp)
                pauseApp(type = "backup", wh = When.pre, packageName = app.packageName)

            try {
                val password = getEncryptionPassword()
                ResticHandler.initRepo(context, backupRoot, password).onFailure { e ->
                    throw BackupFailedException("Failed to initialize restic repository: ${e.message}", e)
                }

                val resticResult = backupWithRestic(
                    app = app,
                    backupMode = backupMode,
                    backupDate = backupBuilder.backupDate,
                    password = password,
                    backupRoot = backupRoot,
                )

                backupBuilder.setHasApk(resticResult.hasApk)
                backupBuilder.setHasAppData(resticResult.hasAppData)
                backupBuilder.setHasDevicesProtectedData(resticResult.hasDevicesProtectedData)
                backupBuilder.setHasExternalData(resticResult.hasExternalData)
                backupBuilder.setHasObbData(resticResult.hasObbData)
                backupBuilder.setHasMediaData(resticResult.hasMediaData)
                backupBuilder.setResticSnapshotId(
                    resticResult.snapshotId
                        ?: throw BackupFailedException("restic backup returned no snapshot ID", null)
                )
                backupBuilder.setCompressionType("restic")

                StorageFile.invalidateCache(backupInstanceDir)
                backupBuilder.setSize(resticResult.size)

                backup = backupBuilder.createBackup()

                ok = backup.file != null && !backup.resticSnapshotId.isNullOrEmpty()

            } catch (e: BackupFailedException) {
                return handleException(e)
            } catch (e: CryptoSetupException) {
                return handleException(e)
            } catch (e: IOException) {
                return handleException(e)
            } finally {
                work?.setOperation("======")
                if (pauseApp)
                    pauseApp(type = "backup", wh = When.post, packageName = app.packageName)
                if (backup == null)
                    backup = backupBuilder.createBackup()
                if (ok)
                    app.addNewBackup(backup)
                else {
                    Timber.d("Backup failed -> deleting it")
                    app.deleteBackup(backup)
                }
            }
        } catch (e: Throwable) {
            return handleException(e)
        } finally {
            work?.setOperation("======>")
            Timber.i("${app.packageName}: Backup done: $backup")
        }
        return ActionResult(app, backup, "", true)
    }

    /**
     * 通过 `mount --bind` 构建虚拟暂存目录，然后调用 `restic backup` 提交快照。
     * 无用户数据复制开销，仅操作挂载表并在退出时自动逆序卸载。
     */
    @Throws(BackupFailedException::class)
    private fun backupWithRestic(
        app: Package,
        backupMode: Int,
        backupDate: LocalDateTime,
        password: String,
        backupRoot: StorageFile,
    ): ResticBackupResult {
        val stagingDir = File("/data/local/tmp/nb_staging_${app.packageName}_${System.currentTimeMillis()}")
        runAsRoot("export TMPDIR=/data/local/tmp && $utilBoxQ mkdir -p ${quote(stagingDir.absolutePath)}")

        val result = ResticBackupResult()

        try {
            BindMountManager().use { mounts ->
                // APK：以 root cp 复制至 staging/apk/
                if ((backupMode and MODE_APK) != 0) {
                    Timber.i("$app: Backing up ${batchModes[MODE_APK]}")
                    work?.setOperation(batchOperations[MODE_APK]!!)
                    val apkDir = File(stagingDir, "apk")
                    runAsRoot("$utilBoxQ mkdir -p ${quote(apkDir.absolutePath)}")
                    stageApkFiles(app, apkDir)
                    result.hasApk = true
                }

                // 数据分区：按需执行 mount --bind（或由子类覆写 stageAppData 处理特殊备份）
                if ((backupMode and MODE_DATA) != 0) {
                    Timber.i("$app: Backing up ${batchModes[MODE_DATA]}")
                    work?.setOperation(batchOperations[MODE_DATA]!!)
                    result.hasAppData = stageAppData(app, stagingDir, mounts)
                }

                if ((backupMode and MODE_DATA_DE) != 0) {
                    Timber.i("$app: Backing up ${batchModes[MODE_DATA_DE]}")
                    work?.setOperation(batchOperations[MODE_DATA_DE]!!)
                    result.hasDevicesProtectedData = if (app.devicesProtectedDataPath.isNotEmpty()) {
                        mounts.bindMount(app.devicesProtectedDataPath, stagingDir, "data_de")
                    } else {
                        false
                    }
                }

                if ((backupMode and MODE_DATA_EXT) != 0) {
                    Timber.i("$app: Backing up ${batchModes[MODE_DATA_EXT]}")
                    work?.setOperation(batchOperations[MODE_DATA_EXT]!!)
                    val extPath = app.getExternalDataPath()
                    result.hasExternalData = if (extPath.isNotEmpty()) {
                        mounts.bindMount(extPath, stagingDir, "external")
                    } else {
                        false
                    }
                }

                if ((backupMode and MODE_DATA_OBB) != 0) {
                    Timber.i("$app: Backing up ${batchModes[MODE_DATA_OBB]}")
                    work?.setOperation(batchOperations[MODE_DATA_OBB]!!)
                    val obbPath = app.getObbFilesPath()
                    result.hasObbData = if (obbPath.isNotEmpty()) {
                        mounts.bindMount(obbPath, stagingDir, "obb")
                    } else {
                        false
                    }
                }

                if ((backupMode and MODE_DATA_MEDIA) != 0) {
                    Timber.i("$app: Backing up ${batchModes[MODE_DATA_MEDIA]}")
                    work?.setOperation(batchOperations[MODE_DATA_MEDIA]!!)
                    val mediaPath = app.getMediaFilesPath()
                    result.hasMediaData = if (mediaPath.isNotEmpty()) {
                        mounts.bindMount(mediaPath, stagingDir, "media")
                    } else {
                        false
                    }
                }

                // 写入 props.json 到暂存目录根
                writeStagingProps(stagingDir, app, result, backupDate)

                // 执行 restic backup（root 进程，直接读取 bind-mount 树）
                result.snapshotId = ResticHandler.backup(
                    context = context,
                    backupRoot = backupRoot,
                    password = password,
                    packageName = app.packageName,
                    backupDate = backupDate.toString(),
                    stagingDir = stagingDir,
                ) { totalBytesProcessed, dataAddedPacked ->
                    result.size = if (totalBytesProcessed > 0L) totalBytesProcessed else dataAddedPacked
                } ?: throw BackupFailedException("restic backup returned no snapshot ID", null)
            }
        } catch (e: BackupFailedException) {
            throw e
        } catch (e: Throwable) {
            throw BackupFailedException("restic backup failed: ${e.message}", e)
        } finally {
            // 此时 BindMountManager.close() 已完成所有挂载点的卸载，安全清理暂存目录
            try {
                val stillMounted = runAsRoot(
                    "grep -q ${quote(stagingDir.absolutePath)} /proc/mounts",
                    throwFail = false
                ).isSuccess
                if (!stillMounted) {
                    runAsRoot("$utilBoxQ rm -rf ${quote(stagingDir.absolutePath)}", throwFail = false)
                } else {
                    Timber.e("Mount points still active under $stagingDir! Skipping recursive rm to protect data.")
                }
            } catch (e: Throwable) {
                Timber.w(e, "Could not delete staging dir: $stagingDir")
            }
        }

        return result
    }

    @Throws(BackupFailedException::class)
    protected open fun stageAppData(
        app: Package,
        stagingDir: File,
        mounts: BindMountManager,
    ): Boolean {
        return if (app.dataPath.isNotEmpty()) {
            mounts.bindMount(app.dataPath, stagingDir, BACKUP_DIR_DATA)
        } else {
            false
        }
    }

    @Throws(BackupFailedException::class)
    private fun stageApkFiles(app: Package, targetDir: File) {
        val baseApkPath = app.apkPath.ifEmpty { app.packageInfo.sourceDir ?: "" }
        val apks = (listOf(baseApkPath) + app.apkSplits).filter { it.isNotEmpty() }
        if (apks.isEmpty()) {
            throw BackupFailedException("No APK path found for ${app.packageName}", null)
        }
        apks.forEachIndexed { index, path ->
            val fileName = RootFile(path).name
            val destFile = File(targetDir, fileName)
            runAsRoot("$utilBoxQ cp ${quote(path)} ${quote(destFile.absolutePath)}")
            if (index == 0 && fileName != "base.apk") {
                val baseAlias = File(targetDir, "base.apk")
                runAsRoot("$utilBoxQ cp ${quote(path)} ${quote(baseAlias.absolutePath)}")
            }
        }
    }

    private fun writeStagingProps(
        stagingDir: File,
        app: Package,
        result: ResticBackupResult,
        backupDate: LocalDateTime,
    ) {
        val pkgInfo = app.packageInfo
        if (pkgInfo.versionName == "")
            pkgInfo.versionName = BACKUP_DATE_TIME_FORMATTER.format(backupDate)
        if (pkgInfo.versionCode == 0)
            pkgInfo.versionCode = DATE_TIME_AS_VERSION_CODE_FORMATTER.format(backupDate).toInt()

        val stagingBackup = Backup(
            base = pkgInfo,
            backupDate = backupDate,
            hasApk = result.hasApk,
            hasAppData = result.hasAppData,
            hasDevicesProtectedData = result.hasDevicesProtectedData,
            hasExternalData = result.hasExternalData,
            hasObbData = result.hasObbData,
            hasMediaData = result.hasMediaData,
            compressionType = "restic",
            cipherType = null,
            iv = null,
            cpuArch = Android.mainPlatform,
            permissions = if (pkgInfo is AppInfo) pkgInfo.permissions else emptyList(),
            size = 0L,
            persistent = false,
            note = "",
            resticSnapshotId = result.snapshotId,
        )
        val serialized = stagingBackup.toSerialized()
        val tempProps = File(context.cacheDir, "nb_props_${app.packageName}_${System.currentTimeMillis()}.json")
        try {
            tempProps.writeText(serialized)
            val targetProps = File(stagingDir, "props.json")
            runAsRoot("$utilBoxQ cp ${quote(tempProps.absolutePath)} ${quote(targetProps.absolutePath)}")
        } finally {
            runCatching { tempProps.delete() }
        }
    }

    @Deprecated("Replaced by backupWithRestic")
    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun genericBackupData(
        dataType: String,
        backupInstanceDir: StorageFile,
        filesToBackup: List<ShellHandler.FileInfo>,
        compress: Boolean,
        iv: ByteArray?,
    ): Boolean = false

    @Deprecated("Replaced by backupWithRestic")
    @Throws(BackupFailedException::class)
    protected open fun backupPackage(app: Package, backupInstanceDir: StorageFile) {}

    @Deprecated("Replaced by backupWithRestic")
    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupData(
        app: Package,
        backupInstanceDir: StorageFile,
        iv: ByteArray?,
    ): Boolean = false

    @Deprecated("Replaced by backupWithRestic")
    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupExternalData(
        app: Package,
        backupInstanceDir: StorageFile,
        iv: ByteArray?,
    ): Boolean = false

    @Deprecated("Replaced by backupWithRestic")
    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupObbData(
        app: Package,
        backupInstanceDir: StorageFile,
        iv: ByteArray?,
    ): Boolean = false

    @Deprecated("Replaced by backupWithRestic")
    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupMediaData(
        app: Package,
        backupInstanceDir: StorageFile,
        iv: ByteArray?,
    ): Boolean = false

    @Deprecated("Replaced by backupWithRestic")
    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupDeviceProtectedData(
        app: Package,
        backupInstanceDir: StorageFile,
        iv: ByteArray?,
    ): Boolean = false

    class BackupFailedException(message: String?, cause: Throwable?) :
        AppActionFailedException(message, cause)

    companion object {
        const val LOG_START_BACKUP = "[%s] Starting %s backup"
        const val LOG_NO_THING_TO_BACKUP = "[%s] No %s to backup available"
        const val STORAGE_LOCATION_INACCESSIBLE =
            "Cannot backup data. Storage location not set or inaccessible"
        const val STORAGE_LOCATION_NOTWRITABLE =
            "Cannot backup data. Storage location not writable"
    }
}
