package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.library.Track
import kotlin.random.Random

/**
 * Decides what a channel actually plays. Pure, so queue policy is unit-testable.
 *
 * Extension points:
 * - Issue #2 (auto-mixing): a mixed-channel planner interleaves or layers podcast and music items.
 * - Issue #4 (queue fallback): [onExhausted] returns the music-only channel to move to once
 *   the podcast queue runs out. It is only called at a track boundary, so the current track
 *   is never cut off.
 */
interface StreamPlanner {
    /** The ordered tracks to queue when [channel] is tuned in. */
    fun plan(channel: Channel): List<Track>

    /**
     * Called when [channel]'s queue has played to the end.
     * Return the channel to continue on; returning [channel] itself replans (reshuffles) it.
     */
    fun onExhausted(channel: Channel, allChannels: List<Channel>): Channel = channel
}

/** Scaffold behaviour: a fresh shuffle each time, looping forever on the same channel. */
class ShuffledStreamPlanner(private val random: Random = Random.Default) : StreamPlanner {
    override fun plan(channel: Channel): List<Track> = channel.tracks.shuffled(random)
}
