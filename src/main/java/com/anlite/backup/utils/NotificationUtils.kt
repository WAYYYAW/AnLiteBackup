package com.anlite.backup.utils

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.anlite.backup.NOTIFICATION_CHANNEL_REFRESH
import com.anlite.backup.R
import com.anlite.backup.ui.activities.AnLiteActivity

val Context.notificationManager: NotificationManager
    get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

fun Context.reportRefreshFail(
    message: String,
) {
    val title = getString(R.string.refresh_failed)

    val builder = NotificationCompat
        .Builder(this, NOTIFICATION_CHANNEL_REFRESH)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(title)
        .setContentText(message)
        .setTicker(title)
        .setOngoing(false)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, AnLiteActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

    if (ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    ) notificationManager.notify(
        (title + message).hashCode(),
        builder.build()
    )
}