package app.viberadio.dial

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelGenerator
import app.viberadio.channels.ChannelType
import app.viberadio.podcasts.PodcastChannels
import app.viberadio.tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialTest {

    private val jazz = Channel("genre:jazz", "Jazz", ChannelType.MUSIC_ONLY, tracks("Jazz", 3))
    private val allSongs = Channel(ChannelGenerator.ALL_SONGS_ID, "All songs", ChannelType.MUSIC_ONLY, tracks("Jazz", 3))

    @Test
    fun `podcast channel joins the dial after the song channels once a show is on it`() {
        val stations = Dial.stations(listOf(jazz, allSongs), setOf(PodcastChannels.PODCASTS_ID), "Podcasts")
        assertEquals(listOf("genre:jazz", "all-songs", "podcasts"), stations.map { it.id })
        assertEquals(ChannelType.VOCAL_ONLY, stations.last().type)
        assertEquals("Podcasts", stations.last().name)
    }

    @Test
    fun `no shows, no podcast channel`() {
        assertEquals(listOf("genre:jazz"), Dial.stations(listOf(jazz), emptySet(), "Podcasts").map { it.id })
    }

    @Test
    fun `podcasts alone still make a dial`() {
        assertEquals(listOf("podcasts"), Dial.stations(emptyList(), setOf(PodcastChannels.PODCASTS_ID), "Podcasts").map { it.id })
    }

    @Test
    fun `falls back to all songs, else the first song channel`() {
        assertEquals(allSongs, Dial.fallback(listOf(jazz, allSongs)))
        assertEquals(jazz, Dial.fallback(listOf(jazz)))
        assertNull(Dial.fallback(emptyList()))
    }
}
