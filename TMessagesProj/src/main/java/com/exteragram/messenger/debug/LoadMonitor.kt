package com.exteragram.messenger.debug

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.health.HealthStats
import android.os.health.SystemHealthManager
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.FileLog
import org.telegram.messenger.LiteMode
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ForegroundDetector
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LoadMonitor {

    private const val SAMPLE_INTERVAL = 60000L
    private const val EPISODE_MIN_DURATION = 300000L
    private const val MAX_SAMPLES = 240
    private const val MAX_REPORTS = 10
    private const val MAX_ORIGIN_ATTEMPTS = 8
    private const val UNATTRIBUTED = "<unattributed>"

    private const val STATE_FOREGROUND = 0
    private const val STATE_BACKGROUND = 1
    private const val STATE_SCREEN_OFF = 2
    private val STATE_NAMES = arrayOf("foreground", "background", "screenOff")

    private val SKIPPED_FRAMES = arrayOf(
        "java.", "javax.", "kotlin.", "android.", "com.android.", "dalvik.", "libcore.", "sun.", "jdk.",
        "de.robv.android.xposed.", LoadMonitor::class.java.name
    )

    @Volatile
    private var sampler: Sampler? = null

    @Volatile
    private var pendingAlert: PendingAlert? = null

    private var alertRef: WeakReference<AlertDialog>? = null

    private val healthKeyNames = HashMap<String, Map<Int, String>>()
    private val threadOrigins = HashMap<String, String>()
    private val originAttempts = HashMap<String, Int>()

    val reportsDir: File by lazy { debugFilesDir("loadreports") }

    private val clockTicks: Long by lazy {
        Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
    }

    @JvmStatic
    fun init() {
        if (DebugConfig.loadMonitorEnabled) {
            sampler = Sampler()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (DebugConfig.loadMonitorEnabled == enabled) {
            return
        }
        DebugConfig.loadMonitorEnabled = enabled
        sampler?.stop()
        sampler = if (enabled) Sampler() else null
        if (!enabled) {
            pendingAlert = null
        }
    }

    var cpuPercent: Int
        get() = DebugConfig.loadMonitorCpuPercent.coerceIn(1, 50)
        set(value) {
            DebugConfig.loadMonitorCpuPercent = value
        }

    val summary: String
        get() = sampler?.summary ?: (formatDuration(Process.getElapsedCpuTime()) + " CPU since launch")

    val lastReport: File?
        get() = reportsDir.listFiles()?.filter { it.name.endsWith(".json") }?.maxByOrNull { it.lastModified() }

    val reportsSize: Long
        get() = reportsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteReports(onDone: () -> Unit) {
        Utilities.globalQueue.postRunnable {
            reportsDir.listFiles()?.forEach { it.delete() }
            AndroidUtilities.runOnUIThread { onDone() }
        }
    }

    fun shareLastReport(activity: Activity) {
        lastReport?.let { share(activity, it) }
    }

    fun captureAndShare(activity: Activity) {
        val currentSampler = sampler
        val queue = currentSampler?.queue ?: Utilities.globalQueue
        queue.postRunnable {
            val result = runCatching {
                val report = currentSampler?.captureReport("manual") ?: baseReport("manual")
                writeReport(report, "manual")
            }
            AndroidUtilities.runOnUIThread {
                result.onSuccess { share(activity, it) }
                result.onFailure {
                    FileLog.e(it)
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, "Load report failed: $it").show()
                }
            }
        }
    }

    private fun share(activity: Activity, file: File) {
        shareDebugFile(activity, file, "application/json", "Share load report")
    }

    private fun showPendingAlert() {
        val alert = pendingAlert ?: return
        val activity = LaunchActivity.instance
        if (sampler == null || activity == null || activity.isFinishing) {
            return
        }
        if (alertRef?.get()?.isShowing == true) {
            return
        }
        pendingAlert = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle("High background load")
            .setMessage(alert.message)
            .setPositiveButton("Share") { _, _ -> share(activity, alert.file) }
            .setNeutralButton("Turn off") { _, _ -> setEnabled(false) }
            .setNegativeButton("Later", null)
            .create()
        alertRef = WeakReference(dialog)
        try {
            dialog.show()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun currentState(): Int {
        val powerManager = ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            return STATE_SCREEN_OFF
        }
        return if (ForegroundDetector.getInstance()?.isForeground == true) STATE_FOREGROUND else STATE_BACKGROUND
    }

    private fun isPlugged(): Boolean? = runCatching {
        ApplicationLoader.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { it.getIntExtra("plugged", 0) != 0 }
    }.getOrNull()

    private fun batteryJson(): JSONObject =
        JSONObject().put("level", LiteMode.getBatteryLevel()).put("plugged", isPlugged())

    private fun uidRxBytes(): Long = TrafficStats.getUidRxBytes(Process.myUid())

    private fun uidTxBytes(): Long = TrafficStats.getUidTxBytes(Process.myUid())

    private fun readThreads(): HashMap<Int, ThreadStat> {
        val result = HashMap<Int, ThreadStat>()
        val tids = File("/proc/self/task").list() ?: return result
        val buffer = ByteArray(1024)
        for (tid in tids) {
            val id = tid.toIntOrNull() ?: continue
            try {
                val read = FileInputStream("/proc/self/task/$tid/stat").use { it.read(buffer) }
                if (read > 0) {
                    parseStat(String(buffer, 0, read, Charsets.UTF_8))?.let { result[id] = it }
                }
            } catch (ignore: Exception) {
            }
        }
        return result
    }

    private fun parseStat(stat: String): ThreadStat? {
        val nameStart = stat.indexOf('(')
        val nameEnd = stat.lastIndexOf(')')
        val fieldsStart = nameEnd + 2
        if (nameStart < 0 || nameEnd <= nameStart || fieldsStart >= stat.length) {
            return null
        }
        val fields = stat.substring(fieldsStart).split(' ')
        if (fields.size < 13) {
            return null
        }
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return ThreadStat(stat.substring(nameStart + 1, nameEnd), utime + stime)
    }

    private fun ticksToMs(ticks: Long): Long = ticks * 1000 / clockTicks

    private fun threadDeltas(before: Map<Int, ThreadStat>, after: Map<Int, ThreadStat>): HashMap<String, Long> {
        val result = HashMap<String, Long>()
        for ((tid, stat) in after) {
            val previous = before[tid]
            val ticks = if (previous != null && previous.name == stat.name) stat.ticks - previous.ticks else stat.ticks
            if (ticks > 0) {
                result[stat.name] = (result[stat.name] ?: 0L) + ticksToMs(ticks)
            }
        }
        return result
    }

    private fun topThreads(threads: Map<String, Long>, count: Int): List<Pair<String, Long>> =
        threads.entries.sortedByDescending { it.value }.take(count).map { it.key to it.value }

    private fun threadsJson(threads: List<Pair<String, Long>>, withOrigin: Boolean = false): JSONArray {
        val array = JSONArray()
        for ((name, cpuMs) in threads) {
            val json = JSONObject().put("name", name).put("cpuMs", cpuMs)
            if (withOrigin) {
                json.put("at", originOf(name))
            }
            array.put(json)
        }
        return array
    }

    private fun originOf(name: String): String? = synchronized(threadOrigins) { threadOrigins[name] }

    private fun resolveOrigins(names: List<String>) {
        val unresolved = synchronized(threadOrigins) {
            names.filter { it != UNATTRIBUTED && !threadOrigins.containsKey(it) && (originAttempts[it] ?: 0) < MAX_ORIGIN_ATTEMPTS }
        }
        if (unresolved.isEmpty()) {
            return
        }
        val traces = runCatching { Thread.getAllStackTraces() }.getOrNull() ?: return
        synchronized(threadOrigins) {
            for (name in unresolved) {
                originAttempts[name] = (originAttempts[name] ?: 0) + 1
            }
            for ((thread, stack) in traces) {
                val name = thread.name.take(15)
                if (unresolved.contains(name) && !threadOrigins.containsKey(name)) {
                    appFrame(stack)?.let { threadOrigins[name] = it }
                }
            }
        }
    }

    private fun appFrame(stack: Array<StackTraceElement>): String? {
        for (frame in stack) {
            if (SKIPPED_FRAMES.none { frame.className.startsWith(it) }) {
                return frame.className.substringAfterLast('.') + "." + frame.methodName + ":" + frame.lineNumber
            }
        }
        return null
    }

    private fun takeHealthStats(): HealthStats? = runCatching {
        (ApplicationLoader.applicationContext.getSystemService(Context.SYSTEM_HEALTH_SERVICE) as? SystemHealthManager)?.takeMyUidSnapshot()
    }.onFailure { FileLog.e(it) }.getOrNull()

    private fun healthKeyName(dataType: String, key: Int): String {
        val names = synchronized(healthKeyNames) {
            healthKeyNames.getOrPut(dataType) {
                runCatching {
                    Class.forName(if (dataType.contains('.')) dataType else "android.os.health.$dataType").fields
                        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
                        .associate { it.getInt(null) to it.name }
                }.getOrDefault(emptyMap())
            }
        }
        return names[key] ?: key.toString()
    }

    private fun healthJson(stats: HealthStats): JSONObject {
        val dataType = stats.dataType
        val json = JSONObject().put("type", dataType)
        if (stats.measurementKeyCount > 0) {
            val measurements = JSONObject()
            for (i in 0 until stats.measurementKeyCount) {
                val key = stats.getMeasurementKeyAt(i)
                measurements.put(healthKeyName(dataType, key), stats.getMeasurement(key))
            }
            json.put("measurements", measurements)
        }
        if (stats.timerKeyCount > 0) {
            val timers = JSONObject()
            for (i in 0 until stats.timerKeyCount) {
                val key = stats.getTimerKeyAt(i)
                timers.put(healthKeyName(dataType, key), JSONArray().put(stats.getTimerCount(key)).put(stats.getTimerTime(key)))
            }
            json.put("timers", timers)
        }
        if (stats.measurementsKeyCount > 0) {
            val measurementMaps = JSONObject()
            for (i in 0 until stats.measurementsKeyCount) {
                val key = stats.getMeasurementsKeyAt(i)
                val map = JSONObject()
                for ((name, value) in stats.getMeasurements(key)) {
                    map.put(name, value)
                }
                measurementMaps.put(healthKeyName(dataType, key), map)
            }
            json.put("measurementMaps", measurementMaps)
        }
        if (stats.timersKeyCount > 0) {
            val timerMaps = JSONObject()
            for (i in 0 until stats.timersKeyCount) {
                val key = stats.getTimersKeyAt(i)
                val map = JSONObject()
                for ((name, timer) in stats.getTimers(key)) {
                    map.put(name, JSONArray().put(timer.count).put(timer.time))
                }
                timerMaps.put(healthKeyName(dataType, key), map)
            }
            json.put("timerMaps", timerMaps)
        }
        if (stats.statsKeyCount > 0) {
            val statsMaps = JSONObject()
            for (i in 0 until stats.statsKeyCount) {
                val key = stats.getStatsKeyAt(i)
                val map = JSONObject()
                for ((name, child) in stats.getStats(key)) {
                    map.put(name, healthJson(child))
                }
                statsMaps.put(healthKeyName(dataType, key), map)
            }
            json.put("stats", statsMaps)
        }
        return json
    }

    private fun baseReport(trigger: String): JSONObject {
        val context = ApplicationLoader.applicationContext
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val totals = HashMap<String, Long>()
        for (stat in readThreads().values) {
            totals[stat.name] = (totals[stat.name] ?: 0L) + ticksToMs(stat.ticks)
        }
        val threads = topThreads(totals, 30)
        resolveOrigins(threads.map { it.first })
        val report = JSONObject()
            .put("format", 1)
            .put("trigger", trigger)
            .put("createdAt", System.currentTimeMillis())
            .put("app", JSONObject()
                .put("version", BuildVars.BUILD_VERSION_STRING)
                .put("code", BuildVars.BUILD_VERSION)
                .put("debug", BuildVars.DEBUG_VERSION))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("cores", Runtime.getRuntime().availableProcessors()))
            .put("battery", batteryJson())
            .put("settings", JSONObject()
                .put("accounts", UserConfig.getActivatedAccountsCount())
                .put("proxy", SharedConfig.isProxyEnabled())
                .put("logs", BuildVars.LOGS_ENABLED)
                .put("powerSave", powerManager != null && powerManager.isPowerSaveMode)
                .put("ignoringBatteryOptimizations", powerManager != null && powerManager.isIgnoringBatteryOptimizations(context.packageName)))
            .put("process", JSONObject()
                .put("uptimeMs", SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
                .put("cpuMs", Process.getElapsedCpuTime())
                .put("state", STATE_NAMES[currentState()])
                .put("threads", threadsJson(threads, true)))
        return report.put("healthNow", takeHealthStats()?.let { healthJson(it) })
    }

    private fun writeReport(report: JSONObject, trigger: String): File {
        reportsDir.mkdirs()
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(reportsDir, "load_" + BuildVars.BUILD_VERSION_STRING + "_" + date + "_" + trigger + ".json")
        file.writeText(report.toString(1))
        reportsDir.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_REPORTS)
            ?.forEach { it.delete() }
        return file
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return when {
            ms < 1000 -> "${ms}ms"
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        }
    }

    private fun formatPercent(part: Long, total: Long): String =
        String.format(Locale.US, "%.1f%%", if (total > 0) part * 100.0 / total else 0.0)

    private fun describeEpisode(wallMs: Long, cpuMs: Long, rxBytes: Long, txBytes: Long, threads: List<Pair<String, Long>>): String {
        val sb = StringBuilder()
        sb.append("In " + formatDuration(wallMs) + " in the background, the app used " + formatPercent(cpuMs, wallMs) + " of one core: ")
        sb.append(formatDuration(cpuMs) + " of CPU")
        if (rxBytes >= 0 && txBytes >= 0) {
            sb.append(", " + AndroidUtilities.formatFileSize(rxBytes) + " received, " + AndroidUtilities.formatFileSize(txBytes) + " sent")
        }
        sb.append('.')
        if (threads.isNotEmpty()) {
            sb.append("\n\nBusiest threads: ")
            sb.append(threads.joinToString(", ") { it.first + " " + formatDuration(it.second) })
            sb.append('.')
        }
        sb.append("\n\nThe report holds thread names, traffic counters and battery stats, no message content.")
        return sb.toString()
    }

    class ThreadStat(val name: String, val ticks: Long)

    class PendingAlert(val file: File, val message: String)

    class Episode(
        val startedAt: Long,
        val startRealtime: Long,
        val cpuMs: Long,
        val rxBytes: Long,
        val txBytes: Long,
        val threads: Map<Int, ThreadStat>,
        val health: HealthStats?
    ) {
        var reported = false
    }

    class Sampler : ForegroundDetector.Listener, NotificationCenter.NotificationCenterDelegate {

        val queue = DispatchQueue("loadMonitorQueue")

        private val sampleRunnable = Runnable {
            sample()
            scheduleNext()
        }

        private val startedAt = System.currentTimeMillis()
        private var startHealth: HealthStats? = null
        private var startBattery: JSONObject? = null
        private var recentHealth: HealthStats? = null
        private var recentHealthRealtime = 0L

        private var lastRealtime = 0L
        private var lastCpuMs = 0L
        private var lastRxBytes = -1L
        private var lastTxBytes = -1L
        private var lastState = STATE_FOREGROUND
        private var threads = HashMap<Int, ThreadStat>()

        private val wallMs = LongArray(STATE_NAMES.size)
        private val cpuMs = LongArray(STATE_NAMES.size)
        private val rxBytes = LongArray(STATE_NAMES.size)
        private val txBytes = LongArray(STATE_NAMES.size)
        private val threadMs = HashMap<String, LongArray>()
        private val samples = ArrayDeque<JSONObject>()

        private var episode: Episode? = null
        private var observers: NotificationCenter.ObserversGroup? = null

        @Volatile
        var summary: String? = null
            private set

        init {
            ForegroundDetector.getInstance()?.addListener(this)
            AndroidUtilities.runOnUIThread {
                observers = NotificationCenter.getGlobalInstance().createObserversGroup(this).add(NotificationCenter.screenStateChanged)
            }
            queue.postRunnable {
                startHealth = recentHealthStats()
                startBattery = batteryJson()
                lastRealtime = SystemClock.elapsedRealtime()
                lastCpuMs = Process.getElapsedCpuTime()
                lastRxBytes = uidRxBytes()
                lastTxBytes = uidTxBytes()
                threads = readThreads()
                updateState()
                scheduleNext()
            }
        }

        fun stop() {
            ForegroundDetector.getInstance()?.removeListener(this)
            AndroidUtilities.runOnUIThread {
                observers?.removeAllObservers()
                observers = null
            }
            queue.cleanupQueue()
            queue.recycle()
        }

        override fun onBecameForeground() {
            queue.postRunnable { sample() }
            AndroidUtilities.runOnUIThread({ showPendingAlert() }, 1000)
        }

        override fun onBecameBackground() {
            queue.postRunnable { sample() }
        }

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            if (id == NotificationCenter.screenStateChanged) {
                queue.postRunnable { sample() }
            }
        }

        private fun scheduleNext() {
            queue.cancelRunnable(sampleRunnable)
            queue.postRunnable(sampleRunnable, SAMPLE_INTERVAL)
        }

        // jadx output of sample() was duplicated per branch; logic reconstructed from it.
        fun sample() {
            val now = SystemClock.elapsedRealtime()
            val cpu = Process.getElapsedCpuTime()
            val rx = uidRxBytes()
            val tx = uidTxBytes()
            val currentThreads = readThreads()
            val state = lastState
            val wallDelta = now - lastRealtime
            val cpuDelta = cpu - lastCpuMs
            val rxDelta = if (rx >= 0 && lastRxBytes >= 0) rx - lastRxBytes else 0L
            val txDelta = if (tx >= 0 && lastTxBytes >= 0) tx - lastTxBytes else 0L

            val deltas = threadDeltas(threads, currentThreads)
            val unattributed = cpuDelta - deltas.values.sum()
            if (unattributed > 0) {
                deltas[UNATTRIBUTED] = unattributed
            }
            resolveOrigins(topThreads(deltas, 3).filter { it.second >= 200 }.map { it.first })

            wallMs[state] += wallDelta
            cpuMs[state] += cpuDelta
            rxBytes[state] += rxDelta
            txBytes[state] += txDelta
            for ((name, ms) in deltas) {
                threadMs.getOrPut(name) { LongArray(STATE_NAMES.size) }[state] += ms
            }

            if (wallDelta >= 1000) {
                samples.addLast(JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("state", STATE_NAMES[state])
                    .put("wallMs", wallDelta)
                    .put("cpuMs", cpuDelta)
                    .put("rxBytes", rxDelta)
                    .put("txBytes", txDelta)
                    .put("plugged", isPlugged())
                    .put("threads", threadsJson(topThreads(deltas, 3))))
                while (samples.size > MAX_SAMPLES) {
                    samples.removeFirst()
                }
            }

            lastRealtime = now
            lastCpuMs = cpu
            lastRxBytes = rx
            lastTxBytes = tx
            threads = currentThreads
            summary = formatDuration(cpuMs.sum()) + " CPU, " + AndroidUtilities.formatFileSize(rxBytes.sum() + txBytes.sum())
            checkEpisode(now, cpu, rx, tx, currentThreads)
            updateState()
        }

        private fun updateState() {
            val state = currentState()
            lastState = state
            if (state == STATE_FOREGROUND) {
                episode = null
            } else if (episode == null) {
                episode = Episode(System.currentTimeMillis(), lastRealtime, lastCpuMs, lastRxBytes, lastTxBytes, threads, recentHealthStats())
            }
        }

        private fun checkEpisode(now: Long, cpu: Long, rx: Long, tx: Long, currentThreads: Map<Int, ThreadStat>) {
            val episode = episode ?: return
            val wallDelta = now - episode.startRealtime
            if (episode.reported || wallDelta < EPISODE_MIN_DURATION) {
                return
            }
            val cpuDelta = cpu - episode.cpuMs
            if (100 * cpuDelta < cpuPercent.toLong() * wallDelta) {
                return
            }
            episode.reported = true
            val file = runCatching { writeReport(buildReport("background"), "background") }
                .onFailure { FileLog.e(it) }
                .getOrNull() ?: return
            val rxDelta = if (rx < 0 || episode.rxBytes < 0) -1L else rx - episode.rxBytes
            val txDelta = if (tx >= 0 && episode.txBytes >= 0) tx - episode.txBytes else -1L
            pendingAlert = PendingAlert(file, describeEpisode(wallDelta, cpuDelta, rxDelta, txDelta, topThreads(threadDeltas(episode.threads, currentThreads), 3)))
            if (ForegroundDetector.getInstance()?.isForeground != true) {
                return
            }
            AndroidUtilities.runOnUIThread { showPendingAlert() }
        }

        private fun recentHealthStats(): HealthStats? {
            val now = SystemClock.elapsedRealtime()
            if (recentHealth == null || now - recentHealthRealtime > SAMPLE_INTERVAL) {
                recentHealth = takeHealthStats()
                recentHealthRealtime = now
            }
            return recentHealth
        }

        fun captureReport(trigger: String): JSONObject {
            sample()
            return buildReport(trigger)
        }

        fun buildReport(trigger: String): JSONObject {
            val report = baseReport(trigger)
            val states = JSONObject()
            for (i in STATE_NAMES.indices) {
                states.put(STATE_NAMES[i], JSONObject()
                    .put("wallMs", wallMs[i])
                    .put("cpuMs", cpuMs[i])
                    .put("rxBytes", rxBytes[i])
                    .put("txBytes", txBytes[i]))
            }
            val threadsArray = JSONArray()
            for ((name, perState) in threadMs.entries.sortedByDescending { it.value.sum() }.take(30)) {
                val json = JSONObject().put("name", name).put("cpuMs", perState.sum()).put("at", originOf(name))
                for (i in STATE_NAMES.indices) {
                    json.put(STATE_NAMES[i], perState[i])
                }
                threadsArray.put(json)
            }
            report.put("monitor", JSONObject()
                .put("startedAt", startedAt)
                .put("sampleIntervalMs", SAMPLE_INTERVAL)
                .put("batteryAtStart", startBattery)
                .put("states", states)
                .put("threads", threadsArray)
                .put("samples", JSONArray(samples)))
            episode?.let { episode ->
                val now = SystemClock.elapsedRealtime()
                val cpu = Process.getElapsedCpuTime()
                val rx = uidRxBytes()
                val tx = uidTxBytes()
                report.put("episode", JSONObject()
                    .put("startedAt", episode.startedAt)
                    .put("wallMs", now - episode.startRealtime)
                    .put("cpuMs", cpu - episode.cpuMs)
                    .put("rxBytes", if (rx < 0 || episode.rxBytes < 0) -1L else rx - episode.rxBytes)
                    .put("txBytes", if (tx >= 0 && episode.txBytes >= 0) tx - episode.txBytes else -1L)
                    .put("threads", threadsJson(topThreads(threadDeltas(episode.threads, readThreads()), 30), true)))
                episode.health?.let { report.put("healthEpisodeStart", healthJson(it)) }
            }
            startHealth?.let { report.put("healthStart", healthJson(it)) }
            return report
        }
    }
}
