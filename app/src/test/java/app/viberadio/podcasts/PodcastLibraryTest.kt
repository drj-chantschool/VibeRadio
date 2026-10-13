package app.viberadio.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastLibraryTest {

    private val showA = "https://a.example/feed"
    private val showB = "https://b.example/feed"

    /** Items newest first, as feeds list them; item n is published on day n. */
    private fun feed(title: String, vararg days: Int) =
        Feed(title, days.map { FeedItem(guid = "g$it", title = "$title $it", publishedMs = it * DAY, audioUrl = "https://x/$it.mp3", durationMs = null) })

    private fun id(show: String, day: Int) = EpisodeIds.of(show, "g$day")

    private val library = PodcastLibrary()
        .withFeed(showA, feed("A", 3, 2, 1), nowMs = 100)
        .withFeed(showB, feed("B", 5, 4), nowMs = 100)

    @Test
    fun `new shows land on the podcasts channel`() {
        assertEquals(PodcastChannels.DEFAULT, library.podcast(showA)!!.channels)
        assertEquals(setOf(PodcastChannels.PODCASTS_ID), library.channelIds)
    }

    @Test
    fun `queue is oldest first across shows`() {
        assertEquals(listOf("A 1", "A 2", "A 3", "B 4", "B 5"), library.queue(PodcastChannels.PODCASTS_ID).map { it.title })
    }

    @Test
    fun `played episodes leave the queue`() {
        val lib = library.withPlayed(id(showA, 1), true)
        assertEquals("A 2", lib.queue(PodcastChannels.PODCASTS_ID).first().title)
        assertTrue(lib.isPlayed(id(showA, 1)))
        assertFalse(lib.withPlayed(id(showA, 1), false).isPlayed(id(showA, 1)))
    }

    @Test
    fun `marking older played skips the back catalogue of that show only`() {
        val lib = library.withOlderPlayed(id(showA, 2))
        assertEquals(listOf("A 3", "B 4", "B 5"), lib.queue(PodcastChannels.PODCASTS_ID).map { it.title })
    }

    @Test
    fun `a started episode goes first so tuning back resumes it`() {
        val lib = library.withPosition(id(showB, 5), 60_000)
        assertEquals("B 5", lib.queue(PodcastChannels.PODCASTS_ID).first().title)
        assertEquals(60_000L, lib.positions[id(showB, 5)])
    }

    @Test
    fun `finishing an episode clears its position`() {
        val lib = library.withPosition(id(showB, 5), 60_000).withPlayed(id(showB, 5), true)
        assertTrue(lib.positions.isEmpty())
        assertEquals(lib, lib.withPosition(id(showB, 5), 1_000))
    }

    @Test
    fun `one queue for the app - a channel only plays its own shows`() {
        val lib = library.withChannel(showB, "evening", true).withChannel(showB, PodcastChannels.PODCASTS_ID, false)
        assertEquals(listOf("A 1", "A 2", "A 3"), lib.queue(PodcastChannels.PODCASTS_ID).map { it.title })
        assertEquals(listOf("B 4", "B 5"), lib.queue("evening").map { it.title })
        // Played state is global: once heard on one channel it's gone from all of them.
        val both = lib.withChannel(showB, PodcastChannels.PODCASTS_ID, true).withPlayed(id(showB, 4), true)
        assertEquals(listOf("B 5"), both.queue("evening").map { it.title })
        assertFalse(both.queue(PodcastChannels.PODCASTS_ID).any { it.title == "B 4" })
    }

    @Test
    fun `an unassigned show is never queued or downloaded`() {
        val lib = library.withChannel(showA, PodcastChannels.PODCASTS_ID, false)
        assertEquals(listOf("B 4", "B 5"), lib.queue(PodcastChannels.PODCASTS_ID).map { it.title })
        assertEquals(listOf("B 4"), lib.upNext(1).map { it.title })
    }

    @Test
    fun `refresh adds new episodes and keeps state and dropped ones`() {
        val before = library.withPlayed(id(showA, 1), true).withChannel(showA, "evening", true)
        val after = before.withFeed(showA, feed("A renamed", 4, 3), nowMs = 200)
        val a = after.podcast(showA)!!
        assertEquals("A renamed", a.title)
        assertEquals(200L, a.lastRefreshedMs)
        assertEquals(setOf(PodcastChannels.PODCASTS_ID, "evening"), a.channels)
        assertEquals(listOf("A renamed 4", "A renamed 3", "A 2", "A 1"), after.episodesOf(showA).map { it.title })
        assertTrue(after.isPlayed(id(showA, 1)))
    }

    @Test
    fun `removing a show drops its episodes and state`() {
        val lib = library.withPlayed(id(showA, 1), true).withoutPodcast(showA)
        assertEquals(listOf(showB), lib.podcasts.map { it.feedUrl })
        assertTrue(lib.episodes.none { it.feedUrl == showA })
        assertTrue(lib.played.isEmpty())
    }

    private companion object {
        const val DAY = 86_400_000L
    }

    @Test
    fun `shows start at normal speed`() {
        assertEquals(PodcastSpeeds.DEFAULT, library.podcast(showA)!!.speed)
        assertEquals(PodcastSpeeds.DEFAULT, library.speedOf(id(showA, 1)))
    }

    @Test
    fun `speed is per show and applies to every episode of it`() {
        val lib = library.withSpeed(showA, 1.5f)
        assertEquals(1.5f, lib.speedOf(id(showA, 1)))
        assertEquals(1.5f, lib.speedOf(id(showA, 3)))
        assertEquals(PodcastSpeeds.DEFAULT, lib.speedOf(id(showB, 4)))
    }

    @Test
    fun `speed survives a feed refresh and resets with the show`() {
        val lib = library.withSpeed(showA, 1.5f).withFeed(showA, feed("A", 4, 3, 2, 1), nowMs = 200)
        assertEquals(1.5f, lib.podcast(showA)!!.speed)
        val readded = lib.withoutPodcast(showA).withFeed(showA, feed("A", 1), nowMs = 300)
        assertEquals(PodcastSpeeds.DEFAULT, readded.podcast(showA)!!.speed)
    }

    @Test
    fun `speed changes leave the queue alone`() {
        val lib = library.withSpeed(showB, 2.0f)
        assertEquals(library.queue(PodcastChannels.PODCASTS_ID), lib.queue(PodcastChannels.PODCASTS_ID))
    }

    @Test
    fun `unknown episodes play at normal speed`() {
        assertEquals(PodcastSpeeds.DEFAULT, library.withSpeed(showA, 2.0f).speedOf("nope"))
    }
}
