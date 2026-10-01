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
import com.machiav3lli.backup.data.dbs.entity.SpecialInfo
import com.machiav3lli.backup.data.entity.ActionResult
import com.machiav3lli.backup.data.entity.Package
import com.machiav3lli.backup.manager.handler.BindMountManager
import com.machiav3lli.backup.manager.handler.LogsHandler
import com.machiav3lli.backup.manager.handler.ShellHandler
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.quote
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.utilBoxQ
import com.machiav3lli.backup.manager.handler.ShellHandler.ShellCommandFailedException
import com.machiav3lli.backup.manager.tasks.AppActionWork
import timber.log.Timber
import java.io.File

class BackupSpecialAction(context: Context, work: AppActionWork?, shell: ShellHandler) :
    BackupAppAction(context, work, shell) {
    override fun run(app: Package, backupMode: Int): ActionResult {
        if (backupMode and MODE_APK == MODE_APK) {
            Timber.e("Special contents don't have APKs to backup. Ignoring")
        }
        return if (backupMode and MODE_DATA == MODE_DATA)
            super.run(app, MODE_DATA)
        else ActionResult(
            app, null,
            "Special backup only backups data, but data was not selected for backup",
            false
        )
    }

    @Throws(BackupFailedException::class)
    override fun stageAppData(
        app: Package,
        stagingDir: File,
        mounts: BindMountManager,
    ): Boolean {
        Timber.i("$app: Backup special data")
        require(app.packageInfo is SpecialInfo) { "Provided app is not an instance of SpecialAppMetaInfo" }
        val appInfo = app.packageInfo as SpecialInfo
        val dataDir = File(stagingDir, BACKUP_DIR_DATA)
        var stagedCount = 0
        try {
            work?.setOperation("s")
            runAsRoot("$utilBoxQ mkdir -p ${quote(dataDir.absolutePath)}")
            for (filePath in appInfo.specialFiles) {
                if (app.packageName == "special.smsmms.json") {
                    BackupSMSMMSJSONAction.backupData(context, filePath)
                }
                if (app.packageName == "special.calllogs.json") {
                    BackupCallLogsJSONAction.backupData(context, filePath)
                }
                val isDirSource = filePath.endsWith("/")
                val cleanPath = filePath.removeSuffix("/")
                val file = File(cleanPath)
                val destFile = File(dataDir, file.name)
                if (isDirSource) {
                    val exists = runAsRoot(
                        "$utilBoxQ test -d ${quote(file.absolutePath)}",
                        throwFail = false
                    ).isSuccess
                    if (!exists) {
                        Timber.w("$app: Special directory ${file.absolutePath} does not exist, skipping")
                        continue
                    }
                }
                runAsRoot("$utilBoxQ cp -a ${quote(file.absolutePath)} ${quote(destFile.absolutePath)}")
                stagedCount++
            }
            if (stagedCount == 0) {
                throw BackupFailedException("No special files found to backup for ${app.packageName}", null)
            }
        } catch (e: BackupFailedException) {
            throw e
        } catch (e: RuntimeException) {
            throw BackupFailedException("${e.message}", e)
        } catch (e: ShellCommandFailedException) {
            val error = extractErrorMessage(e.shellResult)
            Timber.e("$app: Backup Special Data failed: $error")
            throw BackupFailedException(error, e)
        } catch (e: Throwable) {
            LogsHandler.unexpectedException(e, app)
            throw BackupFailedException("unhandled exception", e)
        } finally {
            if (app.packageName == "special.smsmms.json" || app.packageName == "special.calllogs.json") {
                for (filePath in appInfo.specialFiles) {
                    File(filePath).delete()
                }
            }
        }
        return true
    }

    override fun pauseApp(type: String, wh: When, packageName: String) {
        // special packages are not apps
    }
}