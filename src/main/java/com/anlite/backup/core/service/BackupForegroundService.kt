package com.anlite.backup.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.anlite.backup.R
import com.anlite.backup.core.queue.QueueActionType
import com.anlite.backup.core.queue.QueueProgress
import com.anlite.backup.core.queue.QueueStep
import com.anlite.backup.core.queue.SequentialBackupQueue
import com.anlite.backup.ui.activities.AnLiteActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * 备份与还原核心前台服务 (Android 12+ / 14+ 标准实现)。
 *
 * 核心职责：
 * 1. 声明 `dataSync` 前台服务类型，保证在后台执行高负荷 restic 压缩与 I/O 时不被系统杀后台；
 * 2. 持有 `PARTIAL_WAKE_LOCK`，防止 CPU 进入睡眠；
 * 3. 响应式订阅 [SequentialBackupQueue]，向系统状态栏实时刷新动态进度；
 * 4. 提供通知栏快捷中断后续任务（"取消后续"）操作；
 * 5. 当所有排队任务完成并进入 IDLE 时，自适应优雅退出前台并释放 WakeLock。
 */
class BackupForegroundService : Service(), KoinComponent {

    private val queue: SequentialBackupQueue by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var progressCollectorJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        acquireWakeLock()
        observeQueueProgress()
        Timber.i("BackupForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Timber.i("BackupForegroundService onStartCommand action=$action")

        when (action) {
            ACTION_CANCEL_QUEUE -> {
                Timber.i("User requested queue cancellation from notification")
                queue.clearPendingTasks()
            }
            ACTION_STOP -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }

        // 立即展示前台通知，满足 Android 12+ 5 秒内必须调用 startForeground 的规范
        val initialNotification = buildNotification(queue.progress.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        progressCollectorJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        Timber.i("BackupForegroundService destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "备份与还原进度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "实时显示当前备份与还原任务执行进度与后台守护"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // 最长 2 小时安全超时
            }
            Timber.i("BackupForegroundService: WakeLock acquired")
        } catch (e: Throwable) {
            Timber.e(e, "Failed to acquire WakeLock")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Timber.i("BackupForegroundService: WakeLock released")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to release WakeLock")
        }
    }

    private fun observeQueueProgress() {
        progressCollectorJob = serviceScope.launch {
            queue.progress.collect { progress ->
                if (progress.isRunning) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(progress))
                } else if (progress.step == QueueStep.COMPLETED) {
                    // 当队列所有任务执行完成，等待短暂延迟确认无后续任务后优雅退出
                    delay(1200)
                    if (!queue.progress.value.isRunning) {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
    }

    private fun buildNotification(progress: QueueProgress): Notification {
        val clickIntent = Intent(this, AnLiteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingClickIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isRestore = progress.actionType == QueueActionType.RESTORE
        val actionVerb = if (isRestore) "还原" else "备份"
        val currentLabel = progress.currentTask?.displayLabel

        val title = if (progress.isRunning && currentLabel != null) {
            "正在$actionVerb: $currentLabel (${progress.currentIndex}/${progress.totalCount})"
        } else if (progress.isRunning) {
            "正在准备$actionVerb..."
        } else {
            "AnLite 任务队列处理中"
        }

        val content = if (progress.message.isNotEmpty()) {
            progress.message
        } else if (progress.isRunning) {
            "正在执行数据同步..."
        } else {
            "空闲"
        }

        val pct = (progress.percent * 100).toInt().coerceIn(0, 100)
        val isIndeterminate = progress.isRunning && (progress.percent <= 0f || progress.percent >= 1f && progress.step != QueueStep.FINALIZING)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingClickIntent)
            .setOngoing(progress.isRunning)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (progress.isRunning) {
            if (isIndeterminate) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(100, pct, false)
            }
        } else {
            builder.setProgress(0, 0, false)
        }

        return builder.build()
    }

    private fun stopForegroundAndSelf() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Timber.e(e, "Error stopping foreground")
        }
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "anlite_backup_progress"
        const val NOTIFICATION_ID = 1001
        const val WAKELOCK_TAG = "AnLiteBackup:WakeLock"

        const val ACTION_START = "com.anlite.backup.action.START_FOREGROUND"
        const val ACTION_STOP = "com.anlite.backup.action.STOP_FOREGROUND"
        const val ACTION_CANCEL_QUEUE = "com.anlite.backup.action.CANCEL_QUEUE"

        fun start(context: Context) {
            try {
                val intent = Intent(context, BackupForegroundService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Throwable) {
                Timber.e(e, "Failed to start BackupForegroundService")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, BackupForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Throwable) {
                Timber.e(e, "Failed to stop BackupForegroundService")
            }
        }
    }
}
