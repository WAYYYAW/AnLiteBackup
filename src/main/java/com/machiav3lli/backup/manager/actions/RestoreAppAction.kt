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
import com.machiav3lli.backup.R
import com.machiav3lli.backup.batchModes
import com.machiav3lli.backup.batchOperations
import com.machiav3lli.backup.data.dbs.entity.Backup
import com.machiav3lli.backup.data.entity.ActionResult
import com.machiav3lli.backup.data.entity.Package
import com.machiav3lli.backup.data.entity.RootFile
import com.machiav3lli.backup.data.entity.StorageFile
import com.machiav3lli.backup.manager.handler.ResticHandler
import com.machiav3lli.backup.manager.handler.ResticLsEntry
import com.machiav3lli.backup.manager.handler.ShellCommands
import com.machiav3lli.backup.manager.handler.ShellHandler
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.hasPmBypassLowTargetSDKBlock
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.quote
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.quoteMultiple
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.runAsRootPipeInCollectErr
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.utilBoxQ
import com.machiav3lli.backup.manager.handler.ShellHandler.ShellCommandFailedException
import com.machiav3lli.backup.manager.handler.ShellHandler.UnexpectedCommandResult
import com.machiav3lli.backup.manager.handler.findBackups
import com.machiav3lli.backup.manager.tasks.AppActionWork
import com.machiav3lli.backup.ui.pages.pref_delayBeforeRefreshAppInfo
import com.machiav3lli.backup.ui.pages.pref_installationPackage
import com.machiav3lli.backup.ui.pages.pref_refreshAppInfoTimeout
import com.machiav3lli.backup.ui.pages.pref_restoreKillApps
import com.machiav3lli.backup.ui.pages.pref_restorePermissions
import com.machiav3lli.backup.utils.CryptoSetupException
import com.machiav3lli.backup.utils.extensions.Dirty
import com.machiav3lli.backup.utils.getEncryptionPassword
import com.machiav3lli.backup.utils.isAllowDowngrade
import com.machiav3lli.backup.utils.isDisableVerification
import com.machiav3lli.backup.utils.isRestoreAllPermissions
import timber.log.Timber
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream

open class RestoreAppAction(context: Context, work: AppActionWork?, shell: ShellHandler) :
    BaseAppAction(context, work, shell) {
    fun run(
        app: Package,
        backup: Backup,
        backupMode: Int,
    ): ActionResult {

        fun handleException(e: Throwable): ActionResult {
            val message =
                "${e::class.simpleName}: ${e.message}${e.cause?.message?.let { " - $it" } ?: ""}"
            Timber.e("Restore failed: $message")
            return ActionResult(app, null, message, false)
        }

        try {
            Timber.i("Restoring: ${app.packageName} (${app.packageLabel})")
            work?.setOperation("R")
            val killApp = pref_restoreKillApps.value
            if (killApp)
                pauseApp(type = "restore", wh = When.pre, packageName = app.packageName)

            try {
                if (backup.isLegacyBackup) {
                    throw RestoreFailedException(context.getString(R.string.legacy_backup_unsupported))
                }

                val backupDir = backup.dir
                    ?: run {
                        val backups =
                            context.findBackups(backup.packageName)[backup.packageName]
                        val found = backups?.find { it.backupDate == backup.backupDate }
                        found?.dir
                    }
                    ?: NeoApp.backupRoot
                if (backupDir != null) {

                    //==================================================
                    restoreAll(work, app, backup, backupDir, backupMode)
                    //==================================================

                } else return ActionResult(
                    app,
                    null,
                    "No backup file exists",
                    false
                )
            } catch (e: PackageManagerDataIncompleteException) {
                return ActionResult(
                    app,
                    null,
                    "${e::class.simpleName}: ${e.message}. ${context.getString(R.string.error_pmDataIncompleteException_dataRestoreFailed)}",
                    false
                )
            } catch (e: RestoreFailedException) {
                // Unwrap issues with shell commands so users know what command ran and what was the issue
                val message =
                    when (val cause = e.cause) {
                        is ShellCommandFailedException -> {
                            "Shell command failed: ${cause.command}\n${
                                extractErrorMessage(cause.shellResult)
                            }"
                        }

                        else                           -> {
                            "${e::class.simpleName}: ${e.message}"
                        }
                    }
                Timber.e("Restore failed: $message")
                return ActionResult(app, null, message, false)
            } catch (e: CryptoSetupException) {
                return handleException(e)
            } finally {
                work?.setOperation("======")
                if (killApp)
                    pauseApp(type = "restore", wh = When.post, packageName = app.packageName)
            }
        } catch (e: Throwable) {
            return handleException(e)
        } finally {
            work?.setOperation("======>")
            Timber.i("$app: Restore done: $backup")
        }
        return ActionResult(app, backup, "", true)
    }

    @Throws(CryptoSetupException::class, RestoreFailedException::class)
    protected open fun restoreAll(
        work: AppActionWork?,
        app: Package,
        backup: Backup,
        backupDir: StorageFile,
        backupMode: Int,
    ) {
        if (backup.isLegacyBackup) {
            throw RestoreFailedException(context.getString(R.string.legacy_backup_unsupported))
        }
        fun doRestore(mode: Int, cond: Boolean, todo: () -> Unit) {
            if (cond && (backupMode and mode) != 0) {
                Timber.i("$app: Restoring ${batchModes[mode]}")
                work?.setOperation(batchOperations[mode]!!)
                todo()
            }
        }
        doRestore(MODE_APK, backup.hasApk) {
            restorePackage(backupDir, backup)
        }
        refreshAppInfo(context, app)    // also waits for valid paths
        doRestore(MODE_DATA, backup.hasAppData) {
            restoreData(app, backup, backupDir)
        }
        doRestore(MODE_DATA_DE, backup.hasDevicesProtectedData) {
            restoreDeviceProtectedData(app, backup, backupDir)
        }
        doRestore(MODE_DATA_EXT, backup.hasExternalData) {
            restoreExternalData(app, backup, backupDir)
        }
        doRestore(MODE_DATA_OBB, backup.hasObbData) {
            restoreObbData(app, backup, backupDir)
        }
        doRestore(MODE_DATA_MEDIA, backup.hasMediaData) {
            restoreMediaData(app, backup, backupDir)
        }
    }

    @Throws(ShellCommandFailedException::class)
    protected fun wipeDirectory(targetPath: String, excludeDirs: List<String>) {
        if (targetPath != "/" && targetPath.isNotEmpty() && RootFile(targetPath).exists()) {
            val targetContents: MutableList<String> =
                mutableListOf(*shell.suGetDirectoryContents(RootFile(targetPath)))
            targetContents.removeAll(excludeDirs)
            if (targetContents.isEmpty()) {
                Timber.i("Nothing to remove in $targetPath")
                return
            }
            val removeTargets = targetContents
                .map { s -> RootFile(targetPath, s).absolutePath }
            Timber.d("Removing existing files in $targetPath")
            val command = "$utilBoxQ rm -rf ${quoteMultiple(removeTargets)}"
            runAsRoot(command)
        }
    }

    @Throws(RestoreFailedException::class)
    open fun restorePackage(backupDir: StorageFile, backup: Backup) {
        val backupRoot = NeoApp.backupRoot ?: backupDir
        restorePackageViaResticDump(backup, backupRoot)
    }

    /**
     * 通过 `restic dump` 流式提取并安装 APK（不落地公共目录）：
     * - 单 APK：直接将 `restic dump` 的 stdout 通过管道传入 `pm install -S <size>`
     * - Split APK：创建 `pm install-create` 会话，循环 `pm install-write` 流式写入每个 APK，最后 `pm install-commit`；
     *   若会话中途发生异常，在 `finally` 中调用 `pm install-abandon` 防止会话泄漏。
     */
    @Throws(RestoreFailedException::class)
    fun restorePackageViaResticDump(backup: Backup, backupRoot: StorageFile) {
        if (backup.isLegacyBackup) {
            throw RestoreFailedException(context.getString(R.string.legacy_backup_unsupported))
        }
        val snapshotId = backup.resticSnapshotId?.takeIf { it.isNotEmpty() }
            ?: throw RestoreFailedException("No restic snapshot ID in backup properties")
        val password = getEncryptionPassword()
        val profileId = ShellCommands.currentProfile
        val packageName = backup.packageName

        Timber.i("<$packageName> Restoring APK from restic snapshot $snapshotId to profile $profileId")

        val apkEntries = ResticHandler.listSnapshotPath(
            context = context,
            backupRoot = backupRoot,
            password = password,
            snapshotId = snapshotId,
            path = "/apk",
        ).filter { it.type == "file" && it.path.endsWith(".apk") }

        if (apkEntries.isEmpty()) {
            throw RestoreFailedException("No APK files found under /apk in snapshot $snapshotId")
        }

        val sourceApkName = backup.sourceDir?.let { File(it).name }?.takeIf { it.isNotEmpty() }
        val baseApk = apkEntries.find { it.path.endsWith("/$BASE_APK_FILENAME") || it.path == BASE_APK_FILENAME }
            ?: apkEntries.find { sourceApkName != null && it.path.substringAfterLast("/") == sourceApkName }
            ?: apkEntries.firstOrNull()
            ?: throw RestoreFailedException("base.apk not found in snapshot $snapshotId")

        val splitApks = apkEntries.filter { entry ->
            entry != baseApk &&
                !(sourceApkName != null &&
                    sourceApkName != BASE_APK_FILENAME &&
                    entry.path.substringAfterLast("/") == sourceApkName &&
                    entry.size == baseApk.size)
        }

        val disableVerification = isDisableVerification
        if (disableVerification) {
            runAsRoot("settings put global verifier_verify_adb_installs 0", throwFail = false)
        }

        try {
            if (splitApks.isEmpty()) {
                installSingleApkViaStream(snapshotId, baseApk, profileId, backupRoot, password)
            } else {
                installSplitApksViaSession(snapshotId, baseApk, splitApks, profileId, backupRoot, password)
            }

            if (!isRestoreAllPermissions && pref_restorePermissions.value) {
                backup.permissions
                    .filterNot { it.isEmpty() }
                    .forEach { p ->
                        try {
                            runAsRoot("pm grant --user $profileId ${backup.packageName} $p")
                        } catch (e: ShellCommandFailedException) {
                            val details = e.shellResult.err
                                .joinToString("\n")
                                .splitToSequence("\n\tat ")
                                .first()
                            Timber.e("Restoring permission $p failed: $details")
                        }
                    }
            }
        } finally {
            if (disableVerification) {
                runAsRoot("settings put global verifier_verify_adb_installs 1", throwFail = false)
            }
        }
    }

    @Throws(RestoreFailedException::class)
    private fun installSingleApkViaStream(
        snapshotId: String,
        baseApk: ResticLsEntry,
        profileId: Int,
        backupRoot: StorageFile,
        password: String,
    ) {
        if (baseApk.size <= 0L) {
            throw RestoreFailedException("Invalid APK size (${baseApk.size}) for ${baseApk.path}")
        }
        val cmd = listOfNotNull(
            "pm", "install",
            if (isRestoreAllPermissions) "-g" else null,
            if (isAllowDowngrade) "-d" else null,
            if (hasPmBypassLowTargetSDKBlock) "--bypass-low-target-sdk-block" else null,
            pref_installationPackage.value.takeIf { it.isNotBlank() }?.let { "-i ${quote(it)}" },
            "-t",
            "-r",
            "-S", baseApk.size.toString(),
            "--user", profileId.toString(),
            "1>&2",
        ).joinToString(" ")

        pipeResticDumpToPm(
            backupRoot = backupRoot,
            password = password,
            snapshotId = snapshotId,
            apkPath = baseApk.path,
            pmCommand = cmd,
        )
    }

    @Throws(RestoreFailedException::class)
    private fun installSplitApksViaSession(
        snapshotId: String,
        baseApk: ResticLsEntry,
        splitApks: List<ResticLsEntry>,
        profileId: Int,
        backupRoot: StorageFile,
        password: String,
    ) {
        val allApks = listOf(baseApk) + splitApks
        val totalSize = allApks.sumOf { it.size }
        if (totalSize <= 0L) {
            throw RestoreFailedException("Invalid total APK size ($totalSize) in snapshot $snapshotId")
        }

        val sessionCreateCmd = getSessionCreateCommand(profileId, totalSize)
        val createResult = try {
            runAsRoot(sessionCreateCmd)
        } catch (e: ShellCommandFailedException) {
            throw RestoreFailedException(
                "Could not create install session: ${extractErrorMessage(e.shellResult)}",
                e
            )
        }

        val sessionId = createResult.out
            .firstNotNullOfOrNull { line ->
                Regex("""(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
            }
            ?: throw RestoreFailedException(
                "Could not parse install session ID from: ${createResult.out.joinToString()}"
            )

        var committed = false
        try {
            for (entry in allApks) {
                val fileName = entry.path.substringAfterLast("/")
                val writeCmd = listOf(
                    "pm", "install-write",
                    "-S", entry.size.toString(),
                    sessionId.toString(),
                    quote(fileName),
                    "-",
                    "1>&2",
                ).joinToString(" ")

                pipeResticDumpToPm(
                    backupRoot = backupRoot,
                    password = password,
                    snapshotId = snapshotId,
                    apkPath = entry.path,
                    pmCommand = writeCmd,
                )
            }

            val commitCmd = getSessionCommitCommand(sessionId)
            val commitResult = runAsRoot(commitCmd)
            val commitOutput = (commitResult.out + commitResult.err).joinToString("\n")
            if (!commitResult.isSuccess || commitOutput.contains("Failure", ignoreCase = true)) {
                throw RestoreFailedException("pm install-commit failed for session $sessionId: $commitOutput")
            }
            committed = true
        } catch (e: ShellCommandFailedException) {
            throw RestoreFailedException(
                "pm session install failed: ${extractErrorMessage(e.shellResult)}",
                e
            )
        } finally {
            if (!committed) {
                Timber.w("Abandoning incomplete pm install session $sessionId")
                runCatching {
                    runAsRoot("pm install-abandon $sessionId", throwFail = false)
                }
            }
        }
    }

    @Throws(RestoreFailedException::class)
    private fun pipeResticDumpToPm(
        backupRoot: StorageFile,
        password: String,
        snapshotId: String,
        apkPath: String,
        pmCommand: String,
    ) {
        val pipedIn = PipedInputStream(65536)
        val pipedOut = PipedOutputStream(pipedIn)
        var dumpOk = false
        var dumpError: Throwable? = null

        val dumpThread = Thread {
            try {
                pipedOut.use { out ->
                    dumpOk = ResticHandler.dumpFile(
                        context = context,
                        backupRoot = backupRoot,
                        password = password,
                        snapshotId = snapshotId,
                        filePath = apkPath,
                        output = out,
                    )
                }
            } catch (t: Throwable) {
                dumpError = t
            }
        }.apply {
            name = "restic-dump-$snapshotId"
            start()
        }

        val (code, err) = pipedIn.use { input ->
            try {
                runAsRootPipeInCollectErr(input, pmCommand)
            } finally {
                // Drain any remaining bytes if pm exited early so restic dump does not block on pipe_write
                runCatching {
                    val drainBuf = ByteArray(65536)
                    while (input.read(drainBuf) != -1) {
                        // discard remaining stream
                    }
                }
            }
        }
        dumpThread.join(60_000)

        dumpError?.let { error ->
            throw RestoreFailedException(
                "Failed dumping $apkPath from restic: ${error.message}",
                error
            )
        }
        if (!dumpOk) {
            throw RestoreFailedException("restic dump failed for $apkPath in snapshot $snapshotId")
        }
        if (code != 0 || err.contains("Failure [", ignoreCase = true)) {
            throw RestoreFailedException("pm command failed (code=$code): ${err.trim()}")
        }
    }

    /**
     * 使用 `restic restore --include /<dataType>` 精准恢复单个数据分区子树。
     */
    @Throws(RestoreFailedException::class)
    fun genericRestoreFromRestic(
        dataType: String,
        backup: Backup,
        targetPath: String,
    ) {
        if (backup.isLegacyBackup) {
            throw RestoreFailedException(context.getString(R.string.legacy_backup_unsupported))
        }
        val snapshotId = backup.resticSnapshotId?.takeIf { it.isNotEmpty() }
            ?: throw RestoreFailedException("No restic snapshot ID in backup properties")
        val backupRoot = NeoApp.backupRoot
            ?: throw RestoreFailedException("Backup root not configured")
        val password = getEncryptionPassword()

        val resticDataType = when (dataType) {
            BACKUP_DIR_DEVICE_PROTECTED_FILES -> "data_de"
            BACKUP_DIR_EXTERNAL_FILES         -> "external"
            BACKUP_DIR_OBB_FILES              -> "obb"
            BACKUP_DIR_MEDIA_FILES            -> "media"
            else                              -> dataType.trimStart('/')
        }

        val tempDir = File("/data/local/tmp/nb_restore_${backup.packageName}_${System.currentTimeMillis()}")
        try {
            val ok = ResticHandler.restoreDataType(
                context = context,
                backupRoot = backupRoot,
                password = password,
                snapshotId = snapshotId,
                dataType = resticDataType,
                targetDir = tempDir,
            )
            if (!ok) {
                Timber.i("No $resticDataType in snapshot $snapshotId, skipping")
                return
            }

            val sourceDir = File(tempDir, resticDataType)
            val sourceExists = runAsRoot(
                "$utilBoxQ test -d ${quote(sourceDir.absolutePath)}",
                throwFail = false
            ).isSuccess
            if (!sourceExists) {
                Timber.i("$resticDataType not found after restore, skipping")
                return
            }

            val excludedInSource = NeoApp.assets.DATA_RESTORE_EXCLUDED_BASENAMES
                .map { File(sourceDir, it).absolutePath }
            if (excludedInSource.isNotEmpty()) {
                runAsRoot("$utilBoxQ rm -rf ${quoteMultiple(excludedInSource)}", throwFail = false)
            }

            runAsRoot("$utilBoxQ mkdir -p ${quote(targetPath)}")
            wipeDirectory(targetPath, NeoApp.assets.DATA_RESTORE_EXCLUDED_BASENAMES)
            runAsRoot("$utilBoxQ cp -a ${quote(sourceDir.absolutePath)}/. ${quote(targetPath)}/")
        } catch (e: ShellCommandFailedException) {
            throw RestoreFailedException(
                "Shell command failed restoring $resticDataType: ${extractErrorMessage(e.shellResult)}",
                e
            )
        } finally {
            try {
                runAsRoot("$utilBoxQ rm -rf ${quote(tempDir.absolutePath)}", throwFail = false)
            } catch (e: Throwable) {
                Timber.w("Could not clean restore temp dir: $e")
            }
        }
    }

    @Throws(RestoreFailedException::class, CryptoSetupException::class)
    fun genericRestoreFromArchive(
        dataType: String,
        backup: Backup,
        targetPath: String,
    ) {
        if (backup.isLegacyBackup) {
            throw RestoreFailedException(context.getString(R.string.legacy_backup_unsupported))
        }
        genericRestoreFromRestic(dataType, backup, targetPath)
    }

    @Deprecated("Legacy tar archive restore is no longer supported; use genericRestoreFromRestic")
    @Throws(RestoreFailedException::class, CryptoSetupException::class)
    fun genericRestoreFromArchive(
        dataType: String,
        archive: StorageFile,
        targetPath: String,
        isCompressed: Boolean,
        compressionType: String?,
        isEncrypted: Boolean,
        iv: ByteArray?,
        cachePath: File?,
        forceOldVersion: Boolean = false,
        backup: Backup? = null,
    ) {
        if (backup == null || backup.isLegacyBackup) {
            throw RestoreFailedException(context.getString(R.string.legacy_backup_unsupported))
        }
        genericRestoreFromRestic(dataType, backup, targetPath)
    }

    fun getOwnerGroupContextWithWorkaround(
        // TODO hg42 this is the best I could come up with for now
        app: Package,
        extractTo: String,
    ): Array<String> {
        val uidgidcon = try {
            shell.suGetOwnerGroupContext(extractTo)
        } catch (e: Throwable) {
            val fromParent = shell.suGetOwnerGroupContext(File(extractTo).parent!!)
            val fromData = shell.suGetOwnerGroupContext(app.dataPath)
            arrayOf(
                fromData[0],    // user from app data
                fromParent[1],  // group is independent of app
                fromParent[2]   // context is independent of app //TODO hg42 really? some seem to be restricted to app? or may be they should...
                // note: restorecon does not work, because it sets storage_file instead of media_rw_data_file
                // (returning "?" here would choose restorecon)
            )
        }
        return uidgidcon
    }

    @Throws(RestoreFailedException::class)
    private fun genericRestorePermissions(
        dataType: String,
        targetPath: String,
        uidgidcon: Array<String>,
    ) {
        try {
            val (uid, gid, con) = uidgidcon
            val gidCache = Dirty.appGidToCacheGid(gid)
            Timber.i("Getting user/group info and apply it recursively on $targetPath")
            // get the contents. lib for example must be owned by root
            //TODO hg42 I think, lib is always a link
            //TODO hg42 directories we exclude would keep their uidgidcon from before
            //TODO hg42 this doesn't seem to be correct, unless the apk install would manage updating uidgidcon
            val topLevelFiles: MutableList<String> =
                mutableListOf(*shell.suGetDirectoryContents(RootFile(targetPath)))
            // Don't exclude any files from chown, as this may cause SELINUX issues (lost of data on restart)
            // calculate a list of what must be updated inside the directory

            // assuming target exists, otherwise we should not enter this function, it's guarded outside
            val target = RootFile(targetPath).absolutePath
            val chownTargets = topLevelFiles
                .filterNot { it in NeoApp.assets.DATA_EXCLUDED_CACHE_DIRS }
                .map { s -> RootFile(targetPath, s).absolutePath }
            val cacheTargets = topLevelFiles
                .filter { it in NeoApp.assets.DATA_EXCLUDED_CACHE_DIRS }
                .map { s -> RootFile(targetPath, s).absolutePath }
            Timber.d("Changing owner and group to $uid:$gid for $target and recursive for $chownTargets")
            Timber.d("Changing owner and group to $uid:$gidCache for cache $cacheTargets")
            Timber.d("Changing selinux context to $con for $target")

            fun commandChown(uid: String, gid: String, target: String): String {
                return "$utilBoxQ chown $uid:$gid ${
                    quote(target)
                }"
            }

            fun commandChownMultiRec(uid: String, gid: String, targets: List<String>): String? {
                return if (targets.isNotEmpty())
                    "$utilBoxQ chown -R $uid:$gid ${
                        quoteMultiple(targets)
                    }"
                else
                    null
            }

            fun commandChcon(con: String, target: String): String? {
                return if (con == "?")
                    null
                else
                    "chcon -R -h -v '$con' ${quote(target)}"
            }

            val command = listOf(
                commandChown(uid, gid, target),
                commandChownMultiRec(uid, gid, chownTargets),
                commandChownMultiRec(uid, gidCache, cacheTargets),
                commandChcon(con, target),
            ).filterNotNull().joinToString(" ; ")
            runAsRoot(command)
        } catch (e: ShellCommandFailedException) {
            val errorMessage = "Could not update permissions for $dataType"
            Timber.e(errorMessage)
            throw RestoreFailedException(errorMessage, e)
        } catch (e: UnexpectedCommandResult) {
            val errorMessage =
                "Could not extract user and group information from $dataType directory"
            Timber.e(errorMessage)
            throw RestoreFailedException(errorMessage, e)
        }
    }

    @Throws(RestoreFailedException::class, CryptoSetupException::class)
    open fun restoreData(
        app: Package,
        backup: Backup,
        backupDir: StorageFile,
    ) {
        val dataType = "data"
        val extractTo = app.dataPath
        if (!isPlausiblePath(extractTo, app.packageName))
            throw RestoreFailedException(
                "path '$extractTo' does not contain ${app.packageName}"
            )

        if (!RootFile(extractTo).isDirectory)
            throw RestoreFailedException("directory '$extractTo' does not exist")

        // retrieve the assigned uid and gid from the data directory Android created
        val uidgidcon = shell.suGetOwnerGroupContext(extractTo)
        genericRestoreFromArchive(dataType, backup, extractTo)
        genericRestorePermissions(
            dataType,
            extractTo,
            uidgidcon
        )
    }

    @Throws(RestoreFailedException::class, CryptoSetupException::class)
    open fun restoreDeviceProtectedData(
        app: Package,
        backup: Backup,
        backupDir: StorageFile,
    ) {
        val dataType = "data_de"
        val extractTo = app.devicesProtectedDataPath
        if (!isPlausiblePath(extractTo, app.packageName))
            throw RestoreFailedException(
                "path '$extractTo' does not contain ${app.packageName}"
            )

        if (!RootFile(extractTo).isDirectory)
            throw RestoreFailedException("directory '$extractTo' does not exist")

        // retrieve the assigned uid and gid from the data directory Android created
        val uidgidcon = shell.suGetOwnerGroupContext(extractTo)
        genericRestoreFromArchive(dataType, backup, extractTo)
        genericRestorePermissions(
            dataType,
            extractTo,
            uidgidcon
        )
    }

    @Throws(RestoreFailedException::class, CryptoSetupException::class)
    open fun restoreExternalData(
        app: Package,
        backup: Backup,
        backupDir: StorageFile,
    ) {
        val dataType = "external"
        val extractTo = app.getExternalDataPath()
        if (!isPlausiblePath(extractTo, app.packageName))
            throw RestoreFailedException(
                "path '$extractTo' does not contain ${app.packageName}"
            )

        val uidgidcon = getOwnerGroupContextWithWorkaround(app, extractTo)
        genericRestoreFromArchive(dataType, backup, extractTo)
        genericRestorePermissions(
            dataType,
            extractTo,
            uidgidcon
        )
    }

    @Throws(RestoreFailedException::class)
    open fun restoreObbData(
        app: Package,
        backup: Backup,
        backupDir: StorageFile,
    ) {
        val dataType = "obb"
        val extractTo = app.getObbFilesPath()
        if (!isPlausiblePath(extractTo, app.packageName))
            throw RestoreFailedException(
                "path '$extractTo' does not contain ${app.packageName}"
            )

        val uidgidcon = getOwnerGroupContextWithWorkaround(app, extractTo)
        genericRestoreFromArchive(dataType, backup, extractTo)
        genericRestorePermissions(
            dataType,
            extractTo,
            uidgidcon
        )
    }

    @Throws(RestoreFailedException::class)
    open fun restoreMediaData(
        app: Package,
        backup: Backup,
        backupDir: StorageFile,
    ) {
        val dataType = "media"
        val extractTo = app.getMediaFilesPath()
        if (!isPlausiblePath(extractTo, app.packageName))
            throw RestoreFailedException(
                "path '$extractTo' does not contain ${app.packageName}"
            )

        val uidgidcon = getOwnerGroupContextWithWorkaround(app, extractTo)
        genericRestoreFromArchive(dataType, backup, extractTo)
        genericRestorePermissions(
            dataType,
            extractTo,
            uidgidcon
        )
    }

    private fun getSessionCreateCommand(
        profileId: Int,
        sumSize: Long,
    ): String =
        listOfNotNull(
            "pm", "install-create",
            if (isRestoreAllPermissions) "-g" else null,
            if (isAllowDowngrade) "-d" else null,
            if (hasPmBypassLowTargetSDKBlock) "--bypass-low-target-sdk-block" else null,
            pref_installationPackage.value.takeIf { it.isNotBlank() }?.let { "-i ${quote(it)}" },
            "-t",
            "-r",
            "-S", sumSize,
            "--user", profileId,
        ).joinToString(" ")


    private fun getSessionCommitCommand(
        sessionId: Int,
    ): String =
        listOfNotNull(
            "pm", "install-commit", sessionId
        ).joinToString(" ")

    @Throws(PackageManagerDataIncompleteException::class)
    open fun refreshAppInfo(context: Context, app: Package) {
        val sleepTimeMs = 1000L

        // delay before first try
        val delayMs = pref_delayBeforeRefreshAppInfo.value * 1000L
        var timeWaitedMs = 0L
        do {
            Thread.sleep(sleepTimeMs)
            timeWaitedMs += sleepTimeMs
        } while (timeWaitedMs < delayMs)

        // try multiple times to get valid paths from PackageManager
        // maxWaitMs is cumulated sleep time between tries
        val maxWaitMs = pref_refreshAppInfoTimeout.value * 1000L
        timeWaitedMs = 0L
        var attemptNo = 0
        do {
            if (timeWaitedMs > maxWaitMs) {
                throw PackageManagerDataIncompleteException(maxWaitMs / 1000L)
            }
            if (timeWaitedMs > 0) {
                Timber.d("<${app.packageName}> PackageManager returned invalid data paths, attempt $attemptNo, waited ${timeWaitedMs / 1000L} of $maxWaitMs seconds")
                Thread.sleep(sleepTimeMs)
            }
            app.refreshFromPackageManager(context)
            timeWaitedMs += sleepTimeMs
            attemptNo++
        } while (!this.isPlausiblePackageInfo(app))
    }

    private fun isPlausiblePackageInfo(app: Package): Boolean {
        return app.dataPath.isNotBlank()
                && app.apkPath.isNotBlank()
                && app.devicesProtectedDataPath.isNotBlank()
    }

    private fun isPlausiblePath(path: String, packageName: String): Boolean {
        return path.contains(packageName)
    }

    class RestoreFailedException : AppActionFailedException {
        constructor(message: String?) : super(message)
        constructor(message: String?, cause: Throwable?) : super(message, cause)
    }

    class PackageManagerDataIncompleteException(val seconds: Long) :
        Exception("PackageManager returned invalid data paths after trying $seconds seconds to retrieve them")

    companion object {
        protected val PACKAGE_STAGING_DIRECTORY = RootFile("/data/local/tmp")
        const val BASE_APK_FILENAME = "base.apk"
        const val LOG_DIR_IS_MISSING_CANNOT_RESTORE =
            "Backup directory %s is missing. Cannot restore"
        const val LOG_EXTRACTING_S = "[%s] Extracting %s"
        const val LOG_BACKUP_ARCHIVE_MISSING = "Backup archive %s is missing. Cannot restore"

        fun isOldVersion(backup: Backup) = backup.backupVersionCode < 8000
    }
}
