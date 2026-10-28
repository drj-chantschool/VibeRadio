package app.viberadio.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.util.TimeZone

/**
 * Keeps [CrashLog] in `crashes.txt`: [install] records every uncaught exception before handing it
 * on to the platform's handler, which still shows the crash and ends the process.
 */
class CrashRecorder(file: File, private val appVersion: String) {
    private val log = AtomicFile(file)

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** The log, or null if the app hasn't crashed since it started keeping one. */
    fun read(): String? = runCatching { log.readFully().decodeToString() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Forgets the log once it has been exported. */
    fun clear() = log.delete()

    /** Best effort, and never throws: the process is going down either way (after an OutOfMemoryError there may be no room). */
    private fun record(threadName: String, error: Throwable) {
        try {
            val now = System.currentTimeMillis()
            val entry = CrashLog.entry(now, TimeZone.getDefault().getOffset(now) / 60_000, appVersion, threadName, error.stackTraceToString())
            val bytes = CrashLog.append(read(), entry).encodeToByteArray()
            val out = log.startWrite()
            try {
                out.write(bytes)
                log.finishWrite(out)
            } catch (e: Throwable) {
                log.failWrite(out)
            }
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        fun forApp(context: Context): CrashRecorder {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            return CrashRecorder(File(context.filesDir, "crashes.txt"), version ?: "unknown")
        }

        /** Why the app's processes ended, newest first, as Android keeps it; empty before API 30. */
        fun exitReasons(context: Context): List<ExitRecord> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
            val activities = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return runCatching {
                activities.getHistoricalProcessExitReasons(context.packageName, 0, 0).map {
                    ExitRecord(it.timestamp, it.reason, it.status, it.importance, it.description, it.pss, it.rss)
                }
            }.getOrDefault(emptyList())
        }
    }
}
