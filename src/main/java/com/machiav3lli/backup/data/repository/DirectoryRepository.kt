package com.machiav3lli.backup.data.repository

import com.machiav3lli.backup.core.engine.RootExecutor
import com.machiav3lli.backup.data.dbs.dao.DirectoryDao
import com.machiav3lli.backup.data.dbs.entity.BackupDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class DirectoryValidationResult(
    val exists: Boolean,
    val isDirectory: Boolean,
    val isReadable: Boolean,
    val sizeBytes: Long = 0L,
    val errorMessage: String? = null,
)

data class PresetDirectory(
    val name: String,
    val path: String,
    val description: String,
)

class DirectoryRepository(
    private val directoryDao: DirectoryDao,
) {
    val presets = listOf(
        PresetDirectory(
            name = "Termux Home",
            path = "/data/data/com.termux/files/home",
            description = "Termux 终端沙盒主目录与配置文件",
        ),
        PresetDirectory(
            name = "Documents",
            path = "/sdcard/Documents",
            description = "共享存储文档目录",
        ),
        PresetDirectory(
            name = "Download",
            path = "/sdcard/Download",
            description = "共享存储下载目录",
        ),
        PresetDirectory(
            name = "DCIM",
            path = "/sdcard/DCIM",
            description = "相机照片与截图媒体目录",
        ),
        PresetDirectory(
            name = "Pictures",
            path = "/sdcard/Pictures",
            description = "图片保存目录",
        ),
    )

    fun observeDirectories(): Flow<List<BackupDirectory>> = directoryDao.observeAll()

    suspend fun getAll(): List<BackupDirectory> = directoryDao.getAll()

    suspend fun getById(id: Long): BackupDirectory? = directoryDao.getById(id)

    suspend fun getByPath(path: String): BackupDirectory? = directoryDao.getByPath(normalizePath(path))

    suspend fun saveDirectory(name: String, rawPath: String): Long {
        val path = normalizePath(rawPath)
        val existing = directoryDao.getByPath(path)
        return if (existing != null) {
            directoryDao.update(existing.copy(name = name))
            existing.id
        } else {
            directoryDao.insert(
                BackupDirectory(
                    name = name,
                    path = path,
                )
            )
        }
    }

    suspend fun update(directory: BackupDirectory) {
        directoryDao.update(directory)
    }

    suspend fun delete(directory: BackupDirectory) {
        directoryDao.delete(directory)
    }

    suspend fun deleteById(id: Long) {
        directoryDao.deleteById(id)
    }

    suspend fun validatePath(rawPath: String): DirectoryValidationResult = withContext(Dispatchers.IO) {
        val path = normalizePath(rawPath)
        if (path.isEmpty()) {
            return@withContext DirectoryValidationResult(
                exists = false,
                isDirectory = false,
                isReadable = false,
                errorMessage = "路径不能为空",
            )
        }

        val quoted = RootExecutor.quote(path)
        val checkDir = RootExecutor.execute("test -d $quoted")
        if (!checkDir.isSuccess) {
            val checkExists = RootExecutor.execute("test -e $quoted")
            return@withContext if (checkExists.isSuccess) {
                DirectoryValidationResult(
                    exists = true,
                    isDirectory = false,
                    isReadable = false,
                    errorMessage = "目标路径不是有效目录",
                )
            } else {
                DirectoryValidationResult(
                    exists = false,
                    isDirectory = false,
                    isReadable = false,
                    errorMessage = "目录不存在",
                )
            }
        }

        val checkReadable = RootExecutor.execute("test -r $quoted")
        val isReadable = checkReadable.isSuccess

        // 计算大概占用体积 (du -sk)
        val duRes = RootExecutor.execute("du -sk $quoted")
        val sizeKb = duRes.out.firstOrNull()?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.toLongOrNull() ?: 0L
        val sizeBytes = sizeKb * 1024L

        DirectoryValidationResult(
            exists = true,
            isDirectory = true,
            isReadable = isReadable,
            sizeBytes = sizeBytes,
            errorMessage = if (!isReadable) "目录无读取权限" else null,
        )
    }

    fun normalizePath(path: String): String {
        var p = path.trim()
        while (p.length > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length - 1)
        }
        return p
    }
}
