package app.viberadio.feedback

import java.io.File
import java.io.IOException

/**
 * Reader for the tab-separated `feedback.log` that builds before the SQLite log wrote, one event
 * per line:
 *
 * ```
 * 1  <atMs>  <utcOffsetMinutes>  <trackHash>  <UP|DOWN>  <channelId>
 * ```
 *
 * Only used to import that file into [FeedbackDatabase] once. Torn or unknown lines are skipped.
 */
internal object LegacyTsvLog {

    fun read(file: File): List<FeedbackEvent> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull(::decode)
        } catch (e: IOException) {
            emptyList()
        }
    }

    fun decode(line: String): FeedbackEvent? {
        val f = line.split('\t')
        if (f.size != 6 || f[0] != "1") return null
        return FeedbackEvent(
            atMs = f[1].toLongOrNull() ?: return null,
            utcOffsetMinutes = f[2].toIntOrNull() ?: return null,
            trackHash = f[3].takeIf { it.isNotEmpty() } ?: return null,
            thumb = Thumb.entries.firstOrNull { it.name == f[4] } ?: return null,
            channelId = unescape(f[5]),
        )
    }

    private fun unescape(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\' || i == s.length) {
                append(c)
                continue
            }
            when (val n = s[i++]) {
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                else -> append(n)
            }
        }
    }
}
