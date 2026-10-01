package com.anlite.backup.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anlite.backup.core.queue.QueueActionType
import com.anlite.backup.core.queue.QueueProgress
import com.anlite.backup.ui.components.AppDetailSheet
import com.anlite.backup.ui.components.DirectoryDetailSheet
import com.anlite.backup.ui.dialogs.AddDirectoryDialog
import com.anlite.backup.ui.dialogs.BatchRestoreDialog
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.ClockCounterClockwise
import com.anlite.backup.ui.compose.icons.phosphor.FolderNotch
import com.anlite.backup.ui.compose.icons.phosphor.GearSix
import com.anlite.backup.ui.compose.icons.phosphor.HardDrives
import com.anlite.backup.ui.compose.icons.phosphor.List
import com.anlite.backup.ui.compose.icons.phosphor.Play
import com.anlite.backup.ui.compose.icons.phosphor.Warning
import com.anlite.backup.ui.compose.icons.phosphor.X
import com.anlite.backup.ui.viewmodel.AppsViewModel
import com.anlite.backup.ui.viewmodel.DirectoriesViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: AppsViewModel,
    directoriesViewModel: DirectoriesViewModel = koinInject(),
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val apps by viewModel.appsList.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterMode by viewModel.filterMode.collectAsState()
    val selectedPackages by viewModel.selectedPackages.collectAsState()
    val queueProgress by viewModel.queueProgress.collectAsState()
    val engineConfig by viewModel.engineConfig.collectAsState()
    val repoStats by viewModel.repoStats.collectAsState()
    val selectedAppForSheet by viewModel.selectedAppForSheet.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val isRootGranted by viewModel.isRootGranted.collectAsState()

    // 存储库快照页响应式状态
    val unifiedSnapshots by viewModel.unifiedSnapshots.collectAsState()
    val snapshotSearchQuery by viewModel.snapshotSearchQuery.collectAsState()
    val snapshotFilterType by viewModel.snapshotFilterType.collectAsState()

    // 目录相关响应式状态
    val directories by directoriesViewModel.directories.collectAsState()
    val selectedDirectoryForSheet by directoriesViewModel.selectedDirectory.collectAsState()
    val pathValidation by directoriesViewModel.pathValidation.collectAsState()
    val isValidatingPath by directoriesViewModel.isValidatingPath.collectAsState()
    val isDirectoriesSyncing by directoriesViewModel.isSyncing.collectAsState()
    var showAddDirectoryDialog by remember { mutableStateOf(false) }
    var showBatchRestoreDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AnLite Backup",
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    if (!isRootGranted) {
                        IconButton(onClick = { viewModel.checkRoot() }) {
                            Icon(
                                Phosphor.Warning,
                                contentDescription = "Root Warning",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.openSettings() }) {
                        Icon(Phosphor.GearSix, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Phosphor.List, contentDescription = "应用管理") },
                    label = { Text("应用管理") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Phosphor.FolderNotch, contentDescription = "目录备份") },
                    label = { Text("目录备份") },
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        viewModel.loadRepoStats()
                    },
                    icon = { Icon(Phosphor.HardDrives, contentDescription = "存储库快照") },
                    label = { Text("存储库快照") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 实时调度队列进度横幅（当正在执行备份时展示）
                QueueProgressBanner(
                    progress = queueProgress,
                    onDismiss = { /* 可清除状态 */ },
                )

                // Tab 内容切换
                when (selectedTab) {
                    0 -> {
                        AppsPage(
                            apps = apps,
                            searchQuery = searchQuery,
                            filterMode = filterMode,
                            selectedPackages = selectedPackages,
                            onSearchChange = { viewModel.searchQuery.value = it },
                            onFilterChange = { viewModel.filterMode.value = it },
                            onAppClick = { viewModel.openAppDetail(it) },
                            onAppLongClick = { viewModel.togglePackageSelection(it.packageName) },
                            onToggleSelect = { viewModel.togglePackageSelection(it) },
                        )
                    }
                    1 -> {
                        DirectoriesPage(
                            directories = directories,
                            isSyncing = isDirectoriesSyncing,
                            onSync = {
                                directoriesViewModel.syncDirectories { success ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(if (success) "目录快照同步完成" else "目录快照同步失败")
                                    }
                                }
                            },
                            onDirectoryClick = { directoriesViewModel.selectDirectory(it) },
                            onBackupSingle = { directoriesViewModel.backupDirectory(it) },
                            onBackupAll = { directoriesViewModel.backupAllDirectories() },
                            onAddClick = {
                                directoriesViewModel.clearValidation()
                                showAddDirectoryDialog = true
                            },
                        )
                    }
                    2 -> {
                        SnapshotsPage(
                            snapshots = unifiedSnapshots,
                            searchQuery = snapshotSearchQuery,
                            filterType = snapshotFilterType,
                            onSearchChange = { viewModel.snapshotSearchQuery.value = it },
                            onFilterChange = { viewModel.snapshotFilterType.value = it },
                            config = engineConfig,
                            stats = repoStats,
                            onSyncRepository = { viewModel.syncRepository() },
                            onPruneRepository = {
                                viewModel.pruneRepository { success ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(if (success) "碎片整理完成" else "碎片整理失败")
                                    }
                                }
                            },
                            onUnlockRepository = {
                                viewModel.unlockRepository { success, msg ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(if (success) "仓库锁死已成功清除" else "清除锁死失败: $msg")
                                    }
                                }
                            },
                            onCheckRepository = {
                                viewModel.checkRepository { success, msg ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar(if (success) "存储库体检通过，数据完整" else "体检发现问题: $msg")
                                    }
                                }
                            },
                            onRestoreAppSnapshot = { pkgName, snapId, onComplete ->
                                val appLabel = apps.find { it.packageName == pkgName }?.appLabel ?: pkgName
                                viewModel.restoreApp(
                                    packageName = pkgName,
                                    snapshotId = snapId,
                                    packageLabel = appLabel,
                                ) { success, err ->
                                    onComplete()
                                    if (!success && err != null) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("还原失败: $err")
                                        }
                                    }
                                }
                            },
                            onRestoreDirectorySnapshot = { dirSnap, targetPath, onComplete ->
                                viewModel.restoreDirectory(
                                    snapshotId = dirSnap.snapshotId,
                                    originalPath = dirSnap.path,
                                    targetPath = targetPath,
                                ) { success, err ->
                                    onComplete()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (success) "目录 ${dirSnap.displayTitle} 还原成功" else "目录还原失败: $err"
                                        )
                                    }
                                }
                            },
                            onDeleteSnapshot = { snapId, dirId, runPrune, onComplete ->
                                viewModel.deleteSnapshot(
                                    snapshotId = snapId,
                                    directoryId = dirId,
                                    runPrune = runPrune,
                                ) { success, err ->
                                    onComplete(success, err)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (success) "快照已成功删除" else "删除快照失败: $err"
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // 批量操作底部悬浮栏（仅在应用管理 Tab 且有选中项时展示）
            AnimatedVisibility(
                visible = selectedTab == 0 && selectedPackages.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${selectedPackages.size} 项",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.clearSelection() },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Icon(Phosphor.X, contentDescription = "取消", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("取消", maxLines = 1, fontSize = 12.sp)
                            }

                            FilledTonalButton(
                                onClick = { showBatchRestoreDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    Phosphor.ClockCounterClockwise,
                                    contentDescription = "批量还原",
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("批量还原", maxLines = 1, fontSize = 12.sp)
                            }

                            Button(
                                onClick = { viewModel.backupSelectedApps() },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Icon(Phosphor.Play, contentDescription = "立即备份", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("立即备份", maxLines = 1, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // App 详情与快照管理 Sheet
        selectedAppForSheet?.let { app ->
            AppDetailSheet(
                app = app,
                onDismiss = { viewModel.closeAppDetail() },
                onBackupNow = { viewModel.backupSingleApp(it) },
                onRestoreSnapshot = { pkgName, snapId ->
                    viewModel.restoreApp(
                        packageName = pkgName,
                        snapshotId = snapId,
                        packageLabel = app.appLabel,
                    ) { success, err ->
                        if (!success && err != null) {
                            scope.launch {
                                snackbarHostState.showSnackbar("还原失败: $err")
                            }
                        }
                    }
                },
                onDeleteSnapshot = { snapId, runPrune, onComplete ->
                    viewModel.deleteSnapshot(snapshotId = snapId, runPrune = runPrune) { success, err ->
                        onComplete(success, err)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (success) "快照已成功删除" else "删除快照失败: $err"
                            )
                        }
                    }
                },
            )
        }

        // 批量还原对话框
        if (showBatchRestoreDialog) {
            val selectedApps = remember(selectedPackages, apps) {
                apps.filter { selectedPackages.contains(it.packageName) }
            }
            BatchRestoreDialog(
                selectedApps = selectedApps,
                onDismiss = { showBatchRestoreDialog = false },
                onConfirm = { restoreApk, restoreData ->
                    showBatchRestoreDialog = false
                    viewModel.restoreSelectedApps(
                        restoreApk = restoreApk,
                        restoreData = restoreData,
                    )
                },
            )
        }

        // 目录详情与快照管理 Sheet
        selectedDirectoryForSheet?.let { dirState ->
            DirectoryDetailSheet(
                directory = dirState.directory,
                snapshots = dirState.snapshots,
                isLoadingSnapshots = dirState.isLoadingSnapshots,
                onDismiss = { directoriesViewModel.dismissDetail() },
                onBackupNow = { directoriesViewModel.backupDirectory(it) },
                onDeleteDirectory = { directoriesViewModel.deleteDirectory(it) },
                onRestoreSnapshot = { snapId, origPath, targetPath, onComplete ->
                    directoriesViewModel.restoreDirectory(snapId, origPath, targetPath) { success, err ->
                        onComplete(success, err)
                        scope.launch {
                            snackbarHostState.showSnackbar(if (success) "目录还原成功" else "还原失败: $err")
                        }
                    }
                },
                onDeleteSnapshot = { dir, snapId, runPrune, onComplete ->
                    directoriesViewModel.deleteSnapshot(dir, snapId, runPrune) { success, err ->
                        onComplete(success, err)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (success) "快照已成功删除" else "删除快照失败: $err"
                            )
                        }
                    }
                },
            )
        }

        // 添加目录对话框
        if (showAddDirectoryDialog) {
            AddDirectoryDialog(
                presets = directoriesViewModel.presets,
                validationResult = pathValidation,
                isValidating = isValidatingPath,
                onValidatePath = { directoriesViewModel.validatePath(it) },
                onDismiss = {
                    showAddDirectoryDialog = false
                    directoriesViewModel.clearValidation()
                },
                onConfirm = { name, path ->
                    directoriesViewModel.addDirectory(name, path) {
                        showAddDirectoryDialog = false
                        directoriesViewModel.clearValidation()
                        scope.launch {
                            snackbarHostState.showSnackbar("已添加目录: $name")
                        }
                    }
                },
            )
        }

        // 设置对话框
        if (showSettingsDialog) {
            SettingsDialog(
                config = engineConfig,
                isRootGranted = isRootGranted,
                onDismiss = { viewModel.closeSettings() },
                onSave = { repoPath, repoPassword, maxSnapshots, autoPrune, restoreSelinux, showSystemApps ->
                    viewModel.updateConfig(
                        repoPath = repoPath,
                        repoPassword = repoPassword,
                        maxSnapshots = maxSnapshots,
                        autoPrune = autoPrune,
                        restoreSelinux = restoreSelinux,
                        showSystemApps = showSystemApps,
                    )
                    viewModel.closeSettings()
                },
            )
        }
    }
}

@Composable
fun QueueProgressBanner(
    progress: QueueProgress,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = progress.isRunning,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
    ) {
        val isRestore = progress.actionType == QueueActionType.RESTORE
        val actionTitle = if (isRestore) "正在还原" else "正在备份"
        val containerColor = if (isRestore) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
        val onContainerColor = if (isRestore) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(12.dp),
            color = containerColor,
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = progress.currentTask?.displayLabel ?: "准备中..."
                    Text(
                        text = "$actionTitle [${progress.currentIndex}/${progress.totalCount}]: $label",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = onContainerColor,
                    )
                    Text(
                        text = "${(progress.percent * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = onContainerColor,
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { progress.percent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )

                if (progress.message.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = progress.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = onContainerColor.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
