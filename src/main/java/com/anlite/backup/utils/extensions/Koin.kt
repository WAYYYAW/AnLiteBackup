package com.anlite.backup.utils.extensions

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.component.getScopeId
import timber.log.Timber

open class AnLiteViewModel : ViewModel() {
    init {
        Timber.w("neoviewmodel@koinscope: ${getScopeId()}")
    }
}

@Composable
inline fun <reified T : ViewModel> koinAnLiteViewModel() = koinViewModel<T>(
    viewModelStoreOwner = LocalActivity.current as ComponentActivity
)