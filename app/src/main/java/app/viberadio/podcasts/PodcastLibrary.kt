package app.viberadio.podcasts

/**
 * Everything the podcast pipeline knows, as one immutable value: shows, episodes, and the
 * global played / resume state (#19). Every change returns a new library, so the repository
 * can publish it as a StateFlow and persist it whole.
 *
 * Queue policy (#19): one queue for the whole app. A channel plays the unplayed episodes of
 * the shows assigned to it, an episode already started first (so tuning away and back
 * resumes it), then oldest first, so a back catalogue plays in order. Marking older
 * episodes played is how the user skips a back catalogue they don't want.
 */
data class PodcastLibrary(
    val podcasts: List<Podcast> = emptyList(),
    /** In feed order per show (usually newest first). */
    val episodes: List<Episode> = emptyList(),
    val played: Set<String> = emptySet(),
    /** Resume positions of started, unplayed episodes. */
    val positions: Map<String, Long> = emptyMap(),
) {
    private val episodesById: Map<String, Episode> by lazy { episodes.associateBy { it.id } }

    fun podcast(feedUrl: String): Podcast? = podcasts.firstOrNull { it.feedUrl == feedUrl }

    fun episode(id: String): Episode? = episodesById[id]

    /** A show's episodes, newest first. */
    fun episodesOf(feedUrl: String): List<Episode> =
        episodes.filter { it.feedUrl == feedUrl }.sortedByDescending { it.publishedMs }

    fun isPlayed(id: String): Boolean = id in played

    /** Every channel at least one show is assigned to. */
    val channelIds: Set<String> get() = podcasts.flatMapTo(LinkedHashSet()) { it.channels }

    /** What [channelId] plays next, in order. Empty if nothing is assigned or everything is played. */
    fun queue(channelId: String): List<Episode> {
        val shows = podcasts.filter { channelId in it.channels }.mapTo(HashSet()) { it.feedUrl }
        return ordered(episodes.filter { it.feedUrl in shows && it.id !in played })
    }

    /** The next [limit] episodes across all channels: what's worth having on the device. */
    fun upNext(limit: Int): List<Episode> {
        val shows = podcasts.filter { it.channels.isNotEmpty() }.mapTo(HashSet()) { it.feedUrl }
        return ordered(episodes.filter { it.feedUrl in shows && it.id !in played }).take(limit)
    }

    private fun ordered(unplayed: List<Episode>): List<Episode> =
        unplayed.sortedWith(
            compareByDescending<Episode> { (positions[it.id] ?: 0L) > 0L }.thenBy { it.publishedMs },
        )

    /**
     * Adds a new show from [feed], or merges a refresh into an existing one. Existing episodes
     * keep their played state and position; episodes that dropped off the feed are kept, since
     * feeds often truncate their back catalogue.
     */
    fun withFeed(feedUrl: String, feed: Feed, nowMs: Long): PodcastLibrary {
        val existing = podcast(feedUrl)
        val podcast = existing?.copy(title = feed.title, lastRefreshedMs = nowMs)
            ?: Podcast(feedUrl = feedUrl, title = feed.title, channels = PodcastChannels.DEFAULT, lastRefreshedMs = nowMs)

        val fresh = feed.items
            .map { item ->
                Episode(
                    id = EpisodeIds.of(feedUrl, item.guid),
                    feedUrl = feedUrl,
                    title = item.title,
                    publishedMs = item.publishedMs,
                    audioUrl = item.audioUrl,
                    durationMs = item.durationMs,
                )
            }
            .distinctBy { it.id }
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val kept = episodes.filter { it.feedUrl == feedUrl && it.id !in freshIds }
        val others = episodes.filter { it.feedUrl != feedUrl }

        return copy(
            podcasts = if (existing == null) podcasts + podcast else podcasts.map { if (it.feedUrl == feedUrl) podcast else it },
            episodes = others + fresh + kept,
        )
    }

    fun withoutPodcast(feedUrl: String): PodcastLibrary {
        val gone = episodes.filter { it.feedUrl == feedUrl }.mapTo(HashSet()) { it.id }
        return copy(
            podcasts = podcasts.filter { it.feedUrl != feedUrl },
            episodes = episodes.filter { it.feedUrl != feedUrl },
            played = played - gone,
            positions = positions - gone,
        )
    }

    fun withChannel(feedUrl: String, channelId: String, assigned: Boolean): PodcastLibrary = copy(
        podcasts = podcasts.map {
            if (it.feedUrl != feedUrl) it else it.copy(channels = if (assigned) it.channels + channelId else it.channels - channelId)
        },
    )

    /** Playing to the end and marking by hand are the same thing; either clears the resume point. */
    fun withPlayed(id: String, isPlayed: Boolean): PodcastLibrary {
        if (episode(id) == null) return this
        return copy(
            played = if (isPlayed) played + id else played - id,
            positions = positions - id,
        )
    }

    /** Marks [id] and every older episode of the same show played, e.g. to skip a back catalogue. */
    fun withOlderPlayed(id: String): PodcastLibrary {
        val anchor = episode(id) ?: return this
        val older = episodesOf(anchor.feedUrl).dropWhile { it.id != id }.map { it.id }
        return copy(played = played + older, positions = positions - older.toSet())
    }

    fun withPosition(id: String, positionMs: Long): PodcastLibrary {
        if (episode(id) == null || id in played) return this
        return copy(positions = if (positionMs > 0) positions + (id to positionMs) else positions - id)
    }
}
