package com.anlite.backup.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.engineDataStore: DataStore<Preferences> by preferencesDataStore(name = "engine_preferences")

data class EngineConfig(
    val repoPath: String,
    val repoPassword: String,
    val backupApk: Boolean,
    val backupData: Boolean,
    val backupDataDe: Boolean,
    val maxSnapshotsPerApp: Int,
    val autoPruneAfterBatch: Boolean,
    val restoreSelinux: Boolean,
    val showSystemApps: Boolean,
    val themeMode: String,
    val useDynamicColors: Boolean,
)

/**
 * 纯净配置仓储：基于 Jetpack DataStore 实现。
 * 仅收录底层与核心功能紧密相关的配置，完全解耦 UI，零 Compose 依赖。
 */
class EnginePreferences(private val context: Context) {

    private val dataStore = context.engineDataStore

    companion object {
        val KEY_REPO_PATH = stringPreferencesKey("repo_path")
        val KEY_REPO_PASSWORD = stringPreferencesKey("repo_password")
        val KEY_BACKUP_APK = booleanPreferencesKey("backup_apk")
        val KEY_BACKUP_DATA = booleanPreferencesKey("backup_data")
        val KEY_BACKUP_DATA_DE = booleanPreferencesKey("backup_data_de")
        val KEY_MAX_SNAPSHOTS = intPreferencesKey("max_snapshots_per_app")
        val KEY_AUTO_PRUNE = booleanPreferencesKey("auto_prune_after_batch")
        val KEY_RESTORE_SELINUX = booleanPreferencesKey("restore_selinux")
        val KEY_SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLORS = booleanPreferencesKey("use_dynamic_colors")

        const val DEFAULT_REPO_PATH = "/storage/emulated/0/AnLiteBackup/restic-repo"
        const val DEFAULT_REPO_PASSWORD = "neobackup-default-restic-password"
    }

    val repoPath: Flow<String> = dataStore.data.map { it[KEY_REPO_PATH] ?: DEFAULT_REPO_PATH }
    val repoPassword: Flow<String> = dataStore.data.map { it[KEY_REPO_PASSWORD] ?: DEFAULT_REPO_PASSWORD }
    val backupApk: Flow<Boolean> = dataStore.data.map { it[KEY_BACKUP_APK] ?: true }
    val backupData: Flow<Boolean> = dataStore.data.map { it[KEY_BACKUP_DATA] ?: true }
    val backupDataDe: Flow<Boolean> = dataStore.data.map { it[KEY_BACKUP_DATA_DE] ?: true }
    val maxSnapshotsPerApp: Flow<Int> = dataStore.data.map { it[KEY_MAX_SNAPSHOTS] ?: 3 }
    val autoPruneAfterBatch: Flow<Boolean> = dataStore.data.map { it[KEY_AUTO_PRUNE] ?: false }
    val restoreSelinux: Flow<Boolean> = dataStore.data.map { it[KEY_RESTORE_SELINUX] ?: true }
    val showSystemApps: Flow<Boolean> = dataStore.data.map { it[KEY_SHOW_SYSTEM_APPS] ?: false }
    val themeMode: Flow<String> = dataStore.data.map { it[KEY_THEME_MODE] ?: "SYSTEM" }
    val useDynamicColors: Flow<Boolean> = dataStore.data.map { it[KEY_DYNAMIC_COLORS] ?: true }

    val configFlow: Flow<EngineConfig> = dataStore.data.map { prefs ->
        EngineConfig(
            repoPath = prefs[KEY_REPO_PATH] ?: DEFAULT_REPO_PATH,
            repoPassword = prefs[KEY_REPO_PASSWORD] ?: DEFAULT_REPO_PASSWORD,
            backupApk = prefs[KEY_BACKUP_APK] ?: true,
            backupData = prefs[KEY_BACKUP_DATA] ?: true,
            backupDataDe = prefs[KEY_BACKUP_DATA_DE] ?: true,
            maxSnapshotsPerApp = prefs[KEY_MAX_SNAPSHOTS] ?: 3,
            autoPruneAfterBatch = prefs[KEY_AUTO_PRUNE] ?: false,
            restoreSelinux = prefs[KEY_RESTORE_SELINUX] ?: true,
            showSystemApps = prefs[KEY_SHOW_SYSTEM_APPS] ?: false,
            themeMode = prefs[KEY_THEME_MODE] ?: "SYSTEM",
            useDynamicColors = prefs[KEY_DYNAMIC_COLORS] ?: true,
        )
    }

    suspend fun getSnapshotConfig(): EngineConfig = configFlow.first()

    suspend fun setRepoPath(path: String) {
        dataStore.edit { it[KEY_REPO_PATH] = path }
    }

    suspend fun setRepoPassword(pwd: String) {
        dataStore.edit { it[KEY_REPO_PASSWORD] = pwd }
    }

    suspend fun setBackupApk(enabled: Boolean) {
        dataStore.edit { it[KEY_BACKUP_APK] = enabled }
    }

    suspend fun setBackupData(enabled: Boolean) {
        dataStore.edit { it[KEY_BACKUP_DATA] = enabled }
    }

    suspend fun setBackupDataDe(enabled: Boolean) {
        dataStore.edit { it[KEY_BACKUP_DATA_DE] = enabled }
    }

    suspend fun setMaxSnapshotsPerApp(count: Int) {
        dataStore.edit { it[KEY_MAX_SNAPSHOTS] = count }
    }

    suspend fun setAutoPruneAfterBatch(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_PRUNE] = enabled }
    }

    suspend fun setRestoreSelinux(enabled: Boolean) {
        dataStore.edit { it[KEY_RESTORE_SELINUX] = enabled }
    }

    suspend fun setShowSystemApps(show: Boolean) {
        dataStore.edit { it[KEY_SHOW_SYSTEM_APPS] = show }
    }

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setUseDynamicColors(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLORS] = enabled }
    }
}
