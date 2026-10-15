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

class LegacyTsvLogTest {

    private val file = File(Files.createTempDirectory("feedback").toFile(), "feedback.log")

    @Test
    fun `missing file reads as empty`() {
        assertEquals(emptyList<FeedbackEvent>(), LegacyTsvLog.read(file))
    }

    @Test
    fun `reads lines in order, unescaping the channel id`() {
        file.writeText("1\t1\t-300\tsha256:aa\tUP\tgenre:jazz\n1\t2\t-300\tsha256:bb\tDOWN\tgenre:a\\tb\\\\c\\nd\n")
        assertEquals(
            listOf(
                FeedbackEvent("sha256:aa", Thumb.UP, 1, -300, "genre:jazz"),
                FeedbackEvent("sha256:bb", Thumb.DOWN, 2, -300, "genre:a\tb\\c\nd"),
            ),
            LegacyTsvLog.read(file),
        )
    }

    @Test
    fun `torn, unknown-version and garbage lines are skipped`() {
        file.writeText("2\t1\t0\th\tUP\tc\nnot a line\n\n1\t3\t0\tsha256:aa\tUP\tother\n1\t2\t-300\tsha256:aa\tU")
        assertEquals(listOf(FeedbackEvent("sha256:aa", Thumb.UP, 3, 0, "other")), LegacyTsvLog.read(file))
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
    fun `records the separation module in effect`() = runBlocking {
        assertEquals("graphic", recorder.record("content://media/1", "breeze", Thumb.UP, separationEq = "graphic")!!.separationEq)
        assertNull(recorder.record("content://media/1", "breeze", Thumb.UP)!!.separationEq)
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
