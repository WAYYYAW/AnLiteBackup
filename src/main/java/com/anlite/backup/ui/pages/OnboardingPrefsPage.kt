package com.anlite.backup.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anlite.backup.R
import com.anlite.backup.data.entity.ColoringState
import com.anlite.backup.data.entity.EnumPref
import com.anlite.backup.data.entity.ListPref
import com.anlite.backup.data.entity.NeoPrefAdapter
import com.anlite.backup.data.entity.PasswordPref
import com.anlite.backup.data.entity.Pref
import com.anlite.backup.data.entity.StringPref
import com.anlite.backup.data.preferences.PrefEnum
import com.anlite.backup.data.preferences.PrefList
import com.anlite.backup.ui.compose.blockBorderBottom
import com.anlite.backup.ui.compose.component.ActionButton
import com.anlite.backup.ui.compose.component.PrefsGroup
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.ArrowRight
import com.anlite.backup.ui.dialogs.BaseDialog
import com.anlite.backup.ui.dialogs.DSEnumPrefDialogUI
import com.anlite.backup.ui.dialogs.DSListPrefDialogUI
import com.anlite.backup.ui.dialogs.EnumPrefDialogUI
import com.anlite.backup.ui.dialogs.ListPrefDialogUI
import com.anlite.backup.ui.dialogs.StringPrefDialogUI
import kotlinx.collections.immutable.persistentListOf

@Composable
fun OnboardingPrefsPage(
    onNext: () -> Unit,
) {
    val openDialog = remember { mutableStateOf(false) }
    var dialogsPref by remember { mutableStateOf<Pref?>(null) }

    Column {
        LazyColumn(
            modifier = Modifier
                .blockBorderBottom()
                .weight(1f),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OboardingPrefGroups { pref ->
                dialogsPref = pref
                openDialog.value = true
            }
        }
        ActionButton(
            text = stringResource(id = R.string.dialog_start),
            icon = Phosphor.ArrowRight,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            coloring = ColoringState.Positive,
            onClick = onNext,
        )
    }

    if (openDialog.value) {
        val currentPref = dialogsPref
        BaseDialog(onDismiss = { openDialog.value = false }) {
            when (currentPref) {
                is NeoPrefAdapter -> {
                    when (currentPref.dsPref) {
                        is PrefList -> DSListPrefDialogUI(
                            pref = currentPref.dsPref,
                            openDialogCustom = openDialog,
                        )

                        is PrefEnum -> DSEnumPrefDialogUI(
                            pref = currentPref.dsPref,
                            openDialogCustom = openDialog,
                        )

                        else        -> {}
                    }
                }

                is ListPref       -> ListPrefDialogUI(
                    pref = currentPref,
                    openDialogCustom = openDialog,
                )

                is EnumPref       -> EnumPrefDialogUI(
                    pref = currentPref,
                    openDialogCustom = openDialog
                )

                is PasswordPref   -> StringPrefDialogUI(
                    pref = currentPref,
                    isPrivate = true,
                    confirm = true,
                    openDialogCustom = openDialog
                )

                is StringPref     -> StringPrefDialogUI(
                    pref = currentPref,
                    openDialogCustom = openDialog
                )
            }
        }
    }
}

private fun LazyListScope.OboardingPrefGroups(onPrefDialog: (Pref) -> Unit) {
    val userPrefs = persistentListOf(
        NeoPrefAdapter(pref_languages),
        NeoPrefAdapter(pref_appTheme),
        NeoPrefAdapter(pref_pathBackupFolder),
        NeoPrefAdapter(pref_deviceLock),
        NeoPrefAdapter(pref_biometricLock),
    )
    val servicePrefs = persistentListOf(
        pref_encryption,
        pref_numBackupRevisions,
        pref_restorePermissions,
    )
    val advancedPrefs = persistentListOf(
        pref_enableSpecialBackups,
        pref_giveAllPermissions,
    )

    item {
        PrefsGroup(
            prefs = userPrefs,
            heading = stringResource(id = R.string.prefs_user_short),
            onPrefDialog = onPrefDialog
        )
    }
    item {
        PrefsGroup(
            prefs = servicePrefs,
            heading = stringResource(id = R.string.prefs_service_short),
            onPrefDialog = onPrefDialog
        )
    }
    item {
        PrefsGroup(
            prefs = advancedPrefs,
            heading = stringResource(id = R.string.prefs_advanced_short),
            onPrefDialog = onPrefDialog
        )
    }
}