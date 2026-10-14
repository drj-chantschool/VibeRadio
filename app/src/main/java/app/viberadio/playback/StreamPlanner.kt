package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.library.Track
import kotlin.math.pow
import kotlin.random.Random

/**
 * Decides what a channel actually plays, one song at a time. Pure, so queue policy is
 * unit-testable.
 *
 * Extension points:
 * - Issue #2 (auto-mixing): a mixed-channel planner interleaves or layers podcast and music items.
 * - Issue #4 (queue fallback): [onExhausted] returns the music-only channel to move to once
 *   the podcast queue runs out. It is only called at a track boundary, so the current track
 *   is never cut off.
 */
interface StreamPlanner {
    /**
     * The next song to play on [channel], or null if it has none. Songs in [avoid] (playing or
     * already queued) are not picked unless nothing else is on the channel.
     */
    fun next(channel: Channel, avoid: Set<Track> = emptySet()): Track?

    /**
     * Called when [channel]'s queue has played to the end.
     * Return the channel to continue on; returning [channel] itself keeps playing it.
     */
    fun onExhausted(channel: Channel, allChannels: List<Channel>): Channel = channel
}

/** The next [count] songs for [channel], each avoiding the ones before it (and [avoid]). */
fun StreamPlanner.upcoming(channel: Channel, count: Int, avoid: Set<Track> = emptySet()): List<Track> {
    val picked = LinkedHashSet<Track>()
    repeat(count) { next(channel, avoid + picked)?.let(picked::add) ?: return picked.toList() }
    return picked.toList()
}

/**
 * Every song is an independent weighted draw from the whole channel; there are no passes, so a
 * song can come round again before others have played once, and a disliked one comes up rarely.
 *
 * A song's weight is `p × (p × age)^k`:
 * - `age`: time since it was last heard, on the [ListeningClock][app.viberadio.feedback.ListeningClock]
 *   (song listening time, so time away doesn't age everything alike). Just played → 0, so no
 *   immediate repeats; it grows without limit, so nothing is starved. Capped at [capCycles]
 *   cycles (a cycle = the channel's total running time) so a song returning to a channel after a
 *   long absence (seasonal, a "Wrong channel" move) doesn't jump the line. A song never heard on
 *   the listening clock counts as last heard one cycle ago, i.e. due about now.
 * - `p`: the song's [preference] (#14), floored at [PREFERENCE_FLOOR]. Play frequency is roughly
 *   proportional to p, whatever k: a weight `q × age^k` gives a mean gap between plays that goes
 *   as `q^(-1/(k+1))`, so `q = p^(k+1)` makes the gap go as 1/p. (Without the extra factor of p,
 *   frequency would only go as `p^(k/(k+1))`.)
 * - `k` ([exponent]): randomness only. 1 is loose; higher approaches evenly spaced plays.
 */
class WeightedRandomStreamPlanner(
    /** The listening-clock reading when a track last started, or null if never (or unknown). */
    private val lastHeardAt: (Track) -> Long? = { null },
    /** The listening clock now. */
    private val listeningNow: () -> Long = { 0L },
    /** How strongly the user wants to hear a track; must be positive. Must not block. */
    private val preference: (Track) -> Double = { 1.0 },
    private val exponent: Double = DEFAULT_EXPONENT,
    private val capCycles: Double = DEFAULT_CAP_CYCLES,
    private val random: Random = Random.Default,
) : StreamPlanner {

    override fun next(channel: Channel, avoid: Set<Track>): Track? {
        val tracks = channel.tracks
        if (tracks.isEmpty()) return null
        val candidates = tracks.filter { it !in avoid }.ifEmpty { tracks }
        val weights = weights(channel, candidates)
        val total = weights.sum()
        if (!(total > 0)) return candidates[random.nextInt(candidates.size)]
        var r = random.nextDouble() * total
        for (i in candidates.indices) {
            r -= weights[i]
            if (r < 0) return candidates[i]
        }
        return candidates.last()
    }

    /** The draw weights of [candidates] on [channel], in order. */
    internal fun weights(channel: Channel, candidates: List<Track>): List<Double> {
        val cycle = channel.tracks.sumOf { it.durationMs.coerceAtLeast(MIN_TRACK_MS) }.toDouble()
        val cap = capCycles * cycle
        val now = listeningNow()
        return candidates.map { track ->
            // Clock behind the record (e.g. a crash lost a checkpoint): treat as just played.
            val age = lastHeardAt(track)?.let { (now - it).coerceAtLeast(0).toDouble() } ?: cycle
            val p = preference(track).coerceAtLeast(PREFERENCE_FLOOR)
            // Ages in cycles keep the numbers small; only the ratios matter.
            p * (p * age.coerceAtMost(cap) / cycle).pow(exponent)
        }
    }

    companion object {
        const val DEFAULT_EXPONENT = 1.5

        /** Bigger than the longest natural gap (about 1 / [PREFERENCE_FLOOR] cycles), so it only catches returns. */
        const val DEFAULT_CAP_CYCLES = 20.0

        const val PREFERENCE_FLOOR = 0.1

        /** Tracks with missing or tiny durations still count for something in the cycle. */
        const val MIN_TRACK_MS = 60_000L
    }
}
