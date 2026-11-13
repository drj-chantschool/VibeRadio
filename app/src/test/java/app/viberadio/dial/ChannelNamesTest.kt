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

class ChannelShortNamesTest {

    private val names = ChannelNames(mapOf(ChannelLineup.BREEZE to "The Breeze", ChannelLineup.VOICE to "The Voice"))

    @Test
    fun `lineup channels have their own tags`() {
        assertEquals("V-BRZ", names.shortOf(ChannelLineup.BREEZE))
        assertEquals("V-OIC", names.shortOf(ChannelLineup.VOICE))
        assertEquals(
            listOf("V-BRZ", "V-BUZ", "V-ZNE", "V-BRH", "V-HAL", "V-SHF", "V-OIC"),
            ChannelLineup.ids.map { ChannelLineup.shortNames.getValue(it) },
        )
    }

    @Test
    fun `a renamed channel's tag follows its name`() {
        assertEquals("V-EAS", names.renamed(ChannelLineup.BREEZE, "The Easy Hour").shortOf(ChannelLineup.BREEZE))
        assertEquals("V-JAZ", names.renamed(ChannelLineup.BREEZE, "Jazz").shortOf(ChannelLineup.BREEZE))
        assertEquals("V-AB", names.renamed(ChannelLineup.BREEZE, "A b").shortOf(ChannelLineup.BREEZE))
    }

    @Test
    fun `restoring the default restores the tag`() {
        assertEquals("V-BRZ", names.renamed(ChannelLineup.BREEZE, "Easy").renamed(ChannelLineup.BREEZE, "").shortOf(ChannelLineup.BREEZE))
    }

    @Test
    fun `other channels are tagged from the fallback name`() {
        assertEquals("V-ROC", names.shortOf("genre:rock", "Rock"))
        assertNull(names.shortOf("genre:rock"))
        assertEquals("V", ChannelShortNames.derive("!!!"))
    }
}
