package app.viberadio.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNamesTest {

    private val names = ChannelNames(mapOf(ChannelLineup.BREEZE to "The Breeze", ChannelLineup.VOICE to "The Voice"))

    @Test
    fun `defaults until renamed`() {
        assertEquals("The Breeze", names.of(ChannelLineup.BREEZE))
        assertFalse(names.isCustom(ChannelLineup.BREEZE))
        assertNull(names.of("genre:jazz"))
    }

    @Test
    fun `rename is tidied`() {
        val renamed = names.renamed(ChannelLineup.BREEZE, "  Easy \n  going ")
        assertEquals("Easy going", renamed.of(ChannelLineup.BREEZE))
        assertTrue(renamed.isCustom(ChannelLineup.BREEZE))
    }

    @Test
    fun `empty or default name restores the default`() {
        val renamed = names.renamed(ChannelLineup.BREEZE, "Easy")
        assertEquals(names, renamed.renamed(ChannelLineup.BREEZE, "   "))
        assertEquals(names, renamed.renamed(ChannelLineup.BREEZE, " The Breeze "))
    }

    @Test
    fun `long names are capped`() {
        val renamed = names.renamed(ChannelLineup.VOICE, "x".repeat(100))
        assertEquals(ChannelNames.MAX_LENGTH, renamed.of(ChannelLineup.VOICE)!!.length)
    }

    @Test
    fun `unknown channels can't be named`() {
        assertEquals(names, names.renamed("genre:jazz", "Jazz"))
    }

    @Test
    fun `saved names are checked like new ones`() {
        val loaded = names.withSaved(mapOf(ChannelLineup.BREEZE to "Easy", ChannelLineup.VOICE to "The Voice", "gone" to "Old"))
        assertEquals(mapOf(ChannelLineup.BREEZE to "Easy"), loaded.custom)
    }

    @Test
    fun `lineup has the seven channels in settings order`() {
        assertEquals(listOf("breeze", "buzz", "zone", "breath", "hall", "all-songs", "podcasts"), ChannelLineup.ids)
    }
}
