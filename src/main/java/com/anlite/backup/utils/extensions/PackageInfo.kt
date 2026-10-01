package com.anlite.backup.utils.extensions

import android.content.pm.PackageInfo
import com.anlite.backup.IGNORED_PERMISSIONS

val PackageInfo.grantedPermissions: List<String>
    get() = requestedPermissions?.filterIndexed { index, perm ->
        (((requestedPermissionsFlags?.getOrNull(index)
            ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED)
                == PackageInfo.REQUESTED_PERMISSION_GRANTED)
                &&
                perm !in IGNORED_PERMISSIONS
    }.orEmpty()