package app.viberadio.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiFilesTest {

    @Test
    fun `MIDI MIME types are MIDI`() {
        listOf("audio/midi", "audio/x-midi", "audio/sp-midi", "AUDIO/MIDI", "audio/midi; charset=binary").forEach {
            assertTrue("'$it' should be MIDI", MidiFiles.isMidi(it, "song"))
        }
    }

    @Test
    fun `MIDI extensions are MIDI even with a generic MIME type`() {
        listOf("a.mid", "b.MIDI", "c.kar", "d.rmi", "my.song.Mid").forEach {
            assertTrue("'$it' should be MIDI", MidiFiles.isMidi("application/octet-stream", it))
            assertTrue("'$it' should be MIDI", MidiFiles.isMidi(null, it))
        }
    }

    @Test
    fun `recorded audio is not MIDI`() {
        assertFalse(MidiFiles.isMidi("audio/mpeg", "song.mp3"))
        assertFalse(MidiFiles.isMidi("audio/flac", "midnight.flac"))
        assertFalse(MidiFiles.isMidi("audio/ogg", "mid"))
        assertFalse(MidiFiles.isMidi(null, null))
        assertFalse(MidiFiles.isMidi("", ""))
    }
}
