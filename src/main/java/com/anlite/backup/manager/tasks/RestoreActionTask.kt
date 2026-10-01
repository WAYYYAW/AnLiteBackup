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

import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.entity.ActionResult
import com.anlite.backup.data.entity.Package
import com.anlite.backup.manager.handler.BackupRestoreHelper
import com.anlite.backup.manager.handler.ShellHandler
import com.anlite.backup.ui.activities.AnLiteActivity
import com.anlite.backup.utils.SystemUtils

class RestoreActionTask(
    appInfo: Package, oAndBackupX: AnLiteActivity, shellHandler: ShellHandler, restoreMode: Int,
    private val backup: Backup, setInfoBar: (String) -> Unit,
) : BaseActionTask(
    appInfo, oAndBackupX, shellHandler, restoreMode,
    BackupRestoreHelper.ActionType.RESTORE, setInfoBar
) {
    override fun onPreExecute() {
        super.onPreExecute()
        notificationId = SystemUtils.now.toInt()
    }

    override suspend fun doInBackground(vararg params: Void?): ActionResult? {
        val mainActivityX = neoActivityReference.get()?.takeIf { !it.isFinishing }
            ?: return ActionResult(app, null, "", false)

        publishProgress()
        result = BackupRestoreHelper.restore(
            mainActivityX, null, shellHandler,
            app, mode, backup
        )
        return result
    }
}