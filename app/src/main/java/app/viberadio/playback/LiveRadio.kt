package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.library.Track
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The song a channel last put on air: which one (by content hash), how long it runs, and when it started. */
data class ChannelAiring(
    val channelId: String,
    val trackHash: String,
    val durationMs: Long,
    /** Wall-clock epoch milliseconds, so it survives restarts and reboots. */
    val startedAtMs: Long,
)

/** Persisted [ChannelAiring]s, one per channel, overwritten each time a song starts on it. */
interface ChannelAiringStore {
    fun readAll(): List<ChannelAiring>

    fun put(airing: ChannelAiring)
}

/**
 * Live-radio resume, songs only (#29): a song channel keeps "playing" while you're tuned away.
 *
 * Each song channel remembers its last song. Tuning back in while that song would still be on
 * air ([ChannelAiring.startedAtMs] + [ChannelAiring.durationMs] is in the future) joins it at
 * `now - start`; otherwise the channel shuffles as usual. It also shuffles if the clock went
 * backward, the file is gone (no track on the channel has that hash any more), or the song is no
 * longer on that channel.
 *
 * Podcasts are not involved: an episode resumes where you left it, never at a wall-clock point,
 * so it is only marked played once it has actually been heard.
 *
 * Pure apart from [store]: the airings are read into memory once ([load], or lazily) so tuning,
 * on the main thread, never touches the disk.
 */
class LiveRadio(
    private val store: ChannelAiringStore,
    /** A track's content hash if already known, without reading the file. */
    private val hashOf: (Track) -> String?,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What to queue on tuning in: the tracks in order, and where to start the first one (null = from the top). */
    data class TuneIn(val tracks: List<Track>, val resumeAtMs: Long?)

    private val airings = ConcurrentHashMap<String, ChannelAiring>()

    private val loaded by lazy {
        runCatching { store.readAll().forEach { airings[it.channelId] = it } }
    }

    /** Reads the store into memory. Blocking; call off the main thread, e.g. at startup. */
    fun load() = loaded

    /**
     * The queue for tuning in to [channel], given the songs [planned] for it. If the channel's
     * last song is still on air it goes first, joined part-way, and is dropped from the rest so
     * it isn't heard twice in a row.
     */
    fun tuneIn(channel: Channel, planned: List<Track>): TuneIn {
        val (track, positionMs) = onAir(channel) ?: return TuneIn(planned, null)
        return TuneIn(listOf(track) + (planned - track), positionMs)
    }

    /** The song still on air on [channel] and how far into it the broadcast is, or null to shuffle. */
    fun onAir(channel: Channel): Pair<Track, Long>? {
        load()
        val airing = airings[channel.id] ?: return null
        val elapsed = clock() - airing.startedAtMs
        if (elapsed < 0 || elapsed >= airing.durationMs) return null
        val track = channel.tracks.firstOrNull { hashOf(it) == airing.trackHash } ?: return null
        return track to elapsed
    }

    /** Records that the song with [trackHash] started on [channelId] at [startedAtMs]. */
    suspend fun record(channelId: String, trackHash: String, durationMs: Long, startedAtMs: Long) {
        val airing = ChannelAiring(channelId, trackHash, durationMs, startedAtMs)
        withContext(ioDispatcher) {
            load()
            airings[channelId] = airing
            runCatching { store.put(airing) }
        }
    }
}
