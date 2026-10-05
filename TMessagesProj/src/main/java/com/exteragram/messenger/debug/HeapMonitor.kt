package com.exteragram.messenger.debug

import android.app.Activity
import android.os.Debug
import android.os.SystemClock
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.FileLog
import org.telegram.messenger.R
import org.telegram.messenger.Utilities
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.ForegroundDetector
import org.telegram.ui.LaunchActivity
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

object HeapMonitor {

    private const val CHECK_INTERVAL = 5000L
    private const val COLLECT_INTERVAL = 30000L
    private const val MIN_LIMIT_MB = 64

    @Volatile
    private var watcher: LimitWatcher? = null

    @Volatile
    private var dumping = false

    private var alertRef: WeakReference<AlertDialog>? = null

    val dumpsDir: File by lazy { debugFilesDir("heapdumps") }

    val promptStepBytes: Long
        get() = Runtime.getRuntime().maxMemory() / 8

    @JvmStatic
    fun init() {
        if (DebugConfig.heapMonitorEnabled) {
            watcher = LimitWatcher()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (DebugConfig.heapMonitorEnabled == enabled) {
            return
        }
        DebugConfig.heapMonitorEnabled = enabled
        watcher?.stop()
        watcher = if (enabled) LimitWatcher() else null
    }

    val limitMb: Int
        get() = DebugConfig.heapMonitorLimitMb.coerceIn(MIN_LIMIT_MB, maxLimitMb)

    val maxLimitMb: Int
        get() = maxOf(MIN_LIMIT_MB, ((Runtime.getRuntime().maxMemory() shr 20).toInt() / 16 - 1) * 16)

    fun setLimitMb(value: Int) {
        DebugConfig.heapMonitorLimitMb = value
        watcher?.resetPrompt()
    }

    val usedBytes: Long
        get() {
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }

    val lastDump: File?
        get() = dumpsDir.listFiles()?.filter { it.name.endsWith(".zip") }?.maxByOrNull { it.lastModified() }

    val dumpsSize: Long
        get() = dumpsDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteDumps(onDone: () -> Unit) {
        if (dumping) {
            return
        }
        Utilities.globalQueue.postRunnable {
            dumpsDir.listFiles()?.forEach { it.delete() }
            AndroidUtilities.runOnUIThread { onDone() }
        }
    }

    fun shareLastDump(activity: Activity) {
        lastDump?.let { share(activity, it) }
    }

    fun dumpAndShare(activity: Activity, resourcesProvider: Theme.ResourcesProvider?) {
        if (dumping) {
            return
        }
        dumping = true
        val progressDialog = AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING, resourcesProvider)
        progressDialog.setMessage("Dumping heap…")
        progressDialog.setCanceledOnTouchOutside(false)
        progressDialog.setCancelable(false)
        progressDialog.show()
        thread(name = "heapDump") {
            SystemClock.sleep(300)
            val result = runCatching {
                dumpHeap { progress ->
                    AndroidUtilities.runOnUIThread {
                        if (progress == 0) {
                            progressDialog.setMessage("Compressing…")
                        }
                        progressDialog.setProgress(progress)
                    }
                }
            }
            AndroidUtilities.runOnUIThread {
                dumping = false
                try {
                    progressDialog.dismiss()
                } catch (e: Exception) {
                    FileLog.e(e)
                }
                result.onSuccess { share(activity, it) }
                result.exceptionOrNull()?.let {
                    FileLog.e(it)
                    BulletinFactory.global().createSimpleBulletin(R.raw.error, "Heap dump failed: $it").show()
                }
            }
        }
    }

    private fun showLimitAlert(usedBytes: Long, limitBytes: Long) {
        val activity = LaunchActivity.instance
        if (watcher == null || dumping || activity == null || activity.isFinishing) {
            return
        }
        if (alertRef?.get()?.isShowing == true) {
            return
        }
        val maxBytes = Runtime.getRuntime().maxMemory()
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Heap limit exceeded")
            .setMessage(
                "Java heap is at " + AndroidUtilities.formatFileSize(usedBytes, true, false) +
                    " of " + AndroidUtilities.formatFileSize(maxBytes, true, false) +
                    " after GC, over the " + AndroidUtilities.formatFileSize(limitBytes, true, false) + " limit.\n\n" +
                    "The app freezes while dumping. The dump holds everything in memory, so share it only with people you trust."
            )
            .setPositiveButton("Dump & share") { _, _ -> dumpAndShare(activity, null) }
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

    internal fun collectGarbage() {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        runtime.runFinalization()
        runtime.gc()
    }

    private fun dumpHeap(onProgress: (Int) -> Unit): File {
        dumpsDir.mkdirs()
        val name = "heap_" + BuildVars.BUILD_VERSION_STRING + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val hprof = File(dumpsDir, "$name.hprof")
        val part = File(dumpsDir, "$name.zip.part")
        val zip = File(dumpsDir, "$name.zip")
        try {
            collectGarbage()
            watcher?.postponePrompt(usedBytes)
            Debug.dumpHprofData(hprof.absolutePath)
            compress(hprof, part, onProgress)
            if (!part.renameTo(zip)) {
                throw IOException("Can't rename " + part.name)
            }
            hprof.delete()
            part.delete()
            dumpsDir.listFiles()?.forEach {
                if (it != zip) {
                    it.delete()
                }
            }
            return zip
        } catch (t: Throwable) {
            hprof.delete()
            part.delete()
            throw t
        }
    }

    private fun compress(source: File, target: File, onProgress: (Int) -> Unit) {
        val total = maxOf(1L, source.length())
        onProgress(0)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target), 65536)).use { zip ->
            zip.setLevel(1)
            zip.putNextEntry(ZipEntry(source.name))
            FileInputStream(source).use { input ->
                val buffer = ByteArray(65536)
                var written = 0L
                var lastProgress = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    zip.write(buffer, 0, read)
                    written += read
                    val progress = (100 * written / total).toInt()
                    if (progress != lastProgress) {
                        onProgress(progress)
                        lastProgress = progress
                    }
                }
            }
            zip.closeEntry()
        }
    }

    private fun share(activity: Activity, file: File) {
        shareDebugFile(activity, file, "application/zip", "Share heap dump")
    }

    class LimitWatcher : ForegroundDetector.Listener {

        private val queue = DispatchQueue("heapMonitorQueue")
        private val checkRunnable = Runnable { check() }

        @Volatile
        private var running = false
        private var nextPromptBytes = 0L
        private var lastCollectTime = 0L

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
            running = false
            queue.cleanupQueue()
            queue.recycle()
        }

        fun resetPrompt() {
            queue.postRunnable {
                nextPromptBytes = 0L
                lastCollectTime = 0L
            }
        }

        fun postponePrompt(usedBytes: Long) {
            queue.postRunnable {
                nextPromptBytes = maxOf(nextPromptBytes, usedBytes + promptStepBytes)
            }
        }

        private fun updateRunning() {
            val foreground = ForegroundDetector.getInstance()?.isForeground == true
            if (running == foreground) {
                return
            }
            running = foreground
            queue.postRunnable {
                queue.cancelRunnable(checkRunnable)
                if (running) {
                    queue.postRunnable(checkRunnable, CHECK_INTERVAL)
                }
            }
        }

        private fun check() {
            if (!running) {
                return
            }
            val limitBytes = limitMb.toLong() shl 20
            val used = usedBytes
            if (used >= limitBytes) {
                if (used >= nextPromptBytes && !dumping && !ApplicationLoader.mainInterfacePaused &&
                    SystemClock.elapsedRealtime() - lastCollectTime >= COLLECT_INTERVAL
                ) {
                    lastCollectTime = SystemClock.elapsedRealtime()
                    collectGarbage()
                    val usedAfterGc = usedBytes
                    if (usedAfterGc >= maxOf(limitBytes, nextPromptBytes)) {
                        nextPromptBytes = usedAfterGc + promptStepBytes
                        AndroidUtilities.runOnUIThread { showLimitAlert(usedAfterGc, limitBytes) }
                    }
                }
            } else {
                nextPromptBytes = 0L
            }
            if (running) {
                queue.postRunnable(checkRunnable, CHECK_INTERVAL)
            }
        }
    }
}
