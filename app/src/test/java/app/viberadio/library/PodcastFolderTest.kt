package app.viberadio.library

import app.viberadio.track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastFolderTest {

    private fun inFolder(folder: String) = track("Rock").copy(folder = folder)

    private val song = inFolder("Music/Rock")
    private val episode = inFolder("Podcasts")
    private val nested = inFolder("Podcasts/Talks")
    private val lookalike = inFolder("PodcastsOld")
    private val all = listOf(song, episode, nested, lookalike)

    @Test
    fun `the default folder takes its files and its subfolders' from the songs`() {
        assertEquals(listOf(episode, nested), PodcastFolder.DEFAULT.episodes(all))
        assertEquals(listOf(song, lookalike), PodcastFolder.DEFAULT.songs(all))
    }

    @Test
    fun `no podcast folder leaves every track a song`() {
        assertEquals(all, PodcastFolder.NONE.songs(all))
        assertTrue(PodcastFolder.NONE.episodes(all).isEmpty())
        assertFalse(PodcastFolder.NONE.contains("Podcasts"))
    }

    @Test
    fun `paths are tidied and blank means none`() {
        assertEquals("Audio/Talks", PodcastFolder.of("/Audio//Talks/").path)
        assertNull(PodcastFolder.of("").path)
        assertNull(PodcastFolder.of("/").path)
        assertNull(PodcastFolder.of(null).path)
    }

    @Test
    fun `the show is named after the folder itself`() {
        assertEquals("Podcasts", PodcastFolder.DEFAULT.title)
        assertEquals("Talks", PodcastFolder.of("Audio/Talks").title)
    }

    @Test
    fun `an episode's key survives a new MediaStore ID but not a different file`() {
        val rescanned = episode.copy(id = 999, uri = "content://media/external/audio/media/999")
        assertEquals(PodcastFolder.episodeKey(episode), PodcastFolder.episodeKey(rescanned))
        assertFalse(PodcastFolder.episodeKey(episode) == PodcastFolder.episodeKey(episode.copy(sizeBytes = 1)))
    }
}
