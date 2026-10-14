package app.viberadio.playback

/**
 * When podcast episodes may start on a song channel (#2). Pure; [RadioSessionCallback] reports
 * song boundaries and episode starts and ends, and asks whether an episode is due.
 *
 * - An episode only starts at a music track boundary, never part-way through a song.
 * - One episode at a time.
 * - After an episode ends, the music carries on and the next episode waits for the current
 *   track to finish. After an episode longer than 15 minutes one more full track plays first;
 *   after one longer than 30 minutes, three more.
 *
 * Applies to every podcast, whether it plays over the music or (a music podcast) by itself.
 */
class PodcastSchedule {

    /** Track boundaries still to pass before an episode may start. */
    var waitTracks: Int = 0
        private set

    var episodeActive: Boolean = false
        private set

    /** An episode would start at the next track boundary. */
    val due: Boolean get() = !episodeActive && waitTracks == 0

    /** Fresh state, e.g. on tuning to a channel. */
    fun reset() {
        waitTracks = 0
        episodeActive = false
    }

    /** A song just ended and the next one starts. True if an episode may start here. */
    fun songBoundary(): Boolean {
        if (episodeActive) return false
        if (waitTracks > 0) {
            waitTracks--
            return false
        }
        return true
    }

    fun episodeStarted() {
        episodeActive = true
    }

    /** The episode that was playing ended after [durationMs] of content (0 if unknown). */
    fun episodeEnded(durationMs: Long) {
        episodeActive = false
        waitTracks = extraTracksAfter(durationMs)
    }

    companion object {
        const val LONG_EPISODE_MS = 15 * 60_000L
        const val VERY_LONG_EPISODE_MS = 30 * 60_000L

        /** Full tracks to play, after the current one finishes, before the next episode. */
        fun extraTracksAfter(durationMs: Long): Int = when {
            durationMs > VERY_LONG_EPISODE_MS -> 3
            durationMs > LONG_EPISODE_MS -> 1
            else -> 0
        }
    }
}
