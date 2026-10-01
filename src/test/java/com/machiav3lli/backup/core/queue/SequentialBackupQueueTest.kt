package com.machiav3lli.backup.core.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialBackupQueueTest {

    @Test
    fun testAppRestoreTaskProperties() {
        var callbackInvoked = false
        val task = BackupTask.AppRestoreTask(
            packageName = "com.android.chrome",
            packageLabel = "Chrome",
            snapshotId = "1a2b3c4d",
            restoreApk = true,
            restoreData = true,
            onFinished = { success, _ ->
                callbackInvoked = true
                assertTrue(success)
            }
        )

        assertEquals("Chrome", task.displayLabel)
        assertEquals("com.android.chrome:1a2b3c4d", task.identifier)
        assertEquals(QueueActionType.RESTORE, task.actionType)
        assertTrue(task.restoreApk)
        assertTrue(task.restoreData)

        task.onFinished?.invoke(true, null)
        assertTrue(callbackInvoked)
    }

    @Test
    fun testAppTaskProperties() {
        val task = BackupTask.AppTask(
            packageName = "com.android.chrome",
            packageLabel = "Chrome",
        )

        assertEquals("Chrome", task.displayLabel)
        assertEquals("com.android.chrome", task.identifier)
        assertEquals(QueueActionType.BACKUP, task.actionType)
    }

    @Test
    fun testDirectoryTaskProperties() {
        val task = BackupTask.DirectoryTask(
            directoryId = 1L,
            name = "Documents",
            path = "/sdcard/Documents",
        )

        assertEquals("Documents", task.displayLabel)
        assertEquals("/sdcard/Documents", task.identifier)
        assertEquals(QueueActionType.BACKUP, task.actionType)
    }

    @Test
    fun testQueueProgressDefaultAndActionType() {
        val defaultProgress = QueueProgress()
        assertEquals(false, defaultProgress.isRunning)
        assertEquals(QueueActionType.BACKUP, defaultProgress.actionType)
        assertEquals(QueueStep.IDLE, defaultProgress.step)

        val restoreProgress = defaultProgress.copy(
            isRunning = true,
            actionType = QueueActionType.RESTORE,
            step = QueueStep.RESTORING,
            percent = 0.5f,
            message = "正在安装应用安装包...",
        )
        assertTrue(restoreProgress.isRunning)
        assertEquals(QueueActionType.RESTORE, restoreProgress.actionType)
        assertEquals(QueueStep.RESTORING, restoreProgress.step)
        assertEquals(0.5f, restoreProgress.percent)
        assertEquals("正在安装应用安装包...", restoreProgress.message)
    }
}
