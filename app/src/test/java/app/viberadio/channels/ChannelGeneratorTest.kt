package app.viberadio.channels

import app.viberadio.library.ContentKind
import app.viberadio.library.Track
import app.viberadio.track
import app.viberadio.tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGeneratorTest {

    private val generator = ChannelGenerator(maxChannels = 4, minTracksPerChannel = 3)

    /** The genre part of the dial, without the "All songs" channel appended after it. */
    private fun genreChannels(library: List<Track>) =
        generator.generate(library).filterNot { it.id == ChannelGenerator.ALL_SONGS_ID }

    private val twentyMinutes = ChannelGenerator.ALL_SONGS_MAX_DURATION_MS

    @Test
    fun `empty library gives no channels`() {
        assertTrue(generator.generate(emptyList()).isEmpty())
    }

    @Test
    fun `untagged library falls back to a single All channel`() {
        val library = tracks(null, 10)
        val channels = generator.generate(library)
        assertEquals(1, channels.size)
        assertEquals(ChannelGenerator.ALL_ID, channels[0].id)
        assertEquals(library, channels[0].tracks)
    }

    @Test
    fun `genres below the threshold fall back to All`() {
        val channels = generator.generate(tracks("Jazz", 2) + tracks("Rock", 2))
        assertEquals(listOf(ChannelGenerator.ALL_ID), channels.map { it.id })
    }

    @Test
    fun `channels are ordered by size and leftovers go to the catch-all`() {
        val library = tracks("Rock", 5) + tracks("Jazz", 8) + tracks("Polka", 1) + tracks(null, 2)
        val channels = genreChannels(library)
        assertEquals(listOf("genre:jazz", "genre:rock", ChannelGenerator.OTHER_ID), channels.map { it.id })
        assertEquals(3, channels.last().tracks.size)
    }

    @Test
    fun `no catch-all when every track is in a genre channel`() {
        val channels = genreChannels(tracks("Rock", 3) + tracks("Jazz", 3))
        assertEquals(2, channels.size)
    }

    @Test
    fun `channel count never exceeds the maximum`() {
        val library = listOf("A", "B", "C", "D", "E", "F").flatMap { tracks(it, 4) }
        val channels = genreChannels(library)
        assertEquals(4, channels.size)
        assertEquals(ChannelGenerator.OTHER_ID, channels.last().id)
        assertEquals(library.size, channels.sumOf { it.tracks.size })
    }

    @Test
    fun `exactly max genres and no leftovers uses all slots for genres`() {
        val library = listOf("A", "B", "C", "D").flatMap { tracks(it, 4) }
        val channels = genreChannels(library)
        assertTrue(channels.all { it.id.startsWith(ChannelGenerator.GENRE_PREFIX) })
        assertEquals(4, channels.size)
    }

    @Test
    fun `display name is the most common spelling`() {
        val library = listOf(track("hip hop")) + tracks("Hip-Hop", 3)
        assertEquals("Hip-Hop", genreChannels(library).single().name)
    }

    @Test
    fun `every track lands in exactly one channel`() {
        val library = tracks("Rock", 6) + tracks("Jazz", 4) + tracks("Folk", 1) + tracks(null, 3)
        val placed = genreChannels(library).flatMap { it.tracks }
        assertEquals(library.toSet(), placed.toSet())
        assertEquals(library.size, placed.size)
    }

    @Test
    fun `type resolver decides each channel's type`() {
        val typed = ChannelGenerator(minTracksPerChannel = 1, typeResolver = { ChannelType.VOCAL_ONLY })
        assertTrue(typed.generate(tracks("Talk", 2)).all { it.type == ChannelType.VOCAL_ONLY })
    }

    @Test
    fun `All songs is appended last with every music track`() {
        val library = tracks("Rock", 4) + tracks("Jazz", 3) + tracks(null, 2)
        val allSongs = generator.generate(library).last()
        assertEquals(ChannelGenerator.ALL_SONGS_ID, allSongs.id)
        assertEquals("All songs", allSongs.name)
        assertEquals(ChannelType.MUSIC_ONLY, allSongs.type)
        assertEquals(library, allSongs.tracks)
    }

    @Test
    fun `All songs excludes tracks over 20 minutes`() {
        val atLimit = track("Rock", durationMs = twentyMinutes)
        val tooLong = track("Rock", durationMs = twentyMinutes + 1)
        val library = tracks("Rock", 3) + atLimit + tooLong
        val allSongs = generator.generate(library).single { it.id == ChannelGenerator.ALL_SONGS_ID }
        assertTrue(atLimit in allSongs.tracks)
        assertFalse(tooLong in allSongs.tracks)
    }

    @Test
    fun `All songs never includes podcasts or audiobooks`() {
        val music = tracks("Rock", 3)
        val podcast = track("Rock", kind = ContentKind.PODCAST)
        val audiobook = track("Rock", kind = ContentKind.AUDIOBOOK)
        val allSongs = generator.generate(music + podcast + audiobook).single { it.id == ChannelGenerator.ALL_SONGS_ID }
        assertEquals(music, allSongs.tracks)
    }

    @Test
    fun `long tracks still play on their genre channel`() {
        val long = track("Rock", durationMs = twentyMinutes * 3)
        val channels = generator.generate(tracks("Rock", 3) + long)
        assertTrue(long in channels.single { it.id == "genre:rock" }.tracks)
    }

    @Test
    fun `All songs is left out when it would duplicate the only channel`() {
        val channels = generator.generate(tracks(null, 5))
        assertEquals(listOf(ChannelGenerator.ALL_ID), channels.map { it.id })
    }

    @Test
    fun `All songs is left out when there is no music`() {
        val podcasts = List(5) { track("Talk", kind = ContentKind.PODCAST) }
        assertFalse(generator.generate(podcasts).any { it.id == ChannelGenerator.ALL_SONGS_ID })
    }
}
