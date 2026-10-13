package app.viberadio.feedback

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentHashTest {

    @Test
    fun `hash is sha256 of the bytes with a prefix`() {
        assertEquals(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.of("abc".byteInputStream()),
        )
    }

    @Test
    fun `hash covers the whole stream, not just the first buffer`() {
        val a = ByteArray(200_000)
        val b = a.copyOf().also { it[it.lastIndex] = 1 }
        assertNotEquals(ContentHash.of(a.inputStream()), ContentHash.of(b.inputStream()))
    }
}

class TsvFeedbackLogTest {

    private val file = File(Files.createTempDirectory("feedback").toFile(), "feedback.log")
    private val log = TsvFeedbackLog(file)

    private fun event(thumb: Thumb = Thumb.UP, at: Long = 1_000L, channel: String = "genre:jazz") =
        FeedbackEvent("sha256:aa", thumb, at, -300, channel)

    @Test
    fun `missing file reads as empty`() {
        assertEquals(emptyList<FeedbackEvent>(), log.readAll())
    }

    @Test
    fun `appends in order and never overwrites`() {
        val events = listOf(event(Thumb.UP, 1), event(Thumb.DOWN, 2), event(Thumb.UP, 3))
        events.forEach(log::append)
        assertEquals(events, log.readAll())
        // A fresh instance on the same file (app restart) sees the same history and adds to it.
        TsvFeedbackLog(file).append(event(Thumb.DOWN, 4))
        assertEquals(events + event(Thumb.DOWN, 4), log.readAll())
    }

    @Test
    fun `channel ids with separators round-trip`() {
        val odd = event(channel = "genre:a\tb\\c\nd")
        log.append(odd)
        assertEquals(listOf(odd), log.readAll())
        assertEquals(1, file.readLines().size)
    }

    @Test
    fun `torn line is skipped and the next append starts fresh`() {
        log.append(event(at = 1))
        file.appendText("1\t2\t-300\tsha256:aa\tU") // crash mid-write
        log.append(event(at = 3))
        assertEquals(listOf(event(at = 1), event(at = 3)), log.readAll())
    }

    @Test
    fun `unknown versions and garbage are skipped`() {
        file.writeText("2\t1\t0\th\tUP\tc\nnot a line\n\n")
        log.append(event())
        assertEquals(listOf(event()), log.readAll())
    }
}

class FeedbackRecorderTest {

    private class MemoryLog : FeedbackLog {
        val events = mutableListOf<FeedbackEvent>()
        override fun append(event: FeedbackEvent) { events += event }
        override fun readAll() = events.toList()
    }

    private val files = mutableMapOf(
        "content://media/1" to "song a",
        "content://media/2" to "song b",
    )
    private var opens = 0
    private val log = MemoryLog()
    private val recorder = FeedbackRecorder(
        log = log,
        open = { uri ->
            opens++
            files[uri]?.byteInputStream() ?: throw FileNotFoundException(uri)
        },
        ioDispatcher = Dispatchers.Unconfined,
        clock = { 1_700_000_000_000L },
        utcOffsetMinutes = { -300 },
    )

    @Test
    fun `records hash, thumb, time, offset and channel`() = runBlocking {
        val event = recorder.record("content://media/1", "genre:jazz", Thumb.DOWN)
        val expected = FeedbackEvent(ContentHash.of("song a".byteInputStream()), Thumb.DOWN, 1_700_000_000_000L, -300, "genre:jazz")
        assertEquals(expected, event)
        assertEquals(listOf(expected), log.events)
    }

    @Test
    fun `a moved file keeps its identity`() = runBlocking {
        val before = recorder.record("content://media/1", "all-songs", Thumb.UP)!!
        files["content://media/99"] = files.remove("content://media/1")!!
        val after = recorder.record("content://media/99", "all-songs", Thumb.UP)!!
        assertEquals(before.trackHash, after.trackHash)
        assertNotEquals(before.trackHash, recorder.record("content://media/2", "all-songs", Thumb.UP)!!.trackHash)
    }

    @Test
    fun `repeat presses append every time but hash once`() = runBlocking {
        repeat(3) { recorder.record("content://media/1", "other", Thumb.UP) }
        assertEquals(3, log.events.size)
        assertEquals(1, opens)
    }

    @Test
    fun `unreadable file logs nothing`() = runBlocking {
        assertNull(recorder.record("content://media/404", "other", Thumb.UP))
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `failed write reports null`() = runBlocking {
        val failing = FeedbackRecorder(
            log = object : FeedbackLog {
                override fun append(event: FeedbackEvent) = throw java.io.IOException("disk full")
                override fun readAll() = emptyList<FeedbackEvent>()
            },
            open = { "x".byteInputStream() },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertNull(failing.record("content://media/1", "other", Thumb.UP))
    }
}
