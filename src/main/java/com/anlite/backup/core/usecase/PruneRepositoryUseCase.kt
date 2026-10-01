package com.anlite.backup.core.usecase

import com.anlite.backup.core.engine.ResticDriver
import com.anlite.backup.core.engine.ShellResult
import com.anlite.backup.data.preferences.EnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class PruneRepositoryUseCase(
    private val resticDriver: ResticDriver,
    private val preferences: EnginePreferences,
) {
    suspend fun execute(onLine: ((String) -> Unit)? = null): Result<ShellResult> = withContext(Dispatchers.IO) {
        runCatching {
            val config = preferences.getSnapshotConfig()
            Timber.i("Starting restic prune on ${config.repoPath}...")
            val result = resticDriver.prune(config.repoPath, config.repoPassword, onLine)
            if (!result.isSuccess) {
                val err = (result.err + result.out).joinToString("\n")
                throw IllegalStateException("Restic prune failed: $err")
            }
            result
        }
    }
}
