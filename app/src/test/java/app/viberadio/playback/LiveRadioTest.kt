package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelType
import app.viberadio.library.Track
import app.viberadio.tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRadioTest {

    private class MemoryAirings : ChannelAiringStore {
        val rows = mutableMapOf<String, ChannelAiring>()
        override fun readAll() = rows.values.toList()
        override fun put(airing: ChannelAiring) { rows[airing.channelId] = airing }
    }

    private val songs = tracks("Jazz", 4)
    private val channel = Channel("genre:jazz", "Jazz", ChannelType.MUSIC_ONLY, songs)
    private val hashes: Map<Track, String> = songs.associateWith { "hash-${it.id}" }
    private val store = MemoryAirings()
    private var now = 1_000_000L

    private fun radio() = LiveRadio(store, hashOf = hashes::get, ioDispatcher = Dispatchers.Unconfined, clock = { now })

    private fun aired(track: Track, startedAtMs: Long, durationMs: Long = 180_000, on: Channel = channel) {
        store.rows[on.id] = ChannelAiring(on.id, hashes.getValue(track), durationMs, startedAtMs)
    }

    @Test
    fun `a song still on air is joined at now minus start`() {
        aired(songs[2], startedAtMs = now - 60_000)
        assertEquals(songs[2] to 60_000L, radio().onAir(channel))
    }

    @Test
    fun `the joined song goes first and is not queued again`() {
        aired(songs[2], startedAtMs = now - 60_000)
        val tuneIn = radio().tuneIn(channel, planned = songs.reversed())
        assertEquals(listOf(songs[2], songs[3], songs[1], songs[0]), tuneIn.tracks)
        assertEquals(60_000L, tuneIn.resumeAtMs)
    }

    @Test
    fun `a song that has finished airing shuffles as usual`() {
        aired(songs[2], startedAtMs = now - 180_000)
        val tuneIn = radio().tuneIn(channel, planned = songs)
        assertEquals(songs, tuneIn.tracks)
        assertNull(tuneIn.resumeAtMs)
    }

    @Test
    fun `just started still joins at zero`() {
        aired(songs[0], startedAtMs = now)
        assertEquals(songs[0] to 0L, radio().onAir(channel))
    }

    @Test
    fun `a clock that went backward shuffles`() {
        aired(songs[0], startedAtMs = now + 5_000)
        assertNull(radio().onAir(channel))
    }

    @Test
    fun `a song no longer on the channel or gone from the library shuffles`() {
        aired(songs[0], startedAtMs = now - 1_000)
        val without = channel.copy(tracks = songs.drop(1))
        assertNull(radio().onAir(without))
    }

    @Test
    fun `an unknown hash shuffles`() {
        store.rows[channel.id] = ChannelAiring(channel.id, "some-other-file", 180_000, now - 1_000)
        assertNull(radio().onAir(channel))
    }

    @Test
    fun `a channel with nothing recorded shuffles`() {
        assertNull(radio().onAir(channel))
    }

    @Test
    fun `each channel remembers its own last song`() {
        val other = Channel("genre:rock", "Rock", ChannelType.MUSIC_ONLY, songs)
        aired(songs[1], startedAtMs = now - 10_000)
        aired(songs[3], startedAtMs = now - 20_000, on = other)
        val radio = radio()
        assertEquals(songs[1] to 10_000L, radio.onAir(channel))
        assertEquals(songs[3] to 20_000L, radio.onAir(other))
    }

    @Test
    fun `record overwrites the channel's airing and persists it`() = runBlocking {
        val radio = radio()
        radio.record(channel.id, hashes.getValue(songs[0]), 180_000, startedAtMs = now - 5_000)
        radio.record(channel.id, hashes.getValue(songs[1]), 200_000, startedAtMs = now - 1_000)
        assertEquals(songs[1] to 1_000L, radio.onAir(channel))
        assertEquals(ChannelAiring(channel.id, "hash-${songs[1].id}", 200_000, now - 1_000), store.rows[channel.id])
        assertEquals("a fresh instance reads it back", songs[1] to 1_000L, radio().onAir(channel))
    }

    @Test
    fun `the recorded duration decides, not the elapsed time alone`() = runBlocking {
        val radio = radio()
        radio.record(channel.id, hashes.getValue(songs[0]), durationMs = 30_000, startedAtMs = now)
        now += 29_999
        assertEquals(songs[0] to 29_999L, radio.onAir(channel))
        now += 1
        assertNull(radio.onAir(channel))
    }
}
