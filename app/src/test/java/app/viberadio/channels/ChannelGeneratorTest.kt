package app.viberadio.channels

import app.viberadio.track
import app.viberadio.tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGeneratorTest {

    private val generator = ChannelGenerator(maxChannels = 4, minTracksPerChannel = 3)

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
        val channels = generator.generate(library)
        assertEquals(listOf("genre:jazz", "genre:rock", ChannelGenerator.OTHER_ID), channels.map { it.id })
        assertEquals(3, channels.last().tracks.size)
    }

    @Test
    fun `no catch-all when every track is in a genre channel`() {
        val channels = generator.generate(tracks("Rock", 3) + tracks("Jazz", 3))
        assertEquals(2, channels.size)
    }

    @Test
    fun `channel count never exceeds the maximum`() {
        val library = listOf("A", "B", "C", "D", "E", "F").flatMap { tracks(it, 4) }
        val channels = generator.generate(library)
        assertEquals(4, channels.size)
        assertEquals(ChannelGenerator.OTHER_ID, channels.last().id)
        assertEquals(library.size, channels.sumOf { it.tracks.size })
    }

    @Test
    fun `exactly max genres and no leftovers uses all slots for genres`() {
        val library = listOf("A", "B", "C", "D").flatMap { tracks(it, 4) }
        val channels = generator.generate(library)
        assertTrue(channels.all { it.id.startsWith(ChannelGenerator.GENRE_PREFIX) })
        assertEquals(4, channels.size)
    }

    @Test
    fun `display name is the most common spelling`() {
        val library = listOf(track("hip hop")) + tracks("Hip-Hop", 3)
        assertEquals("Hip-Hop", generator.generate(library).single().name)
    }

    @Test
    fun `every track lands in exactly one channel`() {
        val library = tracks("Rock", 6) + tracks("Jazz", 4) + tracks("Folk", 1) + tracks(null, 3)
        val placed = generator.generate(library).flatMap { it.tracks }
        assertEquals(library.toSet(), placed.toSet())
        assertEquals(library.size, placed.size)
    }

    @Test
    fun `type resolver decides each channel's type`() {
        val typed = ChannelGenerator(minTracksPerChannel = 1, typeResolver = { ChannelType.VOCAL_ONLY })
        assertTrue(typed.generate(tracks("Talk", 2)).all { it.type == ChannelType.VOCAL_ONLY })
    }
}
