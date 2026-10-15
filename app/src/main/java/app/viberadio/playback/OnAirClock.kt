package app.viberadio.playback

/**
 * Time the channel has spent playing, in ms: runs between [start] and [stop], on a monotonic
 * [elapsed] clock. [PodcastSchedule] measures podcast streaks and music gaps on it, so a pause
 * counts as neither. Not persisted; only differences matter.
 */
class OnAirClock(private val elapsed: () -> Long) {
    private var baseMs = 0L

    /** [elapsed] when the clock last started, or null while stopped. */
    private var runningSince: Long? = null

    fun now(): Long = baseMs + (runningSince?.let { (elapsed() - it).coerceAtLeast(0) } ?: 0)

    fun start() {
        if (runningSince == null) runningSince = elapsed()
    }

    fun stop() {
        if (runningSince == null) return
        baseMs = now()
        runningSince = null
    }
}
