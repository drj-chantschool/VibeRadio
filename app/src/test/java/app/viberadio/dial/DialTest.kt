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
    private val names = ChannelNames(
        defaults = mapOf(ChannelLineup.SHUFFLE to "The Shuffle (all)", ChannelLineup.VOICE to "The Voice", ChannelLineup.BREEZE to "The Breeze"),
    )

    @Test
    fun `podcast channel joins the dial after the song channels once a show is on it`() {
        val stations = Dial.stations(listOf(jazz, allSongs), setOf(PodcastChannels.PODCASTS_ID), names)
        assertEquals(listOf("genre:jazz", "all-songs", "podcasts"), stations.map { it.id })
        assertEquals(ChannelType.VOCAL_ONLY, stations.last().type)
    }

    @Test
    fun `named channels show their names, generated ones keep theirs`() {
        val stations = Dial.stations(listOf(jazz, allSongs), setOf(PodcastChannels.PODCASTS_ID), names)
        assertEquals(listOf("Jazz", "The Shuffle (all)", "The Voice"), stations.map { it.name })
    }

    @Test
    fun `renamed channels show the new name`() {
        val renamed = names.renamed(ChannelLineup.VOICE, "Talk").renamed(ChannelLineup.SHUFFLE, "Everything")
        val stations = Dial.stations(listOf(allSongs), setOf(PodcastChannels.PODCASTS_ID), renamed)
        assertEquals(listOf("Everything", "Talk"), stations.map { it.name })
    }

    @Test
    fun `named channels with nothing on them stay off the dial`() {
        assertEquals(listOf("genre:jazz"), Dial.stations(listOf(jazz), emptySet(), names).map { it.id })
    }

    @Test
    fun `no shows, no podcast channel`() {
        assertEquals(listOf("genre:jazz"), Dial.stations(listOf(jazz), emptySet(), names).map { it.id })
    }

    @Test
    fun `podcasts alone still make a dial`() {
        assertEquals(listOf("podcasts"), Dial.stations(emptyList(), setOf(PodcastChannels.PODCASTS_ID), names).map { it.id })
    }

    @Test
    fun `falls back to all songs, else the first song channel`() {
        assertEquals(allSongs, Dial.fallback(listOf(jazz, allSongs)))
        assertEquals(jazz, Dial.fallback(listOf(jazz)))
        assertNull(Dial.fallback(emptyList()))
    }

    private val lineup = listOf("a", "b", "c").map { Station(it, it, ChannelType.MUSIC_ONLY) }

    @Test
    fun `step moves one station either way and wraps past the ends`() {
        assertEquals("b", Dial.step(lineup, "a", 1)?.id)
        assertEquals("a", Dial.step(lineup, "b", -1)?.id)
        assertEquals("a", Dial.step(lineup, "c", 1)?.id)
        assertEquals("c", Dial.step(lineup, "a", -1)?.id)
    }

    @Test
    fun `step from off the dial starts at the end it moves from`() {
        assertEquals("a", Dial.step(lineup, null, 1)?.id)
        assertEquals("c", Dial.step(lineup, "gone", -1)?.id)
    }

    @Test
    fun `step has nowhere to go on an empty or one-station dial`() {
        assertNull(Dial.step(emptyList(), "a", 1))
        assertNull(Dial.step(lineup.take(1), "a", 1))
        assertEquals("a", Dial.step(lineup.take(1), null, -1)?.id)
    }
}
