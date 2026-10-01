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
package com.anlite.backup.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.anlite.backup.MODE_APK
import com.anlite.backup.MODE_DATA
import com.anlite.backup.MODE_DATA_DE
import com.anlite.backup.MODE_DATA_EXT
import com.anlite.backup.MODE_DATA_MEDIA
import com.anlite.backup.MODE_DATA_OBB
import com.anlite.backup.MODE_UNSET
import com.anlite.backup.R
import com.anlite.backup.batchModesSequence
import com.anlite.backup.data.dbs.entity.AppInfo
import com.anlite.backup.data.entity.Package
import com.anlite.backup.utils.backupModeIfActive
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

@Composable
fun BackupDialogUI(
    appPackage: Package,
    openDialogCustom: MutableState<Boolean>,
    onAction: (mode: Int) -> Unit,
) {
    val context = LocalContext.current

    val modePairs = mutableMapOf<Int, String>()
    val possibleModes = batchModesSequence.toMutableList()

    val selectedMode = mutableSetOf<Int>()

    val pi = appPackage.packageInfo
    val showApkBtn = pi is AppInfo && pi.apkDir?.isNotEmpty() == true
    if (showApkBtn) {
        modePairs[MODE_APK] = stringResource(R.string.radio_apk)
    } else {
        possibleModes.remove(MODE_APK)
    }
    modePairs[MODE_DATA] = stringResource(R.string.radio_data)
    if (appPackage.isSpecial) {
        possibleModes.remove(MODE_DATA_DE)
        possibleModes.remove(MODE_DATA_EXT)
        possibleModes.remove(MODE_DATA_OBB)
        possibleModes.remove(MODE_DATA_MEDIA)
    } else {
        modePairs[MODE_DATA_DE] = stringResource(R.string.radio_deviceprotecteddata)
        modePairs[MODE_DATA_EXT] = stringResource(R.string.radio_externaldata)
        modePairs[MODE_DATA_OBB] = stringResource(R.string.radio_obbdata)
        modePairs[MODE_DATA_MEDIA] = stringResource(R.string.radio_mediadata)
    }

    possibleModes.forEach { mode -> // TODO reusing mode of last backup?
        val activeMode = backupModeIfActive(mode)
        selectedMode.add(
            if (appPackage.latestBackup?.hasMode(mode) != false)
                activeMode
            else MODE_UNSET
        )
    }

    MultiSelectionDialogUI(
        titleText = appPackage.packageLabel,
        entryMap = modePairs.toPersistentMap(),
        selectedItems = selectedMode.toPersistentList(),
        openDialogCustom = openDialogCustom,
    ) {
        onAction(it.fold(MODE_UNSET) { acc, s -> acc xor s }) // TODO Add action type?
    }
}