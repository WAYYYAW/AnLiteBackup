package com.anlite.backup.data.entity

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.anlite.backup.EnabledFilter
import com.anlite.backup.InstalledFilter
import com.anlite.backup.LatestFilter
import com.anlite.backup.LaunchableFilter
import com.anlite.backup.MAIN_FILTER_SPECIAL
import com.anlite.backup.MAIN_FILTER_SYSTEM
import com.anlite.backup.MAIN_FILTER_USER
import com.anlite.backup.MODE_APK
import com.anlite.backup.MODE_DATA
import com.anlite.backup.MODE_DATA_DE
import com.anlite.backup.MODE_DATA_EXT
import com.anlite.backup.MODE_DATA_MEDIA
import com.anlite.backup.MODE_DATA_OBB
import com.anlite.backup.MODE_NONE
import com.anlite.backup.R
import com.anlite.backup.Sort
import com.anlite.backup.UpdatedFilter
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.ArrowSquareOut
import com.anlite.backup.ui.compose.icons.phosphor.AsteriskSimple
import com.anlite.backup.ui.compose.icons.phosphor.Checks
import com.anlite.backup.ui.compose.icons.phosphor.CircleWavyWarning
import com.anlite.backup.ui.compose.icons.phosphor.Clock
import com.anlite.backup.ui.compose.icons.phosphor.DiamondsFour
import com.anlite.backup.ui.compose.icons.phosphor.FloppyDisk
import com.anlite.backup.ui.compose.icons.phosphor.FolderNotch
import com.anlite.backup.ui.compose.icons.phosphor.GameController
import com.anlite.backup.ui.compose.icons.phosphor.HardDrives
import com.anlite.backup.ui.compose.icons.phosphor.Leaf
import com.anlite.backup.ui.compose.icons.phosphor.Placeholder
import com.anlite.backup.ui.compose.icons.phosphor.PlayCircle
import com.anlite.backup.ui.compose.icons.phosphor.ProhibitInset
import com.anlite.backup.ui.compose.icons.phosphor.ShieldCheckered
import com.anlite.backup.ui.compose.icons.phosphor.Spinner
import com.anlite.backup.ui.compose.icons.phosphor.Star
import com.anlite.backup.ui.compose.icons.phosphor.TagSimple
import com.anlite.backup.ui.compose.icons.phosphor.TrashSimple
import com.anlite.backup.ui.compose.icons.phosphor.User

data class ChipItem(
    val flag: Int,
    val textId: Int,
    val icon: ImageVector,
) {

    companion object {
        val None = ChipItem(
            MODE_NONE,
            R.string.showNotBackedup,
            Phosphor.Placeholder,
        )
        val Apk = ChipItem(
            MODE_APK,
            R.string.radio_apk,
            Phosphor.DiamondsFour
        )
        val Data = ChipItem(
            MODE_DATA,
            R.string.radio_data,
            Phosphor.HardDrives
        )
        val DeData = ChipItem(
            MODE_DATA_DE,
            R.string.radio_deviceprotecteddata,
            Phosphor.ShieldCheckered
        )
        val ExtData = ChipItem(
            MODE_DATA_EXT,
            R.string.radio_externaldata,
            Phosphor.FloppyDisk
        )
        val MediaData = ChipItem(
            MODE_DATA_MEDIA,
            R.string.radio_mediadata,
            Phosphor.PlayCircle
        )
        val ObbData = ChipItem(
            MODE_DATA_OBB,
            R.string.radio_obbdata,
            Phosphor.GameController
        )
        val System = ChipItem(
            MAIN_FILTER_SYSTEM,
            R.string.radio_system,
            Phosphor.Spinner
        )
        val User = ChipItem(
            MAIN_FILTER_USER,
            R.string.radio_user,
            Phosphor.User
        )
        val Special = ChipItem(
            MAIN_FILTER_SPECIAL,
            R.string.radio_special,
            Phosphor.AsteriskSimple
        )
        val All = ChipItem(
            0,
            R.string.radio_all,
            Phosphor.Checks
        )
        val Launchable = ChipItem(
            LaunchableFilter.LAUNCHABLE.ordinal,
            R.string.radio_launchable,
            Phosphor.ArrowSquareOut
        )
        val NotLaunchable = ChipItem(
            LaunchableFilter.NOT.ordinal,
            R.string.radio_notlaunchable,
            Phosphor.ProhibitInset
        )
        val UpdatedApps = ChipItem(
            UpdatedFilter.UPDATED.ordinal,
            R.string.show_updated_apps,
            Phosphor.CircleWavyWarning
        )
        val NewApps = ChipItem(
            UpdatedFilter.NEW.ordinal,
            R.string.show_new_apps,
            Phosphor.Star
        )
        val OldApps = ChipItem(
            UpdatedFilter.NOT.ordinal,
            R.string.show_old_apps,
            Phosphor.Clock
        )
        val OldBackups = ChipItem(
            LatestFilter.OLD.ordinal,
            R.string.showOldBackups,
            Phosphor.Clock
        )
        val NewBackups = ChipItem(
            LatestFilter.NEW.ordinal,
            R.string.show_new_backups,
            Phosphor.CircleWavyWarning
        )
        val Enabled = ChipItem(
            EnabledFilter.ENABLED.ordinal,
            R.string.show_enabled_apps,
            Phosphor.Leaf
        )
        val Disabled = ChipItem(
            EnabledFilter.DISABLED.ordinal,
            R.string.showDisabled,
            Phosphor.ProhibitInset
        )
        val Installed = ChipItem(
            InstalledFilter.INSTALLED.ordinal,
            R.string.show_installed_apps,
            Phosphor.DiamondsFour,
        )
        val NotInstalled = ChipItem(
            InstalledFilter.NOT.ordinal,
            R.string.showNotInstalled,
            Phosphor.TrashSimple,
        )
        val Label = ChipItem(
            Sort.LABEL.ordinal,
            R.string.sortByLabel,
            Phosphor.TagSimple
        )
        val PackageName = ChipItem(
            Sort.PACKAGENAME.ordinal,
            R.string.sortPackageName,
            Phosphor.Placeholder
        )
        val AppSize = ChipItem(
            Sort.APP_SIZE.ordinal,
            R.string.sortAppSize,
            Phosphor.DiamondsFour
        )
        val DataSize = ChipItem(
            Sort.DATA_SIZE.ordinal,
            R.string.sortDataSize,
            Phosphor.HardDrives
        )
        val AppDataSize = ChipItem(
            Sort.APPDATA_SIZE.ordinal,
            R.string.sort_appdata_size,
            Phosphor.FloppyDisk
        )
        val BackupSize = ChipItem(
            Sort.BACKUP_SIZE.ordinal,
            R.string.sortBackupSize,
            Phosphor.FolderNotch
        )
        val BackupDate = ChipItem(
            Sort.BACKUP_DATE.ordinal,
            R.string.sortBackupDate,
            Phosphor.Clock
        )
    }
}

data class InfoChipItem(
    val flag: Int,
    val text: String,
    val icon: ImageVector? = null,
    val color: Color? = null,
)
