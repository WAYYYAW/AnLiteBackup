/*
 * AnLite Backup: open-source apps backup and restore app.
 * Copyright (C) 2025 Antonios Hazim
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
package com.anlite.backup.manager.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anlite.backup.EXTRA_NAME
import com.anlite.backup.EXTRA_SCHEDULE_ID
import com.anlite.backup.AnLiteApp
import com.anlite.backup.data.preferences.traceSchedule
import com.anlite.backup.manager.tasks.ScheduleWork
import com.anlite.backup.ui.pages.pref_fakeScheduleDups
import com.anlite.backup.utils.scheduleAlarmsOnce
import com.anlite.backup.utils.scheduleNextAlarm
import kotlinx.coroutines.launch

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val scheduleId = intent?.getLongExtra(EXTRA_SCHEDULE_ID, -1) ?: -1
        val scheduleName = intent?.getStringExtra(EXTRA_NAME) ?: ""

        if (scheduleId < 0) return

        AnLiteApp.wakelock(true)
        traceSchedule { "[$scheduleId] ScheduleReceiver triggered for '$scheduleName'" }

        val pendingResult = goAsync()
        val appScope = (context.applicationContext as AnLiteApp).applicationScope
        appScope.launch {
            try {
                repeat(1 + pref_fakeScheduleDups.value) { count ->
                    scheduleNextAlarm(context, scheduleId, rescheduleBoolean = true)
                    ScheduleWork.enqueueScheduled(scheduleId, scheduleName)
                    traceSchedule {
                        "[$scheduleId] starting task for schedule${if (count > 0) " (dup $count)" else ""}"
                    }
                }
                scheduleAlarmsOnce(context)
            } finally {
                AnLiteApp.wakelock(false)
                pendingResult.finish()
            }
        }
    }
}