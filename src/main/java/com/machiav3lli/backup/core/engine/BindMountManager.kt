package com.machiav3lli.backup.core.engine

import timber.log.Timber
import java.io.File

/**
 * 挂载点准备结果
 */
data class StagingResult(
    val stagingDir: File,
    val hasApk: Boolean,
    val hasData: Boolean,
    val hasDataDe: Boolean,
)

/**
 * 管理 restic 备份期间虚拟目录树的 `mount --bind` 生命周期。
 *
 * 在 `/data/local/tmp/nb_staging_<pkg>` 下建立虚拟挂载点 (`/apk`, `/data`, `/data_de`)，
 * 继承 [AutoCloseable]，确保在备份结束或发生异常时逆序干净卸载所有挂载点并删除临时暂存目录。
 */
class BindMountManager(
    val packageName: String,
    baseStagingParent: String = "/data/local/tmp",
) : AutoCloseable {

    val stagingDir = File(baseStagingParent, "nb_staging_$packageName")
    private val mountedPoints = mutableListOf<String>()

    /**
     * 将源目录通过 `mount --bind` 挂载到暂存目录下的子目录 [subDir]。
     * 若源路径不是目录或不存在，则跳过挂载并返回 false。
     */
    fun bindMount(sourcePath: String, subDir: String): Boolean {
        val checkRes = RootExecutor.execute("test -d ${RootExecutor.quote(sourcePath)}")
        if (!checkRes.isSuccess) {
            return false
        }

        val targetPath = File(stagingDir, subDir).absolutePath
        val mkdirRes = RootExecutor.execute("mkdir -p ${RootExecutor.quote(targetPath)}")
        if (!mkdirRes.isSuccess) {
            Timber.e("Failed to create mount target dir: $targetPath")
            return false
        }

        val mountRes = RootExecutor.execute(
            "mount --bind ${RootExecutor.quote(sourcePath)} ${RootExecutor.quote(targetPath)}"
        )
        if (mountRes.isSuccess) {
            mountedPoints.add(targetPath)
            Timber.d("Successfully bind-mounted dir $sourcePath -> $targetPath")
            return true
        } else {
            Timber.e("Failed to bind-mount dir $sourcePath -> $targetPath: ${mountRes.err.joinToString("\n")}")
            return false
        }
    }

    /**
     * 挂载应用的所有 APK 文件（包含 base.apk 与 split APKs）至 [stagingDir]/apk 目录下。
     * 精准挂载每个 .apk 文件，杜绝无关 oat/lib 等文件混入。
     */
    fun bindMountApks(apkSourceDir: String?): Boolean {
        if (apkSourceDir.isNullOrBlank()) return false
        val sourceFile = File(apkSourceDir)
        val apkDir = File(stagingDir, "apk")
        val mkdirRes = RootExecutor.execute("mkdir -p ${RootExecutor.quote(apkDir.absolutePath)}")
        if (!mkdirRes.isSuccess) {
            Timber.e("Failed to create apk target dir: ${apkDir.absolutePath}")
            return false
        }

        // 定位应用所在目录（通常为 base.apk 的上级目录）
        val parentDir = if (sourceFile.isDirectory) sourceFile else sourceFile.parentFile
        val findCmd = if (parentDir != null) {
            "find ${RootExecutor.quote(parentDir.absolutePath)} -maxdepth 1 -name '*.apk'"
        } else {
            "ls ${RootExecutor.quote(apkSourceDir)}"
        }
        val findRes = RootExecutor.execute(findCmd)
        val apkPaths = findRes.out.map { it.trim() }.filter { it.isNotBlank() && it.endsWith(".apk") }

        var anyMounted = false
        if (apkPaths.isNotEmpty()) {
            for (apkPath in apkPaths) {
                val apkName = File(apkPath).name
                val targetFile = File(apkDir, apkName).absolutePath
                RootExecutor.execute("touch ${RootExecutor.quote(targetFile)}")
                val mountRes = RootExecutor.execute(
                    "mount --bind ${RootExecutor.quote(apkPath)} ${RootExecutor.quote(targetFile)}"
                )
                if (mountRes.isSuccess) {
                    mountedPoints.add(targetFile)
                    anyMounted = true
                    Timber.d("Successfully bind-mounted APK: $apkPath -> $targetFile")
                } else {
                    Timber.e("Failed to bind-mount APK: $apkPath -> $targetFile: ${mountRes.err.joinToString("\n")}")
                }
            }
        } else {
            // Fallback: 直接挂载 sourceFile 单文件
            val apkName = sourceFile.name.ifEmpty { "base.apk" }
            val targetFile = File(apkDir, apkName).absolutePath
            RootExecutor.execute("touch ${RootExecutor.quote(targetFile)}")
            val mountRes = RootExecutor.execute(
                "mount --bind ${RootExecutor.quote(apkSourceDir)} ${RootExecutor.quote(targetFile)}"
            )
            if (mountRes.isSuccess) {
                mountedPoints.add(targetFile)
                anyMounted = true
                Timber.d("Successfully bind-mounted single APK: $apkSourceDir -> $targetFile")
            }
        }
        return anyMounted
    }

    /**
     * 组织应用的完整虚拟路径树：
     * - `/apk`: 包含应用 base.apk 或 apk 安装目录
     * - `/data`: `/data/data/<pkg>` 私有数据目录
     * - `/data_de`: `/data/user_de/0/<pkg>` 设备加密私有数据目录（若存在）
     */
    fun setupStaging(
        apkSourceDir: String?,
        includeData: Boolean = true,
        includeDataDe: Boolean = true,
    ): StagingResult {
        // 先确保创建根暂存目录
        RootExecutor.execute("mkdir -p ${RootExecutor.quote(stagingDir.absolutePath)}")

        var hasApk = false
        if (!apkSourceDir.isNullOrBlank()) {
            hasApk = bindMountApks(apkSourceDir)
        }

        var hasData = false
        if (includeData) {
            // Android 常见标准应用私有路径
            val primaryDataPath = "/data/data/$packageName"
            val fallbackDataPath = "/data/user/0/$packageName"
            hasData = bindMount(primaryDataPath, "data") || bindMount(fallbackDataPath, "data")
        }

        var hasDataDe = false
        if (includeDataDe) {
            val dePath = "/data/user_de/0/$packageName"
            hasDataDe = bindMount(dePath, "data_de")
        }

        return StagingResult(
            stagingDir = stagingDir,
            hasApk = hasApk,
            hasData = hasData,
            hasDataDe = hasDataDe,
        )
    }

    /**
     * 逆序卸载所有已挂载的路径，并清理临时暂存根目录。
     */
    override fun close() {
        for (mountPoint in mountedPoints.asReversed()) {
            try {
                val umountRes = RootExecutor.execute("umount ${RootExecutor.quote(mountPoint)}")
                if (!umountRes.isSuccess) {
                    // 若普通卸载由于设备忙报错，立即降级使用 lazy umount
                    RootExecutor.execute("umount -l ${RootExecutor.quote(mountPoint)}")
                }
            } catch (e: Throwable) {
                Timber.w(e, "Error unmounting $mountPoint")
            }
        }
        mountedPoints.clear()

        // 卸载后移除临时目录
        RootExecutor.execute("rm -rf ${RootExecutor.quote(stagingDir.absolutePath)}")
        Timber.d("Cleaned up staging directory for $packageName")
    }

    companion object {
        private const val STAGING_PREFIX = "/data/local/tmp/nb_"

        /**
         * 挂载自愈清理探针：
         * 读取 `/proc/mounts`，扫描所有属于 Neo-Backup 的遗留挂载点并强制执行 `umount -l`，
         * 随后清理残留的临时目录。可安全在应用冷启动、初始化或异常退出自愈时调用。
         *
         * @return 清理成功的残留挂载点数量
         */
        fun reapOrphanMounts(): Int {
            Timber.i("Running BindMount self-healing orphan reaper probe...")
            val result = RootExecutor.execute("cat /proc/mounts")
            if (!result.isSuccess) {
                Timber.w("Failed to read /proc/mounts: ${result.err.joinToString("\n")}")
                return 0
            }

            var reapedCount = 0
            val orphanMounts = mutableListOf<String>()

            for (line in result.out) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val mountPoint = parts[1]
                    if (mountPoint.startsWith(STAGING_PREFIX)) {
                        orphanMounts.add(mountPoint)
                    }
                }
            }

            // 逆序卸载，先卸载深层子目录再卸载父级
            for (mountPoint in orphanMounts.sortedDescending()) {
                Timber.w("Reaping orphan mount point: $mountPoint")
                val umountRes = RootExecutor.execute("umount -l ${RootExecutor.quote(mountPoint)}")
                if (umountRes.isSuccess) {
                    reapedCount++
                }
            }

            // 清理遗留的暂存目录
            RootExecutor.execute("rm -rf /data/local/tmp/nb_staging_* /data/local/tmp/nb_restore_*")
            Timber.i("BindMount reaper finished: reaped $reapedCount orphan mount(s)")
            return reapedCount
        }
    }
}
