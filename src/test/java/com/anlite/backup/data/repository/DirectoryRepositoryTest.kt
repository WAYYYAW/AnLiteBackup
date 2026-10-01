package com.anlite.backup.data.repository

import com.anlite.backup.core.queue.BackupTask
import com.anlite.backup.data.dbs.dao.DirectoryDao
import com.anlite.backup.data.dbs.entity.BackupDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FakeDirectoryDao : DirectoryDao {
    private val data = mutableListOf<BackupDirectory>()
    private val flow = MutableStateFlow<List<BackupDirectory>>(emptyList())
    private var idCounter = 1L

    private fun notifyFlow() {
        flow.value = data.sortedByDescending { it.createdAt }
    }

    override fun observeAll(): Flow<List<BackupDirectory>> = flow.asStateFlow()

    override suspend fun getAll(): List<BackupDirectory> = data.sortedByDescending { it.createdAt }

    override suspend fun getById(id: Long): BackupDirectory? = data.find { it.id == id }

    override suspend fun getByPath(path: String): BackupDirectory? = data.find { it.path == path }

    override suspend fun insert(directory: BackupDirectory): Long {
        val id = if (directory.id == 0L) idCounter++ else directory.id
        val item = directory.copy(id = id)
        data.removeAll { it.id == id || it.path == directory.path }
        data.add(item)
        notifyFlow()
        return id
    }

    override suspend fun update(directory: BackupDirectory) {
        val index = data.indexOfFirst { it.id == directory.id || it.path == directory.path }
        if (index >= 0) {
            data[index] = directory
        } else {
            data.add(directory)
        }
        notifyFlow()
    }

    override suspend fun delete(directory: BackupDirectory) {
        data.removeAll { it.id == directory.id }
        notifyFlow()
    }

    override suspend fun deleteById(id: Long) {
        data.removeAll { it.id == id }
        notifyFlow()
    }
}

class DirectoryRepositoryTest {

    @Test
    fun testNormalizePath() {
        val repo = DirectoryRepository(FakeDirectoryDao())
        assertEquals("/sdcard/Documents", repo.normalizePath("/sdcard/Documents/"))
        assertEquals("/sdcard/Documents", repo.normalizePath("/sdcard/Documents///"))
        assertEquals("/sdcard/Documents", repo.normalizePath("  /sdcard/Documents/  "))
        assertEquals("/", repo.normalizePath("/"))
        assertEquals("/data/data/com.termux/files/home", repo.normalizePath("/data/data/com.termux/files/home/"))
    }

    @Test
    fun testSaveAndRetrieveDirectory() = runBlocking {
        val fakeDao = FakeDirectoryDao()
        val repo = DirectoryRepository(fakeDao)

        val id1 = repo.saveDirectory("My Documents", "/sdcard/Documents/")
        assertTrue(id1 > 0)

        val retrieved = repo.getById(id1)
        assertNotNull(retrieved)
        assertEquals("My Documents", retrieved?.name)
        assertEquals("/sdcard/Documents", retrieved?.path)

        // 重复保存相同路径，应更新名称而非创建重复项
        val id2 = repo.saveDirectory("Renamed Documents", "/sdcard/Documents")
        assertEquals(id1, id2)

        val updated = repo.getByPath("/sdcard/Documents")
        assertNotNull(updated)
        assertEquals("Renamed Documents", updated?.name)

        val all = repo.getAll()
        assertEquals(1, all.size)
    }

    @Test
    fun testDeleteDirectory() = runBlocking {
        val fakeDao = FakeDirectoryDao()
        val repo = DirectoryRepository(fakeDao)

        val id = repo.saveDirectory("Temp", "/sdcard/Temp")
        assertEquals(1, repo.getAll().size)

        repo.deleteById(id)
        assertEquals(0, repo.getAll().size)
        assertNull(repo.getById(id))
    }

    @Test
    fun testPresetsContainTermuxAndDocuments() {
        val repo = DirectoryRepository(FakeDirectoryDao())
        val presets = repo.presets
        assertTrue(presets.isNotEmpty())
        assertTrue(presets.any { it.path.contains("com.termux") })
        assertTrue(presets.any { it.path.contains("Documents") })
    }

    @Test
    fun testBackupTaskPolymorphism() {
        val appTask = BackupTask(
            packageName = "com.example.app",
            packageLabel = "Example App",
            versionName = "1.0",
        )
        assertEquals("Example App", appTask.displayLabel)
        assertEquals("com.example.app", appTask.identifier)
        assertTrue(appTask is BackupTask.AppTask)

        val dirTask = BackupTask.DirectoryTask(
            directoryId = 42L,
            name = "Termux Sandbox",
            path = "/data/data/com.termux/files/home",
        )
        assertEquals("Termux Sandbox", dirTask.displayLabel)
        assertEquals("/data/data/com.termux/files/home", dirTask.identifier)
    }
}
