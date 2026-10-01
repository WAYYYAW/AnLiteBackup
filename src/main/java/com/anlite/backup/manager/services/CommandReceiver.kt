package com.anlite.backup.manager.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anlite.backup.ACTION_CANCEL
import com.anlite.backup.ACTION_CANCEL_SCHEDULE
import com.anlite.backup.ACTION_CRASH
import com.anlite.backup.ACTION_RE_SCHEDULE
import com.anlite.backup.ACTION_RUN_SCHEDULE
import com.anlite.backup.EXTRA_PERIODIC
import com.anlite.backup.EXTRA_SCHEDULE_ID
import com.anlite.backup.AnLiteApp
import com.anlite.backup.data.preferences.traceSchedule
import com.anlite.backup.data.repository.ScheduleRepository
import com.anlite.backup.manager.handler.WorkHandler
import com.anlite.backup.manager.tasks.ScheduleWork
import com.anlite.backup.utils.SystemUtils
import com.anlite.backup.utils.scheduleNextAlarm
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale

class CommandReceiver : //TODO hg42 how to maintain security?
//TODO machiav3lli by making the receiver only internally accessible (not exported)
//TODO hg42 but it's one of the purposes to be remotely controllable from other apps like Tasker
//TODO hg42 no big prob for now: cancel, starting or changing schedule isn't very critical
    BroadcastReceiver(), KoinComponent {
    private val scheduleRepo: ScheduleRepository by inject()

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val command = intent.action
        Timber.i("Command: command $command")

        val pendingResult = goAsync()
        val appScope = (context.applicationContext as AnLiteApp).applicationScope
        appScope.launch {
            try {
                when (command) {
                    ACTION_CANCEL          -> {
                        val batchName = intent.getStringExtra("name")
                        Timber.d("################################################### command intent cancel -------------> name=$batchName")
                        AnLiteApp.addInfoLogText("$command $batchName")
                        get<WorkHandler>(WorkHandler::class.java).cancel(batchName)
                    }

                    ACTION_RUN_SCHEDULE    -> {
                        intent.getStringExtra("name")?.let { name ->
                            AnLiteApp.addInfoLogText("$command $name")
                            Timber.d("################################################### command intent schedule -------------> name=$name")
                            scheduleRepo.getSchedule(name)?.let { schedule ->
                                ScheduleWork.enqueueImmediate(schedule)
                            }
                        }
                    }

                    ACTION_CANCEL_SCHEDULE -> {
                        intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L).takeIf { it != -1L }
                            ?.let { id ->
                                Timber.d("################################################### command cancel schedule -------------> id=$id")
                                ScheduleWork.cancel(
                                    id,
                                    intent.getBooleanExtra(EXTRA_PERIODIC, false)
                                )
                            }
                    }

                    ACTION_RE_SCHEDULE     -> { // TODO reconsider when ScheduleWork is fully implemented
                        intent.getStringExtra("name")?.let { name ->
                            val now = SystemUtils.now
                            val time = intent.getStringExtra("time")
                            val setTime = time ?: SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(now + 120)
                            AnLiteApp.addInfoLogText("$command $name $time -> $setTime")
                            Timber.d("################################################### command intent reschedule -------------> name=$name time=$time -> $setTime")
                            scheduleRepo.getSchedule(name)?.let { schedule ->
                                val (hour, minute) = setTime.split(":").map { it.toInt() }
                                traceSchedule { "[${schedule.id}] command receiver -> re-schedule to hour=$hour minute=$minute" }
                                val newSched = schedule.copy(
                                    timeHour = hour,
                                    timeMinute = minute,
                                )
                                scheduleRepo.update(newSched)
                                scheduleNextAlarm(context, newSched.id, true)
                            }
                        }
                    }

                    ACTION_CRASH           -> {
                        throw Exception("this is a crash via command intent")
                    }

                    null                   -> {}
                    else                   -> {
                        AnLiteApp.addInfoLogText("Command: command '$command'")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}