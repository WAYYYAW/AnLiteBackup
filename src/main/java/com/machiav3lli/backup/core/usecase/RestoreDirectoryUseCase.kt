package com.machiav3lli.backup.core.usecase

import com.machiav3lli.backup.core.engine.ResticDriver
import com.machiav3lli.backup.core.engine.RootExecutor
import com.machiav3lli.backup.data.preferences.EnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class RestoreDirectoryUseCase(
    private val resticDriver: ResticDriver,
    private val preferences: EnginePreferences,
) {
    /**
     * 还原目录快照。
     *
     * @param snapshotId 快照 ID
     * @param originalPath 原始物理路径（用于判断权限属组）
     * @param targetPath 目标还原路径（原地覆盖或重定向自定义目录）
     */
    suspend fun execute(
        snapshotId: String,
        originalPath: String,
        targetPath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = preferences.getSnapshotConfig()
            val targetDir = File(targetPath)

            // 1. 确保目标目录已创建
            RootExecutor.execute("mkdir -p ${RootExecutor.quote(targetDir.absolutePath)}")

            // 2. 检测原目录或父目录的 UID/GID，以便在还原到私有目录时恢复权限
            val (origUid, origGid) = RootExecutor.getOwnerGroup(originalPath)

            Timber.i("Restoring directory snapshot $snapshotId to ${targetDir.absolutePath}...")
            val restoreRes = resticDriver.restore(
                repoPath = config.repoPath,
                password = config.repoPassword,
                snapshotId = snapshotId,
                targetDir = targetDir,
            )

            if (!restoreRes.isSuccess) {
                throw IllegalStateException("Directory restore failed: ${restoreRes.err.joinToString("\n")}")
            }

            // 3. 若原始目录有特定属主（如属于 Termux 或某特定 App），恢复属组与 SELinux
            if (origUid > 0 && origGid > 0) {
                Timber.i("Applying UID=$origUid GID=$origGid to ${targetDir.absolutePath}")
                RootExecutor.execute("chown -R $origUid:$origGid ${RootExecutor.quote(targetDir.absolutePath)}")
            }
            RootExecutor.restoreSelinux(targetDir.absolutePath)

            Timber.i("Directory restored successfully to ${targetDir.absolutePath}")
        }
    }
}
