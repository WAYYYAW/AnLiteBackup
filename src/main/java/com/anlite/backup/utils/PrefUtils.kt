package com.anlite.backup.utils

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.preference.PreferenceManager
import com.anlite.backup.PREFS_LANGUAGES_SYSTEM
import com.anlite.backup.R
import com.anlite.backup.config.BuildConfig
import com.anlite.backup.data.preferences.pref_appAccentColor
import com.anlite.backup.data.preferences.pref_appSecondaryColor
import com.anlite.backup.data.preferences.pref_appTheme
import com.anlite.backup.data.preferences.pref_biometricLock
import com.anlite.backup.data.preferences.pref_deviceLock
import com.anlite.backup.data.preferences.pref_languages
import com.anlite.backup.data.preferences.pref_pathBackupFolder
import java.util.Locale

fun Context.getDefaultSharedPreferences(): SharedPreferences =
    PreferenceManager.getDefaultSharedPreferences(this)

fun isDeviceLockEnabled(): Boolean = pref_deviceLock.value

fun Context.isDeviceLockAvailable(): Boolean =
    (getSystemService(KeyguardManager::class.java) as KeyguardManager).isDeviceSecure

fun isBiometricLockEnabled(): Boolean = pref_biometricLock.value

fun Context.isBiometricLockAvailable(): Boolean =
    BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

class StorageLocationNotConfiguredException : Exception("Storage Location has not been configured")

val backupDirConfigured: String
    get() {
        val location = pref_pathBackupFolder.value
        if (location.isEmpty())
            throw StorageLocationNotConfiguredException()
        return location
    }

fun setBackupDir(uri: android.net.Uri): String {
    val fullUriString = uri.toString()
    if (pref_pathBackupFolder.value != fullUriString) {
        pref_pathBackupFolder.value = fullUriString
    }
    return fullUriString
}

fun backupFolderExists(uri: String? = null): Boolean {
    return try {
        if (!uri.isNullOrEmpty()) java.io.File(uri).exists() else false
    } catch (_: Throwable) {
        false
    }
}

val isBackupDeviceProtectedData: Boolean get() = true
val isBackupExternalData: Boolean get() = true
val isBackupObbData: Boolean get() = true
val isBackupMediaData: Boolean get() = true
val isRestoreDeviceProtectedData: Boolean get() = true
val isRestoreExternalData: Boolean get() = true
val isRestoreObbData: Boolean get() = true
val isRestoreMediaData: Boolean get() = true
val isDisableVerification: Boolean get() = false
val isRestoreAllPermissions: Boolean get() = true
val isAllowDowngrade: Boolean get() = true

var styleTheme: Int
    get() = pref_appTheme.value
    set(value) {
        pref_appTheme.value = value
    }

var stylePrimary: Int
    get() = pref_appAccentColor.value
    set(value) {
        pref_appAccentColor.value = value
    }

var styleSecondary: Int
    get() = pref_appSecondaryColor.value
    set(value) {
        pref_appSecondaryColor.value = value
    }

var language: String
    get() = pref_languages.value
    set(value) {
        pref_languages.value = value
    }

var specialBackupsEnabled: Boolean
    get() = false
    set(_) {}

fun Context.getLocaleOfCode(localeCode: String): Locale = when {
    localeCode.isEmpty()      -> resources.configuration.locales[0]
    localeCode.contains("-r") -> Locale(
        localeCode.substring(0, 2),
        localeCode.substring(4)
    )

    localeCode.contains("_")  -> Locale(
        localeCode.substring(0, 2),
        localeCode.substring(3)
    )

    else                      -> Locale(localeCode)
}

fun Context.getLanguageList() =
    mapOf(PREFS_LANGUAGES_SYSTEM to resources.getString(R.string.prefs_language_system)) +
            BuildConfig.DETECTED_LOCALES
                .sorted()
                .associateWith { translateLocale(getLocaleOfCode(it)) }

private fun translateLocale(locale: Locale): String {
    val country = locale.getDisplayCountry(locale)
    val language = locale.getDisplayLanguage(locale)
    return (language.replaceFirstChar { it.uppercase(Locale.getDefault()) }
            + (if (country.isNotEmpty() && country.compareTo(language, true) != 0)
        "($country)" else ""))
}
