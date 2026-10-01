package com.anlite.backup.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.anlite.backup.data.preferences.EngineConfig
import com.anlite.backup.data.repository.SnapshotFilterType
import com.anlite.backup.data.repository.UnifiedSnapshotUiItem
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.AndroidLogo
import com.anlite.backup.ui.compose.icons.phosphor.ArrowsClockwise
import com.anlite.backup.ui.compose.icons.phosphor.CaretDown
import com.anlite.backup.ui.compose.icons.phosphor.CaretUp
import com.anlite.backup.ui.compose.icons.phosphor.Clock
import com.anlite.backup.ui.compose.icons.phosphor.FolderNotch
import com.anlite.backup.ui.compose.icons.phosphor.LockOpen
import com.anlite.backup.ui.compose.icons.phosphor.MagnifyingGlass
import com.anlite.backup.ui.compose.icons.phosphor.ShieldCheckered
import com.anlite.backup.ui.compose.icons.phosphor.TrashSimple
import com.anlite.backup.ui.compose.icons.phosphor.Wrench
import com.anlite.backup.ui.compose.icons.phosphor.X
import com.anlite.backup.ui.dialogs.DeleteSnapshotConfirmDialog
import com.anlite.backup.ui.viewmodel.RepoStatsUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 自然日期聚合分组模型
 */
data class DateGroupUnifiedSnapshots(
    val date: LocalDate,
    val displayTitle: String,
    val items: List<UnifiedSnapshotUiItem>,
    val totalSizeBytes: Long,
)

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes > 0 -> "$bytes B"
        else -> "0 B"
    }
}

private fun formatGroupDateTitle(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天 (${date.format(DateTimeFormatter.ofPattern("M月d日"))})"
        today.minusDays(1) -> "昨天 (${date.format(DateTimeFormatter.ofPattern("M月d日"))})"
        else -> {
            if (date.year == today.year) {
                date.format(DateTimeFormatter.ofPattern("M月d日"))
            } else {
                date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
            }
        }
    }
}

/**
 * 现代 Material 3 统一存储库快照浏览页面。
 * 融合应用快照与自定义目录快照于同一响应式时间轴，并提供检索筛选与全套存储库维护工具箱。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnapshotsPage(
    snapshots: List<UnifiedSnapshotUiItem>,
    searchQuery: String,
    filterType: SnapshotFilterType,
    onSearchChange: (String) -> Unit,
    onFilterChange: (SnapshotFilterType) -> Unit,
    config: EngineConfig,
    stats: RepoStatsUiState,
    onSyncRepository: () -> Unit,
    onPruneRepository: () -> Unit,
    onUnlockRepository: () -> Unit,
    onCheckRepository: () -> Unit,
    onRestoreAppSnapshot: (packageName: String, snapshotId: String, onComplete: () -> Unit) -> Unit,
    onRestoreDirectorySnapshot: (UnifiedSnapshotUiItem.DirectorySnapshot, targetPath: String, onComplete: () -> Unit) -> Unit,
    onDeleteSnapshot: (snapshotId: String, directoryId: Long?, runPrune: Boolean, onComplete: (Boolean, String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待删除与正在删除状态
    var snapshotPendingDelete by remember { mutableStateOf<UnifiedSnapshotUiItem?>(null) }
    var isDeletingSnapshot by remember { mutableStateOf(false) }

    // 待还原的目录快照（弹窗选择目标路径）
    var dirPendingRestore by remember { mutableStateOf<UnifiedSnapshotUiItem.DirectorySnapshot?>(null) }
    var restoringSnapshotId by remember { mutableStateOf<String?>(null) }

    // 体检结果弹窗
    var showCheckResultDialog by remember { mutableStateOf(false) }

    // 按自然日期聚合分组
    val groupedSnapshots = remember(snapshots) {
        snapshots.groupBy { it.backupDate.toLocalDate() }
            .map { (date, items) ->
                DateGroupUnifiedSnapshots(
                    date = date,
                    displayTitle = formatGroupDateTitle(date),
                    items = items,
                    totalSizeBytes = items.sumOf { it.sizeBytes },
                )
            }
    }

    // 日期折叠状态（默认全部展开）
    val collapsedDates = remember { mutableStateMapOf<LocalDate, Boolean>() }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. 存储库概览与工具箱卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Phosphor.FolderNotch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Restic 存储仓库",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = config.repoPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 指标统计区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "${stats.snapshotCount}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            if (stats.snapshotCount > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${stats.appSnapshotCount} 应用 · ${stats.dirSnapshotCount} 目录)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }
                        Text(
                            text = "总快照版本数",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    val totalSizeStr = if (stats.totalSizeBytes > 0) {
                        formatBytes(stats.totalSizeBytes)
                    } else {
                        "计算中..."
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = totalSizeStr,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "仓库物理占用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 2x2 维护工具箱网格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 同步仓库
                    Button(
                        onClick = onSyncRepository,
                        enabled = !stats.isSyncing && !stats.isPruning && !stats.isChecking && !stats.isUnlocking,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        if (stats.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("同步中...", fontSize = 12.sp)
                        } else {
                            Icon(Phosphor.ArrowsClockwise, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("同步", fontSize = 13.sp)
                        }
                    }

                    // 碎片整理
                    OutlinedButton(
                        onClick = onPruneRepository,
                        enabled = !stats.isSyncing && !stats.isPruning && !stats.isChecking && !stats.isUnlocking,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        if (stats.isPruning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("整理中...", fontSize = 12.sp)
                        } else {
                            Icon(Phosphor.Wrench, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("整理", fontSize = 13.sp)
                        }
                    }

                    // 仓库体检
                    OutlinedButton(
                        onClick = {
                            onCheckRepository()
                        },
                        enabled = !stats.isSyncing && !stats.isPruning && !stats.isChecking && !stats.isUnlocking,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        if (stats.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("体检中...", fontSize = 12.sp)
                        } else {
                            Icon(Phosphor.ShieldCheckered, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("体检", fontSize = 13.sp)
                        }
                    }

                    // 解除锁死
                    OutlinedButton(
                        onClick = onUnlockRepository,
                        enabled = !stats.isSyncing && !stats.isPruning && !stats.isChecking && !stats.isUnlocking,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        if (stats.isUnlocking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("解锁中...", fontSize = 12.sp)
                        } else {
                            Icon(Phosphor.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("解锁", fontSize = 13.sp)
                        }
                    }
                }

                // 状态消息横条
                if (!stats.lastSyncMessage.isNullOrEmpty() || !stats.lastCheckResult.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val msg = stats.lastCheckResult ?: stats.lastSyncMessage ?: ""
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!stats.lastCheckResult.isNullOrEmpty()) {
                                    showCheckResultDialog = true
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!stats.lastCheckResult.isNullOrEmpty()) {
                            Text(
                                text = "查看详情",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // 2. 搜索框与筛选 Chip 栏
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("搜索快照、应用、目录或短 ID...", fontSize = 14.sp) },
                leadingIcon = { Icon(Phosphor.MagnifyingGlass, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Phosphor.X, contentDescription = "清空", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 筛选 Chip 滚动条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterType == SnapshotFilterType.ALL,
                    onClick = { onFilterChange(SnapshotFilterType.ALL) },
                    label = { Text("全部 (${stats.snapshotCount})", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                    shape = RoundedCornerShape(8.dp),
                )
                FilterChip(
                    selected = filterType == SnapshotFilterType.APPS_ONLY,
                    onClick = { onFilterChange(SnapshotFilterType.APPS_ONLY) },
                    label = { Text("📦 应用 (${stats.appSnapshotCount})", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                    shape = RoundedCornerShape(8.dp),
                )
                FilterChip(
                    selected = filterType == SnapshotFilterType.DIRECTORIES_ONLY,
                    onClick = { onFilterChange(SnapshotFilterType.DIRECTORIES_ONLY) },
                    label = { Text("📁 目录 (${stats.dirSnapshotCount})", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                    shape = RoundedCornerShape(8.dp),
                )
                FilterChip(
                    selected = filterType == SnapshotFilterType.UNINSTALLED_ONLY,
                    onClick = { onFilterChange(SnapshotFilterType.UNINSTALLED_ONLY) },
                    label = { Text("⚠️ 已卸载", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(),
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }

        // 3. 快照时间轴主体
        if (snapshots.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (searchQuery.isNotEmpty() || filterType != SnapshotFilterType.ALL) {
                        "未找到匹配的快照记录"
                    } else {
                        "仓库中尚无任何快照\n可在「应用管理」或「目录备份」面板开始备份"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                for (group in groupedSnapshots) {
                    val isCollapsed = collapsedDates[group.date] == true

                    // 吸顶日期头部
                    stickyHeader(key = "header_${group.date}") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    collapsedDates[group.date] = !isCollapsed
                                },
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Phosphor.Clock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = group.displayTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                    ) {
                                        Text(
                                            text = "${group.items.size} 个快照",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (group.totalSizeBytes > 0) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "· ${formatBytes(group.totalSizeBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = if (isCollapsed) Phosphor.CaretDown else Phosphor.CaretUp,
                                    contentDescription = if (isCollapsed) "展开" else "折叠",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    // 日期分组下的统合快照项
                    if (!isCollapsed) {
                        itemsIndexed(
                            group.items,
                            key = { _, item -> item.snapshotId }
                        ) { index, item ->
                            val isFirst = index == 0
                            val isLast = index == group.items.size - 1
                            val isRestoring = restoringSnapshotId == item.snapshotId

                            UnifiedSnapshotTimelineCard(
                                item = item,
                                isFirst = isFirst,
                                isLast = isLast,
                                isRestoring = isRestoring,
                                isBusy = isDeletingSnapshot || restoringSnapshotId != null,
                                onRestore = {
                                    when (item) {
                                        is UnifiedSnapshotUiItem.AppSnapshot -> {
                                            restoringSnapshotId = item.snapshotId
                                            onRestoreAppSnapshot(item.packageName, item.snapshotId) {
                                                restoringSnapshotId = null
                                            }
                                        }
                                        is UnifiedSnapshotUiItem.DirectorySnapshot -> {
                                            dirPendingRestore = item
                                        }
                                    }
                                },
                                onDelete = {
                                    snapshotPendingDelete = item
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 4. 删除快照二次确认弹窗
    snapshotPendingDelete?.let { item ->
        val dirId = if (item is UnifiedSnapshotUiItem.DirectorySnapshot) item.directoryEntity?.id else null
        DeleteSnapshotConfirmDialog(
            snapshotId = item.snapshotId,
            targetName = item.displayTitle,
            snapshotTime = item.backupDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            isDeleting = isDeletingSnapshot,
            onDismiss = {
                if (!isDeletingSnapshot) snapshotPendingDelete = null
            },
            onConfirm = { runPrune ->
                isDeletingSnapshot = true
                onDeleteSnapshot(item.snapshotId, dirId, runPrune) { _, _ ->
                    isDeletingSnapshot = false
                    snapshotPendingDelete = null
                }
            },
        )
    }

    // 5. 目录快照还原目标路径选择对话框
    dirPendingRestore?.let { dirSnap ->
        var restoreMode by remember { mutableStateOf(0) } // 0: 原地覆盖, 1: 重定向
        var redirectPath by remember { mutableStateOf("/sdcard/Restored/${dirSnap.displayTitle}") }

        AlertDialog(
            onDismissRequest = {
                if (restoringSnapshotId == null) dirPendingRestore = null
            },
            title = { Text("还原目录快照") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "目录名称: ${dirSnap.displayTitle}\n版本: ${dirSnap.shortId} (${dirSnap.backupDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )

                    // 选项 1: 原地覆盖
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { restoreMode = 0 },
                    ) {
                        RadioButton(
                            selected = restoreMode == 0,
                            onClick = { restoreMode = 0 },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("原地覆盖恢复", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "恢复至原始目录: ${dirSnap.path}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 选项 2: 重定向到独立目录
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { restoreMode = 1 },
                    ) {
                        RadioButton(
                            selected = restoreMode == 1,
                            onClick = { restoreMode = 1 },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("重定向恢复到新路径", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "不会覆盖现有目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (restoreMode == 1) {
                        OutlinedTextField(
                            value = redirectPath,
                            onValueChange = { redirectPath = it },
                            label = { Text("还原目标路径") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = if (restoreMode == 0) dirSnap.path else redirectPath.trim()
                        val pending = dirSnap
                        dirPendingRestore = null
                        restoringSnapshotId = pending.snapshotId
                        onRestoreDirectorySnapshot(pending, target) {
                            restoringSnapshotId = null
                        }
                    },
                    enabled = restoringSnapshotId == null && (restoreMode == 0 || redirectPath.isNotBlank()),
                ) {
                    Text("开始还原")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { dirPendingRestore = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 6. 体检详情弹窗
    if (showCheckResultDialog && !stats.lastCheckResult.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { showCheckResultDialog = false },
            title = { Text("存储库完整性体检结果") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stats.lastCheckResult,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCheckResultDialog = false }) {
                    Text("确定")
                }
            },
        )
    }
}

/**
 * 统合时间轴快照卡片项
 */
@Composable
private fun UnifiedSnapshotTimelineCard(
    item: UnifiedSnapshotUiItem,
    isFirst: Boolean,
    isLast: Boolean,
    isRestoring: Boolean,
    isBusy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val appIcon: Drawable? = remember(item) {
        if (item is UnifiedSnapshotUiItem.AppSnapshot) {
            try {
                context.packageManager.getApplicationIcon(item.packageName)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    val dotColor = when (item) {
        is UnifiedSnapshotUiItem.AppSnapshot -> MaterialTheme.colorScheme.primary
        is UnifiedSnapshotUiItem.DirectorySnapshot -> MaterialTheme.colorScheme.tertiary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(IntrinsicSize.Min),
    ) {
        // 纵向时间轴轨道与节点
        Column(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部连线
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(20.dp)
                    .background(
                        if (isFirst) Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
            // 时间轴圆点节点
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        color = dotColor,
                        shape = CircleShape,
                    )
            )
            // 底部连线
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(
                        if (isLast) Color.Transparent
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }

        // 快照卡片主体
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 图标区
                if (item is UnifiedSnapshotUiItem.AppSnapshot) {
                    if (appIcon != null) {
                        Image(
                            painter = rememberAsyncImagePainter(appIcon),
                            contentDescription = item.displayTitle,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Phosphor.AndroidLogo,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Phosphor.FolderNotch,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 核心信息区
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = item.displayTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // 类型与状态 Badge
                        when (item) {
                            is UnifiedSnapshotUiItem.AppSnapshot -> {
                                if (item.versionName.isNotBlank() && item.versionName != "-") {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    ) {
                                        Text(
                                            text = "v${item.versionName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                if (!item.isInstalled) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                    ) {
                                        Text(
                                            text = "已卸载",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            is UnifiedSnapshotUiItem.DirectorySnapshot -> {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                ) {
                                    Text(
                                        text = "目录快照",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 快照元数据与组件 Badge 行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = item.backupDate.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = item.shortId,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }

                        if (item.sizeBytes > 0) {
                            Text(
                                text = formatBytes(item.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                        }

                        // 应用备份组件徽章
                        if (item is UnifiedSnapshotUiItem.AppSnapshot) {
                            if (item.hasApk) {
                                ComponentBadge(text = "APK", color = MaterialTheme.colorScheme.primary)
                            }
                            if (item.hasAppData) {
                                ComponentBadge(text = "DATA", color = MaterialTheme.colorScheme.secondary)
                            }
                            if (item.hasDataDe) {
                                ComponentBadge(text = "DE", color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 操作按钮区 (还原 + 删除)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = onRestore,
                        enabled = !isRestoring && !isBusy,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(text = "还原", fontSize = 12.sp)
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        enabled = !isRestoring && !isBusy,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Phosphor.TrashSimple,
                            contentDescription = "删除快照",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}
