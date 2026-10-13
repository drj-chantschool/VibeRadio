package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.library.Track
import kotlin.math.ln
import kotlin.math.pow
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

/**
 * A fresh shuffle each time, looping forever on the same channel, weighted so songs heard
 * recently tend to come later. Every track is still queued exactly once.
 *
 * A track's weight is `1 - 2^(-age / halfLife)`: just played ≈ 0, one half-life ago 0.5, long
 * ago or never → 1. The order is a weighted random permutation (Efraimidis–Spirakis: sort by
 * `u^(1/w)` descending), so with no history it's a uniform shuffle. Weights are floored at
 * [MIN_WEIGHT] so nothing is pinned to the end.
 */
class RecencyWeightedStreamPlanner(
    /** When a track last started playing, epoch ms, or null if never (or unknown). */
    private val lastPlayedMs: (Track) -> Long? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val halfLifeMs: Long = DEFAULT_HALF_LIFE_MS,
    private val random: Random = Random.Default,
) : StreamPlanner {

    override fun plan(channel: Channel): List<Track> {
        val now = clock()
        return channel.tracks
            .map { it to key(weight(it, now)) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    internal fun weight(track: Track, now: Long): Double {
        val played = lastPlayedMs(track) ?: return 1.0
        val age = (now - played).coerceAtLeast(0).toDouble()
        return (1 - 2.0.pow(-age / halfLifeMs)).coerceAtLeast(MIN_WEIGHT)
    }

    /** `ln(u) / w` orders the same as `u^(1/w)` without underflowing for tiny weights. */
    private fun key(weight: Double): Double {
        val u = random.nextDouble().coerceAtLeast(Double.MIN_VALUE)
        return ln(u) / weight
    }

    companion object {
        const val DEFAULT_HALF_LIFE_MS = 3L * 24 * 60 * 60 * 1000
        const val MIN_WEIGHT = 1e-3
    }
}
