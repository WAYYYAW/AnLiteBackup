package com.anlite.backup.ui.dialogs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anlite.backup.data.repository.DirectoryValidationResult
import com.anlite.backup.data.repository.PresetDirectory
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.CheckCircle
import com.anlite.backup.ui.compose.icons.phosphor.FolderNotch
import com.anlite.backup.ui.compose.icons.phosphor.Warning

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes > 0 -> "$bytes B"
        else -> "0 B"
    }
}

@Composable
fun AddDirectoryDialog(
    presets: List<PresetDirectory>,
    validationResult: DirectoryValidationResult?,
    isValidating: Boolean,
    onValidatePath: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, path: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf<PresetDirectory?>(null) }

    LaunchedEffect(path) {
        if (path.isNotBlank()) {
            onValidatePath(path)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Phosphor.FolderNotch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("添加备份目录", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "支持物理路径或沙盒目录镜像备份：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 快捷预设横向滚动
                if (presets.isNotEmpty()) {
                    Text(
                        text = "推荐预设",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (preset in presets) {
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = {
                                    selectedPreset = preset
                                    name = preset.name
                                    path = preset.path
                                },
                                label = { Text(preset.name, fontSize = 12.sp) },
                            )
                        }
                    }
                }

                // 目录备注名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("目录备注名称") },
                    placeholder = { Text("例如：Documents") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                // 绝对路径
                OutlinedTextField(
                    value = path,
                    onValueChange = {
                        path = it
                        selectedPreset = null
                    },
                    label = { Text("物理绝对路径") },
                    placeholder = { Text("/sdcard/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                // 路径实时探针探测状态
                if (path.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            isValidating -> MaterialTheme.colorScheme.surfaceVariant
                            validationResult?.exists == true && validationResult.isDirectory ->
                                Color(0xFF4CAF50).copy(alpha = 0.12f)
                            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isValidating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Root 探针正在检验路径...",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (validationResult?.exists == true && validationResult.isDirectory) {
                                Icon(
                                    Phosphor.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF2E7D32),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "目录有效 (占用预估: ${formatBytes(validationResult.sizeBytes)})",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF2E7D32),
                                )
                            } else {
                                Icon(
                                    Phosphor.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = validationResult?.errorMessage ?: "路径无效或无法访问",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), path.trim()) },
                enabled = name.isNotBlank() && path.isNotBlank(),
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
