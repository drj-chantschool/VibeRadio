package app.viberadio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {

    @Test
    fun `reads timestamped lines`() {
        val notes = """
            In this episode:

            00:00 Intro
            (05:30) The interview
            - 1:02:03 - Wrap-up
            Thanks for listening
        """.trimIndent()
        assertEquals(
            listOf(Chapter(0, "Intro"), Chapter(330_000, "The interview"), Chapter(3_723_000, "Wrap-up")),
            Chapters.fromDescription(notes),
        )
    }

    @Test
    fun `link text is kept`() {
        val notes = "0:00 [Opening](https://example.org/a)\n2:00 Second"
        assertEquals(listOf(Chapter(0, "Opening"), Chapter(120_000, "Second")), Chapters.fromDescription(notes))
    }

    @Test
    fun `a stray time is not a chapter list`() {
        assertTrue(Chapters.fromDescription("Recorded live\n10:30 We start").isEmpty())
        assertTrue(Chapters.fromDescription(null).isEmpty())
    }

    @Test
    fun `a list that starts late or runs backwards is ignored`() {
        assertTrue(Chapters.fromDescription("10:00 Late\n20:00 Later").isEmpty())
        assertTrue(Chapters.fromDescription("0:00 A\n5:00 B\n3:00 C").isEmpty())
    }

    @Test
    fun `at picks the chapter on air`() {
        val chapters = listOf(Chapter(0, "A"), Chapter(60_000, "B"), Chapter(120_000, "C"))
        assertEquals("A", Chapters.at(chapters, 0))
        assertEquals("A", Chapters.at(chapters, 59_999))
        assertEquals("B", Chapters.at(chapters, 60_000))
        assertEquals("C", Chapters.at(chapters, 9_999_999))
        assertNull(Chapters.at(listOf(Chapter(5_000, "A")), 1_000))
        assertNull(Chapters.at(emptyList(), 1_000))
    }
}
