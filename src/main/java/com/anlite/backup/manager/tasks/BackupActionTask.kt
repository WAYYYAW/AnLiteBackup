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
package com.anlite.backup.manager.tasks

import com.anlite.backup.AnLiteApp
import com.anlite.backup.data.entity.ActionResult
import com.anlite.backup.data.entity.Package
import com.anlite.backup.manager.handler.BackupRestoreHelper
import com.anlite.backup.manager.handler.ShellHandler
import com.anlite.backup.ui.activities.AnLiteActivity
import com.anlite.backup.utils.SystemUtils
import kotlin.system.measureTimeMillis

class BackupActionTask(
    appInfo: Package, oAndBackupX: AnLiteActivity, shellHandler: ShellHandler, backupMode: Int,
    setInfoBar: (String) -> Unit,
) : BaseActionTask(
    appInfo, oAndBackupX, shellHandler, backupMode,
    BackupRestoreHelper.ActionType.BACKUP, setInfoBar,
) {
    override fun onPreExecute() {
        super.onPreExecute()
        notificationId = SystemUtils.now.toInt()
    }

    override suspend fun doInBackground(vararg params: Void?): ActionResult? {
        val mainActivityX = neoActivityReference.get()?.takeIf { !it.isFinishing }
            ?: return ActionResult(app, null, "", false)

        val time = measureTimeMillis {
            publishProgress()
            result = BackupRestoreHelper.backup(mainActivityX, null, shellHandler, app, mode)
        }
        AnLiteApp.addInfoLogText(
            "backup: ${app.packageName}: ${(time / 1000 + 0.5).toInt()} sec"
        )

        return result
    }
}