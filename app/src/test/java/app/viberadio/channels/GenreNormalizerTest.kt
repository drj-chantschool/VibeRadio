package app.viberadio.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenreNormalizerTest {

    @Test
    fun `missing and placeholder tags are untagged`() {
        listOf(null, "", "   ", "Unknown", "<unknown>", "Other").forEach {
            assertNull("'$it' should be untagged", GenreNormalizer.normalize(it))
        }
    }

    @Test
    fun `bare ID3v1 numeric codes are untagged`() {
        assertNull(GenreNormalizer.normalize("17"))
        assertNull(GenreNormalizer.normalize("(17)"))
    }

    @Test
    fun `ID3v1 prefix is stripped from a named genre`() {
        assertEquals("Rock", GenreNormalizer.normalize("(17)Rock")?.displayName)
    }

    @Test
    fun `multi-value tags use the first value`() {
        assertEquals("Jazz", GenreNormalizer.normalize("Jazz/Fusion")?.displayName)
        assertEquals("Rock", GenreNormalizer.normalize(" Rock ; Indie")?.displayName)
    }

    @Test
    fun `spelling variants share a key`() {
        val keys = listOf("Hip-Hop", "hip hop", "HipHop").map { GenreNormalizer.normalize(it)?.key }
        assertEquals(listOf("hiphop", "hiphop", "hiphop"), keys)
    }
}
