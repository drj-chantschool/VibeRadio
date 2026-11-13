package app.viberadio.playback

/** A named stretch of an episode, from [startMs] of its content to the next chapter's start. */
data class Chapter(val startMs: Long, val title: String)

/**
 * Chapters read from an episode's show notes, and which one is on air. Pure. File metadata
 * (ID3 chapters) is read by [OnAirTitlePlayer]; this is the fallback when a file has none.
 */
object Chapters {
    /** Show notes keep web links as `[text](url)` ([app.viberadio.podcasts.RssParser.plainText]). */
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")

    /** A line led by a time: optional bullet and bracket, `m:ss` or `h:mm:ss`, separator, then the title. */
    private val line = Regex("""^[\s\-*•]*[(\[]?(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[)\]]?[\s\-–—:.|)]*(\S.*)$""")

    /** A list of chapters starts at the top; a lone stray time in the text is not one. */
    private const val FIRST_START_LIMIT_MS = 60_000L

    /**
     * The chapters written as timestamps in [description], one per line ("00:00 Intro",
     * "(12:34) Interview", "1:02:03 - Wrap-up"). Needs at least two, strictly ascending, the first
     * within the opening minute; otherwise empty.
     */
    fun fromDescription(description: String?): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()
        val chapters = description.lines().mapNotNull { raw ->
            val match = line.matchEntire(link.replace(raw, "$1").trim()) ?: return@mapNotNull null
            val (hours, minutes, seconds, title) = match.destructured
            val start = ((hours.toLongOrNull() ?: 0) * 3600 + minutes.toLong() * 60 + seconds.toLong()) * 1000
            if (seconds.toInt() > 59) null else Chapter(start, title.trim())
        }
        val ascending = chapters.zipWithNext().all { (a, b) -> a.startMs < b.startMs }
        return if (chapters.size >= 2 && ascending && chapters.first().startMs <= FIRST_START_LIMIT_MS) chapters else emptyList()
    }

    /** The title of the chapter on air at [positionMs] of [chapters] (sorted by start), null before the first. */
    fun at(chapters: List<Chapter>, positionMs: Long): String? =
        chapters.lastOrNull { it.startMs <= positionMs }?.title?.takeIf { it.isNotBlank() }
}
