package app.viberadio.podcasts

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDownloaderTest {

    private val dir: File = Files.createTempDirectory("episodes").toFile()
    private val fetched = mutableListOf<String>()
    private val downloader = EpisodeDownloader(dir) { url ->
        fetched += url
        if (url.contains("broken")) throw IOException("nope")
        "audio for $url".byteInputStream()
    }

    private fun episode(n: Int, url: String = "https://x/$n.mp3") =
        Episode(id = "e$n", feedUrl = "f", title = "$n", publishedMs = n.toLong(), audioUrl = url, durationMs = null)

    @Test
    fun `downloads only the targets, once`() {
        downloader.sync(listOf(episode(1), episode(2)))
        downloader.sync(listOf(episode(1), episode(2)))
        assertEquals(listOf("https://x/1.mp3", "https://x/2.mp3"), fetched)
        assertEquals(setOf("e1", "e2"), downloader.downloaded.value)
        assertEquals("audio for https://x/1.mp3", downloader.file("e1").readText())
    }

    @Test
    fun `episodes that drop out of the targets are deleted`() {
        downloader.sync(listOf(episode(1), episode(2)))
        downloader.sync(listOf(episode(2), episode(3)))
        assertFalse(downloader.file("e1").exists())
        assertEquals(setOf("e2", "e3"), downloader.downloaded.value)
    }

    @Test
    fun `a failed download leaves no file and doesn't block the rest`() {
        downloader.sync(listOf(episode(1, "https://broken/1.mp3"), episode(2)))
        assertEquals(setOf("e2"), downloader.downloaded.value)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `feed urls are normalised from what people paste`() {
        assertEquals("https://example.com/feed.xml", FeedUrls.normalize(" example.com/feed.xml "))
        assertEquals("https://example.com/rss", FeedUrls.normalize("feed://example.com/rss"))
        assertEquals("http://example.com/rss", FeedUrls.normalize("http://example.com/rss"))
        assertNull(FeedUrls.normalize("ftp://example.com/rss"))
        assertNull(FeedUrls.normalize("not a url"))
        assertNull(FeedUrls.normalize("localhost"))
    }
}
