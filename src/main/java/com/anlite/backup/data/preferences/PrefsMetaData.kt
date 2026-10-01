package com.anlite.backup.data.preferences

import android.content.Context
import com.anlite.backup.ui.pages.pref_deviceLock
import com.anlite.backup.utils.isBiometricLockAvailable
import com.anlite.backup.utils.isDeviceLockAvailable

val PrefsIsEnabled: Map<String, (Context) -> Boolean> = mapOf(
    UserPrefKey.DEVICE_LOCK.name to { it.isDeviceLockAvailable() },
    UserPrefKey.BIOMETRIC_LOCK.name to { it.isBiometricLockAvailable() && pref_deviceLock.value },
)

val PrefsDependency: Map<String, String> = mapOf(
    UserPrefKey.BIOMETRIC_LOCK.name to UserPrefKey.DEVICE_LOCK.name,
)