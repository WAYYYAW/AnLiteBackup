package com.machiav3lli.backup.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.machiav3lli.backup.data.dbs.entity.Backup
import com.machiav3lli.backup.data.preferences.EngineConfig
import com.machiav3lli.backup.data.repository.AppItemUiState
import com.machiav3lli.backup.ui.compose.icons.Phosphor
import com.machiav3lli.backup.ui.compose.icons.phosphor.ArrowsClockwise
import com.machiav3lli.backup.ui.compose.icons.phosphor.CaretDown
import com.machiav3lli.backup.ui.compose.icons.phosphor.CaretUp
import com.machiav3lli.backup.ui.compose.icons.phosphor.Clock
import com.machiav3lli.backup.ui.compose.icons.phosphor.FolderNotch
import com.machiav3lli.backup.ui.compose.icons.phosphor.TrashSimple
import com.machiav3lli.backup.ui.compose.icons.phosphor.Wrench
import com.machiav3lli.backup.ui.dialogs.DeleteSnapshotConfirmDialog
import com.machiav3lli.backup.ui.viewmodel.RepoStatsUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 按自然日期聚合的快照分组
 */
data class DateGroupSnapshots(
    val date: LocalDate,
    val displayTitle: String,
    val items: List<Pair<AppItemUiState, Backup>>,
    val totalSizeBytes: Long,
)

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnapshotsPage(
    apps: List<AppItemUiState>,
    config: EngineConfig,
    stats: RepoStatsUiState,
    onSyncRepository: () -> Unit,
    onPruneRepository: () -> Unit,
    onRestoreSnapshot: (packageName: String, snapshotId: String, onComplete: () -> Unit) -> Unit,
    onDeleteSnapshot: (snapshotId: String, runPrune: Boolean, onComplete: (Boolean, String?) -> Unit) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    var snapshotPendingDelete by remember { mutableStateOf<Pair<AppItemUiState, Backup>?>(null) }
    var isDeletingSnapshot by remember { mutableStateOf(false) }

    // 汇总所有应用的快照并按时间降序排列
    val allSnapshots = remember(apps) {
        apps.flatMap { app ->
            app.snapshots.map { snap -> Pair(app, snap) }
        }.sortedByDescending { it.second.backupDate }
    }

    // 按自然日期聚合分组
    val groupedSnapshots = remember(allSnapshots) {
        allSnapshots.groupBy { it.second.backupDate.toLocalDate() }
            .map { (date, items) ->
                DateGroupSnapshots(
                    date = date,
                    displayTitle = formatGroupDateTitle(date),
                    items = items,
                    totalSizeBytes = items.sumOf { it.second.size },
                )
            }
    }

    // 日期折叠状态（默认全部展开）
    val collapsedDates = remember { mutableStateMapOf<LocalDate, Boolean>() }

    // 正在还原的快照 ID
    var restoringSnapshotId by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // 存储库概览卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
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

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = config.repoPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = "${allSnapshots.size}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "总快照版本数",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    val totalMb = if (stats.totalSizeBytes > 0) {
                        "%.1f MB".format(stats.totalSizeBytes / (1024.0 * 1024.0))
                    } else {
                        "计算中..."
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = totalMb,
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

                Spacer(modifier = Modifier.height(16.dp))

                // 操作按钮区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onSyncRepository,
                        enabled = !stats.isSyncing,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (stats.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("同步中...")
                        } else {
                            Icon(Phosphor.ArrowsClockwise, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("同步仓库")
                        }
                    }

                    OutlinedButton(
                        onClick = onPruneRepository,
                        enabled = !stats.isPruning,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (stats.isPruning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("整理中...")
                        } else {
                            Icon(Phosphor.Wrench, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("碎片整理")
                        }
                    }
                }

                if (!stats.lastSyncMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stats.lastSyncMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // 快照时间轴主体
        if (allSnapshots.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "仓库中尚无任何快照\n可在「应用管理」面板勾选应用开始备份",
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
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
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
                                        val totalMb = "%.1f MB".format(group.totalSizeBytes / (1024.0 * 1024.0))
                                        Text(
                                            text = "· $totalMb",
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

                    // 日期分组下的时间轴项
                    if (!isCollapsed) {
                        itemsIndexed(
                            group.items,
                            key = { _, (app, snap) -> snap.resticSnapshotId ?: "${app.packageName}_${snap.backupDate}" }
                        ) { index, (app, snap) ->
                            val isFirst = index == 0
                            val isLast = index == group.items.size - 1
                            val isRestoring = restoringSnapshotId == snap.resticSnapshotId

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
                                                color = MaterialTheme.colorScheme.primary,
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

                                // 快照卡片
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
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(
                                                    text = app.appLabel,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                )
                                                if (!app.isInstalled) {
                                                    Spacer(modifier = Modifier.width(6.dp))
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

                                            Spacer(modifier = Modifier.height(2.dp))

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = snap.backupDate.format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                Text(
                                                    text = snap.resticSnapshotId?.take(8) ?: "-",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.outline,
                                                )
                                                if (snap.size > 0) {
                                                    val sizeMb = "%.1f MB".format(snap.size / (1024.0 * 1024.0))
                                                    Text(
                                                        text = sizeMb,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.outline,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        snap.resticSnapshotId?.let { snapshotId ->
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                Button(
                                                    onClick = {
                                                        restoringSnapshotId = snapshotId
                                                        onRestoreSnapshot(app.packageName, snapshotId) {
                                                            restoringSnapshotId = null
                                                        }
                                                    },
                                                    enabled = !isRestoring && !isDeletingSnapshot,
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
                                                    onClick = { snapshotPendingDelete = Pair(app, snap) },
                                                    enabled = !isRestoring && !isDeletingSnapshot,
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
                        }
                    }
                }
            }
        }
    }

    // 删除快照确认对话框
    snapshotPendingDelete?.let { (app, snap) ->
        val snapId = snap.resticSnapshotId ?: ""
        DeleteSnapshotConfirmDialog(
            snapshotId = snapId,
            targetName = app.appLabel,
            snapshotTime = snap.backupDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            isDeleting = isDeletingSnapshot,
            onDismiss = {
                if (!isDeletingSnapshot) snapshotPendingDelete = null
            },
            onConfirm = { runPrune ->
                if (snapId.isNotEmpty()) {
                    isDeletingSnapshot = true
                    onDeleteSnapshot(snapId, runPrune) { _, _ ->
                        isDeletingSnapshot = false
                        snapshotPendingDelete = null
                    }
                }
            },
        )
    }
}
