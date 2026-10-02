package com.anlite.backup.core.queue

import android.content.Context
import com.anlite.backup.core.service.BackupForegroundService
import com.anlite.backup.core.usecase.BackupAppUseCase
import com.anlite.backup.core.usecase.BackupDirectoryUseCase
import com.anlite.backup.core.usecase.PruneRepositoryUseCase
import com.anlite.backup.core.usecase.RestoreAppUseCase
import com.anlite.backup.data.preferences.EnginePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

enum class QueueActionType {
    BACKUP,
    RESTORE,
}

sealed interface BackupTask {
    val displayLabel: String
    val identifier: String
    val actionType: QueueActionType get() = QueueActionType.BACKUP

    data class AppTask(
        val packageName: String,
        val packageLabel: String,
        val versionName: String? = "-",
        val versionCode: Int = 0,
        val apkSourceDir: String? = null,
        val isSystem: Boolean = false,
    ) : BackupTask {
        override val displayLabel: String get() = packageLabel
        override val identifier: String get() = packageName
        override val actionType: QueueActionType get() = QueueActionType.BACKUP
    }

    data class DirectoryTask(
        val directoryId: Long,
        val name: String,
        val path: String,
    ) : BackupTask {
        override val displayLabel: String get() = name
        override val identifier: String get() = path
        override val actionType: QueueActionType get() = QueueActionType.BACKUP
    }

    data class AppRestoreTask(
        val packageName: String,
        val packageLabel: String,
        val snapshotId: String,
        val restoreApk: Boolean = true,
        val restoreData: Boolean = true,
        val onFinished: ((Boolean, String?) -> Unit)? = null,
    ) : BackupTask {
        override val displayLabel: String get() = packageLabel
        override val identifier: String get() = "$packageName:$snapshotId"
        override val actionType: QueueActionType get() = QueueActionType.RESTORE
    }

    companion object {
        operator fun invoke(
            packageName: String,
            packageLabel: String,
            versionName: String? = "-",
            versionCode: Int = 0,
            apkSourceDir: String? = null,
            isSystem: Boolean = false,
        ): BackupTask = AppTask(
            packageName = packageName,
            packageLabel = packageLabel,
            versionName = versionName,
            versionCode = versionCode,
            apkSourceDir = apkSourceDir,
            isSystem = isSystem,
        )
    }
}

enum class QueueStep {
    IDLE,
    PREPARING_MOUNT,
    BACKING_UP,
    RESTORING,
    FINALIZING,
    COMPLETED,
    FAILED,
}

data class QueueProgress(
    val isRunning: Boolean = false,
    val currentTask: BackupTask? = null,
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val step: QueueStep = QueueStep.IDLE,
    val percent: Float = 0f,
    val message: String = "",
    val failedTasks: List<Pair<String, String>> = emptyList(),
    val actionType: QueueActionType = QueueActionType.BACKUP,
)

/**
 * 全流程全串行备份/还原调度队列。
 *
 * 核心保证：
 * 1. 严格全串行执行每个任务（Preparing -> Running -> Finalizing -> Next）；
 * 2. 统一支持应用/目录备份 (AppTask, DirectoryTask) 与应用还原 (AppRestoreTask)；
 * 3. 彻底消除多任务 restic 排他锁冲突与 I/O 瞬时争死；
 * 4. 实时通过 [progress] StateFlow 暴露执行状态给现代 UI 与前台服务 [BackupForegroundService]；
 * 5. 安全任务取消控制：提供 [clearPendingTasks] 排空未开始任务并安全中断后续批处理；
 * 6. 仅在批处理全部结束后（或用户手动触发时）按需调度 prune 碎片整理。
 */
class SequentialBackupQueue(
    private val context: Context,
    private val backupAppUseCase: BackupAppUseCase,
    private val backupDirectoryUseCase: BackupDirectoryUseCase,
    private val restoreAppUseCase: RestoreAppUseCase,
    private val pruneRepositoryUseCase: PruneRepositoryUseCase,
    private val preferences: EnginePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _progress = MutableStateFlow(QueueProgress())
    val progress: StateFlow<QueueProgress> = _progress.asStateFlow()

    private val taskChannel = Channel<List<BackupTask>>(Channel.UNLIMITED)
    private val isCancelRequested = AtomicBoolean(false)
    private var workerJob: Job? = null

    init {
        startQueueWorker()
    }

    private fun startQueueWorker() {
        workerJob = scope.launch {
            for (taskList in taskChannel) {
                if (taskList.isEmpty()) continue

                // 启动保活前台服务与持久 WakeLock
                BackupForegroundService.start(context)

                val total = taskList.size
                val failedList = mutableListOf<Pair<String, String>>()

                val firstTaskAction = taskList.firstOrNull()?.actionType ?: QueueActionType.BACKUP
                val isRestore = firstTaskAction == QueueActionType.RESTORE
                _progress.update {
                    QueueProgress(
                        isRunning = true,
                        totalCount = total,
                        currentIndex = 0,
                        actionType = firstTaskAction,
                        step = if (isRestore) QueueStep.RESTORING else QueueStep.PREPARING_MOUNT,
                        message = if (isRestore) "准备开始串行还原..." else "准备开始串行备份...",
                    )
                }

                try {
                    for ((index, task) in taskList.withIndex()) {
                        // 软取消检查：若用户点击了“取消后续”，安全退出后续未开始的任务循环
                        if (isCancelRequested.get()) {
                            Timber.i("SequentialBackupQueue: Cancel requested, skipping remaining tasks in current batch")
                            break
                        }

                        Timber.i("SequentialBackupQueue: Processing [${index + 1}/$total] ${task.displayLabel}")
                        val taskAction = task.actionType
                        val isTaskRestore = taskAction == QueueActionType.RESTORE
                        _progress.update {
                            it.copy(
                                isRunning = true,
                                currentTask = task,
                                currentIndex = index + 1,
                                totalCount = total,
                                actionType = taskAction,
                                step = if (isTaskRestore) QueueStep.RESTORING else QueueStep.PREPARING_MOUNT,
                                percent = 0f,
                                message = if (isTaskRestore) "准备还原 ${task.displayLabel}..." else "准备备份 ${task.displayLabel}...",
                            )
                        }

                        val result = when (task) {
                            is BackupTask.AppTask -> {
                                backupAppUseCase.execute(
                                    packageName = task.packageName,
                                    packageLabel = task.packageLabel,
                                    versionName = task.versionName,
                                    versionCode = task.versionCode,
                                    apkSourceDir = task.apkSourceDir,
                                    isSystem = task.isSystem,
                                    onProgress = { pct, msg ->
                                        _progress.update {
                                            it.copy(
                                                step = QueueStep.BACKING_UP,
                                                percent = pct,
                                                message = msg,
                                            )
                                        }
                                    }
                                )
                            }
                            is BackupTask.DirectoryTask -> {
                                backupDirectoryUseCase.execute(
                                    directoryId = task.directoryId,
                                    onProgress = { pct, msg ->
                                        _progress.update {
                                            it.copy(
                                                step = QueueStep.BACKING_UP,
                                                percent = pct,
                                                message = msg,
                                            )
                                        }
                                    }
                                )
                            }
                            is BackupTask.AppRestoreTask -> {
                                restoreAppUseCase.execute(
                                    packageName = task.packageName,
                                    snapshotId = task.snapshotId,
                                    restoreApk = task.restoreApk,
                                    restoreData = task.restoreData,
                                    onProgress = { pct, msg ->
                                        _progress.update {
                                            it.copy(
                                                step = QueueStep.RESTORING,
                                                percent = pct,
                                                message = msg,
                                            )
                                        }
                                    }
                                ).also { res ->
                                    task.onFinished?.invoke(res.isSuccess, res.exceptionOrNull()?.message)
                                }
                            }
                        }

                        if (result.isSuccess) {
                            _progress.update {
                                it.copy(
                                    step = QueueStep.FINALIZING,
                                    percent = 1f,
                                    message = "已完成 ${task.displayLabel}",
                                )
                            }
                        } else {
                            val errMsg = result.exceptionOrNull()?.message ?: "操作失败"
                            Timber.e("Task failed for ${task.displayLabel}: $errMsg")
                            failedList.add(task.identifier to errMsg)
                            _progress.update {
                                it.copy(
                                    step = QueueStep.FAILED,
                                    message = errMsg,
                                    failedTasks = failedList.toList(),
                                )
                            }
                        }
                    }

                    // 批处理任务全部执行完成后，若未被取消且包含备份任务，检查是否需要自动碎片整理
                    val wasCancelled = isCancelRequested.get()
                    val hasBackupTasks = taskList.any { it.actionType == QueueActionType.BACKUP }
                    if (!wasCancelled && hasBackupTasks) {
                        val config = preferences.getSnapshotConfig()
                        if (config.autoPruneAfterBatch) {
                            _progress.update {
                                it.copy(
                                    step = QueueStep.FINALIZING,
                                    message = "批处理完成，正在执行碎片整理...",
                                )
                            }
                            pruneRepositoryUseCase.execute()
                        }
                    }

                    val actionName = if (firstTaskAction == QueueActionType.RESTORE) "还原" else "备份"
                    val finalMsg = if (wasCancelled) {
                        "任务已停止 (已取消后续任务，${failedList.size} 失败)"
                    } else {
                        "全部 $total 项$actionName 任务已完成 (${failedList.size} 失败)"
                    }

                    _progress.update {
                        QueueProgress(
                            isRunning = false,
                            currentTask = null,
                            currentIndex = total,
                            totalCount = total,
                            step = QueueStep.COMPLETED,
                            percent = 1f,
                            actionType = firstTaskAction,
                            message = finalMsg,
                            failedTasks = failedList.toList(),
                        )
                    }
                } finally {
                    isCancelRequested.set(false)
                    BackupForegroundService.stop(context)
                }
            }
        }
    }

    /**
     * 向调度队列投递单项或批量备份任务
     */
    fun enqueue(tasks: List<BackupTask>) {
        if (tasks.isEmpty()) return
        taskChannel.trySend(tasks)
    }

    /**
     * 便捷重载：投递单个备份任务
     */
    fun enqueue(task: BackupTask) {
        enqueue(listOf(task))
    }

    /**
     * 安全取消控制：排空排队任务，并对当前批次设置取消信号
     */
    fun clearPendingTasks() {
        isCancelRequested.set(true)
        var drainedCount = 0
        while (true) {
            val drained = taskChannel.tryReceive().getOrNull() ?: break
            drainedCount += drained.size
        }
        Timber.i("SequentialBackupQueue: Drained $drainedCount pending task batches from channel")
        _progress.update {
            if (it.isRunning) {
                it.copy(message = "正在等待当前任务完成并取消后续任务...")
            } else {
                it.copy(
                    isRunning = false,
                    step = QueueStep.IDLE,
                    message = "已取消所有排队任务",
                )
            }
        }
    }

    /**
     * 重置状态（例如用户清空已完成提示）
     */
    fun resetState() {
        if (!_progress.value.isRunning) {
            _progress.value = QueueProgress()
        }
    }
}
