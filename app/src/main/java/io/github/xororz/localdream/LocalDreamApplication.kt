package io.github.xororz.localdream

import android.app.Application
import android.content.Context
import io.github.xororz.localdream.data.HistoryMigration
import io.github.xororz.localdream.data.MigrationState
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.utils.LocaleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocalDreamApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _migrationState = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val migrationState: StateFlow<MigrationState> = _migrationState.asStateFlow()

    private var migrationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        io.github.xororz.localdream.cloud.RollingLogger.init(this)
        // et.30: log OpenCL/GPU probe once at startup for diagnostics.
        runCatching {
            io.github.xororz.localdream.cloud.LogHub.log(
                io.github.xororz.localdream.cloud.LogHub.Category.APP,
                "openclProbe: ${io.github.xororz.localdream.utils.DeviceCapabilities.openclProbeReport()}",
            )
        }
        // et.28: log uncaught crashes to filesDir/crash_<ts>.log before delegating
        // to the default handler, so a screen-off generation crash is diagnosable.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val ts = System.currentTimeMillis()
                val sb = StringBuilder()
                    .append("time=").append(ts).append('\n')
                    .append("thread=").append(t.name).append('\n')
                    .append("device=").append(android.os.Build.MANUFACTURER)
                    .append('/').append(android.os.Build.MODEL).append('\n')
                    .append("screenOn=").append(
                        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                            .isInteractive,
                    ).append('\n')
                    .append("exception=").append(e).append('\n')
                for (el in e.stackTrace.take(40)) sb.append("  at ").append(el).append('\n')
                java.io.File(filesDir, "crash_$ts.log").writeText(sb.toString())
            }
            prev?.uncaughtException(t, e)
        }
        // et.27: RAM-adaptive SD1.5 long edge.
        runCatching {
            io.github.xororz.localdream.ui.screens.sd15LongEdge =
                io.github.xororz.localdream.utils.DeviceCapabilities.sd15LongEdgeForRam(this)
        }
        io.github.xororz.localdream.cloud.LogHub.log(
            io.github.xororz.localdream.cloud.LogHub.Category.APP,
            "App start; sd15LongEdge=${io.github.xororz.localdream.ui.screens.sd15LongEdge}",
        )
        startMigration()
    }

    private fun startMigration() {
        migrationJob?.cancel()
        migrationJob = appScope.launch {
            try {
                if (HistoryMigration.isDone(this@LocalDreamApplication)) {
                    _migrationState.value = MigrationState.NotNeeded
                    return@launch
                }
                HistoryMigration.migrate(
                    this@LocalDreamApplication,
                    AppDatabase.get(this@LocalDreamApplication),
                    _migrationState,
                )
            } catch (e: Throwable) {
                _migrationState.value = MigrationState.Failed(e)
            }
        }
    }

    fun retryMigration() {
        _migrationState.value = MigrationState.Idle
        startMigration()
    }

    fun skipMigration() {
        migrationJob?.cancel()
        appScope.launch {
            try {
                HistoryMigration.markDoneExternal(this@LocalDreamApplication)
            } catch (_: Throwable) {
                // ignore — UI will still proceed
            }
            _migrationState.value = MigrationState.Done
        }
    }
}
