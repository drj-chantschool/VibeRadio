package app.viberadio.ui

import app.viberadio.feedback.Thumb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayThumbsTest {

    private val song = "content://media/external/audio/media/1"
    private val other = "content://media/external/audio/media/2"

    @Test
    fun `a press stays lit for the rest of the play`() {
        assertEquals(Thumb.DOWN, PlayThumbs.carry(Thumb.DOWN, song, song, newPlay = false, ended = false))
    }

    @Test
    fun `the next song starts neutral`() {
        assertNull(PlayThumbs.carry(Thumb.DOWN, song, other, newPlay = true, ended = false))
    }

    @Test
    fun `a new play of the same song starts neutral`() {
        assertNull(PlayThumbs.carry(Thumb.DOWN, song, song, newPlay = true, ended = false))
    }

    @Test
    fun `the press clears when the track ends`() {
        assertNull(PlayThumbs.carry(Thumb.UP, song, song, newPlay = false, ended = true))
    }

    @Test
    fun `a folded update that changes the song still clears`() {
        assertNull(PlayThumbs.carry(Thumb.UP, song, other, newPlay = false, ended = false))
    }

    @Test
    fun `a podcast episode clears it`() {
        assertNull(PlayThumbs.carry(Thumb.UP, song, null, newPlay = true, ended = false))
    }
}
