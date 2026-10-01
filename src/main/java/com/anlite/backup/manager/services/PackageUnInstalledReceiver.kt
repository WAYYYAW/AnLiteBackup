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
package com.anlite.backup.manager.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.anlite.backup.AnLiteApp
import com.anlite.backup.data.dbs.entity.AppInfo
import com.anlite.backup.data.entity.Package
import com.anlite.backup.data.preferences.pref_autoLogUnInstallBroadcast
import com.anlite.backup.data.repository.PackageRepository
import com.anlite.backup.manager.handler.LogsHandler.Companion.logException
import com.anlite.backup.ui.pages.supportLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

// TODO make main way of refresh & handle new installed and backup list
class PackageUnInstalledReceiver : BroadcastReceiver(), KoinComponent {
    private val packageRepository: PackageRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val packageName =
            intent.data?.let { if (it.scheme == "package") it.schemeSpecificPart else null }

        val pendingResult = goAsync()
        val appScope = (context.applicationContext as AnLiteApp).applicationScope
        if (packageName != null) {
            appScope.launch {
                try {
                    Package.invalidateSystemCacheForPackage(packageName)
                    when (intent.action.orEmpty()) {
                        Intent.ACTION_PACKAGE_ADDED,
                        Intent.ACTION_PACKAGE_REPLACED,
                            -> {
                            context.packageManager.getPackageInfo(
                                packageName,
                                PackageManager.GET_PERMISSIONS
                            )?.let { packageInfo ->
                                val appInfo = AppInfo(context, packageInfo)
                                packageRepository.upsertAppInfo(appInfo)
                            }
                        }

                        Intent.ACTION_PACKAGE_REMOVED,
                            -> {
                            val backups = packageRepository.getBackups(packageName)
                            if (backups.isEmpty())
                                packageRepository.deleteAppInfoOf(packageName)
                            else {
                                val appInfo = backups.maxBy { it.backupDate }.toAppInfo()
                                packageRepository.upsertAppInfo(appInfo)
                            }
                        }
                    }

                    if (pref_autoLogUnInstallBroadcast.value) {
                        delay(60_0000)
                        supportLog("PackageUnInstalledReceiver")
                    }
                } catch (e: Throwable) {
                    //TODO how to communicate that error to the main app?
                    // it currently leads to a "missing directories from PM" error,
                    // even when only restoring the apk
                    logException(e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
