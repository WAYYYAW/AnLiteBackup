package com.anlite.backup.utils

import android.content.Context
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

object FileUtils {

    fun getExternalStorageDirectory(context: Context): File? {
        return context.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.parentFile
    }

    fun getName(fullPath: String): String {
        var path = fullPath
        if (path.endsWith(File.separator)) path = path.substring(0, path.length - 1)
        return path.substring(path.lastIndexOf(File.separator) + 1)
    }

    fun translatePosixPermissionToMode(permissions: String): Int {
        var str = permissions.takeLast(9)
        str = str.replace('s', 'x', false)
        str = str.replace('S', '-', false)
        val set = PosixFilePermissions.fromString(str)
        return translatePosixPermissionToMode(set)
    }

    fun translatePosixPermissionToMode(permissions: Set<PosixFilePermission?>): Int {
        var mode = 0
        PosixFilePermission.values().forEach {
            mode = mode shl 1
            mode += if (permissions.contains(it)) 1 else 0
        }
        return mode
    }

    class BackupLocationInAccessibleException : Exception {
        constructor() : super()
        constructor(message: String?) : super(message)
        constructor(message: String?, cause: Throwable?) : super(message, cause)
    }
}
