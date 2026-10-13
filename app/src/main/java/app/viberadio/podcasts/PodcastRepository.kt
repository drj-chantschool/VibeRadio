package app.viberadio.podcasts

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the podcast pipeline, shared by the podcast page and the
 * playback service. Every change is persisted and re-syncs downloads.
 */
class PodcastRepository(
    private val store: PodcastStore,
    private val fetcher: FeedFetcher,
    private val downloader: EpisodeDownloader,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How many upcoming episodes to keep on the device. */
    private val keepDownloaded: Int = 3,
) {
    private val initial = PodcastLibrary()
    private val _library = MutableStateFlow(initial)
    val library: StateFlow<PodcastLibrary> = _library.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val downloaded: StateFlow<Set<String>> get() = downloader.downloaded

    private val writeLock = Mutex()
    private val refreshLock = Mutex()
    private val downloadRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(ioDispatcher) {
            store.read()?.let { _library.compareAndSet(initial, it) }
            _loaded.value = true
            downloadRequests.trySend(Unit)
        }
        scope.launch(ioDispatcher) {
            for (request in downloadRequests) {
                runCatching { downloader.sync(_library.value.upNext(keepDownloaded)) }
            }
        }
    }

    sealed interface SubscribeResult {
        data class Added(val podcast: Podcast) : SubscribeResult
        data class AlreadySubscribed(val podcast: Podcast) : SubscribeResult
        data object InvalidUrl : SubscribeResult
        data class Failed(val error: Throwable) : SubscribeResult
    }

    /** Fetches [input] (anything [FeedUrls.normalize] accepts) and adds the show. */
    suspend fun subscribe(input: String): SubscribeResult {
        val url = FeedUrls.normalize(input) ?: return SubscribeResult.InvalidUrl
        _library.value.podcast(url)?.let { return SubscribeResult.AlreadySubscribed(it) }
        val feed = try {
            withContext(ioDispatcher) { fetcher.fetch(url) }
        } catch (e: Exception) {
            return SubscribeResult.Failed(e)
        }
        mutate { it.withFeed(url, feed, clock()) }
        return SubscribeResult.Added(_library.value.podcast(url)!!)
    }

    /** Refetches every show last fetched more than [maxAgeMs] ago. Failures keep the old episodes. */
    suspend fun refresh(maxAgeMs: Long = 0) {
        if (!refreshLock.tryLock()) return
        try {
            val due = _library.value.podcasts.filter { clock() - it.lastRefreshedMs >= maxAgeMs }
            for (podcast in due) {
                val feed = runCatching { withContext(ioDispatcher) { fetcher.fetch(podcast.feedUrl) } }.getOrNull() ?: continue
                mutate { if (it.podcast(podcast.feedUrl) == null) it else it.withFeed(podcast.feedUrl, feed, clock()) }
            }
        } finally {
            refreshLock.unlock()
        }
    }

    fun unsubscribe(feedUrl: String) = mutate { it.withoutPodcast(feedUrl) }

    fun setChannel(feedUrl: String, channelId: String, assigned: Boolean) =
        mutate { it.withChannel(feedUrl, channelId, assigned) }

    /** Doesn't change what's queued, so downloads are left alone. */
    fun setSpeed(feedUrl: String, speed: Float) =
        mutate(syncDownloads = false) { it.withSpeed(feedUrl, speed) }

    fun setPlayed(episodeId: String, played: Boolean) = mutate { it.withPlayed(episodeId, played) }

    fun markOlderPlayed(episodeId: String) = mutate { it.withOlderPlayed(episodeId) }

    /** Resume point; doesn't change what's queued, so downloads are left alone. */
    fun savePosition(episodeId: String, positionMs: Long) =
        mutate(syncDownloads = false) { it.withPosition(episodeId, positionMs) }

    /** The local copy if it's on the device, otherwise the feed's URL (streamed). */
    fun playableUri(episode: Episode): String {
        val file = downloader.file(episode.id)
        return if (episode.id in downloader.downloaded.value && file.exists()) "file://${file.absolutePath}" else episode.audioUrl
    }

    private fun mutate(syncDownloads: Boolean = true, change: (PodcastLibrary) -> PodcastLibrary) {
        val before = _library.value
        _library.update(change)
        if (_library.value == before) return
        scope.launch(ioDispatcher) {
            writeLock.withLock { runCatching { store.write(_library.value) } }
        }
        if (syncDownloads) downloadRequests.trySend(Unit)
    }
}
