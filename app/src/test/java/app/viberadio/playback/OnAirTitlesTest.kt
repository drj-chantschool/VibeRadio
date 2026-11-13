package app.viberadio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnAirTitlesTest {

    private val song = TitleLine("Bring Me To Life", "Evanescence")
    private val episode = EpisodeTitles("Episode 12", "The Show", "Second half")

    @Test
    fun `song alone is tagged`() {
        assertEquals(listOf(TitleLine("V-BRZ: Bring Me To Life", "Evanescence")), OnAirTitles.lines("V-BRZ", song, null))
    }

    @Test
    fun `podcast over music switches song, episode, chapter and tags only the song`() {
        assertEquals(
            listOf(
                TitleLine("V-BRZ: Bring Me To Life", "Evanescence"),
                TitleLine("Episode 12", "The Show"),
                TitleLine("Second half", "Episode 12"),
            ),
            OnAirTitles.lines("V-BRZ", song, episode),
        )
    }

    @Test
    fun `an episode by itself is primary`() {
        assertEquals(
            listOf(TitleLine("V-OIC: Episode 12", "The Show"), TitleLine("Second half", "Episode 12")),
            OnAirTitles.lines("V-OIC", null, episode),
        )
        assertEquals(listOf(TitleLine("Episode 12", "The Show")), OnAirTitles.lines(null, null, episode.copy(chapter = null)))
    }

    @Test
    fun `nothing on air gives no lines`() {
        assertTrue(OnAirTitles.lines("V-BRZ", null, null).isEmpty())
        assertNull(OnAirTitles.at(emptyList(), 3))
    }

    @Test
    fun `steps wrap`() {
        val lines = OnAirTitles.lines("V-BRZ", song, episode)
        assertEquals(lines[0], OnAirTitles.at(lines, 0))
        assertEquals(lines[2], OnAirTitles.at(lines, 2))
        assertEquals(lines[0], OnAirTitles.at(lines, 3))
        assertEquals(lines[1], OnAirTitles.at(lines, 4))
    }
}
