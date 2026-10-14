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
        val rows = mutableMapOf<String, PlayRecord>()
        override fun readAll() = rows.toMap()
        override fun put(trackHash: String, record: PlayRecord) { rows[trackHash] = record }
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
    private var listened = 40L
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
        listeningClock = { listened },
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
        assertEquals(mapOf(hashA to PlayRecord(2_000L, 40L)), store.rows)
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
        assertEquals(mapOf(hashA to PlayRecord(5_000L, 40L)), store.rows)
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
    fun `hashOf answers from the cache without reading the file`() = runBlocking {
        assertNull("not hashed until it plays", history().hashOf(song))
        assertEquals(0, opens)
        history().markPlayed(song)
        opens = 0
        assertEquals(hashA, history().hashOf(song))
        assertNull("a changed file's old hash isn't trusted", history().hashOf(song.copy(sizeBytes = 7)))
        assertEquals(0, opens)
    }

    @Test
    fun `unreadable file records nothing`() = runBlocking {
        assertNull(history().markPlayed(track("Jazz")))
        assertEquals(emptyMap<String, PlayRecord>(), store.rows)
    }

    @Test
    fun `a play records the listening clock too`() = runBlocking {
        val h = history()
        listened = 90_000L
        h.markPlayed(song)
        assertEquals(90_000L, h.lastHeardAt(song))
        assertEquals(1_000L, h.lastPlayedMs(song))
    }

    @Test
    fun `plays from before the listening clock have no listening time`() {
        store.rows[hashA] = PlayRecord(1_000L, null)
        hashStore.rows[song.uri] = HashedFile(song.uri, song.sizeBytes, song.modifiedSec, hashA)
        val h = history()
        assertEquals(1_000L, h.lastPlayedMs(song))
        assertNull(h.lastHeardAt(song))
    }
}
