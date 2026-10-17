package app.viberadio.podcasts

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastRepositoryTest {

    private val feedUrl = "https://show.example/feed"
    private val feed = Feed("Show", (5 downTo 1).map { FeedItem("g$it", "Ep $it", it * 1000L, "https://show.example/$it.mp3", null) })

    private class MemoryStore : PodcastStore {
        var saved: PodcastLibrary? = null
        override fun read() = saved
        override fun write(library: PodcastLibrary) { saved = library }
    }

    private val store = MemoryStore()
    private val downloadedUrls = mutableListOf<String>()
    private val downloader = EpisodeDownloader(Files.createTempDirectory("eps").toFile()) {
        downloadedUrls += it
        "x".byteInputStream()
    }

    // Unconfined runs the store writes and the download worker inline, so tests see results at once.
    private fun repository(fetcher: FeedFetcher = FeedFetcher { feed }) = PodcastRepository(
        store = store,
        fetcher = fetcher,
        downloader = downloader,
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
        clock = { 42L },
        keepDownloaded = 2,
    )

    @Test
    fun `subscribing adds the show to the podcasts channel and downloads what's up next`() = runBlocking {
        val repo = repository()
        val result = repo.subscribe("show.example/feed")
        assertTrue(result is PodcastRepository.SubscribeResult.Added)
        assertEquals(setOf(PodcastChannels.PODCASTS_ID), repo.library.value.channelIds)
        assertEquals(listOf("https://show.example/1.mp3", "https://show.example/2.mp3"), downloadedUrls)
        assertEquals(repo.library.value, store.saved)
    }

    @Test
    fun `episodes marked played before they come up are never downloaded`() = runBlocking {
        val repo = repository()
        repo.subscribe(feedUrl)
        downloadedUrls.clear()
        val ep3 = EpisodeIds.of(feedUrl, "g3")
        repo.markOlderPlayed(ep3)
        assertEquals(listOf("https://show.example/4.mp3", "https://show.example/5.mp3"), downloadedUrls)
        assertFalse(downloader.file(EpisodeIds.of(feedUrl, "g1")).exists())
        assertTrue(store.saved!!.isPlayed(ep3))
    }

    @Test
    fun `playable uri prefers the local copy`() = runBlocking {
        val repo = repository()
        repo.subscribe(feedUrl)
        val lib = repo.library.value
        val first = lib.queue(PodcastChannels.PODCASTS_ID).first()
        assertTrue(repo.playableUri(first).startsWith("file://"))
        val last = lib.episodesOf(feedUrl).first()
        assertEquals(last.audioUrl, repo.playableUri(last))
    }

    @Test
    fun `a failed fetch reports the error and changes nothing`() = runBlocking {
        val repo = repository(FeedFetcher { throw IOException("offline") })
        assertTrue(repo.subscribe(feedUrl) is PodcastRepository.SubscribeResult.Failed)
        assertTrue(repo.library.value.podcasts.isEmpty())
        assertEquals(PodcastRepository.SubscribeResult.InvalidUrl, repo.subscribe("nope"))
    }

    @Test
    fun `state survives a restart through the store`() = runBlocking {
        val repo = repository()
        repo.subscribe(feedUrl)
        repo.savePosition(EpisodeIds.of(feedUrl, "g2"), 5_000)
        val reloaded = repository()
        assertEquals(repo.library.value, reloaded.library.value)
        assertTrue(reloaded.loaded.value)
    }

    @Test
    fun `folder shows are neither fetched nor downloaded`() = runBlocking {
        val fetched = mutableListOf<String>()
        val repo = repository(FeedFetcher { url -> fetched += url; feed })
        val folder = LocalFeeds.url("Podcasts")
        repo.syncLocalFeeds(mapOf(folder to Feed("Podcasts", listOf(FeedItem("f1", "File", 1L, "content://media/1", null)))))
        repo.subscribe(feedUrl)
        fetched.clear()
        repo.refresh()
        assertEquals(listOf(feedUrl), fetched)
        // The folder's file is first in the queue but takes no download slot.
        assertEquals("File", repo.library.value.upNext(1).single().title)
        assertEquals(listOf("https://show.example/1.mp3", "https://show.example/2.mp3"), downloadedUrls)
        val file = repo.library.value.episodesOf(folder).single()
        assertEquals("content://media/1", repo.playableUri(file))
    }
}
