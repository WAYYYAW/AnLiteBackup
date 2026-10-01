package com.anlite.backup.core.engine

import com.anlite.backup.BuildConfig
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class ShellResult(
    val code: Int,
    val out: List<String>,
    val err: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = code == 0
    val outputString: String get() = out.joinToString("\n")
}

/**
 * 核心 Root 权限执行器。
 * 封装 com.github.topjohnwu.libsu:core:6.0.0，提供统一且安全的根权限命令执行接口。
 */
object RootExecutor {

    private val initialized = AtomicBoolean(false)

    init {
        ensureInitialized()
    }

    fun ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(120)
            )
        }
    }

    /**
     * 检查当前应用是否已获得 Root 授权
     */
    fun isRootAvailable(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true || Shell.getShell().isRoot
        } catch (e: Throwable) {
            Timber.e(e, "Error checking root availability")
            false
        }
    }

    /**
     * 同步执行单个 shell 命令
     */
    fun execute(
        command: String,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
    ): ShellResult {
        val fullCommand = wrapCommand(command, env, workingDir)
        return try {
            val result = Shell.cmd(fullCommand).exec()
            if (!result.isSuccess) {
                Timber.w("Shell cmd non-zero ($command -> code ${result.code}): ${result.err.joinToString("\n")}")
            }
            ShellResult(
                code = result.code,
                out = result.out,
                err = result.err,
            )
        } catch (e: Throwable) {
            Timber.e(e, "Failed to execute shell command: $command")
            ShellResult(
                code = -1,
                out = emptyList(),
                err = listOf(e.message ?: "Unknown execution error"),
            )
        }
    }

    /**
     * 协程挂起执行 shell 命令
     */
    suspend fun executeAsync(
        command: String,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
    ): ShellResult = withContext(Dispatchers.IO) {
        execute(command, env, workingDir)
    }

    /**
     * 流式执行 shell 命令，实时向 [onLine] 回调每一行输出
     */
    fun executeStream(
        command: String,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
        onLine: (String) -> Unit,
    ): ShellResult {
        val fullCommand = wrapCommand(command, env, workingDir)
        val streamList = StreamingList(onLine)
        return try {
            val result = Shell.cmd(fullCommand).to(streamList).exec()
            ShellResult(
                code = result.code,
                out = streamList,
                err = result.err,
            )
        } catch (e: Throwable) {
            Timber.e(e, "Failed to stream shell command: $command")
            ShellResult(
                code = -1,
                out = streamList,
                err = listOf(e.message ?: "Unknown streaming error"),
            )
        }
    }

    /**
     * 协程挂起流式执行 shell 命令
     */
    suspend fun executeStreamAsync(
        command: String,
        env: Map<String, String> = emptyMap(),
        workingDir: File? = null,
        onLine: (String) -> Unit,
    ): ShellResult = withContext(Dispatchers.IO) {
        executeStream(command, env, workingDir, onLine)
    }

    /**
     * 获取指定路径的属主 UID 和 GID
     */
    fun getOwnerGroup(path: String): Pair<Int, Int> {
        val result = execute("stat -c \"%u %g\" ${quote(path)}")
        if (result.isSuccess && result.out.isNotEmpty()) {
            val parts = result.out.first().trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val uid = parts[0].toIntOrNull()
                val gid = parts[1].toIntOrNull()
                if (uid != null && gid != null) {
                    return Pair(uid, gid)
                }
            }
        }
        return Pair(0, 0)
    }

    /**
     * 递归恢复指定目录或文件的 SELinux 上下文
     */
    fun restoreSelinux(path: String): Boolean {
        val res = execute("restorecon -R ${quote(path)}")
        return res.isSuccess
    }

    /**
     * 安全转义 shell 参数（POSIX 单引号转义）
     */
    fun quote(arg: String): String {
        if (arg.isEmpty()) return "''"
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    private fun wrapCommand(
        command: String,
        env: Map<String, String>,
        workingDir: File?,
    ): String {
        val sb = StringBuilder()
        if (workingDir != null) {
            sb.append("cd ").append(quote(workingDir.absolutePath)).append(" && ")
        }
        for ((k, v) in env) {
            sb.append(k).append("=").append(quote(v)).append(" ")
        }
        sb.append(command)
        return sb.toString()
    }

    private class StreamingList(
        private val onLine: (String) -> Unit,
    ) : ArrayList<String>() {
        override fun add(element: String): Boolean {
            onLine(element)
            return super.add(element)
        }
    }
}
