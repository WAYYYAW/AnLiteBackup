package com.anlite.backup.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anlite.backup.COMPRESSION_TYPES
import com.anlite.backup.ENCRYPTION
import com.anlite.backup.AnLiteApp
import com.anlite.backup.R
import com.anlite.backup.data.entity.BooleanPref
import com.anlite.backup.data.entity.EnumPref
import com.anlite.backup.data.entity.IntPref
import com.anlite.backup.data.entity.KeyPref
import com.anlite.backup.data.entity.LaunchPref
import com.anlite.backup.data.entity.ListPref
import com.anlite.backup.data.entity.PasswordPref
import com.anlite.backup.data.entity.Pref
import com.anlite.backup.data.entity.StringPref
import com.anlite.backup.encryptionModes
import com.anlite.backup.manager.handler.PGPHandler
import com.anlite.backup.ui.compose.component.PrefsGroup
import com.anlite.backup.ui.compose.component.StringPreference
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.FileZip
import com.anlite.backup.ui.compose.icons.phosphor.FloppyDisk
import com.anlite.backup.ui.compose.icons.phosphor.GameController
import com.anlite.backup.ui.compose.icons.phosphor.Hash
import com.anlite.backup.ui.compose.icons.phosphor.Key
import com.anlite.backup.ui.compose.icons.phosphor.Password
import com.anlite.backup.ui.compose.icons.phosphor.PlayCircle
import com.anlite.backup.ui.compose.icons.phosphor.Prohibit
import com.anlite.backup.ui.compose.icons.phosphor.ProhibitInset
import com.anlite.backup.ui.compose.icons.phosphor.ShieldCheckered
import com.anlite.backup.ui.compose.icons.phosphor.ShieldStar
import com.anlite.backup.ui.compose.icons.phosphor.TagSimple
import com.anlite.backup.ui.compose.icons.phosphor.Textbox
import com.anlite.backup.ui.dialogs.BaseDialog
import com.anlite.backup.ui.dialogs.EnumPrefDialogUI
import com.anlite.backup.ui.dialogs.ListPrefDialogUI
import com.anlite.backup.ui.dialogs.StringPrefDialogUI
import com.anlite.backup.ui.navigation.NavRoute
import com.anlite.backup.utils.SystemUtils
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

@Composable
fun ServicePrefsPage() {
    val openDialog = remember { mutableStateOf(false) }
    var dialogsPref by remember { mutableStateOf<Pref?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ServicePrefGroups { pref ->
            dialogsPref = pref
            openDialog.value = true
        }
    }

    if (openDialog.value) {
        BaseDialog(onDismiss = { openDialog.value = false }) {
            when (dialogsPref) {
                is ListPref -> ListPrefDialogUI(
                    pref = dialogsPref as ListPref,
                    openDialogCustom = openDialog,
                )

                is EnumPref -> EnumPrefDialogUI(
                    pref = dialogsPref as EnumPref,
                    openDialogCustom = openDialog
                )

                is PasswordPref -> StringPrefDialogUI(
                    pref = dialogsPref as PasswordPref,
                    isPrivate = true,
                    confirm = true,
                    openDialogCustom = openDialog
                )

                is StringPref -> StringPrefDialogUI(
                    pref = dialogsPref as StringPref,
                    openDialogCustom = openDialog
                )
            }
        }
    }
}

fun LazyListScope.ServicePrefGroups(onPrefDialog: (Pref) -> Unit) {
    val generalServicePrefs = Pref.prefGroups["srv"]?.toPersistentList() ?: persistentListOf()
    val backupServicePrefs = Pref.prefGroups["srv-bkp"]?.toPersistentList() ?: persistentListOf()
    val restoreServicePrefs = Pref.prefGroups["srv-rst"]?.toPersistentList() ?: persistentListOf()

    item {
        PrefsGroup(
            prefs = generalServicePrefs,
            onPrefDialog = onPrefDialog
        )
    }
    item {
        PrefsGroup(
            prefs = backupServicePrefs,
            heading = stringResource(id = R.string.backup),
            onPrefDialog = onPrefDialog
        )
    }
    item {
        PrefsGroup(
            prefs = restoreServicePrefs,
            heading = stringResource(id = R.string.restore),
            onPrefDialog = onPrefDialog
        )
    }
}

val pref_encryption = LaunchPref(
    key = "srv.enc",
    titleId = R.string.prefs_encryption,
    summaryId = R.string.prefs_encryption_restic_summary,
    icon = Phosphor.Key,
    onClick = { AnLiteApp.main?.moveTo(NavRoute.Encryption) }
)

val pref_encryption_mode = EnumPref(
    key = "encryption.mode",
    titleId = R.string.prefs_encryption,
    icon = Phosphor.Key,
    entries = encryptionModes,
    defaultValue = ENCRYPTION.NONE.ordinal,
)

val pref_password = PasswordPref(
    key = "encryption.password",
    titleId = R.string.prefs_password,
    summaryId = R.string.prefs_password_restic_summary,
    icon = Phosphor.Password,
    iconTint = {
        val pref = it as PasswordPref
        if (pref.value.isNotEmpty()) Color.Green else Color.Gray
    },
    enableIf = { pref_encryption_mode.value == ENCRYPTION.PASSWORD.ordinal },
    defaultValue = "",
)

val kill_password = PasswordPref(   // make sure password is never saved in non-encrypted prefs
    key = "kill.password",
    private = false,
    defaultValue = ""
)
val kill_password_set = run { kill_password.value = "" }

val pref_pgpKey = KeyPref(
    key = "encryption.pgpKey",
    titleId = R.string.prefs_pgp_key,
    summaryId = R.string.prefs_pgp_key_summary,
    icon = Phosphor.Key,
    enableIf = { pref_encryption_mode.value == ENCRYPTION.PGP.ordinal },
    UI = { it, _, index, groupSize ->
        val scope = rememberCoroutineScope()
        val pgpManager: PGPHandler = get(PGPHandler::class.java)

        val pref = it as KeyPref
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    scope.launch {
                        pgpManager.loadKeyFromUri(it).fold(
                            onSuccess = {
                                pgpManager.isKeyLoaded()
                            },
                            onFailure = { _ -> }
                        )
                    }
                }
            }

        StringPreference(
            pref = pref,
            index = index,
            groupSize = groupSize,
            onClick = {
                launcher.launch(arrayOf("*/*"))
            },
        )
    },
    defaultValue = "",
)

val pref_pgpPasscode = PasswordPref(
    key = "encryption.pgpPasscode",
    titleId = R.string.prefs_pgp_passcode,
    summaryId = R.string.prefs_pgp_passcode_summary,
    icon = Phosphor.Password,
    enableIf = { pref_encryption_mode.value == ENCRYPTION.PGP.ordinal },
    defaultValue = "",
)


val pref_backupDeviceProtectedData = BooleanPref(
    key = "srv-bkp.backupDeviceProtectedData",
    titleId = R.string.prefs_deviceprotecteddata,
    summaryId = R.string.prefs_deviceprotecteddata_summary,
    icon = Phosphor.ShieldCheckered,
    defaultValue = true
)

val pref_backupExternalData = BooleanPref(
    key = "srv-bkp.backupExternalData",
    titleId = R.string.prefs_externaldata,
    summaryId = R.string.prefs_externaldata_summary,
    icon = Phosphor.FloppyDisk,
    defaultValue = true
)

val pref_backupObbData = BooleanPref(
    key = "srv-bkp.backupObbData",
    titleId = R.string.prefs_obbdata,
    summaryId = R.string.prefs_obbdata_summary,
    icon = Phosphor.GameController,
    defaultValue = true
)

val pref_backupMediaData = BooleanPref(
    key = "srv-bkp.backupMediaData",
    titleId = R.string.prefs_mediadata,
    summaryId = R.string.prefs_mediadata_summary,
    icon = Phosphor.PlayCircle,
    defaultValue = true
)

val pref_backupNoBackupData = BooleanPref(
    key = "srv-bkp.backupNoBackupData",
    titleId = R.string.prefs_nobackupdata,
    summaryId = R.string.prefs_nobackupdata_summary,
    icon = Phosphor.ProhibitInset,
    defaultValue = false,
    onChanged = { AnLiteApp.assets.updateExcludeFiles() },
)

val pref_backupCache = BooleanPref(
    key = "srv-bkp.backupCache",
    titleId = R.string.prefs_backupcache,
    summaryId = R.string.prefs_backupcache_summary,
    icon = Phosphor.Prohibit,
    defaultValue = false
)

val pref_restoreDeviceProtectedData = BooleanPref(
    key = "srv-rst.restoreDeviceProtectedData",
    titleId = R.string.prefs_deviceprotecteddata_rst,
    summaryId = R.string.prefs_deviceprotecteddata_rst_summary,
    icon = Phosphor.ShieldCheckered,
    defaultValue = true
)

val pref_restoreExternalData = BooleanPref(
    key = "srv-rst.restoreExternalData",
    titleId = R.string.prefs_externaldata_rst,
    summaryId = R.string.prefs_externaldata_rst_summary,
    icon = Phosphor.FloppyDisk,
    defaultValue = true
)

val pref_restoreObbData = BooleanPref(
    key = "srv-rst.restoreObbData",
    titleId = R.string.prefs_obbdata_rst,
    summaryId = R.string.prefs_obbdata_rst_summary,
    icon = Phosphor.GameController,
    defaultValue = true
)

val pref_restoreMediaData = BooleanPref(
    key = "srv-rst.restoreMediaData",
    titleId = R.string.prefs_mediadata_rst,
    summaryId = R.string.prefs_mediadata_rst_summary,
    icon = Phosphor.PlayCircle,
    defaultValue = true
)

val pref_restoreNoBackupData = BooleanPref(
    key = "srv-rst.restoreNoBackupData",
    titleId = R.string.prefs_nobackupdata_rst,
    summaryId = R.string.prefs_nobackupdata_rst_summary,
    icon = Phosphor.ProhibitInset,
    defaultValue = false,
    onChanged = { AnLiteApp.assets.updateExcludeFiles() },
)

val pref_restoreCache = BooleanPref(
    key = "srv-rst.restoreCache",
    titleId = R.string.prefs_restorecache,
    summaryId = R.string.prefs_restorecache_summary,
    icon = Phosphor.Prohibit,
    defaultValue = false
)

val pref_restorePermissions = BooleanPref(
    key = "srv.restorePermissions",
    titleId = R.string.prefs_restorepermissions,
    summaryId = R.string.prefs_restorepermissions_summary,
    icon = Phosphor.ShieldStar,
    defaultValue = true
)

val pref_numBackupRevisions = IntPref(
    key = "srv.numBackupRevisions",
    titleId = R.string.prefs_numBackupRevisions,
    summaryId = R.string.prefs_numBackupRevisions_summary,
    icon = Phosphor.Hash,
    entries = ((0..9) + (10..20 step 2) + (50..200 step 50)).toList(),
    defaultValue = 2
)

val pref_enableStorageCheck = BooleanPref(
    key = "srv.enableStorageCheck",
    titleId = R.string.prefs_enablestoragecheck,
    summaryId = R.string.prefs_enablestoragecheck_summary,
    icon = Phosphor.FloppyDisk,
    defaultValue = true
)

@Deprecated("Compression is now managed by restic")
val pref_compressionType = ListPref(
    key = "kill.compressionType",
    private = true,
    titleId = R.string.prefs_compression_type,
    summaryId = R.string.prefs_compression_type_summary,
    icon = Phosphor.FileZip,
    entries = COMPRESSION_TYPES,
    defaultValue = "zst"
)

@Deprecated("Compression is now managed by restic")
val pref_compressionLevel = IntPref(
    key = "kill.compressionLevel",
    private = true,
    titleId = R.string.prefs_compression_level,
    summaryId = R.string.prefs_compression_level_summary,
    icon = Phosphor.FileZip,
    entries = (0..9).toList(),
    defaultValue = 2
)

val pref_enableSessionInstaller = BooleanPref(
    key = "srv.enableSessionInstaller",
    titleId = R.string.prefs_sessionIinstaller,
    summaryId = R.string.prefs_sessionIinstaller_summary,
    icon = Phosphor.TagSimple,
    defaultValue = true
)

val pref_installationPackage = StringPref(
    key = "srv.installationPackage",
    titleId = R.string.prefs_installerpackagename,
    icon = Phosphor.Textbox,
    defaultValue = SystemUtils.packageName
)
