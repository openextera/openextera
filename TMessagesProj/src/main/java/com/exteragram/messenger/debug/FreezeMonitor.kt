package com.exteragram.messenger.debug

import android.app.Activity
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.SparseArray
import com.exteragram.messenger.plugins.PluginsController
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ForegroundDetector
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.RandomAccessFile
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object FreezeMonitor {

    private const val REPORT_FILE = "freeze_report.txt"
    private const val MIN_THRESHOLD_MS = 200
    private const val MAX_THRESHOLD_MS = 2000
    private const val PING_TIMEOUT_MS = 100L
    private const val CHECK_INTERVAL_MS = 200L
    private const val SAMPLE_INTERVAL_MS = 20L
    private const val MAX_SAMPLING_MS = 120000L
    private const val MAX_STACK_DEPTH = 150
    private const val STACK_KEY_DEPTH = 40
    private const val MAX_REPORT_BYTES = 2000000L
    private const val TRIMMED_REPORT_BYTES = 1000000

    private val APP_FRAME_PREFIXES = arrayOf("org.telegram.", "com.exteragram.", "com.chaquo.", "dev.exterahook.")
    private val IDLE_TOP_FRAMES = arrayOf(
        "android.os.MessageQueue.nativePollOnce",
        "java.lang.Object.wait",
        "java.lang.Thread.sleep",
        "sun.misc.Unsafe.park",
        "jdk.internal.misc.Unsafe.park",
        "java.net.",
        "libcore.io.Linux.",
        "android.system.Os.",
        "dalvik.system.VMStack.getThreadStackTrace"
    )
    private val COUNTED_CLASSES = arrayOf(
        "org.telegram.ui.LaunchActivity",
        "org.telegram.ui.ActionBar.BaseFragment",
        "org.telegram.ui.ChatActivity",
        "org.telegram.ui.DialogsActivity",
        "org.telegram.ui.ProfileActivity",
        "org.telegram.ui.Components.ChatActivityEnterView",
        "org.telegram.ui.Components.EmojiView",
        "org.telegram.ui.Cells.ChatMessageCell",
        "org.telegram.messenger.ImageReceiver",
        "org.telegram.ui.Components.RLottieDrawable",
        "org.telegram.ui.Components.AnimatedFileDrawable",
        "org.telegram.ui.Components.AnimatedEmojiDrawable",
        "android.graphics.Bitmap"
    )
    private val GC_STATS = arrayOf("art.gc.blocking-gc-count", "art.gc.blocking-gc-time", "art.gc.gc-count", "art.gc.gc-time")

    @Volatile
    private var watchdog: Watchdog? = null

    @Volatile
    private var freezes = 0

    private val reportLock = Any()

    private val notificationNames: Map<Int, String> by lazy {
        NotificationCenter::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }
    }

    val reportsDir: File by lazy { debugFilesDir("freezereports") }

    private val reportFile: File
        get() = File(reportsDir, REPORT_FILE)

    @JvmStatic
    fun init() {
        if (DebugConfig.freezeMonitorEnabled) {
            watchdog = Watchdog()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (DebugConfig.freezeMonitorEnabled == enabled) {
            return
        }
        DebugConfig.freezeMonitorEnabled = enabled
        watchdog?.stop()
        watchdog = if (enabled) Watchdog() else null
    }

    val thresholdMs: Int
        get() = DebugConfig.freezeMonitorThresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)

    fun setThresholdMs(value: Int) {
        DebugConfig.freezeMonitorThresholdMs = value
    }

    val report: File?
        get() = reportFile.takeIf { it.length() > 0 }

    val reportsSize: Long
        get() = reportsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteReports(onDone: () -> Unit) {
        Utilities.globalQueue.postRunnable {
            synchronized(reportLock) {
                reportsDir.listFiles()?.forEach { it.delete() }
                freezes = 0
            }
            AndroidUtilities.runOnUIThread { onDone() }
        }
    }

    fun shareReport(activity: Activity) {
        Utilities.globalQueue.postRunnable {
            val result = runCatching { copyReport() }
            AndroidUtilities.runOnUIThread {
                result.onSuccess { shareDebugFile(activity, it, "text/plain", "Share freeze report") }
                result.onFailure {
                    FileLog.e(it)
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, "Freeze report failed: $it").show()
                }
            }
        }
    }

    private fun copyReport(): File {
        synchronized(reportLock) {
            val copy = File(reportsDir, "freeze_" + BuildVars.BUILD_VERSION_STRING + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
            reportsDir.listFiles()?.forEach {
                if (it.name != REPORT_FILE) {
                    it.delete()
                }
            }
            reportFile.copyTo(copy, true)
            return copy
        }
    }

    private fun appendReport(text: String) {
        synchronized(reportLock) {
            try {
                reportsDir.mkdirs()
                val file = reportFile
                file.appendText(text)
                if (file.length() > MAX_REPORT_BYTES) {
                    val tail = ByteArray(TRIMMED_REPORT_BYTES)
                    RandomAccessFile(file, "r").use {
                        it.seek(it.length() - TRIMMED_REPORT_BYTES)
                        it.readFully(tail)
                    }
                    file.writeBytes("##### (older records trimmed)\n".toByteArray(Charsets.UTF_8) + tail)
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
    }

    private fun showFreezeBulletin(durationMs: Long) {
        val activity = LaunchActivity.instance
        if (watchdog == null || activity == null || activity.isFinishing) {
            return
        }
        BulletinFactory.global().createSimpleBulletin(R.raw.error, "Main thread froze for " + formatSeconds(durationMs), "Share") {
            shareReport(activity)
        }.show()
    }

    private fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

    private fun formatMb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)

    private fun percent(count: Int, total: Int): String = (count * 100 / total).toString().padStart(4)

    private fun appLine(): String =
        "app " + BuildVars.BUILD_VERSION_STRING + " (" + BuildVars.BUILD_VERSION + "), " + Build.MANUFACTURER + " " + Build.MODEL +
            ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"

    private fun heapLine(): String {
        val used = HeapMonitor.usedBytes
        val max = Runtime.getRuntime().maxMemory()
        return "java " + formatMb(used) + " / " + formatMb(max) + " (" + (used * 100 / max) + "%), native " + formatMb(Debug.getNativeHeapAllocatedSize())
    }

    private fun gcStats(): LongArray = LongArray(GC_STATS.size) { Debug.getRuntimeStat(GC_STATS[it])?.toLongOrNull() ?: 0L }

    private fun frameNames(stack: Array<StackTraceElement>, limit: Int): List<String> = stack.take(limit).map { it.toString() }

    private fun screenName(): String =
        runCatching { LaunchActivity.getLastFragmentIncludeMainTabs()?.javaClass?.name ?: "none" }.getOrDefault("unknown")

    private fun fragmentStack(): String = runCatching {
        val stack = ArrayList<BaseFragment>(LaunchActivity.instance?.actionBarLayout?.fragmentStack ?: emptyList())
        "(" + stack.size + ") " + stack.joinToString(" > ") { it.javaClass.simpleName }
    }.getOrDefault("unavailable")

    private fun otherThreads(mainThread: Thread): String {
        val sb = StringBuilder()
        try {
            var dumped = 0
            for ((thread, stack) in Thread.getAllStackTraces()) {
                if (thread == mainThread || thread == Thread.currentThread()) {
                    continue
                }
                val state = thread.state
                if (state != Thread.State.RUNNABLE && state != Thread.State.BLOCKED) {
                    continue
                }
                val frames = frameNames(stack, 20)
                val top = frames.firstOrNull() ?: continue
                if (IDLE_TOP_FRAMES.any { top.startsWith(it) }) {
                    continue
                }
                sb.append('"').append(thread.name).append("\" ").append(state).append('\n')
                for (frame in frames) {
                    sb.append("    at ").append(frame).append('\n')
                }
                if (++dumped >= 40) {
                    break
                }
            }
        } catch (e: Exception) {
            sb.append("thread dump failed: ").append(e).append('\n')
        }
        return if (sb.isNotEmpty()) sb.toString() else "(none)\n"
    }

    private fun delegateName(delegate: Any?): String {
        if (delegate == null) {
            return "null"
        }
        val cls = delegate.javaClass
        if (cls.name.endsWith("WeakObserversGroupImpl")) {
            val result = runCatching {
                val field = cls.getDeclaredField("reference")
                field.isAccessible = true
                (field.get(delegate) as WeakReference<*>).get()
            }
            if (result.exceptionOrNull() == null) {
                return (result.getOrNull()?.javaClass?.name ?: "cleared") + " (weak)"
            }
            return cls.name
        }
        return cls.name
    }

    private fun observers(): String {
        val sb = StringBuilder()
        for ((name, center) in arrayOf("account" to NotificationCenter.getInstance(UserConfig.selectedAccount), "global" to NotificationCenter.getGlobalInstance())) {
            try {
                val field = NotificationCenter::class.java.getDeclaredField("observers")
                field.isAccessible = true
                val registry = field.get(center) as SparseArray<*>
                val ids = ArrayList<Pair<Int, String>>()
                val byClass = HashMap<String, Int>()
                var total = 0
                for (i in 0 until registry.size()) {
                    val list = registry.valueAt(i) as List<*>
                    total += list.size
                    ids.add(list.size to (notificationNames[registry.keyAt(i)] ?: registry.keyAt(i).toString()))
                    for (j in list.indices) {
                        val className = delegateName(list[j]).substringAfterLast('.')
                        byClass[className] = (byClass[className] ?: 0) + 1
                    }
                }
                sb.append(name + ": " + total + " registrations in " + ids.size + " ids\n")
                sb.append("  biggest ids: ")
                sb.append(ids.sortedByDescending { it.first }.take(8).joinToString(", ") { it.second + " " + it.first })
                sb.append('\n')
                sb.append("  by class: ")
                sb.append(byClass.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { it.key + " " + it.value })
                sb.append('\n')
            } catch (e: Exception) {
                sb.append("$name: failed $e\n")
            }
        }
        return sb.toString()
    }

    private fun countInstances(classes: Array<Class<*>>): LongArray {
        val vmDebug = Class.forName("dalvik.system.VMDebug")
        return try {
            vmDebug.getDeclaredMethod("countInstancesOfClasses", classes.javaClass, Boolean::class.javaPrimitiveType)
                .invoke(null, classes, true) as LongArray
        } catch (e: ReflectiveOperationException) {
            val method = vmDebug.getDeclaredMethod("countInstancesOfClass", Class::class.java, Boolean::class.javaPrimitiveType)
            LongArray(classes.size) { method.invoke(null, classes[it], true) as Long }
        }
    }

    private fun liveObjects(): String = try {
        val classes = Array<Class<*>>(COUNTED_CLASSES.size) { Class.forName(COUNTED_CLASSES[it]) }
        HeapMonitor.collectGarbage()
        val counts = countInstances(classes)
        classes.indices.joinToString("\n", postfix = "\n") { counts[it].toString().padStart(7) + "  " + classes[it].name }
    } catch (t: Throwable) {
        "unavailable: $t\n"
    }

    private fun environment(): String {
        // lite has no plugin engine: the plugins map is always empty
        val plugins = runCatching {
            PluginsController.getInstance().plugins.keys.sorted().joinToString(", ").ifEmpty { "none" }
        }.getOrElse { "unavailable: $it" }
        val premium = runCatching { UserConfig.getInstance(UserConfig.selectedAccount).isPremium }.getOrNull()
        val uptime = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        return "-- environment --\n" + appLine() +
            "\nprocess uptime " + uptime + " s, foreground " + ForegroundDetector.getInstance()?.isForeground +
            "\nheap now: " + heapLine() +
            "\naccounts active " + UserConfig.getActivatedAccountsCount() + ", premium " + premium +
            "\nenabled plugins: " + plugins + "\n"
    }

    class Freeze(
        val durationMs: Long,
        val thresholdMs: Int,
        val samples: Int,
        val maxGapMs: Long,
        val screen: String,
        val fragmentStack: String,
        val gcBefore: LongArray,
        val heapBefore: String,
        val states: Map<String, Int>,
        val hot: Map<String, Int>,
        val hotDepth: Map<String, Int>,
        val topFrames: Map<String, Int>,
        val stacks: Map<List<String>, Int>,
        val otherThreads: String?,
        val observers: String?
    )

    class Watchdog : ForegroundDetector.Listener {

        private val queue = DispatchQueue("freezeMonitorQueue")
        private val mainHandler = Handler(Looper.getMainLooper())
        private val mainThread: Thread = Looper.getMainLooper().thread
        private val checkRunnable = Runnable { check() }

        @Volatile
        private var running = false

        @Volatile
        private var stopped = false

        init {
            ForegroundDetector.getInstance()?.addListener(this)
            updateRunning()
        }

        override fun onBecameForeground() {
            updateRunning()
        }

        override fun onBecameBackground() {
            updateRunning()
        }

        fun stop() {
            ForegroundDetector.getInstance()?.removeListener(this)
            stopped = true
            running = false
            queue.cleanupQueue()
            queue.recycle()
        }

        private fun updateRunning() {
            val shouldRun = !stopped && ForegroundDetector.getInstance()?.isForeground == true
            if (running == shouldRun) {
                return
            }
            running = shouldRun
            queue.postRunnable {
                queue.cancelRunnable(checkRunnable)
                if (running) {
                    queue.postRunnable(checkRunnable)
                }
            }
        }

        private fun check() {
            if (!running) {
                return
            }
            try {
                val latch = CountDownLatch(1)
                val start = SystemClock.uptimeMillis()
                if (mainHandler.post { latch.countDown() } && !latch.await(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    sample(latch, start)?.let { record(it) }
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
            if (running) {
                queue.postRunnable(checkRunnable, CHECK_INTERVAL_MS)
            }
        }

        private fun sample(latch: CountDownLatch, start: Long): Freeze? {
            val threshold = thresholdMs
            val gcBefore = gcStats()
            val heapBefore = heapLine()
            val screenAtStart = screenName()
            val stackAtStart = fragmentStack()
            val states = HashMap<String, Int>()
            val hot = HashMap<String, Int>()
            val hotDepth = HashMap<String, Int>()
            val topFrames = HashMap<String, Int>()
            val stacks = HashMap<List<String>, Int>()
            var maxGap = 0L
            var last = start
            var samples = 0
            var busyThreads: String? = null
            var observerDump: String? = null
            while (!stopped) {
                val now = SystemClock.uptimeMillis()
                maxGap = maxOf(maxGap, now - last)
                val elapsed = now - start
                if (elapsed < MAX_SAMPLING_MS) {
                    val state = mainThread.state.name
                    val frames = frameNames(mainThread.stackTrace, MAX_STACK_DEPTH)
                    samples++
                    states[state] = (states[state] ?: 0) + 1
                    if (frames.isNotEmpty()) {
                        topFrames[frames[0]] = (topFrames[frames[0]] ?: 0) + 1
                        val key = frames.take(STACK_KEY_DEPTH)
                        stacks[key] = (stacks[key] ?: 0) + 1
                        frames.forEachIndexed { depth, frame ->
                            if (depth > (hotDepth[frame] ?: -1)) {
                                hotDepth[frame] = depth
                            }
                        }
                        for (frame in frames.toHashSet()) {
                            hot[frame] = (hot[frame] ?: 0) + 1
                        }
                    }
                    if (busyThreads == null && elapsed + SAMPLE_INTERVAL_MS >= threshold) {
                        busyThreads = otherThreads(mainThread)
                        observerDump = observers()
                    }
                }
                if (latch.await(SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    val duration = SystemClock.uptimeMillis() - start
                    if (duration < threshold) {
                        return null
                    }
                    return Freeze(duration, threshold, samples, maxGap, screenAtStart, stackAtStart, gcBefore, heapBefore, states, hot, hotDepth, topFrames, stacks, busyThreads, observerDump)
                }
                last = now
            }
            return null
        }

        private fun record(freeze: Freeze) {
            val gcAfter = gcStats()
            freezes++
            val number = freezes
            val samples = freeze.samples
            val sb = StringBuilder()
            sb.append("\n=== Freeze #" + number + " at " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + ": ")
            sb.append("main thread blocked " + freeze.durationMs + " ms\n")
            sb.append("threshold " + freeze.thresholdMs + " ms, " + samples + " samples every 20 ms from 100 ms\n")
            if (freeze.maxGapMs > 2000) {
                sb.append("WARNING: the monitor thread itself stalled for " + freeze.maxGapMs + " ms, the system probably froze the whole process; this record may be bogus\n")
            }
            sb.append("screen at start: " + freeze.screen + "\n")
            sb.append("fragment stack " + freeze.fragmentStack + "\n")
            sb.append("GC during freeze: blocking " + (gcAfter[0] - freeze.gcBefore[0]) + " (" + (gcAfter[1] - freeze.gcBefore[1]) + " ms), ")
            sb.append("all " + (gcAfter[2] - freeze.gcBefore[2]) + " (" + (gcAfter[3] - freeze.gcBefore[3]) + " ms)\n")
            sb.append("heap before: " + freeze.heapBefore + "\n")
            sb.append("heap after:  " + heapLine() + "\n")
            sb.append("main thread states: ")
            sb.append(freeze.states.entries.sortedByDescending { it.value }.joinToString(", ") { it.key + " " + it.value })
            sb.append('\n')
            if (samples > 0) {
                sb.append("\n-- hot app frames (share of samples, outermost first) --\n")
                val hotApp = freeze.hot.entries.filter { entry ->
                    entry.value * 100 >= samples * 5 && APP_FRAME_PREFIXES.any { entry.key.startsWith(it) }
                }
                val sorted = hotApp.sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { freeze.hotDepth[it.key] ?: 0 }
                )
                for (entry in sorted.take(60)) {
                    sb.append(percent(entry.value, samples) + "%  " + entry.key + "\n")
                }
                sb.append("\n-- top of stack --\n")
                for (entry in freeze.topFrames.entries.sortedByDescending { it.value }.take(10)) {
                    sb.append(percent(entry.value, samples) + "%  " + entry.key + "\n")
                }
                sb.append("\n-- most frequent stacks --\n")
                for (entry in freeze.stacks.entries.sortedByDescending { it.value }.take(5)) {
                    val count = entry.value
                    sb.append("[" + count + " samples, " + (count * 100 / samples) + "%]\n")
                    for (frame in entry.key) {
                        sb.append("    at ").append(frame).append('\n')
                    }
                }
            }
            if (freeze.otherThreads != null) {
                sb.append("\n-- other busy threads at the threshold --\n")
                sb.append(freeze.otherThreads)
            }
            if (freeze.observers != null) {
                sb.append("\n-- NotificationCenter observers at the threshold --\n")
                sb.append(freeze.observers)
            }
            sb.append('\n')
            sb.append(environment())
            sb.append("\n-- live objects (after GC) --\n")
            sb.append(liveObjects())
            appendReport(sb.toString())
            AndroidUtilities.runOnUIThread { showFreezeBulletin(freeze.durationMs) }
        }
    }
}
