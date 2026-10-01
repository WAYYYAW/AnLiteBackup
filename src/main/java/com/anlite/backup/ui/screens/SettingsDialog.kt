package com.anlite.backup.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.CheckCircle
import com.anlite.backup.ui.compose.icons.phosphor.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anlite.backup.data.preferences.EngineConfig
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
    config: EngineConfig,
    isRootGranted: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        repoPath: String,
        repoPassword: String,
        maxSnapshots: Int,
        autoPrune: Boolean,
        restoreSelinux: Boolean,
        showSystemApps: Boolean,
    ) -> Unit,
) {
    var repoPath by remember { mutableStateOf(config.repoPath) }
    var repoPassword by remember { mutableStateOf(config.repoPassword) }
    var maxSnapshots by remember { mutableFloatStateOf(config.maxSnapshotsPerApp.toFloat()) }
    var autoPrune by remember { mutableStateOf(config.autoPruneAfterBatch) }
    var restoreSelinux by remember { mutableStateOf(config.restoreSelinux) }
    var showSystemApps by remember { mutableStateOf(config.showSystemApps) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "核心备份设置", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Root 状态探针卡片
                Surface(
                    color = if (isRootGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isRootGranted) Phosphor.CheckCircle else Phosphor.Warning,
                            contentDescription = null,
                            tint = if (isRootGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRootGranted) "Root 权限就绪 (KernelSU/Magisk)" else "未检测到 Root 权限",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isRootGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                // 仓库物理路径
                OutlinedTextField(
                    value = repoPath,
                    onValueChange = { repoPath = it },
                    label = { Text("Restic 仓库绝对路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 仓库密码
                OutlinedTextField(
                    value = repoPassword,
                    onValueChange = { repoPassword = it },
                    label = { Text("Restic 仓库加密密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 快照保留版本数
                Column {
                    Text(
                        text = "单应用快照保留数: ${maxSnapshots.roundToInt()} 个",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = maxSnapshots,
                        onValueChange = { maxSnapshots = it },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                }

                // 自动 Prune 开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "批处理后自动整理碎片", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "在整批备份完成后自动执行 restic prune 释放物理存储",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(checked = autoPrune, onCheckedChange = { autoPrune = it })
                }

                // 恢复 SELinux 标签
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "还原时恢复 SELinux 标签", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "自动调用 restorecon 保证还原后应用具备正常系统权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(checked = restoreSelinux, onCheckedChange = { restoreSelinux = it })
                }

                // 显示系统应用
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "显示系统应用", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        repoPath,
                        repoPassword,
                        maxSnapshots.roundToInt(),
                        autoPrune,
                        restoreSelinux,
                        showSystemApps,
                    )
                    onDismiss()
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
