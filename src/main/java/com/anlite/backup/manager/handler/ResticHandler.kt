package com.anlite.backup.manager.handler

import android.content.Context
import com.anlite.backup.data.entity.StorageFile
import com.anlite.backup.manager.handler.ShellHandler.Companion.quote
import com.anlite.backup.manager.handler.ShellHandler.Companion.runAsRoot
import com.anlite.backup.manager.handler.ShellHandler.Companion.runAsRootPipeOutCollectErr
import com.anlite.backup.manager.handler.ShellHandler.Companion.utilBoxQ
import com.anlite.backup.utils.SystemUtils
import com.topjohnwu.superuser.Shell
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.util.UUID

/**
 * Restic 命令执行封装器。
 *
 * 所有底层调用均通过 [ShellHandler] 以 root 权限执行，密码通过临时权限受限的 `--password-file`
 * 安全传递（绝不硬编码于命令行参数中），缓存统一指向应用私有缓存目录。
 */
object ResticHandler {

    private const val DEFAULT_REPO_PASSWORD = "neobackup-default-restic-password"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val snapshotIdRegex = Regex(""""snapshot_id"\s*:\s*"([a-fA-F0-9]+)"""")
    private val totalBytesProcessedRegex = Regex(""""total_bytes_processed"\s*:\s*(\d+)""")
    private val dataAddedPackedRegex = Regex(""""data_added_packed"\s*:\s*(\d+)""")

    /**
     * 获取打包在 APK `nativeLibraryDir` 中的 `librestic.so` 可执行文件，并确保其具备可执行权限。
     */
    fun getResticBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "librestic.so").also {
            it.setExecutable(true, true)
        }

    /**
     * 获取全局 restic 仓库在文件系统中的绝对路径。
     * 若 [backupRoot] 为未映射 shadow 的 SAF `content://` URI，则自动解析其真实文件系统路径。
     */
    fun getRepoPath(backupRoot: StorageFile): String {
        val rawPath = backupRoot.path ?: ""
        val resolvedBase = if (rawPath.startsWith("/tree/") || rawPath.isEmpty()) {
            resolveSafRootPath(backupRoot) ?: rawPath
        } else {
            rawPath
        }
        return "${resolvedBase.trimEnd('/')}/restic-repo"
    }

    /**
     * 获取并创建 App 私有 restic 缓存目录。
     */
    private fun getCacheDir(context: Context): File =
        File(context.cacheDir, "restic").also { it.mkdirs() }

    /**
     * 初始化 restic 仓库（幂等操作：若仓库 `config` 已存在则直接返回成功）。
     */
    fun initRepo(context: Context, backupRoot: StorageFile, password: String): Result<Unit> =
        runCatching {
            val repoPath = getRepoPath(backupRoot)
            runAsRoot("$utilBoxQ mkdir -p ${quote(repoPath)}", throwFail = false)

            val configExists = runAsRoot(
                "$utilBoxQ test -f ${quote("$repoPath/config")}",
                throwFail = false
            ).isSuccess
            if (configExists) {
                return@runCatching
            }

            val result = runResticAsRoot(context, backupRoot, password, "init")
            if (!result.isSuccess) {
                val errOutput = (result.err + result.out).joinToString("\n")
                if (!errOutput.contains("already initialized", ignoreCase = true) &&
                    !errOutput.contains("config file already exists", ignoreCase = true)
                ) {
                    throw IllegalStateException("restic init failed (code=${result.code}): $errOutput")
                }
            }
        }

    /**
     * 执行应用备份。
     *
     * [stagingDir] 是已通过 [BindMountManager] 组织好的虚拟路径树。
     * 执行时切换工作目录至 [stagingDir] 并备份 `.`，使快照内的路径结构保持为 `/apk`、`/data` 等根目录子树。
     *
     * @return 成功时返回生成的 snapshot ID，失败时返回 `null`。
     */
    fun backup(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        packageName: String,
        backupDate: String,
        stagingDir: File,
        onSummary: ((totalBytesProcessed: Long, dataAddedPacked: Long) -> Unit)? = null,
    ): String? {
        initRepo(context, backupRoot, password).onFailure { e ->
            Timber.e(e, "Failed to initialize restic repository before backup")
            return null
        }

        val result = runResticAsRootInDir(
            context = context,
            backupRoot = backupRoot,
            password = password,
            workingDir = stagingDir,
            "backup",
            ".",
            "--tag",
            packageName,
            "--tag",
            backupDate,
            "--json",
        )

        if (!result.isSuccess) {
            Timber.e(
                "restic backup failed for $packageName (code=${result.code}):\n" +
                    "stderr: ${result.err.joinToString("\n")}\n" +
                    "stdout: ${result.out.joinToString("\n")}"
            )
            return null
        }

        var snapshotId: String? = null
        var totalBytesProcessed = 0L
        var dataAddedPacked = 0L

        for (line in result.out.asReversed()) {
            if (totalBytesProcessed == 0L) {
                totalBytesProcessedRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
                    totalBytesProcessed = it
                }
            }
            if (dataAddedPacked == 0L) {
                dataAddedPackedRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
                    dataAddedPacked = it
                }
            }
            if (snapshotId == null) {
                val match = snapshotIdRegex.find(line)
                if (match != null) {
                    snapshotId = match.groupValues[1]
                }
            }
            if (snapshotId != null && (totalBytesProcessed > 0L || dataAddedPacked > 0L)) {
                break
            }
        }

        onSummary?.invoke(totalBytesProcessed, dataAddedPacked)

        if (snapshotId != null) {
            return snapshotId
        }

        // Fallback: query latest snapshot matching package tag
        return listSnapshots(context, backupRoot, password, tag = packageName)
            .lastOrNull()
            ?.id
    }

    /**
     * 精准恢复单个数据分区子树。
     * 使用 `--include /<dataType>` 仅提取对应目录到 [targetDir]。
     */
    fun restoreDataType(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        snapshotId: String,
        dataType: String,
        targetDir: File,
    ): Boolean {
        runAsRoot("$utilBoxQ mkdir -p ${quote(targetDir.absolutePath)}", throwFail = false)
        val includePath = if (dataType.startsWith("/")) dataType else "/$dataType"
        val result = runResticAsRoot(
            context,
            backupRoot,
            password,
            "restore",
            snapshotId,
            "--include",
            includePath,
            "--target",
            targetDir.absolutePath,
        )
        if (!result.isSuccess) {
            Timber.e(
                "restic restore failed for snapshot=$snapshotId dataType=$dataType (code=${result.code}): " +
                    result.err.joinToString("\n")
            )
        }
        return result.isSuccess
    }

    /**
     * 流式提取快照中的单个文件（例如 `/apk/base.apk`），并将其内容写入 [output]。
     */
    fun dumpFile(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        snapshotId: String,
        filePath: String,
        output: OutputStream,
    ): Boolean {
        val normalizedPath = if (filePath.startsWith("/")) filePath else "/$filePath"
        val repoPath = getRepoPath(backupRoot)
        val resticBin = getResticBinary(context)
        val envPrefix = buildEnvExportPrefix(context, repoPath)

        return withPasswordFile(context, password) { pwdFile ->
            val cmd = buildString {
                append(envPrefix)
                append(" ")
                append(quote(resticBin.absolutePath))
                append(" dump --quiet --retry-lock 5m --password-file ")
                append(quote(pwdFile.absolutePath))
                append(" ")
                append(quote(snapshotId))
                append(" ")
                append(quote(normalizedPath))
                append("\n")
            }
            val (code, err) = runAsRootPipeOutCollectErr(output, cmd)
            if (code != 0) {
                Timber.e("restic dump failed for $normalizedPath in $snapshotId (code=$code): $err")
            }
            code == 0
        }
    }

    /**
     * 列出快照内指定路径下的文件/目录条目（用于获取 APK 文件列表及大小）。
     */
    fun listSnapshotPath(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        snapshotId: String,
        path: String,
    ): List<ResticLsEntry> {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val result = runResticAsRoot(
            context,
            backupRoot,
            password,
            "ls",
            "--json",
            snapshotId,
            normalizedPath,
        )
        if (!result.isSuccess) {
            Timber.w(
                "restic ls failed for snapshot=$snapshotId path=$normalizedPath (code=${result.code}): " +
                    result.err.joinToString("\n")
            )
            return emptyList()
        }

        return result.out.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) return@mapNotNull null
            if (trimmed.contains("\"struct_type\":\"snapshot\"")) return@mapNotNull null
            runCatching {
                json.decodeFromString<ResticLsEntry>(trimmed)
            }.getOrNull()
        }
    }

    /**
     * 查询仓库中的快照列表，可选按 [tag] 过滤。
     */
    fun listSnapshots(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        tag: String? = null,
    ): List<ResticSnapshot> {
        val args = buildList {
            add("snapshots")
            add("--json")
            if (!tag.isNullOrEmpty()) {
                add("--tag")
                add(tag)
            }
        }
        val result = runResticAsRoot(context, backupRoot, password, *args.toTypedArray())
        if (!result.isSuccess) {
            Timber.w("restic snapshots failed (code=${result.code}): ${result.err.joinToString("\n")}")
            return emptyList()
        }
        val jsonPayload = result.out.joinToString("\n").trim()
        if (jsonPayload.isEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ResticSnapshot>>(jsonPayload)
        }.getOrElse { e ->
            Timber.w(e, "Failed to parse restic snapshots JSON")
            emptyList()
        }
    }

    /**
     * 清理指定包名的旧快照，仅保留最近的 [keepLast] 个快照（不执行 prune）。
     */
    fun forget(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        packageName: String,
        keepLast: Int,
    ): Boolean {
        if (keepLast <= 0) return true
        val result = runResticAsRoot(
            context,
            backupRoot,
            password,
            "forget",
            "--tag",
            packageName,
            "--group-by",
            "host",
            "--keep-last",
            keepLast.toString(),
            "--json",
        )
        if (!result.isSuccess) {
            Timber.e(
                "restic forget failed for $packageName (keepLast=$keepLast, code=${result.code}): " +
                    result.err.joinToString("\n")
            )
        }
        return result.isSuccess
    }

    /**
     * 执行 `restic prune` 回收未引用数据块并释放磁盘空间。
     */
    fun prune(context: Context, backupRoot: StorageFile, password: String): Boolean {
        val result = runResticAsRoot(context, backupRoot, password, "prune")
        if (!result.isSuccess) {
            Timber.e("restic prune failed (code=${result.code}): ${result.err.joinToString("\n")}")
        }
        return result.isSuccess
    }

    // ──────────────── 内部工具方法 ────────────────

    private fun buildEnv(context: Context, repoPath: String): Map<String, String> = mapOf(
        "RESTIC_REPOSITORY" to repoPath,
        "RESTIC_CACHE_DIR" to getCacheDir(context).absolutePath,
        "HOME" to context.cacheDir.absolutePath,
        "TMPDIR" to getCacheDir(context).absolutePath,
    )

    private fun buildEnvExportPrefix(context: Context, repoPath: String): String =
        buildEnv(context, repoPath)
            .entries
            .joinToString(" ") { (k, v) -> "$k=${quote(v)}" }

    private fun effectivePassword(password: String): String =
        password.ifEmpty { DEFAULT_REPO_PASSWORD }

    /**
     * 在 App 私有缓存目录中创建临时密码文件（权限 600），执行 [block] 后确保删除。
     * 避免密码出现在命令行参数或日志中。
     */
    private inline fun <T> withPasswordFile(
        context: Context,
        password: String,
        block: (File) -> T,
    ): T {
        val cacheDir = getCacheDir(context)
        val pwdFile = File(cacheDir, ".pwd_${UUID.randomUUID()}")
        try {
            pwdFile.writeText(effectivePassword(password))
            pwdFile.setReadable(false, false)
            pwdFile.setReadable(true, true)
            pwdFile.setWritable(false, false)
            pwdFile.setWritable(true, true)
            return block(pwdFile)
        } finally {
            runCatching { pwdFile.delete() }
        }
    }

    private fun runResticAsRoot(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        vararg args: String,
    ): Shell.Result = runResticAsRootInDir(
        context = context,
        backupRoot = backupRoot,
        password = password,
        workingDir = null,
        args = args,
    )

    private fun runResticAsRootInDir(
        context: Context,
        backupRoot: StorageFile,
        password: String,
        workingDir: File?,
        vararg args: String,
    ): Shell.Result {
        val repoPath = getRepoPath(backupRoot)
        val resticBin = getResticBinary(context)
        val envPrefix = buildEnvExportPrefix(context, repoPath)

        return withPasswordFile(context, password) { pwdFile ->
            val cmd = buildString {
                if (workingDir != null) {
                    append("cd ")
                    append(quote(workingDir.absolutePath))
                    append(" && ")
                }
                append(envPrefix)
                append(" ")
                append(quote(resticBin.absolutePath))
                append(" --retry-lock 5m --password-file ")
                append(quote(pwdFile.absolutePath))
                args.forEach { arg ->
                    append(" ")
                    append(quote(arg))
                }
            }
            runAsRoot(cmd, throwFail = false)
        }
    }

    private fun resolveSafRootPath(backupRoot: StorageFile): String? {
        val uri = backupRoot.uri ?: return null
        if (uri.scheme == "file" || uri.scheme == null) {
            return uri.path
        }
        return runCatching {
            val last = URLDecoder.decode(uri.encodedPath?.split("/")?.last() ?: "", "UTF-8")
            if (!last.contains(":")) return@runCatching null
            val (storage, subPath) = last.split(":", limit = 2)
            val userProvider = (uri.authority ?: "").split("@", limit = 2)
            val user = if (userProvider.size > 1) {
                userProvider[0]
            } else {
                ShellCommands.currentProfile.toString()
            }
            val storageNorm = if (storage == "primary") "emulated/$user" else storage
            SystemUtils.getShadowPath(user, storageNorm, subPath)?.absolutePath
                ?: if (storage == "primary") {
                    "/storage/emulated/$user/$subPath"
                } else {
                    "/storage/$storage/$subPath"
                }
        }.getOrNull()
    }
}
