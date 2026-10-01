package com.anlite.backup.ui.components

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.FloppyDisk
import com.anlite.backup.ui.compose.icons.phosphor.TrashSimple
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.repository.AppItemUiState
import com.anlite.backup.data.repository.BackupStatus
import com.anlite.backup.ui.dialogs.DeleteSnapshotConfirmDialog
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailSheet(
    app: AppItemUiState,
    onDismiss: () -> Unit,
    onBackupNow: (AppItemUiState) -> Unit,
    onRestoreSnapshot: (packageName: String, snapshotId: String) -> Unit,
    onDeleteSnapshot: (snapshotId: String, runPrune: Boolean, onComplete: (Boolean, String?) -> Unit) -> Unit = { _, _, _ -> },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val appIcon: Drawable? = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName)
        } catch (_: Throwable) {
            null
        }
    }
    var snapshotPendingDelete by remember { mutableStateOf<Backup?>(null) }
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
            // Header: 图标与基本信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (appIcon != null) {
                    Image(
                        painter = rememberAsyncImagePainter(appIcon),
                        contentDescription = app.appLabel,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Surface(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = app.appLabel.take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "版本: ${app.versionName} (${app.versionCode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 操作主按钮区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        onBackupNow(app)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = "立即备份")
                }

                if (app.snapshots.isNotEmpty()) {
                    val latestSnap = app.snapshots.first()
                    val snapId = latestSnap.resticSnapshotId
                    if (!snapId.isNullOrEmpty()) {
                        OutlinedButton(
                            onClick = {
                                onRestoreSnapshot(app.packageName, snapId)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(text = "还原最新版")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 历史快照版本列表
            Text(
                text = "历史快照 (${app.snapshots.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (app.snapshots.isEmpty()) {
                Text(
                    text = "暂无备份快照记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(app.snapshots, key = { it.resticSnapshotId ?: it.backupDate.toString() }) { snapshot ->
                        SnapshotItemCard(
                            snapshot = snapshot,
                            onRestore = {
                                snapshot.resticSnapshotId?.let { id ->
                                    onRestoreSnapshot(app.packageName, id)
                                    onDismiss()
                                }
                            },
                            onDelete = {
                                snapshotPendingDelete = snapshot
                            },
                        )
                    }
                }
            }
        }
    }

    snapshotPendingDelete?.let { snap ->
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

@Composable
private fun SnapshotItemCard(
    snapshot: Backup,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val dateStr = snapshot.backupDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shortId = snapshot.resticSnapshotId?.take(8) ?: "-"
                    Text(
                        text = "ID: $shortId",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val sizeMb = if (snapshot.size > 0) "%.1f MB".format(snapshot.size / (1024.0 * 1024.0)) else ""
                    if (sizeMb.isNotEmpty()) {
                        Text(
                            text = "($sizeMb)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = onRestore,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(text = "还原", fontSize = 12.sp)
                }

                IconButton(
                    onClick = onDelete,
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
