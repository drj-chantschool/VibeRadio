package app.viberadio.playback

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastLogTest {

    private fun tempFile(): File = Files.createTempDirectory("podcast-log").toFile().resolve("events.tsv")

    @Test
    fun `events round-trip, with tabs and newlines in text flattened`() {
        val event = PodcastEvent(1_000, 20, "breeze", PodcastEvent.Kind.FAIL.id, "e1", "error=x message=a\tb\nc")
        val decoded = PodcastEvent.decode(PodcastEvent.encode(event))
        assertEquals(event.copy(detail = "error=x message=a b c"), decoded)
        val bare = PodcastEvent(1, 2, null, "tune")
        assertEquals(bare, PodcastEvent.decode(PodcastEvent.encode(bare)))
    }

    @Test
    fun `lines that aren't events are skipped`() {
        assertNull(PodcastEvent.decode("garbage"))
        assertNull(PodcastEvent.decode("x\t1\tbreeze\ttune\t\t"))
        assertNull(PodcastEvent.decode("1\t1\tbreeze\t\t\t"))
    }

    @Test
    fun `the store keeps the newest events once it holds twice as many`() {
        val file = tempFile()
        val store = FilePodcastEventStore(file, keep = 3)
        repeat(5) { store.append(PodcastEvent(it.toLong(), 0, null, "boundary")) }
        assertEquals(listOf<Long>(0, 1, 2, 3, 4), store.readAll().map { it.atMs })
        store.append(PodcastEvent(5, 0, null, "boundary"))
        assertEquals(listOf<Long>(3, 4, 5), store.readAll().map { it.atMs })
        // A new store on the same file counts what's there.
        val reopened = FilePodcastEventStore(file, keep = 3)
        repeat(3) { reopened.append(PodcastEvent(6L + it, 0, null, "boundary")) }
        assertEquals(listOf<Long>(6, 7, 8), reopened.readAll().map { it.atMs })
    }

    @Test
    fun `kinds are logged lowercase`() {
        assertEquals("nothing_started", PodcastEvent.Kind.NOTHING_STARTED.id)
    }
}
