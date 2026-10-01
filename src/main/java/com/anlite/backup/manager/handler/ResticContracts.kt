package com.anlite.backup.manager.handler

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** restic ls --json 命令输出的单个文件/目录条目 */
@Serializable
data class ResticLsEntry(
    val path: String,
    val size: Long = 0L,
    val type: String,   // "file" | "dir"
    val mtime: String = "",
)

/** restic backup 操作的结果汇总 */
data class ResticBackupResult(
    var snapshotId: String? = null,
    var size: Long = 0L,
    var hasApk: Boolean = false,
    var hasAppData: Boolean = false,
    var hasDevicesProtectedData: Boolean = false,
    var hasExternalData: Boolean = false,
    var hasObbData: Boolean = false,
    var hasMediaData: Boolean = false,
)

/** restic snapshot 元数据（来自 restic snapshots --json 输出） */
@Serializable
data class ResticSnapshot(
    val id: String,
    @SerialName("short_id")
    val shortId: String = "",
    val time: String,
    val tags: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val hostname: String = "",
)
