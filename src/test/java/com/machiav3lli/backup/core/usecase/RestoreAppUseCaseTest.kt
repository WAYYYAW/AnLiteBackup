package com.machiav3lli.backup.core.usecase

import com.machiav3lli.backup.core.queue.BackupTask
import com.machiav3lli.backup.core.queue.QueueActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreAppUseCaseTest {

    @Test
    fun testAppRestoreTaskTaskConfiguration() {
        val task = BackupTask.AppRestoreTask(
            packageName = "org.mozilla.firefox",
            packageLabel = "Firefox",
            snapshotId = "aabbcc112233",
            restoreApk = true,
            restoreData = true,
        )

        assertEquals("Firefox", task.displayLabel)
        assertEquals("org.mozilla.firefox:aabbcc112233", task.identifier)
        assertEquals(QueueActionType.RESTORE, task.actionType)
        assertTrue(task.restoreApk)
        assertTrue(task.restoreData)
    }

    @Test
    fun testAppRestoreTaskDataOnlyConfiguration() {
        val task = BackupTask.AppRestoreTask(
            packageName = "org.mozilla.firefox",
            packageLabel = "Firefox",
            snapshotId = "aabbcc112233",
            restoreApk = false,
            restoreData = true,
        )

        assertFalse(task.restoreApk)
        assertTrue(task.restoreData)
    }

    @Test
    fun testAppRestoreTaskApkOnlyConfiguration() {
        val task = BackupTask.AppRestoreTask(
            packageName = "org.mozilla.firefox",
            packageLabel = "Firefox",
            snapshotId = "aabbcc112233",
            restoreApk = true,
            restoreData = false,
        )

        assertTrue(task.restoreApk)
        assertFalse(task.restoreData)
    }
}
