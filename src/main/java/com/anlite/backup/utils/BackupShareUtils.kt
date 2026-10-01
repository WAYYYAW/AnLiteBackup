/*
 * AnLite Backup: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.anlite.backup.utils

import android.content.Context
import android.content.Intent
import com.anlite.backup.AnLiteApp
import com.anlite.backup.PROP_NAME
import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.entity.StorageFile
import com.anlite.backup.data.entity.uriFromFile
import com.anlite.backup.manager.handler.LogsHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object BackupShareUtils {
    private const val SHARE_CACHE_DIR = "backup_share"
    private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val MAX_CACHE_FILES = 10
    private const val TAR_BLOCK_SIZE = 512

    private fun getShareCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, SHARE_CACHE_DIR)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }

    fun cleanupShareCache(context: Context) {
        try {
            val cacheDir = getShareCacheDir(context)
            if (!cacheDir.exists()) return

            val files = cacheDir.listFiles() ?: return
            val now = System.currentTimeMillis()

            // Remove old cached backups
            files.forEach { file ->
                if (now - file.lastModified() > MAX_CACHE_AGE_MS) {
                    Timber.d("Deleting old share cached backup: ${file.name}")
                    file.delete()
                }
            }

            // Limit number of cached backups
            val remainingFiles = cacheDir.listFiles() ?: return
            if (remainingFiles.size > MAX_CACHE_FILES) {
                remainingFiles
                    .sortedByDescending { it.lastModified() }
                    .drop(MAX_CACHE_FILES)
                    .forEach { file ->
                        Timber.d("Deleting excess number of cached backup: ${file.name}")
                        file.delete()
                    }
            }
        } catch (e: Exception) {
            Timber.e("Error cleaning up share cache: ${e.message}")
            LogsHandler.logException(e, backTrace = false)
        }
    }

    suspend fun createBackupArchive(backup: Backup, context: Context): File? =
        withContext(Dispatchers.IO) {
            try {
                cleanupShareCache(context)

                val cacheDir = getShareCacheDir(context)
                val dateStr = backup.backupDate.format(BACKUP_DATE_TIME_FORMATTER)
                val archiveFileName = "${backup.packageName}_${dateStr}.tar"
                val archiveFile = File(cacheDir, archiveFileName)
                if (archiveFile.exists()) archiveFile.delete()

                Timber.i("Creating backup archive: ${archiveFile.absolutePath}")
                BufferedOutputStream(FileOutputStream(archiveFile)).use { tarOut ->
                    val backupDirName = backup.dir?.name ?: "backup"

                    backup.file?.let { propsFile ->
                        addFileToTar(tarOut, propsFile, propsFile.name ?: "backup.$PROP_NAME")
                    }
                    backup.dir?.let { backupDir ->
                        val files = backupDir.listFiles()
                        files.forEach { file ->
                            if (file.isFile) {
                                val entryName = "$backupDirName/${file.name ?: "unknown"}"
                                addFileToTar(tarOut, file, entryName)
                            }
                        }
                    }
                    // Write two 512-byte zero blocks marking end of tar archive
                    tarOut.write(ByteArray(TAR_BLOCK_SIZE * 2))
                }

                Timber.i("Successfully created backup archive: ${archiveFile.absolutePath} (${archiveFile.length()} bytes)")
                archiveFile
            } catch (e: Exception) {
                Timber.e("Error creating backup archive: ${e.message}")
                LogsHandler.logException(e, backTrace = true)
                null
            }
        }

    private fun addFileToTar(
        tarOut: OutputStream,
        storageFile: StorageFile,
        entryName: String,
    ) {
        try {
            val fileSize = storageFile.size
            val header = buildUstarHeader(entryName, fileSize)
            tarOut.write(header)

            var written = 0L
            storageFile.inputStream()?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (written < fileSize) {
                    val toRead = minOf(buffer.size.toLong(), fileSize - written).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    tarOut.write(buffer, 0, read)
                    written += read
                }
            }

            val remainder = (written % TAR_BLOCK_SIZE).toInt()
            if (remainder != 0) {
                tarOut.write(ByteArray(TAR_BLOCK_SIZE - remainder))
            }
        } catch (e: Exception) {
            Timber.e("Error adding file to tar: $entryName - ${e.message}")
            throw e
        }
    }

    private fun buildUstarHeader(entryName: String, fileSize: Long): ByteArray {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val normalized = entryName.trimStart('/')
        val nameBytes = normalized.toByteArray(Charsets.UTF_8)

        if (nameBytes.size <= 100) {
            System.arraycopy(nameBytes, 0, header, 0, nameBytes.size)
        } else {
            val splitIndex = normalized.lastIndexOf('/', 154).takeIf { it > 0 } ?: 0
            val prefixBytes = normalized.substring(0, splitIndex).toByteArray(Charsets.UTF_8)
            val restBytes = normalized.substring((splitIndex + 1).coerceAtMost(normalized.length))
                .toByteArray(Charsets.UTF_8)
            System.arraycopy(restBytes, 0, header, 0, minOf(restBytes.size, 100))
            System.arraycopy(prefixBytes, 0, header, 345, minOf(prefixBytes.size, 155))
        }

        writeAsciiField(header, 100, 8, "0000644")
        writeAsciiField(header, 108, 8, "0000000")
        writeAsciiField(header, 116, 8, "0000000")
        writeAsciiField(header, 124, 12, fileSize.toString(8).padStart(11, '0'))
        writeAsciiField(
            header,
            136,
            12,
            (System.currentTimeMillis() / 1000L).toString(8).padStart(11, '0')
        )

        // Fill checksum field with spaces before computing checksum
        for (i in 148 until 156) {
            header[i] = ' '.code.toByte()
        }
        header[156] = '0'.code.toByte()
        writeAsciiField(header, 257, 6, "ustar")
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()

        val checksum = header.sumOf { it.toInt() and 0xFF }
        val checksumBytes = (checksum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray(Charsets.US_ASCII)
        System.arraycopy(checksumBytes, 0, header, 148, minOf(checksumBytes.size, 8))
        return header
    }

    private fun writeAsciiField(target: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        val count = minOf(bytes.size, length - 1)
        System.arraycopy(bytes, 0, target, offset, count)
        target[offset + count] = 0
    }

    fun shareBackup(backup: Backup, context: Context) {
        MainScope().launch(Dispatchers.IO) {
            try {
                val archiveFile = createBackupArchive(backup, context)

                if (archiveFile == null) {
                    Timber.e("Failed to create backup archive")
                    return@launch
                }

                val uri = context.uriFromFile(archiveFile)
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "application/x-tar"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Backup: ${backup.packageLabel}")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Backup of ${backup.packageLabel} (${backup.packageName})\n" +
                                "Version: ${backup.versionName} (${backup.versionCode})\n" +
                                "Date: ${backup.backupDate.getFormattedDate(true)}\n" +
                                "Size: ${archiveFile.length()} bytes"
                    )
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                val chooserIntent =
                    Intent.createChooser(shareIntent, "Share Backup: ${backup.packageLabel}")

                withContext(Dispatchers.Main) {
                    AnLiteApp.activity?.startActivity(chooserIntent)
                }

                Timber.i("Backup share intent launched successfully")
            } catch (e: Exception) {
                Timber.e("Error sharing backup: ${e.message}")
                LogsHandler.logException(e, backTrace = true)
            }
        }
    }
}
