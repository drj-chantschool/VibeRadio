package app.viberadio.podcasts

/**
 * Everything the podcast pipeline knows, as one immutable value: shows, episodes, and the
 * global played / resume state (#19). Every change returns a new library, so the repository
 * can publish it as a StateFlow and persist it whole.
 *
 * Queue policy (#19): one queue for the whole app. A channel plays the unplayed episodes of
 * the shows assigned to it, an episode already started first (so tuning away and back
 * resumes it), then oldest first, so a back catalogue plays in order. Each show's priority
 * comes before all of that: a show on a lower priority plays all its unplayed episodes before
 * a show on a higher one. Marking older episodes played is how the user skips a back
 * catalogue they don't want.
 */
data class PodcastLibrary(
    val podcasts: List<Podcast> = emptyList(),
    /** In feed order per show (usually newest first). */
    val episodes: List<Episode> = emptyList(),
    val played: Set<String> = emptySet(),
    /** Resume positions of started, unplayed episodes. */
    val positions: Map<String, Long> = emptyMap(),
    /**
     * When each episode was last on air (wall clock), for the page's history. Only playback writes
     * it: marking an episode played by hand isn't hearing it. Kept for [HISTORY_MS] only.
     */
    val heard: Map<String, Long> = emptyMap(),
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

    /** Episodes on air in the [HISTORY_MS] up to [nowMs], most recently heard first. */
    fun history(nowMs: Long): List<Episode> =
        heard.entries
            .filter { (_, at) -> at > nowMs - HISTORY_MS }
            .sortedByDescending { it.value }
            .mapNotNull { (id, _) -> episode(id) }

    private fun ordered(unplayed: List<Episode>): List<Episode> {
        val priorities = podcasts.associate { it.feedUrl to it.priority }
        return unplayed.sortedWith(
            compareBy<Episode> { priorities[it.feedUrl] ?: PodcastPriority.DEFAULT }
                .thenByDescending { (positions[it.id] ?: 0L) > 0L }
                .thenBy { it.publishedMs },
        )
    }

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
                    description = item.description,
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

    /**
     * Brings the folder shows ([LocalFeeds]) in line with what's on the device: [feeds] by feed
     * URL, each the folder's files as last scanned. A show is added the first time its folder has
     * any audio; after that its episodes are exactly the folder's files (none while it's empty, the
     * show and its settings kept). Folder shows not in [feeds] are removed. Played state and
     * resume points are kept by episode ID, so a file that comes back (an SD card put back in)
     * stays played.
     */
    fun withLocalFeeds(feeds: Map<String, Feed>, nowMs: Long): PodcastLibrary {
        var library = podcasts.filter { LocalFeeds.isLocal(it.feedUrl) && it.feedUrl !in feeds }
            .fold(this) { lib, gone -> lib.withoutPodcast(gone.feedUrl) }
        for ((feedUrl, feed) in feeds) {
            require(LocalFeeds.isLocal(feedUrl)) { "not a folder show: $feedUrl" }
            if (library.podcast(feedUrl) == null && feed.items.isEmpty()) continue
            val fresh = library.withFeed(feedUrl, feed, nowMs)
            val freshIds = feed.items.mapTo(HashSet()) { EpisodeIds.of(feedUrl, it.guid) }
            // Unlike a web feed, a file that's gone is gone: drop the episodes withFeed kept.
            val synced = fresh.copy(episodes = fresh.episodes.filter { it.feedUrl != feedUrl || it.id in freshIds })
            // A rescan that found the same files changes nothing, so nothing is saved.
            val unchanged = library.podcast(feedUrl)?.title == feed.title &&
                library.episodes.filter { it.feedUrl == feedUrl } == synced.episodes.filter { it.feedUrl == feedUrl }
            if (!unchanged) library = synced
        }
        return library
    }

    fun withoutPodcast(feedUrl: String): PodcastLibrary {
        val gone = episodes.filter { it.feedUrl == feedUrl }.mapTo(HashSet()) { it.id }
        return copy(
            podcasts = podcasts.filter { it.feedUrl != feedUrl },
            episodes = episodes.filter { it.feedUrl != feedUrl },
            played = played - gone,
            positions = positions - gone,
            heard = heard - gone,
        )
    }

    fun withChannel(feedUrl: String, channelId: String, assigned: Boolean): PodcastLibrary = copy(
        podcasts = podcasts.map {
            if (it.feedUrl != feedUrl) it else it.copy(channels = if (assigned) it.channels + channelId else it.channels - channelId)
        },
    )

    fun withSpeed(feedUrl: String, speed: Float): PodcastLibrary = copy(
        podcasts = podcasts.map { if (it.feedUrl != feedUrl) it else it.copy(speed = PodcastSpeeds.snap(speed)) },
    )

    fun withPriority(feedUrl: String, priority: Int): PodcastLibrary = copy(
        podcasts = podcasts.map { if (it.feedUrl != feedUrl) it else it.copy(priority = PodcastPriority.clamp(priority)) },
    )

    fun withSkipIntro(feedUrl: String, seconds: Int): PodcastLibrary = copy(
        podcasts = podcasts.map { if (it.feedUrl != feedUrl) it else it.copy(skipIntroSeconds = PodcastSkipIntro.snap(seconds)) },
    )

    fun withPauseMusic(feedUrl: String, pause: Boolean): PodcastLibrary = copy(
        podcasts = podcasts.map { if (it.feedUrl != feedUrl) it else it.copy(pauseMusic = pause) },
    )

    /** Whether [episodeId]'s show has "Pause music" on; false if the show is gone. */
    fun pausesMusic(episodeId: String): Boolean =
        episode(episodeId)?.let { podcast(it.feedUrl) }?.pauseMusic ?: false

    /** How fast [episodeId] plays: its show's speed, or 1x if the show is gone. */
    fun speedOf(episodeId: String): Float =
        episode(episodeId)?.let { podcast(it.feedUrl) }?.speed ?: PodcastSpeeds.DEFAULT

    /**
     * Where [episodeId] starts playing: its resume point if started, else past its show's intro
     * ("Skip intro"), else null for the top. An intro skip as long as the episode is ignored.
     */
    fun startPositionOf(episodeId: String): Long? {
        positions[episodeId]?.takeIf { it > 0 }?.let { return it }
        val episode = episode(episodeId) ?: return null
        val skipMs = (podcast(episode.feedUrl)?.skipIntroSeconds ?: 0) * 1000L
        if (skipMs <= 0) return null
        val durationMs = episode.durationMs
        return skipMs.takeIf { durationMs == null || durationMs <= 0 || it < durationMs }
    }

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

    /** [id] was on air at [nowMs]. Drops whatever has aged out of the history. */
    fun withHeard(id: String, nowMs: Long): PodcastLibrary {
        if (episode(id) == null) return this
        return copy(heard = heard.filterValues { it > nowMs - HISTORY_MS } + (id to nowMs))
    }

    companion object {
        /** How far back the podcast page's history goes. */
        const val HISTORY_MS = 24 * 60 * 60 * 1000L
    }
}
