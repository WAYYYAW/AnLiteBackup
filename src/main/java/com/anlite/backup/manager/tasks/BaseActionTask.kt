/*
 * OAndBackupX: open-source apps backup and restore app.
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

import android.content.Context
import android.content.DialogInterface
import androidx.lifecycle.lifecycleScope
import com.anlite.backup.AnLiteApp
import com.anlite.backup.R
import com.anlite.backup.data.entity.ActionResult
import com.anlite.backup.data.entity.Package
import com.anlite.backup.manager.handler.BackupRestoreHelper.ActionType
import com.anlite.backup.manager.handler.LogsHandler
import com.anlite.backup.manager.handler.LogsHandler.Companion.logErrors
import com.anlite.backup.manager.handler.ShellHandler
import com.anlite.backup.manager.handler.showNotification
import com.anlite.backup.ui.activities.AnLiteActivity
import com.anlite.backup.utils.showActionResult
import java.lang.ref.WeakReference

abstract class BaseActionTask(
    val app: Package, oAndBackupX: AnLiteActivity, val shellHandler: ShellHandler,
    val mode: Int, private val actionType: ActionType, val setInfoBar: (String) -> Unit,
) : CoroutinesAsyncTask<Void?, Void?, ActionResult>(oAndBackupX.lifecycleScope) {
    val neoActivityReference: WeakReference<AnLiteActivity> = WeakReference(oAndBackupX)
    protected var result: ActionResult? = null
    protected var notificationId = -1

    protected inline fun <T> withActivity(block: (AnLiteActivity) -> T): T? {
        return neoActivityReference.get()?.takeIf { !it.isFinishing }?.let(block)
    }

    override fun onProgressUpdate(vararg values: Void?) {
        withActivity { activity ->
            val progressMessage = getProgressMessage(activity, actionType)
            val fullMessage = "${app.packageLabel}: $progressMessage"

            activity.showSnackBar(fullMessage)
            setInfoBar(fullMessage)

            showNotification(
                activity, AnLiteActivity::class.java,
                notificationId, app.packageLabel, progressMessage, true
            )
        }
    }

    override fun onPostExecute(result: ActionResult?) {
        withActivity { activity ->
            val message = getPostExecuteMessage(activity, actionType, result)
            showNotification(
                activity, AnLiteActivity::class.java,
                notificationId, app.packageLabel, message, true
            )
            activity.showActionResult(this.result!!) { _: DialogInterface?, _: Int ->
                logErrors(
                    LogsHandler.handleErrorMessages(activity, result?.message)
                        ?: ""
                )
            }
            if (result?.succeeded != true)
                AnLiteApp.lastErrorPackage = app.packageName
            activity.updatePackage(app.packageName)
            activity.dismissSnackBar()
            setInfoBar("")
        }
    }

    private fun getProgressMessage(context: Context, actionType: ActionType): String =
        context.getString(if (actionType == ActionType.BACKUP) R.string.backupProgress else R.string.restoreProgress)

    private fun getPostExecuteMessage(
        context: Context,
        actionType: ActionType,
        result: ActionResult?,
    ): String? {
        return result?.let {
            if (it.succeeded) {
                if (actionType == ActionType.BACKUP) {
                    context.getString(R.string.backupSuccess)
                } else {
                    context.getString(R.string.restoreSuccess)
                }
            } else {
                if (actionType == ActionType.BACKUP) {
                    context.getString(R.string.backupFailure)
                } else {
                    context.getString(R.string.restoreFailure)
                }
            }
        }
    }
}