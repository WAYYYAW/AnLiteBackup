package com.anlite.backup.ui.activities

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import com.anlite.backup.AnLiteApp
import com.anlite.backup.data.preferences.pref_appTheme
import com.anlite.backup.ui.compose.theme.AppTheme
import com.anlite.backup.ui.screens.MainScreen
import com.anlite.backup.ui.viewmodel.AppsViewModel
import com.anlite.backup.ui.viewmodel.DirectoriesViewModel
import com.anlite.backup.utils.isDarkTheme
import org.koin.android.ext.android.inject

class AnLiteActivity : BaseActivity() {

    private val appsViewModel: AppsViewModel by inject()
    private val directoriesViewModel: DirectoriesViewModel by inject()

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        AnLiteApp.main = this
        super.onCreate(savedInstanceState)

        setContent {
            val appTheme by pref_appTheme.state

            DisposableEffect(appTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { isDarkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { isDarkTheme },
                )
                onDispose {}
            }

            AppTheme {
                MainScreen(
                    viewModel = appsViewModel,
                    directoriesViewModel = directoriesViewModel,
                )
            }
        }
    }

    override fun onResume() {
        AnLiteApp.main = this
        super.onResume()
    }

    override fun onDestroy() {
        AnLiteApp.mainSaved = AnLiteApp.mainRef
        AnLiteApp.main = null
        super.onDestroy()
    }
}