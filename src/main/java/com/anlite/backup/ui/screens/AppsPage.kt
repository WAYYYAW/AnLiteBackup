package com.anlite.backup.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.anlite.backup.ui.compose.icons.Phosphor
import com.anlite.backup.ui.compose.icons.phosphor.MagnifyingGlass
import com.anlite.backup.ui.compose.icons.phosphor.X
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.anlite.backup.data.repository.AppItemUiState
import com.anlite.backup.data.repository.BackupStatus
import com.anlite.backup.ui.viewmodel.FilterMode

@Composable
fun AppsPage(
    apps: List<AppItemUiState>,
    searchQuery: String,
    filterMode: FilterMode,
    selectedPackages: Set<String>,
    onSearchChange: (String) -> Unit,
    onFilterChange: (FilterMode) -> Unit,
    onAppClick: (AppItemUiState) -> Unit,
    onAppLongClick: (AppItemUiState) -> Unit,
    onToggleSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索应用名称或包名...") },
            leadingIcon = { Icon(Phosphor.MagnifyingGlass, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Phosphor.X, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // 快捷状态过滤 Chip 行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filterMode == FilterMode.ALL,
                onClick = { onFilterChange(FilterMode.ALL) },
                label = { Text("全部") },
            )
            FilterChip(
                selected = filterMode == FilterMode.NEED_BACKUP,
                onClick = { onFilterChange(FilterMode.NEED_BACKUP) },
                label = { Text("待备份 / 待更新") },
            )
            FilterChip(
                selected = filterMode == FilterMode.UP_TO_DATE,
                onClick = { onFilterChange(FilterMode.UP_TO_DATE) },
                label = { Text("已备份") },
            )
            FilterChip(
                selected = filterMode == FilterMode.NOT_BACKED_UP,
                onClick = { onFilterChange(FilterMode.NOT_BACKED_UP) },
                label = { Text("未备份") },
            )
        }

        // 应用列表
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "没有找到匹配的应用",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            val isMultiSelectMode = selectedPackages.isNotEmpty()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    val isSelected = selectedPackages.contains(app.packageName)
                    AppListItemCard(
                        app = app,
                        isSelected = isSelected,
                        isMultiSelectMode = isMultiSelectMode,
                        onClick = {
                            if (isMultiSelectMode) {
                                onToggleSelect(app.packageName)
                            } else {
                                onAppClick(app)
                            }
                        },
                        onLongClick = {
                            onAppLongClick(app)
                        },
                        onCheckChange = {
                            onToggleSelect(app.packageName)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppListItemCard(
    app: AppItemUiState,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val appIcon: Drawable? = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName)
        } catch (_: Throwable) {
            null
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onCheckChange,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            // 应用图标
            if (appIcon != null) {
                Image(
                    painter = rememberAsyncImagePainter(appIcon),
                    contentDescription = app.appLabel,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Surface(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = app.appLabel.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
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
                    text = "v${app.versionName} (${app.versionCode})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 状态徽标
            StatusBadge(status = app.backupStatus)
        }
    }
}

@Composable
private fun StatusBadge(status: BackupStatus) {
    val (bgColor, textColor, text) = when (status) {
        BackupStatus.UP_TO_DATE -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "已备份")
        BackupStatus.UPDATE_AVAILABLE -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "待更新")
        BackupStatus.NOT_BACKED_UP -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "未备份")
        BackupStatus.UNINSTALLED -> Triple(Color(0xFFF3E5F5), Color(0xFF6A1B9A), "已卸载")
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
