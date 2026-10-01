package com.anlite.backup.data.dbs.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

import androidx.room.Index

/**
 * 用户配置的自定义目录备份实体
 */
@Entity(
    tableName = "BackupDirectory",
    indices = [Index(value = ["path"], unique = true)]
)
data class BackupDirectory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val path: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val lastBackupTime: LocalDateTime? = null,
    val lastSnapshotId: String? = null,
    val sizeBytes: Long = 0L,
    val snapshotCount: Int = 0,
)
