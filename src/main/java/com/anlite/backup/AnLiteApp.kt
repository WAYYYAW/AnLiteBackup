/*
 * AnLite Backup: open-source apps backup and restore app.
 * Copyright (C) 2025  Antonios Hazim
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
package com.anlite.backup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.StrictMode
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.anlite.backup.data.dbs.databaseModule
import com.anlite.backup.data.dbs.entity.Backup
import com.anlite.backup.data.entity.StorageFile
import com.anlite.backup.data.plugins.Plugin
import com.anlite.backup.data.preferences.AnLitePrefs.Companion.prefsModule
import com.anlite.backup.data.preferences.pref_catchUncaughtException
import com.anlite.backup.data.preferences.pref_logToSystemLogcat
import com.anlite.backup.data.preferences.pref_maxLogLines
import com.anlite.backup.data.preferences.pref_uncaughtExceptionsJumpToPreferences
import com.anlite.backup.data.preferences.traceDebug
import com.anlite.backup.data.preferences.traceSection
import com.anlite.backup.data.repository.PackageRepository
import com.anlite.backup.ui.activities.AnLiteActivity
import com.anlite.backup.utils.FileUtils.BackupLocationInAccessibleException
import com.anlite.backup.utils.ISO_DATE_TIME_FORMAT_MS
import com.anlite.backup.utils.StorageLocationNotConfiguredException
import com.anlite.backup.utils.SystemUtils
import com.anlite.backup.utils.TraceUtils.beginNanoTimer
import com.anlite.backup.utils.TraceUtils.classAndId
import com.anlite.backup.utils.TraceUtils.endNanoTimer
import com.anlite.backup.utils.backupDirConfigured
import com.anlite.backup.utils.extensions.Android
import com.anlite.backup.utils.isDynamicTheme
import com.anlite.backup.utils.restartApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androix.startup.KoinStartup
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinConfiguration
import org.koin.java.KoinJavaComponent.get
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.system.exitProcess

val RESCUE_NAV get() = "rescue"

class AnLiteApp : Application(), KoinStartup {

    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    @KoinExperimentalAPI
    override fun onKoinStartup() = koinConfiguration {
        androidLogger()
        androidContext(this@AnLiteApp)
        modules(
            com.anlite.backup.core.coreModule,
            databaseModule,
            prefsModule,
        )
    }

    override fun onCreate() {
        if (Android.minSDK(Build.VERSION_CODES.S)) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectUnsafeIntentLaunch()
                    .build()
            )
        }

        // do this early, context will be used immediately
        refNB = WeakReference(this)

        Timber.w("======================================== app ${classAndId(this)} PID=${Process.myPid()}")

        super.onCreate()

        if (pref_catchUncaughtException.value) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                try {
                    Timber.e(e, "Uncaught exception")
                    if (pref_uncaughtExceptionsJumpToPreferences.value) {
                        context.restartApp(RESCUE_NAV)
                    }
                    object : Thread() {
                        override fun run() {
                            Looper.prepare()
                            Looper.loop()
                        }
                    }.start()
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    activity?.finishAffinity()
                    exitProcess(3)
                }
            }
        }

        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> isDynamicTheme }
                .build()
        )

        // 确保纯净 RootExecutor 初始化（FLAG_MOUNT_MASTER 全局挂载命名空间）
        com.anlite.backup.core.engine.RootExecutor.ensureInitialized()

        // 自愈探针：清理遗留的挂载点
        com.anlite.backup.core.engine.BindMountManager.reapOrphanMounts()

        Plugin.ensureScanned()

        MainScope().launch {
            addInfoLogText("--> AnLite Backup initialized")
        }
    }

    override fun onTerminate() {
        refNB = WeakReference(null)
        super.onTerminate()
        applicationScope.cancel()
    }

    companion object {

        val JsonDefault = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        val JsonPretty = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        val propsSerializer: StringFormat get() = JsonPretty
        val schedSerializer: StringFormat get() = JsonPretty

        inline fun <reified T> toSerialized(serializer: StringFormat, value: T): String =
            serializer.encodeToString(value)

        inline fun <reified T> fromSerialized(serialized: String): T =
            JsonDefault.decodeFromString(serialized)

        val lastLogMessages = ConcurrentLinkedQueue<String>()
        fun addLogMessage(message: String) {
            val maxLogLines = try {
                pref_maxLogLines.value
            } catch (_: Throwable) {
                2000
            }
            lastLogMessages.add(message)
            val size = lastLogMessages.size
            val nDelete = size - maxLogLines
            if (nDelete > 0)
                repeat(nDelete) {
                    lastLogMessages.remove()
                }
        }

        var logsDirectory: StorageFile? = null
            get() {
                if (field == null) {
                    field = context.getExternalFilesDir(null)
                        ?.let { StorageFile(it).ensureDirectory("logs") }
                        ?: context.filesDir.let { StorageFile(it).ensureDirectory("logs") }
                }
                return field
            }

        var lastErrorPackage = ""
        var lastErrorCommands = ConcurrentLinkedQueue<String>()
        fun addErrorCommand(command: String) {
            val maxErrorCommands = 10
            lastErrorCommands.add(command)
            val size = lastErrorCommands.size
            val nDelete = size - maxErrorCommands
            if (nDelete > 0)
                repeat(nDelete) {
                    lastErrorCommands.remove()
                }
        }

        private var logSections = mutableMapOf<String, Int>()
            .withDefault { 0 }

        init {
            Timber.plant(object : Timber.DebugTree() {
                override fun log(
                    priority: Int, tag: String?, message: String, t: Throwable?,
                ) {
                    val logToSystemLogcat = try {
                        pref_logToSystemLogcat.value
                    } catch (_: Throwable) {
                        true
                    }
                    if (logToSystemLogcat)
                        super.log(priority, "$tag", message, t)

                    val prio =
                        when (priority) {
                            Log.VERBOSE -> "V"
                            Log.ASSERT  -> "A"
                            Log.DEBUG   -> "DEBUG"
                            Log.ERROR   -> "E"
                            Log.INFO    -> "I"
                            Log.WARN    -> "W"
                            else        -> "?"
                        }
                    val now = SystemUtils.now
                    val date = ISO_DATE_TIME_FORMAT_MS.format(now)
                    try {
                        addLogMessage("$date $prio $tag : $message")
                    } catch (_: Throwable) {
                        // ignore
                    }
                }

                override fun createStackElementTag(element: StackTraceElement): String {
                    var tag = "${
                        super.createStackElementTag(element)
                    }:${
                        element.lineNumber
                    }::${
                        element.methodName
                    }"
                    if (tag.contains("TraceUtils"))
                        tag = ""
                    return "AnLiteBackup>$tag"
                }
            })
        }

        var startup = true
        const val startupMsg = "******************** startup"

        var refNB: WeakReference<AnLiteApp> = WeakReference(null)
        val NB: AnLiteApp get() = refNB.get()!!

        val context: Context get() = NB.applicationContext

        private var activityRefs = mutableListOf<WeakReference<Activity>>()
        private var activityRef: WeakReference<Activity> = WeakReference(null)
        val activity: Activity?
            get() = activityRef.get()

        fun addActivity(activity: Activity) {
            activityRef = WeakReference(activity)
            synchronized(activityRefs) {
                traceDebug { "activities.add: ${classAndId(activity)}" }
                activityRefs.add(activityRef)
                activityRefs.removeIf { it.get() == null }
            }
        }

        fun resumeActivity(activity: Activity) {
            activityRef = WeakReference(activity)
            synchronized(activityRefs) {
                traceDebug { "activities.res: ${classAndId(activity)}" }
                activityRefs.removeIf { it.get() == activity }
                activityRefs.add(activityRef)
                activityRefs.removeIf { it.get() == null }
            }
        }

        fun removeActivity(activity: Activity) {
            synchronized(activityRefs) {
                traceDebug { "activities.remove: ${classAndId(activity)}" }
                activityRefs.removeIf { it.get() == activity }
                activityRef = WeakReference(null)
                activityRefs.removeIf { it.get() == null }
            }
        }

        val activities: List<Activity>
            get() {
                synchronized(activityRefs) {
                    return activityRefs.mapNotNull { it.get() }
                }
            }

        var mainRef: WeakReference<AnLiteActivity> = WeakReference(null)
        var main: AnLiteActivity?
            get() = mainRef.get()
            set(mainActivity) {
                mainRef = WeakReference(mainActivity)
            }
        var mainSaved: WeakReference<AnLiteActivity> = WeakReference(null)

        var appsSuspendedChecked = false

        val isRelease get() = SystemUtils.packageName.endsWith(".backup")
        val isDebug get() = SystemUtils.packageName.contains("debug")
        val isNeo get() = SystemUtils.packageName.contains("neo")
        val isHg42 get() = SystemUtils.packageName.contains("hg42")

        //------------------------------------------------------------------------------------------ backupRoot

        var backupRoot: StorageFile? = null
            get() {
                if (field == null) {
                    val storagePath = backupDirConfigured
                    if (storagePath.isEmpty()) {
                        Timber.e("backup storage location not configured")
                        throw StorageLocationNotConfiguredException()
                    }
                    val storageDir = StorageFile.fromUri(storagePath)
                    if (!storageDir.exists()) {
                        Timber.e("backup storage location not accessible: $storagePath")
                        throw BackupLocationInAccessibleException("Cannot access the root location '$storagePath'")
                    }
                    Timber.e("backup storage location found at ${storageDir.path}")
                    field = storageDir
                }
                return field
            }

        //------------------------------------------------------------------------------------------ infoText

        var infoLogLines = mutableStateListOf<String>()
        const val nInfoLogLines = 100
        var showInfoLog by mutableStateOf(false)

        fun clearInfoLogText() {
            synchronized(infoLogLines) {
                infoLogLines = mutableStateListOf()
            }
        }

        fun addInfoLogText(value: String) {
            synchronized(infoLogLines) {
                infoLogLines.add(value)
                if (infoLogLines.size > nInfoLogLines)
                    infoLogLines.drop(1)
            }
        }

        fun getInfoLogText(n: Int = nInfoLogLines, fill: String? = null): String {
            synchronized(infoLogLines) {
                val lines = infoLogLines.takeLast(n).toMutableList()
                if (fill != null)
                    while (lines.size < n)
                        lines.add(fill)
                return lines.joinToString("\n")
            }
        }

        //------------------------------------------------------------------------------------------ progress

        val progress = mutableStateOf(Pair(false, 0f))

        fun setProgress(now: Int = 0, max: Int = 0) {
            if (max <= 0)
                progress.value = Pair(false, 0f)
            else
                progress.value = Pair(true, 1f * now / max)
        }

        //------------------------------------------------------------------------------------------ section

        fun beginLogSection(section: String) {
            var count: Int
            synchronized(logSections) {
                count = logSections.getValue(section)
                logSections[section] = count + 1
            }
            traceSection { """*** ${"|---".repeat(count)}\ $section""" }
            beginNanoTimer("section.$section")
        }

        fun endLogSection(section: String) {
            val time = endNanoTimer("section.$section")
            var count: Int
            synchronized(logSections) {
                count = logSections.getValue(section)
                logSections[section] = count - 1
            }
            traceSection { "*** ${"|---".repeat(count - 1)}/ $section ${"%.3f".format(time / 1E9)} sec" }
        }

        val runningSchedules = mutableMapOf<Long, Boolean>()

        fun putBackups(packageName: String, backups: Set<Backup>) {
            runBlocking(Dispatchers.IO) {
                get<PackageRepository>(PackageRepository::class.java).apply {
                    updatePackageBackups(packageName, backups)
                }
            }
        }

        fun getBackups(packageName: String): Set<Backup> {
            get<PackageRepository>(PackageRepository::class.java).apply {
                return if (startup) emptySet()
                else getBackups(packageName)
            }
        }
    }
}
