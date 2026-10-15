package app.viberadio.playback.audio

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelingTest {

    private class FakeStore(val saved: MutableMap<String, TrackLevel> = mutableMapOf()) : LevelStore {
        override fun readAll(): Map<String, TrackLevel> = saved.toMap()
        override fun put(key: String, level: TrackLevel) {
            saved[key] = level
        }
    }

    private val loud = TrackLevel(-9f, 1f)

    private fun leveling(
        store: LevelStore = FakeStore(),
        keyOf: suspend (LevelRequest) -> String? = { "key:${it.uri}" },
        measured: MutableList<String> = mutableListOf(),
        measure: (LevelRequest) -> TrackLevel? = { loud },
    ) = Leveling(
        store = store,
        keyOf = keyOf,
        measure = { measured += it.uri; measure(it) },
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `a requested item is measured, saved and found by media ID`() {
        val store = FakeStore()
        val leveling = leveling(store)
        assertNull(leveling.levelOf("track:1"))
        leveling.request(listOf(LevelRequest("track:1", "a")))
        assertEquals(loud, leveling.levelOf("track:1"))
        assertEquals(loud, store.saved["key:a"])
    }

    @Test
    fun `a saved level is used without measuring again`() {
        val measured = mutableListOf<String>()
        val leveling = leveling(FakeStore(mutableMapOf("key:a" to loud)), measured = measured)
        leveling.request(listOf(LevelRequest("track:1", "a"), LevelRequest("track:2", "a")))
        assertEquals(emptyList<String>(), measured)
        assertEquals(loud, leveling.levelOf("track:2"))
    }

    @Test
    fun `items without a key are skipped`() {
        val measured = mutableListOf<String>()
        val leveling = leveling(keyOf = { null }, measured = measured)
        leveling.request(listOf(LevelRequest("episode:x", "https://example.com/x.mp3")))
        assertEquals(emptyList<String>(), measured)
        assertNull(leveling.levelOf("episode:x"))
    }

    @Test
    fun `a failed measurement isn't retried`() {
        val measured = mutableListOf<String>()
        val leveling = leveling(measured = measured, measure = { null })
        leveling.request(listOf(LevelRequest("track:1", "a")))
        leveling.request(listOf(LevelRequest("track:1", "a")))
        assertEquals(listOf("a"), measured)
        assertNull(leveling.levelOf("track:1"))
    }

    @Test
    fun `file store round-trips and the last line for a key wins`() {
        val dir = Files.createTempDirectory("levels").toFile()
        try {
            val file = File(dir, "levels.tsv")
            val store = FileLevelStore(file)
            store.put("song:abc", TrackLevel(-12.5f, 0.9f))
            store.put("episode:x", TrackLevel(Float.NEGATIVE_INFINITY, 0f))
            store.put("song:abc", TrackLevel(-11f, 0.8f))
            file.appendText("garbage line\n")
            val read = FileLevelStore(file).readAll()
            assertEquals(TrackLevel(-11f, 0.8f), read["song:abc"])
            assertEquals(TrackLevel(Float.NEGATIVE_INFINITY, 0f), read["episode:x"])
            assertEquals(2, read.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
