package com.anlite.backup.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.anlite.backup.ui.pages.EncryptionPage
import com.anlite.backup.ui.pages.LockPage
import com.anlite.backup.ui.pages.LogsPage
import com.anlite.backup.ui.pages.MainPage
import com.anlite.backup.ui.pages.OnboardingPage
import com.anlite.backup.ui.pages.PrefsPage
import com.anlite.backup.ui.pages.SchedulesExportsPage
import com.anlite.backup.ui.pages.TerminalPage
import com.anlite.backup.ui.sheets.BatchPrefsSheet
import com.anlite.backup.ui.sheets.HelpSheet
import com.anlite.backup.ui.sheets.SortFilterSheet
import com.anlite.backup.viewmodels.BackupBatchVM
import com.anlite.backup.viewmodels.HomeVM
import com.anlite.backup.viewmodels.RestoreBatchVM
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavDisplay(
    backStack: NavBackStack<NavRoute>,
    modifier: Modifier = Modifier,
) {
    val sceneStrategy = rememberAnLiteSceneStrategy<NavRoute>()

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        sceneStrategies = persistentListOf(sceneStrategy),
        entryProvider = entryProvider {
            // TODO add conditional to avoid Welcome/PermissionsPage when not needed
            fadeInEntry<NavRoute.Lock> {
                LockPage()
            }
            fadeInEntry<NavRoute.Onboarding> {
                OnboardingPage {
                    backStack.navigateUnique(NavRoute.Main)
                    backStack.remove(NavRoute.Onboarding)
                }
            }
            slideInEntry<NavRoute.Main> {
                MainPage(
                    navigator = { backStack.navigateUnique(it) }
                )
            }
            slideInEntry<NavRoute.Prefs> { key ->
                PrefsPage(
                    pageIndex = key.page,
                    navigateUp = { backStack.removeLastOrNull() },
                    navigator = { backStack.navigateUnique(it) }
                )
            }
            slideInEntry<NavRoute.Encryption> {
                EncryptionPage { backStack.removeLastOrNull() }
            }
            slideInEntry<NavRoute.Exports> {
                SchedulesExportsPage { backStack.removeLastOrNull() }
            }
            slideInEntry<NavRoute.Logs> {
                LogsPage { backStack.removeLastOrNull() }
            }
            slideInEntry<NavRoute.Terminal> {
                TerminalPage(title = stringResource(id = NavItem.Terminal.title)) { backStack.removeLastOrNull() }
            }
            slideInEntry<NavRoute.Info> {
                HelpSheet { backStack.removeLastOrNull() }
            }
            slideInEntry<NavRoute.SortFilter>(
                metadata = BottomSheetScene.bottomSheet()
            ) { key ->
                SortFilterSheet(
                    when (key.page) {
                        NavItem.Backup.destination -> koinInject<BackupBatchVM>()
                        NavItem.Restore.destination -> koinInject<RestoreBatchVM>()
                        else -> koinInject<HomeVM>() // NavItem.Home.destination
                    }
                ) {
                    backStack.removeLastOrNull()
                }
            }
            slideInEntry<NavRoute.BatchPrefs>(
                metadata = BottomSheetScene.bottomSheet()
            ) { key ->
                BatchPrefsSheet(key.backup)
            }
        }
    )
}

inline fun <reified K : NavRoute> EntryProviderScope<NavRoute>.slideInEntry(
    metadata: Map<String, Any> = emptyMap(),
    noinline content: @Composable (K) -> Unit,
) {
    entry<K>(
        metadata = metadata + NavDisplay.transitionSpec {
            slideInHorizontally(tween(600)) { it } togetherWith
                    slideOutHorizontally(tween(600)) { -it }
        } + NavDisplay.popTransitionSpec {
            slideInHorizontally(tween(600)) { -it } togetherWith
                    slideOutHorizontally(tween(600)) { it }
        } + NavDisplay.predictivePopTransitionSpec {
            slideInHorizontally(tween(600)) { -it } togetherWith
                    slideOutHorizontally(tween(600)) { it }
        }
    ) {
        content(it)
    }
}

inline fun <reified K : NavRoute> EntryProviderScope<NavRoute>.fadeInEntry(
    metadata: Map<String, Any> = emptyMap(),
    noinline content: @Composable (K) -> Unit,
) {
    entry<K>(
        metadata = metadata + NavDisplay.transitionSpec {
            fadeIn(tween(400), 0.3f) togetherWith
                    fadeOut(tween(400), 0.3f)
        } + NavDisplay.popTransitionSpec {
            fadeIn(tween(400), 0.3f) togetherWith
                    fadeOut(tween(400), 0.3f)
        } + NavDisplay.predictivePopTransitionSpec {
            fadeIn(tween(400), 0.3f) togetherWith
                    fadeOut(tween(400), 0.3f)
        }
    ) {
        content(it)
    }
}

fun MutableList<NavRoute>.navigateUnique(key: NavRoute) {
    val lastKey = lastOrNull()
    if (lastKey != null && lastKey == key) return
    removeAll { existing -> existing::class == key::class }
    add(key)
}

fun MutableList<NavRoute>.navigate(key: NavRoute) {
    val lastKey = lastOrNull()
    if (lastKey != null && lastKey == key) return
    remove(key)
    add(key)
}