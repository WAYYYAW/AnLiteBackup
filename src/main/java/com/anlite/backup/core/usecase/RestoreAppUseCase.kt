package com.anlite.backup.core.usecase

import android.content.Context
import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.core.engine.RootExecutor
import com.anlite.backup.data.preferences.EnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 应用还原用例（高性能直写架构）：
 * 1. 阶段一（APK 暂存安装）：仅提取 `apk/` 子分支至轻量临时目录，调用 `pm install` 成功后立即销毁；
 * 2. 阶段二（数据直写目标）：利用 restic 原生能力将 `data/` 与 `data_de/` 直接还原至 `/data/data/<pkg>` 与 `/data/user_de/0/<pkg>`，
 *    自动排除 `lib` 符号链接，杜绝破坏 Android 系统原生动态库软链；
 *    还原完成后直接执行 `chown` 与 `restorecon` 矫正权限，彻底消除 2x 存储冗余与 `cp -a` 性能损耗。
 */
class RestoreAppUseCase(
    private val context: Context,
    private val resticDriver: ResticDriver,
    private val preferences: EnginePreferences,
) {
    suspend fun execute(
        packageName: String,
        snapshotId: String,
        restoreApk: Boolean = true,
        restoreData: Boolean = true,
        onProgress: (percent: Float, message: String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = preferences.getSnapshotConfig()
            val tempApkDir = File("/data/local/tmp/nb_apk_$packageName")

            onProgress(0.05f, "正在探查快照分支结构...")
            val nodes = resticDriver.listSnapshotNodes(config.repoPath, config.repoPassword, snapshotId)
            val hasApkNode = nodes.any { it == "/apk" || it == "apk" || it.startsWith("/apk/") || it.startsWith("apk/") }
            val hasDataNode = nodes.any { it == "/data" || it == "data" || it.startsWith("/data/") || it.startsWith("data/") }
            val hasDataDeNode = nodes.any { it == "/data_de" || it == "data_de" || it.startsWith("/data_de/") || it.startsWith("data_de/") }

            try {
                // ==========================================
                // 阶段一：APK 最小化暂存与安装
                // ==========================================
                if (restoreApk && (hasApkNode || nodes.isEmpty())) {
                    onProgress(0.15f, "正在提取安装包 (APK)...")
                    RootExecutor.execute("rm -rf ${RootExecutor.quote(tempApkDir.absolutePath)}")
                    RootExecutor.execute("mkdir -p ${RootExecutor.quote(tempApkDir.absolutePath)}")

                    Timber.i("Extracting APK branch for $packageName from snapshot $snapshotId...")
                    val apkRestoreRes = resticDriver.restore(
                        repoPath = config.repoPath,
                        password = config.repoPassword,
                        snapshotId = "$snapshotId:apk",
                        targetDir = tempApkDir,
                    )

                    if (apkRestoreRes.isSuccess) {
                        val apks = tempApkDir.listFiles { _, name -> name.endsWith(".apk") } ?: emptyArray()
                        if (apks.isNotEmpty()) {
                            onProgress(0.35f, "正在安装应用安装包 (共 ${apks.size} 个 APK)...")
                            Timber.i("Installing ${apks.size} APK file(s) for $packageName...")
                            // 确保 base.apk 排在最前面，其余 split APKs 跟在后面
                            val sortedApks = apks.sortedWith(compareBy({ !it.name.startsWith("base") }, { it.name }))
                            val apkPaths = sortedApks.joinToString(" ") { RootExecutor.quote(it.absolutePath) }
                            val installRes = RootExecutor.execute("pm install -r -d -t $apkPaths")
                            if (!installRes.isSuccess) {
                                val errMsg = (installRes.err + installRes.out).joinToString("\n")
                                Timber.e("pm install failed for $packageName: $errMsg")
                                throw IllegalStateException("pm install failed: $errMsg")
                            }
                            Timber.i("Successfully installed ${sortedApks.size} APK(s) for $packageName")
                        }
                    } else {
                        Timber.w("No APK subfolder found in snapshot $snapshotId or restore failed: ${apkRestoreRes.err.joinToString("\n")}")
                    }

                    // 安装完成立即销毁 APK 暂存目录，释放闪存
                    RootExecutor.execute("rm -rf ${RootExecutor.quote(tempApkDir.absolutePath)}")
                }

                // ==========================================
                // 阶段二：数据直写目标目录 (Direct Restore)
                // ==========================================
                if (restoreData) {
                    onProgress(0.55f, "正在准备应用数据目标目录...")
                    RootExecutor.execute("am force-stop ${RootExecutor.quote(packageName)}")

                    // 解析目标 UID 与 GID
                    val targetDataPath = "/data/data/$packageName"
                    var (uid, gid) = RootExecutor.getOwnerGroup(targetDataPath)
                    if (uid <= 0) {
                        uid = try {
                            context.packageManager.getPackageUid(packageName, 0)
                        } catch (_: Throwable) {
                            0
                        }
                        if (uid > 0) {
                            gid = uid
                        } else {
                            val uidRes = RootExecutor.execute("pm list packages -U ${RootExecutor.quote(packageName)}")
                            val parsedUid = Regex("uid:(\\d+)").find(uidRes.outputString)?.groupValues?.get(1)?.toIntOrNull()
                            if (parsedUid != null && parsedUid > 0) {
                                uid = parsedUid
                                gid = parsedUid
                            }
                        }
                    }
                    Timber.d("Target app $packageName resolved UID=$uid GID=$gid")

                    // 1. 直写应用私有数据 /data/data/<pkg>
                    if (hasDataNode || nodes.isEmpty()) {
                        onProgress(0.65f, "正在直写还原应用私有数据...")
                        Timber.i("Directly restoring /data for $packageName to $targetDataPath...")
                        RootExecutor.execute("mkdir -p ${RootExecutor.quote(targetDataPath)}")

                        val dataRestoreRes = resticDriver.restore(
                            repoPath = config.repoPath,
                            password = config.repoPassword,
                            snapshotId = "$snapshotId:data",
                            targetDir = File(targetDataPath),
                            excludePatterns = listOf("lib", "/lib", "lib/*"),
                        )
                        if (!dataRestoreRes.isSuccess) {
                            val errMsg = (dataRestoreRes.err + dataRestoreRes.out).joinToString("\n")
                            Timber.w("Direct restore /data warning: $errMsg")
                        }

                        if (uid > 0 && gid > 0) {
                            RootExecutor.execute("chown -R $uid:$gid ${RootExecutor.quote(targetDataPath)}")
                        }
                        if (config.restoreSelinux) {
                            RootExecutor.restoreSelinux(targetDataPath)
                        }
                    }

                    // 2. 直写设备加密数据 /data/user_de/0/<pkg>
                    if (hasDataDeNode || (nodes.isEmpty() && RootExecutor.execute("test -d /data/user_de/0/$packageName").isSuccess)) {
                        onProgress(0.85f, "正在直写还原设备加密数据...")
                        val targetDePath = "/data/user_de/0/$packageName"
                        Timber.i("Directly restoring /data_de for $packageName to $targetDePath...")
                        RootExecutor.execute("mkdir -p ${RootExecutor.quote(targetDePath)}")

                        val deRestoreRes = resticDriver.restore(
                            repoPath = config.repoPath,
                            password = config.repoPassword,
                            snapshotId = "$snapshotId:data_de",
                            targetDir = File(targetDePath),
                            excludePatterns = listOf("lib", "/lib", "lib/*"),
                        )
                        if (!deRestoreRes.isSuccess) {
                            val errMsg = (deRestoreRes.err + deRestoreRes.out).joinToString("\n")
                            Timber.w("Direct restore /data_de warning: $errMsg")
                        }

                        if (uid > 0 && gid > 0) {
                            RootExecutor.execute("chown -R $uid:$gid ${RootExecutor.quote(targetDePath)}")
                        }
                        if (config.restoreSelinux) {
                            RootExecutor.restoreSelinux(targetDePath)
                        }
                    }
                }

                onProgress(1.0f, "还原完成")
                Timber.i("Successfully direct-restored $packageName from snapshot $snapshotId")
            } finally {
                // 安全兜底清理 APK 临时暂存目录
                RootExecutor.execute("rm -rf ${RootExecutor.quote(tempApkDir.absolutePath)}")
            }
        }
    }
}

