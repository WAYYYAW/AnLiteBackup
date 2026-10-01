package com.anlite.backup.data.repository

import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.dbs.entity.BackupDirectory
import java.time.LocalDateTime

enum class SnapshotFilterType {
    ALL,                // 全部快照
    APPS_ONLY,          // 仅应用快照
    DIRECTORIES_ONLY,   // 仅自定义目录快照
    UNINSTALLED_ONLY,   // 仅已卸载应用残留快照
}

sealed class UnifiedSnapshotUiItem {
    abstract val snapshotId: String
    abstract val shortId: String
    abstract val backupDate: LocalDateTime
    abstract val sizeBytes: Long
    abstract val displayTitle: String
    abstract val subtitle: String

    val isApp: Boolean get() = this is AppSnapshot
    val isDirectory: Boolean get() = this is DirectorySnapshot

    data class AppSnapshot(
        override val snapshotId: String,
        override val shortId: String,
        override val backupDate: LocalDateTime,
        override val sizeBytes: Long,
        override val displayTitle: String,
        override val subtitle: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val isSystem: Boolean,
        val isInstalled: Boolean,
        val hasApk: Boolean,
        val hasAppData: Boolean,
        val hasDataDe: Boolean,
        val backupEntity: Backup,
        val appUiState: AppItemUiState?,
    ) : UnifiedSnapshotUiItem()

    data class DirectorySnapshot(
        override val snapshotId: String,
        override val shortId: String,
        override val backupDate: LocalDateTime,
        override val sizeBytes: Long,
        override val displayTitle: String,
        override val subtitle: String,
        val path: String,
        val directoryEntity: BackupDirectory?,
    ) : UnifiedSnapshotUiItem()
}
