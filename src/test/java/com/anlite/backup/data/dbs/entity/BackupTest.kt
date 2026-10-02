package com.anlite.backup.data.dbs.entity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackupTest {

    private val sampleDate = LocalDateTime.of(2026, 9, 29, 16, 30, 0)

    private fun createBackup(
        compressionType: String?,
        resticSnapshotId: String?,
        cipherType: String? = null,
        iv: ByteArray? = null,
    ): Backup = Backup(
        backupVersionCode = 8003,
        packageName = "com.example.app",
        packageLabel = "Example App",
        versionName = "1.0.0",
        versionCode = 1,
        profileId = 0,
        sourceDir = "/data/app/com.example.app/base.apk",
        splitSourceDirs = arrayOf(),
        isSystem = false,
        backupDate = sampleDate,
        hasApk = true,
        hasAppData = true,
        hasDevicesProtectedData = false,
        hasExternalData = false,
        hasObbData = false,
        hasMediaData = false,
        compressionType = compressionType,
        cipherType = cipherType,
        iv = iv,
        cpuArch = "arm64-v8a",
        permissions = listOf("android.permission.INTERNET"),
        size = 1024L,
        note = "test backup",
        persistent = false,
        resticSnapshotId = resticSnapshotId,
    )

    @Test
    fun testIsResticBackup() {
        val validRestic = createBackup(compressionType = "restic", resticSnapshotId = "a1b2c3d4e5f6")
        assertTrue(validRestic.isResticBackup)
        assertFalse(validRestic.isLegacyBackup)

        val missingSnapshotId = createBackup(compressionType = "restic", resticSnapshotId = null)
        assertFalse(missingSnapshotId.isResticBackup)

        val emptySnapshotId = createBackup(compressionType = "restic", resticSnapshotId = "")
        assertFalse(emptySnapshotId.isResticBackup)

        val wrongCompression = createBackup(compressionType = "zst", resticSnapshotId = "a1b2c3d4e5f6")
        assertFalse(wrongCompression.isResticBackup)
    }

    @Test
    fun testIsLegacyBackup() {
        for (type in listOf("gz", "zst", "no", null)) {
            val legacyNull = createBackup(compressionType = type, resticSnapshotId = null)
            assertTrue("Expected legacy for compressionType=$type, snapshotId=null", legacyNull.isLegacyBackup)
            assertFalse(legacyNull.isResticBackup)

            val legacyEmpty = createBackup(compressionType = type, resticSnapshotId = "")
            assertTrue("Expected legacy for compressionType=$type, snapshotId=''", legacyEmpty.isLegacyBackup)
            assertFalse(legacyEmpty.isResticBackup)
        }
    }

    @Test
    fun testJsonSerializationRoundTrip() {
        val original = createBackup(
            compressionType = "restic",
            resticSnapshotId = "deadbeef12345678",
            cipherType = null,
            iv = null,
        )
        val json = Json { prettyPrint = true }
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Backup>(encoded)

        assertEquals(original, decoded)
        assertEquals("deadbeef12345678", decoded.resticSnapshotId)
        assertTrue(decoded.isResticBackup)
        assertFalse(decoded.isLegacyBackup)
    }


    @Test
    fun testLegacyJsonDeserializationCompatibility() {
        val legacyJson = """
            {
                "backupVersionCode": 8003,
                "packageName": "com.example.legacy",
                "packageLabel": "Legacy App",
                "versionName": "2.0",
                "versionCode": 2,
                "splitSourceDirs": [],
                "backupDate": "2026-09-29T16:30:00",
                "hasApk": true,
                "hasAppData": true,
                "hasDevicesProtectedData": false,
                "hasExternalData": false,
                "compressionType": "zst",
                "cpuArch": "arm64-v8a",
                "size": 2048,
                "note": "old format"
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<Backup>(legacyJson)
        assertNull(decoded.resticSnapshotId)
        assertTrue(decoded.isLegacyBackup)
        assertFalse(decoded.isResticBackup)
    }
}
