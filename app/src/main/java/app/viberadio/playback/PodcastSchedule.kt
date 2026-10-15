package app.viberadio.playback

import kotlin.math.roundToLong

/**
 * When podcast episodes may start on a song channel (#2). Pure; [RadioSessionCallback] reports
 * song boundaries and episode starts and ends, and asks whether an episode is due.
 *
 * - One episode at a time.
 * - Fresh (tuned in, or after an episode that failed to play): the next track boundary.
 * - After an episode ends, the music carries on for as long as [rule] says ([PodcastGapRule]):
 *   a number of track boundaries ([Gap.Tracks]), or a stretch of music ([Gap.Music]), after
 *   which an episode may start part-way through a song.
 *
 * Times are on a clock that runs only while the channel is playing ([OnAirClock]), so a pause
 * doesn't count as music. Applies to every podcast, whether it plays over the music or (a music
 * podcast) by itself; a music podcast still waits for a track boundary.
 */
class PodcastSchedule(private val rule: () -> PodcastGapRule = { PodcastGapRule.DEFAULT }) {

    /** What the next episode waits for. */
    sealed interface Wait {
        /** The next track boundary. */
        data object Boundary : Wait

        /** [left] more track boundaries, then the one after. */
        data class Tracks(val left: Int) : Wait

        /**
         * A stretch of music: any track boundary from [openAt], or part-way through a song from
         * [nominalAt] if no boundary comes by [closeAt].
         */
        data class Music(val openAt: Long, val nominalAt: Long, val closeAt: Long) : Wait
    }

    var wait: Wait = Wait.Boundary
        private set

    var episodeActive: Boolean = false
        private set

    /** Podcast time played back to back, up to the end of the last episode. */
    var streakMs: Long = 0
        private set

    private var episodeStartedAt = 0L

    /** The episode now playing continues the streak. */
    private var continuesStreak = false

    /** When the last episode ended, if it asked for no gap (so the next one can join its streak). */
    private var noGapSince: Long? = null

    /** Fresh state, e.g. on tuning to a channel. */
    fun reset() {
        wait = Wait.Boundary
        episodeActive = false
        streakMs = 0
        continuesStreak = false
        noGapSince = null
    }

    /**
     * Whether an episode would start at a track boundary at [boundaryAtMs] (null if not known).
     * For queueing a music podcast in the song's place ahead of time.
     */
    fun dueAtBoundary(boundaryAtMs: Long?): Boolean {
        if (episodeActive) return false
        return when (val w = wait) {
            Wait.Boundary -> true
            is Wait.Tracks -> w.left == 0
            is Wait.Music -> boundaryAtMs != null && boundaryAtMs >= w.openAt
        }
    }

    /** A song just ended at [nowMs] and the next one starts. True if an episode may start here. */
    fun songBoundary(nowMs: Long): Boolean {
        if (episodeActive) return false
        return when (val w = wait) {
            Wait.Boundary -> true
            is Wait.Tracks -> if (w.left > 0) {
                wait = Wait.Tracks(w.left - 1)
                false
            } else {
                true
            }
            is Wait.Music -> nowMs >= w.openAt
        }
    }

    /**
     * Whether an episode should start now, at [nowMs], part-way through the song playing, which
     * ends at [songEndsAtMs] (null if not known): the wait is over and no boundary is coming soon
     * enough to wait for.
     */
    fun startMidSong(nowMs: Long, songEndsAtMs: Long?): Boolean {
        if (episodeActive) return false
        val w = wait as? Wait.Music ?: return false
        if (nowMs < w.nominalAt) return false
        return songEndsAtMs == null || songEndsAtMs > w.closeAt
    }

    fun episodeStarted(nowMs: Long) {
        episodeActive = true
        episodeStartedAt = nowMs
        val since = noGapSince
        continuesStreak = since != null && nowMs - since <= BACK_TO_BACK_MS
        noGapSince = null
    }

    /** The episode that was playing ended at [nowMs] after [contentMs] of content (0 if unknown). */
    fun episodeEnded(contentMs: Long, nowMs: Long) {
        episodeActive = false
        streakMs = (if (continuesStreak) streakMs else 0) + (nowMs - episodeStartedAt).coerceAtLeast(0)
        wait = when (val gap = rule().gapAfter(contentMs, streakMs)) {
            is Gap.Tracks -> Wait.Tracks(gap.count)
            is Gap.Music -> {
                val slack = (gap.musicMs * gap.tolerance).roundToLong()
                Wait.Music(nowMs + gap.musicMs - slack, nowMs + gap.musicMs, nowMs + gap.musicMs + slack)
            }
        }
        noGapSince = nowMs.takeIf { (wait as? Wait.Music)?.nominalAt == nowMs }
    }

    /** The episode couldn't play. It's tried again at the next track boundary, not straight away. */
    fun episodeFailed() {
        episodeActive = false
        wait = Wait.Boundary
        continuesStreak = false
        noGapSince = null
    }

    companion object {
        /** An episode starting this soon after one that asked for no gap continues its streak. */
        const val BACK_TO_BACK_MS = 2_000L
    }
}
