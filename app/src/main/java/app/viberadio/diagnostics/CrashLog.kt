package app.viberadio.diagnostics

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The app's own crash log (Features doc, Diagnostics): one entry per uncaught exception, its
 * time, app version, thread and full stack trace, newest [KEEP] kept, so an export shows why the
 * app last went down. Pure; [CrashRecorder] writes it as `crashes.txt`.
 */
object CrashLog {
    const val KEEP = 10

    /** Starts every entry's header line, so the log splits back into entries. */
    const val MARKER = "=== crash "

    fun entry(atMs: Long, utcOffsetMinutes: Int, appVersion: String, threadName: String, stackTrace: String): String {
        val offset = ZoneOffset.ofTotalSeconds(utcOffsetMinutes.coerceIn(-18 * 60, 18 * 60) * 60)
        val at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(atMs).atOffset(offset))
        return "$MARKER$at · app $appVersion · thread $threadName ===\n${stackTrace.trimEnd()}\n"
    }

    /** [log] with [entry] added last, the oldest entries dropped past [keep]. */
    fun append(log: String?, entry: String, keep: Int = KEEP): String =
        (entries(log) + entry.trimEnd() + "\n").takeLast(keep).joinToString("\n")

    /** The log's entries, oldest first. Anything before the first header is dropped. */
    fun entries(log: String?): List<String> {
        if (log.isNullOrBlank()) return emptyList()
        val starts = HEADER.findAll(log).map { it.range.first }.toList()
        return starts.mapIndexed { i, start ->
            log.substring(start, starts.getOrElse(i + 1) { log.length }).trimEnd() + "\n"
        }
    }

    private val HEADER = Regex("(?m)^" + Regex.escape(MARKER))
}

/**
 * Why one of the app's processes ended, as Android records it (`ApplicationExitInfo`, API 30+):
 * this also covers what [CrashLog] can't see, such as a low-memory kill or a native crash.
 * Sizes in kB, as the platform gives them.
 */
data class ExitRecord(
    val atMs: Long,
    /** `ApplicationExitInfo.REASON_*`; [ExitReasons.name] spells it out. */
    val reason: Int,
    val status: Int,
    /** `RunningAppProcessInfo.IMPORTANCE_*` when it ended: 100 foreground, 125 foreground service, 400 cached. */
    val importance: Int,
    val description: String?,
    val pssKb: Long,
    val rssKb: Long,
)

object ExitReasons {
    /** `ApplicationExitInfo.REASON_*` by value; unknown values as their number. */
    fun name(reason: Int): String = NAMES.getOrElse(reason) { reason.toString() }

    private val NAMES = listOf(
        "UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
        "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED",
        "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED",
    )
}
