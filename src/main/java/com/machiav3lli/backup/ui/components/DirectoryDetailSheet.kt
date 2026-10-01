package com.machiav3lli.backup.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.machiav3lli.backup.core.engine.ResticSnapshot
import com.machiav3lli.backup.data.dbs.entity.BackupDirectory
import com.machiav3lli.backup.ui.compose.icons.Phosphor
import com.machiav3lli.backup.ui.compose.icons.phosphor.Clock
import com.machiav3lli.backup.ui.compose.icons.phosphor.FloppyDisk
import com.machiav3lli.backup.ui.compose.icons.phosphor.FolderNotch
import com.machiav3lli.backup.ui.compose.icons.phosphor.TrashSimple
import com.machiav3lli.backup.ui.dialogs.DeleteSnapshotConfirmDialog
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private fun formatSnapshotTime(isoTime: String): String {
    return try {
        val dt = ZonedDateTime.parse(isoTime).toLocalDateTime()
        dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    } catch (_: Throwable) {
        isoTime
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryDetailSheet(
    directory: BackupDirectory,
    snapshots: List<ResticSnapshot>,
    isLoadingSnapshots: Boolean,
    onDismiss: () -> Unit,
    onBackupNow: (BackupDirectory) -> Unit,
    onDeleteDirectory: (BackupDirectory) -> Unit,
    onRestoreSnapshot: (snapshotId: String, originalPath: String, targetPath: String, onComplete: (Boolean, String?) -> Unit) -> Unit,
    onDeleteSnapshot: (directory: BackupDirectory, snapshotId: String, runPrune: Boolean, onComplete: (Boolean, String?) -> Unit) -> Unit = { _, _, _, _ -> },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var selectedSnapshotForRestore by remember { mutableStateOf<ResticSnapshot?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoreFeedback by remember { mutableStateOf<String?>(null) }
    var snapshotPendingDelete by remember { mutableStateOf<ResticSnapshot?>(null) }
    var isDeletingSnapshot by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Phosphor.FolderNotch,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = directory.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = directory.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        Phosphor.TrashSimple,
                        contentDescription = "删除配置",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 立即备份主按钮
            Button(
                onClick = { onBackupNow(directory) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Phosphor.FloppyDisk, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("立即增量备份")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 历史快照时间轴
            Text(
                text = "历史快照版本 (${snapshots.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isLoadingSnapshots) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (snapshots.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = "暂无备份快照，点击上方立即备份开始初次全量快照。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(snapshots, key = { it.id }) { snap ->
                        val shortId = snap.short_id ?: snap.id.take(8)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                            modifier = Modifier.padding(end = 8.dp),
                                        ) {
                                            Text(
                                                text = shortId,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                        Icon(
                                            Phosphor.Clock,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = formatSnapshotTime(snap.time),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Button(
                                        onClick = { selectedSnapshotForRestore = snap },
                                        enabled = !isRestoring && !isDeletingSnapshot,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text("还原", fontSize = 12.sp)
                                    }

                                    IconButton(
                                        onClick = { snapshotPendingDelete = snap },
                                        enabled = !isRestoring && !isDeletingSnapshot,
                                        modifier = Modifier.size(36.dp),
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

    // 删除快照确认对话框
    snapshotPendingDelete?.let { snap ->
        DeleteSnapshotConfirmDialog(
            snapshotId = snap.id,
            targetName = directory.name,
            snapshotTime = formatSnapshotTime(snap.time),
            isDeleting = isDeletingSnapshot,
            onDismiss = {
                if (!isDeletingSnapshot) snapshotPendingDelete = null
            },
            onConfirm = { runPrune ->
                isDeletingSnapshot = true
                onDeleteSnapshot(directory, snap.id, runPrune) { _, _ ->
                    isDeletingSnapshot = false
                    snapshotPendingDelete = null
                }
            },
        )
    }

    // 删除配置确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("移除目录配置") },
            text = { Text("确认移除该目录备份配置？已备份至存储库的快照数据仍将安全保留。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteDirectory(directory)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 目录还原目标路径选择对话框（原地覆盖 vs 重定向目录）
    selectedSnapshotForRestore?.let { snap ->
        var restoreMode by remember { mutableStateOf(0) } // 0: 原地覆盖, 1: 重定向
        var redirectPath by remember { mutableStateOf("/sdcard/Restored/${directory.name}") }

        AlertDialog(
            onDismissRequest = {
                if (!isRestoring) selectedSnapshotForRestore = null
            },
            title = { Text("还原目录快照") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "快照版本: ${snap.short_id ?: snap.id.take(8)} (${formatSnapshotTime(snap.time)})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )

                    // 选项 1: 原地覆盖
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = restoreMode == 0,
                            onClick = { restoreMode = 0 },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("原地覆盖还原", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "将快照数据直接写回原目录 (${directory.path})",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 选项 2: 重定向目录
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = restoreMode == 1,
                            onClick = { restoreMode = 1 },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("重定向目录还原", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "安全还原至指定目录，不覆盖当前数据",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (restoreMode == 1) {
                        OutlinedTextField(
                            value = redirectPath,
                            onValueChange = { redirectPath = it },
                            label = { Text("目标还原绝对路径") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        )
                    }

                    if (isRestoring) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在还原目录数据，请稍候...", fontSize = 12.sp)
                        }
                    }

                    restoreFeedback?.let { msg ->
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = if (restoreMode == 0) directory.path else redirectPath.trim()
                        isRestoring = true
                        restoreFeedback = null
                        onRestoreSnapshot(snap.id, directory.path, target) { success, err ->
                            isRestoring = false
                            if (success) {
                                restoreFeedback = "还原成功至: $target"
                                selectedSnapshotForRestore = null
                            } else {
                                restoreFeedback = "还原失败: $err"
                            }
                        }
                    },
                    enabled = !isRestoring && (restoreMode == 0 || redirectPath.isNotBlank()),
                ) {
                    Text("开始还原")
                }
            },
            dismissButton = {
                if (!isRestoring) {
                    OutlinedButton(onClick = { selectedSnapshotForRestore = null }) {
                        Text("取消")
                    }
                }
            },
        )
    }
}
