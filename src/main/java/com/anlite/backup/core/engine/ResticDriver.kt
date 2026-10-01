package com.anlite.backup.core.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File

@Serializable
data class ResticSummary(
    val files_new: Long = 0,
    val files_changed: Long = 0,
    val files_unmodified: Long = 0,
    val dirs_new: Long = 0,
    val dirs_changed: Long = 0,
    val dirs_unmodified: Long = 0,
    val data_blobs: Long = 0,
    val tree_blobs: Long = 0,
    val data_added: Long = 0,
    val data_added_packed: Long = 0,
    val total_files_processed: Long = 0,
    val total_bytes_processed: Long = 0,
)

@Serializable
data class ResticSnapshot(
    val id: String,
    val short_id: String? = null,
    val time: String = "",
    val paths: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val hostname: String? = null,
    val username: String? = null,
    val summary: ResticSummary? = null,
)

@Serializable
data class ResticStats(
    val total_size: Long = 0,
    val total_file_count: Long = 0,
    val total_blob_count: Long = 0,
)

data class BackupResult(
    val isSuccess: Boolean,
    val snapshotId: String? = null,
    val totalBytesProcessed: Long = 0,
    val dataAddedPacked: Long = 0,
    val error: String? = null,
)

/**
 * Restic 纯净底层引擎驱动。
 * 直接调度打包于 APK `nativeLibraryDir` 的 `librestic.so` 二进制。
 *
 * 核心安全与稳定性保证：
 * 1. 密码统一通过环境变量 `RESTIC_PASSWORD` 传递，杜绝在命令行参数泄露；
 * 2. 缓存显式隔离在应用私有缓存目录 `cache/restic`；
 * 3. 自动追加 `--retry-lock 5m` 防止并发瞬时锁冲突；
 * 4. 全输出流通过标准 JSON 协议反序列化解析。
 */
class ResticDriver(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val resticBinaryFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, "librestic.so")

    private val cacheDir: File
        get() = File(context.cacheDir, "restic").also { it.mkdirs() }

    init {
        ensureBinaryExecutable()
    }

    fun ensureBinaryExecutable() {
        if (resticBinaryFile.exists()) {
            resticBinaryFile.setExecutable(true, false)
        }
    }

    private fun getEnvironment(repoPath: String, password: String): Map<String, String> {
        return mapOf(
            "RESTIC_REPOSITORY" to repoPath,
            "RESTIC_PASSWORD" to password,
            "RESTIC_CACHE_DIR" to cacheDir.absolutePath,
            "XDG_CACHE_HOME" to cacheDir.absolutePath,
            "HOME" to "/data/local/tmp",
            "TMPDIR" to "/data/local/tmp",
        )
    }

    private fun buildCommandLine(args: List<String>): String {
        val binPath = RootExecutor.quote(resticBinaryFile.absolutePath)
        val argStrings = args.joinToString(" ") { RootExecutor.quote(it) }
        return "$binPath --retry-lock 5m $argStrings"
    }

    /**
     * 运行 restic 命令行
     */
    suspend fun executeRestic(
        repoPath: String,
        password: String,
        args: List<String>,
        workingDir: File? = null,
        onLine: ((String) -> Unit)? = null,
    ): ShellResult = withContext(Dispatchers.IO) {
        val env = getEnvironment(repoPath, password)
        val command = buildCommandLine(args)
        if (onLine != null) {
            RootExecutor.executeStream(command, env, workingDir, onLine)
        } else {
            RootExecutor.execute(command, env, workingDir)
        }
    }

    /**
     * 初始化 restic 仓库（幂等操作：若仓库 config 存在则直接成功返回）
     */
    suspend fun initRepo(repoPath: String, password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            RootExecutor.execute("mkdir -p ${RootExecutor.quote(repoPath)}")
            val testConfig = RootExecutor.execute("test -f ${RootExecutor.quote("$repoPath/config")}")
            if (testConfig.isSuccess) {
                Timber.d("Restic repository config already exists: $repoPath")
                return@runCatching true
            }

            val result = executeRestic(repoPath, password, listOf("init", "--json"))
            if (!result.isSuccess) {
                val errOutput = (result.err + result.out).joinToString("\n")
                if (!errOutput.contains("already initialized", ignoreCase = true) &&
                    !errOutput.contains("config file already exists", ignoreCase = true)
                ) {
                    throw IllegalStateException("Failed to initialize restic repository: $errOutput")
                }
            }
            Timber.i("Initialized restic repository at: $repoPath")
            true
        }
    }

    /**
     * 执行应用备份
     */
    suspend fun backup(
        repoPath: String,
        password: String,
        stagingDir: File,
        tags: List<String>,
        onProgress: ((percent: Float, message: String) -> Unit)? = null,
    ): BackupResult = withContext(Dispatchers.IO) {
        initRepo(repoPath, password).onFailure { e ->
            return@withContext BackupResult(
                isSuccess = false,
                error = "Repository initialization failed: ${e.message}",
            )
        }

        val args = mutableListOf("backup", ".", "--json")
        for (tag in tags) {
            args.add("--tag")
            args.add(tag)
        }

        var snapshotId: String? = null
        var totalBytesProcessed = 0L
        var dataAddedPacked = 0L

        val result = executeRestic(
            repoPath = repoPath,
            password = password,
            args = args,
            workingDir = stagingDir,
        ) { line ->
            try {
                if (line.trim().startsWith("{")) {
                    val element = json.parseToJsonElement(line).jsonObject
                    val messageType = element["message_type"]?.jsonPrimitive?.content
                    if (messageType == "status") {
                        val percent = element["percent_done"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                        val bytesDone = element["bytes_done"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        val totalBytes = element["total_bytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        val mbDone = bytesDone / (1024 * 1024)
                        val mbTotal = totalBytes / (1024 * 1024)
                        onProgress?.invoke(percent, "$mbDone MB / $mbTotal MB")
                    } else if (messageType == "summary") {
                        snapshotId = element["snapshot_id"]?.jsonPrimitive?.content
                        totalBytesProcessed = element["total_bytes_processed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                        dataAddedPacked = element["data_added_packed"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    }
                }
            } catch (_: Throwable) {
                // 忽略单行 JSON 格式解析偶发异常
            }
        }

        if (!result.isSuccess || snapshotId.isNullOrEmpty()) {
            val errorMsg = (result.err + result.out).joinToString("\n")
            Timber.e("Restic backup failed: $errorMsg")
            return@withContext BackupResult(
                isSuccess = false,
                error = errorMsg,
            )
        }

        BackupResult(
            isSuccess = true,
            snapshotId = snapshotId,
            totalBytesProcessed = totalBytesProcessed,
            dataAddedPacked = dataAddedPacked,
        )
    }

    /**
     * 还原指定快照（或快照子路径 snapshotId:subfolder）至目标目录。
     * 支持包含路径 includePaths 与排除通配规则 excludePatterns（如 `-e lib` 保护原生软链）。
     */
    suspend fun restore(
        repoPath: String,
        password: String,
        snapshotId: String,
        targetDir: File,
        includePaths: List<String> = emptyList(),
        excludePatterns: List<String> = emptyList(),
    ): ShellResult = withContext(Dispatchers.IO) {
        val args = mutableListOf(
            "restore",
            snapshotId,
            "--target",
            targetDir.absolutePath,
        )
        for (path in includePaths) {
            args.add("--include")
            args.add(path)
        }
        for (pattern in excludePatterns) {
            args.add("-e")
            args.add(pattern)
        }
        executeRestic(repoPath, password, args)
    }

    /**
     * 检查快照中是否存在指定子节点（如 apk, data, data_de）。
     */
    suspend fun listSnapshotNodes(
        repoPath: String,
        password: String,
        snapshotId: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val result = executeRestic(repoPath, password, listOf("ls", snapshotId, "--json"))
        if (!result.isSuccess) {
            return@withContext emptyList()
        }
        val paths = mutableListOf<String>()
        for (line in result.out) {
            val trimmed = line.trim()
            if (trimmed.startsWith("{")) {
                try {
                    val elem = json.parseToJsonElement(trimmed).jsonObject
                    val path = elem["path"]?.jsonPrimitive?.content
                    if (!path.isNullOrEmpty()) {
                        paths.add(path)
                    }
                } catch (_: Throwable) {
                }
            }
        }
        paths
    }

    /**
     * 提取快照中的单个文件并写入标准输出
     */
    suspend fun dump(
        repoPath: String,
        password: String,
        snapshotId: String,
        filePathInSnapshot: String,
        destinationFile: File,
    ): ShellResult = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        val env = getEnvironment(repoPath, password)
        val cmd = "${buildCommandLine(listOf("dump", snapshotId, filePathInSnapshot))} > ${RootExecutor.quote(destinationFile.absolutePath)}"
        RootExecutor.executeAsync(cmd, env)
    }

    /**
     * 删除指定快照
     */
    suspend fun forget(
        repoPath: String,
        password: String,
        snapshotId: String,
    ): ShellResult = withContext(Dispatchers.IO) {
        executeRestic(repoPath, password, listOf("forget", snapshotId, "--json"))
    }

    /**
     * 按照标签保留最近 N 个版本
     */
    suspend fun forgetKeep(
        repoPath: String,
        password: String,
        tag: String,
        keepLast: Int,
    ): ShellResult = withContext(Dispatchers.IO) {
        executeRestic(
            repoPath,
            password,
            listOf("forget", "--tag", tag, "--keep-last", keepLast.toString(), "--json")
        )
    }

    /**
     * 清理无引用数据块（碎片整理 / 空间释放）
     */
    suspend fun prune(
        repoPath: String,
        password: String,
        onLine: ((String) -> Unit)? = null,
    ): ShellResult = withContext(Dispatchers.IO) {
        executeRestic(repoPath, password, listOf("prune", "--json"), onLine = onLine)
    }

    /**
     * 列出仓库中的快照列表
     */
    suspend fun listSnapshots(
        repoPath: String,
        password: String,
        tag: String? = null,
    ): List<ResticSnapshot> = withContext(Dispatchers.IO) {
        val args = mutableListOf("snapshots", "--json")
        if (!tag.isNullOrBlank()) {
            args.add("--tag")
            args.add(tag)
        }
        val result = executeRestic(repoPath, password, args)
        if (!result.isSuccess) {
            Timber.e("Failed to list snapshots: ${result.err.joinToString("\n")}")
            return@withContext emptyList()
        }
        val output = result.out.joinToString("\n").trim()
        if (output.isEmpty() || output == "null") {
            return@withContext emptyList()
        }
        val jsonContent = if (output.contains("[") && output.contains("]")) {
            output.substring(output.indexOf("["), output.lastIndexOf("]") + 1)
        } else {
            output
        }
        try {
            json.decodeFromString<List<ResticSnapshot>>(jsonContent)
        } catch (e: Throwable) {
            Timber.e(e, "Error parsing restic snapshots JSON: $output")
            emptyList()
        }
    }

    /**
     * 获取仓库占用空间统计
     */
    suspend fun getStats(
        repoPath: String,
        password: String,
    ): ResticStats? = withContext(Dispatchers.IO) {
        val result = executeRestic(repoPath, password, listOf("stats", "--mode", "raw-data", "--json"))
        if (!result.isSuccess) {
            return@withContext null
        }
        val output = result.out.joinToString("\n").trim()
        val jsonContent = if (output.contains("{") && output.contains("}")) {
            output.substring(output.indexOf("{"), output.lastIndexOf("}") + 1)
        } else {
            output
        }
        try {
            json.decodeFromString<ResticStats>(jsonContent)
        } catch (e: Throwable) {
            Timber.e(e, "Error parsing restic stats JSON: $output")
            null
        }
    }
}
