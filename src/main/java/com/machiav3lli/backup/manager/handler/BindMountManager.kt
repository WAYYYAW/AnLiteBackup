package com.machiav3lli.backup.manager.handler

import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.quote
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.runAsRoot
import com.machiav3lli.backup.manager.handler.ShellHandler.Companion.utilBoxQ
import timber.log.Timber
import java.io.File

/**
 * 管理 `mount --bind` 生命周期，确保在备份或异常退出时逆序干净卸载所有挂载点。
 */
class BindMountManager : AutoCloseable {
    private val mountPoints = mutableListOf<String>()

    /**
     * 将 [sourcePath] 通过 `mount --bind` 挂载到 [targetDir]/[subDir]。
     * 如果 [sourcePath] 目录不存在则直接跳过并返回 `false`。
     */
    fun bindMount(sourcePath: String, targetDir: File, subDir: String): Boolean {
        val existsResult = runAsRoot("$utilBoxQ test -d ${quote(sourcePath)}", throwFail = false)
        if (!existsResult.isSuccess) {
            return false
        }
        val mountTarget = File(targetDir, subDir)
        mountTarget.mkdirs()
        runAsRoot("$utilBoxQ mkdir -p ${quote(mountTarget.absolutePath)}")
        runAsRoot("mount --bind ${quote(sourcePath)} ${quote(mountTarget.absolutePath)}")
        mountPoints.add(mountTarget.absolutePath)
        return true
    }

    /**
     * 逆序卸载所有已注册的挂载点（忽略单个卸载错误，必要时降级为 lazy umount）。
     */
    override fun close() {
        mountPoints.asReversed().forEach { mp ->
            try {
                val res = runAsRoot("umount ${quote(mp)}", throwFail = false)
                if (!res.isSuccess) {
                    runAsRoot("umount -l ${quote(mp)}", throwFail = false)
                }
            } catch (e: Throwable) {
                Timber.w(e, "umount failed for $mp")
            }
        }
        mountPoints.clear()
    }
}
