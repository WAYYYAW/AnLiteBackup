package com.anlite.backup.ui.compose.component

import androidx.compose.runtime.Composable
import com.anlite.backup.data.entity.Pref
import com.anlite.backup.data.preferences.PrefDelegate

@Composable
fun PrefsBuilder(
    pref: Pref,
    onDialogPref: (Pref) -> Unit,
    index: Int,
    size: Int,
) {
    pref.UI?.let { ui ->
        ui(pref, onDialogPref, index, size)
    }
}

@Composable
fun PrefsBuilder(
    pref: PrefDelegate<out Any>,
    onDialogPref: (PrefDelegate<out Any>) -> Unit,
    index: Int,
    size: Int,
) {
    pref.UI?.let { ui ->
        ui(pref, onDialogPref, index, size)
    }
}
