package app.viberadio.feedback

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** Where feedback events are kept. Append-only: nothing is ever rewritten. Blocking; call off the main thread. */
interface FeedbackLog {
    fun append(event: FeedbackEvent)

    /** Every event in the order it was appended. Unreadable lines are skipped. */
    fun readAll(): List<FeedbackEvent>
}

/**
 * One event per line, tab-separated, appended and synced on every press:
 *
 * ```
 * 1  <atMs>  <utcOffsetMinutes>  <trackHash>  <UP|DOWN>  <channelId>
 * ```
 *
 * The leading field is the line format version. The channel ID comes last and has `\`, tab and
 * newline escaped. A line torn by a crash is skipped on read, and the next append starts on a
 * fresh line so it can't be glued onto the torn one.
 */
class TsvFeedbackLog(private val file: File) : FeedbackLog {

    override fun append(event: FeedbackEvent) {
        file.parentFile?.mkdirs()
        val line = encode(event)
        val prefix = if (endsMidLine()) "\n" else ""
        FileOutputStream(file, true).use { out ->
            out.write((prefix + line + "\n").encodeToByteArray())
            out.fd.sync()
        }
    }

    override fun readAll(): List<FeedbackEvent> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull(::decode)
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun endsMidLine(): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() != '\n'.code
        }
    }

    internal companion object {
        const val VERSION = "1"

        fun encode(e: FeedbackEvent): String =
            listOf(VERSION, e.atMs, e.utcOffsetMinutes, e.trackHash, e.thumb.name, escape(e.channelId))
                .joinToString("\t")

        fun decode(line: String): FeedbackEvent? {
            val f = line.split('\t')
            if (f.size != 6 || f[0] != VERSION) return null
            return FeedbackEvent(
                atMs = f[1].toLongOrNull() ?: return null,
                utcOffsetMinutes = f[2].toIntOrNull() ?: return null,
                trackHash = f[3].takeIf { it.isNotEmpty() } ?: return null,
                thumb = Thumb.entries.firstOrNull { it.name == f[4] } ?: return null,
                channelId = unescape(f[5]),
            )
        }

        private fun escape(s: String) = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
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
}
