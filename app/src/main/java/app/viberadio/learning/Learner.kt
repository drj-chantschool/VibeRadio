package app.viberadio.learning

import app.viberadio.channels.Channel
import app.viberadio.dial.ChannelLineup
import app.viberadio.feedback.FeedbackEvent
import app.viberadio.features.SimilarityIndex
import app.viberadio.library.Track

/**
 * Applies [LearningModels] to the library (#14): places songs on the mood channels and gives
 * the shuffle each song's preference weight.
 *
 * Holds every song's [FeatureVector] in memory, built from the feedback log on [load] and kept
 * current with [add]. A song whose hash isn't known yet (it has never played, so it has no
 * feedback either) reads as [FeatureVector.EMPTY].
 *
 * With a [similarity] index (audio features, see `features`), each song's placement also gets
 * the thumbs of its [neighbourCount] most similar songs that have mood-channel feedback, so a
 * few thumbs spread to the songs that sound like them.
 */
class Learner(
    private val models: LearningModels,
    /** Every feedback event so far. Blocking; called once, from [load]. */
    private val readEvents: () -> List<FeedbackEvent>,
    /** A track's content hash if already known, without reading the file. */
    private val hashOf: (Track) -> String?,
    /** The current audio similarity index; empty until songs are analysed. Never blocks. */
    private val similarity: () -> SimilarityIndex = { SimilarityIndex.EMPTY },
    private val neighbourCount: Int = DEFAULT_NEIGHBOURS,
) {
    @Volatile
    private var vectors: Map<String, FeatureVector> = emptyMap()

    private val loaded by lazy {
        vectors = runCatching { FeatureVectors.from(readEvents()) }.getOrDefault(emptyMap())
    }

    /** Reads the feedback log. Blocking; call off the main thread, e.g. at startup. */
    fun load() = loaded

    /** Counts a newly logged [event] in. */
    fun add(event: FeedbackEvent) {
        load()
        synchronized(this) { vectors = vectors + (event.trackHash to (vectors[event.trackHash] ?: FeatureVector.EMPTY) + event) }
    }

    fun vectorOf(track: Track): FeatureVector = hashOf(track)?.let { vectors[it] } ?: FeatureVector.EMPTY

    /**
     * [channels] with the mood channels rebuilt by the channel-fit model: every song that was on
     * any mood channel goes on each channel the model picks. Other channels (The Shuffle) pass
     * through unchanged. A mood channel left empty is dropped, so it stays off the dial.
     * Blocking on first use (loads the log); call off the main thread.
     */
    fun place(channels: List<Channel>): List<Channel> {
        val moodChannels = channels.filter { it.id in ChannelLineup.moods }
        if (moodChannels.isEmpty()) return channels
        load()
        val members = ChannelLineup.moods.associateWith { mutableListOf<Track>() }
        val current = vectors
        val index = similarity()
        // Songs that can lend evidence: analysed, with thumbs on some mood channel.
        val rated = if (index.size == 0) emptyList() else current.filter { (hash, v) -> hash in index && v.channelFit.any { it > 0f } }.keys.toList()
        for (track in moodChannels.flatMap { it.tracks }.distinctBy { it.uri }) {
            val hash = hashOf(track)
            val neighbours = if (hash == null || rated.isEmpty()) emptyList() else {
                index.nearest(hash, rated, neighbourCount).map { NeighbourFit(it.distance, current.getValue(it.hash).channelFit) }
            }
            models.channelsFor(hash?.let { current[it] } ?: FeatureVector.EMPTY, neighbours).forEach { members.getValue(it) += track }
        }
        val template = moodChannels.first()
        val placed = ChannelLineup.moods.mapNotNull { id ->
            val tracks = members.getValue(id).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            (moodChannels.firstOrNull { it.id == id } ?: template.copy(id = id, name = id)).copy(tracks = tracks)
        }
        return placed + channels.filter { it.id !in ChannelLineup.moods }
    }

    /** How strongly the shuffle should favour [track]; never below [LearningModels.preferenceFloor]. Never blocks. */
    fun preferenceWeight(track: Track): Double = models.preferenceWeight(vectorOf(track))

    companion object {
        /** Similar songs consulted per song (the plan's k). */
        const val DEFAULT_NEIGHBOURS = 20
    }
}
