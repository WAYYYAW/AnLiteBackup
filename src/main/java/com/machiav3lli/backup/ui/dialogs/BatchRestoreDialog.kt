package com.machiav3lli.backup.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.machiav3lli.backup.data.repository.AppItemUiState
import com.machiav3lli.backup.ui.compose.icons.Phosphor
import com.machiav3lli.backup.ui.compose.icons.phosphor.ClockCounterClockwise
import com.machiav3lli.backup.ui.compose.icons.phosphor.Info

/**
 * 批量还原确认对话框。
 * 允许用户选择是否还原 APK 与应用数据，并清晰列出待还原应用及跳过项。
 */
@Composable
fun BatchRestoreDialog(
    selectedApps: List<AppItemUiState>,
    onDismiss: () -> Unit,
    onConfirm: (restoreApk: Boolean, restoreData: Boolean) -> Unit,
) {
    var restoreApk by remember { mutableStateOf(true) }
    var restoreData by remember { mutableStateOf(true) }

    val restorableApps = remember(selectedApps) {
        selectedApps.filter { (it.latestSnapshot ?: it.snapshots.firstOrNull())?.resticSnapshotId != null }
    }
    val unbackedApps = remember(selectedApps) {
        selectedApps.filter { (it.latestSnapshot ?: it.snapshots.firstOrNull())?.resticSnapshotId == null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Phosphor.ClockCounterClockwise,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        },
        title = {
            Text(
                text = "批量还原应用",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 还原概括卡片
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (restorableApps.isNotEmpty()) {
                                "已选 ${selectedApps.size} 项，可还原 ${restorableApps.size} 个应用的最新快照。"
                            } else {
                                "已选 ${selectedApps.size} 项，但所选应用均无备份快照，无法执行还原。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )

                        if (unbackedApps.isNotEmpty() && restorableApps.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Phosphor.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = "其中 ${unbackedApps.size} 个未备份应用将自动跳过",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }

                // 可还原应用列表预览
                if (restorableApps.isNotEmpty()) {
                    Text(
                        text = "将还原以下应用 (${restorableApps.size}):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 1.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp),
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(restorableApps, key = { it.packageName }) { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = app.appLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val snap = app.latestSnapshot ?: app.snapshots.firstOrNull()
                                    val snapId = snap?.resticSnapshotId?.take(8) ?: "-"
                                    Text(
                                        text = snapId,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 还原范围勾选
                    Text(
                        text = "还原范围",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreApk = !restoreApk }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = restoreApk,
                            onCheckedChange = { restoreApk = it },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "还原应用安装包 (APK)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "重新安装备份时的 APK 及 Split 架构",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreData = !restoreData }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = restoreData,
                            onCheckedChange = { restoreData = it },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "还原应用数据",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "覆盖 /data/data 与设备加密数据并恢复 SELinux",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(restoreApk, restoreData) },
                enabled = restorableApps.isNotEmpty() && (restoreApk || restoreData),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = if (restorableApps.isNotEmpty()) {
                        "开始还原 (${restorableApps.size})"
                    } else {
                        "无法还原"
                    }
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("取消")
            }
        },
    )
}
