package app.viberadio.feedback

import app.viberadio.track
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayHistoryTest {

    private class MemoryHistory : PlayHistoryStore {
        val rows = mutableMapOf<String, Long>()
        override fun readAll() = rows.toMap()
        override fun put(trackHash: String, lastPlayedMs: Long) { rows[trackHash] = lastPlayedMs }
    }

    private class MemoryHashes : TrackHashStore {
        val rows = mutableMapOf<String, HashedFile>()
        override fun readAll() = rows.values.toList()
        override fun put(file: HashedFile) { rows[file.uri] = file }
    }

    private val song = track("Jazz").copy(sizeBytes = 6, modifiedSec = 100)
    private val files = mutableMapOf(song.uri to "song a")
    private var opens = 0
    private var now = 1_000L
    private val store = MemoryHistory()
    private val hashStore = MemoryHashes()

    private fun history() = PlayHistory(
        store = store,
        hashStore = hashStore,
        open = { uri ->
            opens++
            files[uri]?.byteInputStream() ?: throw FileNotFoundException(uri)
        },
        ioDispatcher = Dispatchers.Unconfined,
        clock = { now },
    )

    private val hashA = ContentHash.of("song a".byteInputStream())

    @Test
    fun `never played reads as null`() {
        assertNull(history().lastPlayedMs(song))
    }

    @Test
    fun `a play is stored by content hash and overwrites the last one`() = runBlocking {
        val h = history()
        assertEquals(hashA, h.markPlayed(song))
        now = 2_000L
        h.markPlayed(song)
        assertEquals(mapOf(hashA to 2_000L), store.rows)
        assertEquals(2_000L, h.lastPlayedMs(song))
        assertEquals("hashed once", 1, opens)
    }

    @Test
    fun `history survives a restart without re-reading files`() = runBlocking {
        history().markPlayed(song)
        opens = 0
        assertEquals(1_000L, history().lastPlayedMs(song))
        assertEquals(0, opens)
    }

    @Test
    fun `a moved file keeps its history once it plays again`() = runBlocking {
        history().markPlayed(song)
        val moved = song.copy(id = 999, uri = "content://media/999")
        files[moved.uri] = files.remove(song.uri)!!
        val h = history()
        assertNull("new URI isn't hashed yet", h.lastPlayedMs(moved))
        now = 5_000L
        assertEquals(hashA, h.markPlayed(moved))
        assertEquals(mapOf(hashA to 5_000L), store.rows)
    }

    @Test
    fun `a changed file is hashed again`() = runBlocking {
        history().markPlayed(song)
        files[song.uri] = "song a, retagged"
        val edited = song.copy(sizeBytes = 16, modifiedSec = 200)
        val h = history()
        assertNull(h.lastPlayedMs(edited))
        h.markPlayed(edited)
        assertEquals(ContentHash.of("song a, retagged".byteInputStream()), hashStore.rows[song.uri]!!.trackHash)
    }

    @Test
    fun `unreadable file records nothing`() = runBlocking {
        assertNull(history().markPlayed(track("Jazz")))
        assertEquals(emptyMap<String, Long>(), store.rows)
    }
}
