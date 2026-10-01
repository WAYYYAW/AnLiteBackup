/*
 * AnLite Backup: open-source apps backup and restore app.
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
package com.anlite.backup.manager.actions

import android.content.Context
import android.os.Build
import com.anlite.backup.AnLiteApp
import com.anlite.backup.R
import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.entity.StorageFile
import com.anlite.backup.manager.handler.ResticHandler
import com.anlite.backup.manager.handler.ShellHandler
import com.anlite.backup.manager.handler.ShellHandler.Companion.quote
import com.anlite.backup.manager.handler.ShellHandler.Companion.runAsRoot
import com.anlite.backup.manager.handler.ShellHandler.Companion.utilBoxQ
import com.anlite.backup.manager.handler.ShellHandler.ShellCommandFailedException
import com.anlite.backup.manager.tasks.AppActionWork
import com.anlite.backup.utils.extensions.Android
import com.anlite.backup.utils.getEncryptionPassword
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class RestoreSystemAppAction(context: Context, work: AppActionWork?, shell: ShellHandler) :
    RestoreAppAction(context, work, shell) {

    @Throws(RestoreFailedException::class)
    override fun restorePackage(backupDir: StorageFile, backup: Backup) {
        if (backup.isLegacyBackup) {
            throw RestoreFailedException(context.getString(R.string.legacy_backup_unsupported))
        }
        val snapshotId = backup.resticSnapshotId?.takeIf { it.isNotEmpty() }
            ?: throw RestoreFailedException("No restic snapshot ID in backup properties")
        val backupRoot = AnLiteApp.backupRoot ?: backupDir
        val password = getEncryptionPassword()

        val sourceDir = backup.sourceDir?.takeIf { it.isNotBlank() }
            ?: throw RestoreFailedException("Could not find apk location in backup")
        val apkTargetPath = File(sourceDir)
        val appDir = apkTargetPath.parentFile?.absoluteFile
            ?: throw RestoreFailedException("Could not find apk location in backup")

        val apkEntries = ResticHandler.listSnapshotPath(
            context = context,
            backupRoot = backupRoot,
            password = password,
            snapshotId = snapshotId,
            path = "/apk",
        ).filter { it.type == "file" && it.path.endsWith(".apk") }

        val apkEntry = apkEntries.find { it.path.substringAfterLast("/") == apkTargetPath.name }
            ?: apkEntries.find { it.path.endsWith("/$BASE_APK_FILENAME") || it.path == BASE_APK_FILENAME }
            ?: apkEntries.firstOrNull()
            ?: throw RestoreFailedException("Could not find main apk in backup")

        // Writing the apk to a temporary location to get it out of the restic snapshot to a local location
        // that can be accessed with shell commands.
        val tempPath = File(context.cacheDir, "${backup.packageName}_${apkTargetPath.name}")
        try {
            val dumped = try {
                FileOutputStream(tempPath).use { outputStream ->
                    ResticHandler.dumpFile(
                        context = context,
                        backupRoot = backupRoot,
                        password = password,
                        snapshotId = snapshotId,
                        filePath = apkEntry.path,
                        output = outputStream,
                    )
                }
            } catch (e: IOException) {
                throw RestoreFailedException("Could extract main apk file to temporary location", e)
            }

            if (!dumped || !tempPath.exists() || tempPath.length() == 0L) {
                throw RestoreFailedException("Could extract main apk file to temporary location")
            }

            var mountPoint = "/"
            if (!Android.minSDK(Build.VERSION_CODES.Q)) {
                // Android versions prior Android 10 use /system
                mountPoint = "/system"
            }
            val command =
                "(mount -o remount,rw ${quote(mountPoint)} && " +
                        "mkdir -p ${quote(appDir)} && (" +  // chmod might be obsolete
                        "$utilBoxQ chmod 755 ${quote(appDir)} ; " +  // for some reason a permissions error is thrown if the apk path is not created first
                        "$utilBoxQ touch ${quote(apkTargetPath)} ; " + // with touch, a reboot is not necessary after restoring system apps
                        "$utilBoxQ mv -f ${quote(tempPath)} ${quote(apkTargetPath)} ; " +
                        "$utilBoxQ chmod 644 ${quote(apkTargetPath)}" +
                        ")" +
                        "); mount -o remount,ro $mountPoint"
            try {
                runAsRoot(command)
            } catch (e: ShellCommandFailedException) {
                val error = extractErrorMessage(e.shellResult)
                Timber.e("Restore System apk failed: $error")
                throw RestoreFailedException(error, e)
            }
        } finally {
            tempPath.delete()
        }
    }

    override fun pauseApp(type: String, wh: When, packageName: String) {
        // system apps will not be paused
    }
}