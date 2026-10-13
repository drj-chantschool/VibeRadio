package app.viberadio.podcasts

import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastSpeedsTest {

    @Test
    fun `snaps to the nearest step`() {
        assertEquals(1.0f, PodcastSpeeds.snap(1.04f))
        assertEquals(1.5f, PodcastSpeeds.snap(1.45f))
        assertEquals(2.0f, PodcastSpeeds.snap(3.0f))
        assertEquals(0.8f, PodcastSpeeds.snap(0.1f))
        assertEquals(PodcastSpeeds.DEFAULT, PodcastSpeeds.snap(Float.NaN))
    }

    @Test
    fun `steps up and down the ladder and stops at the ends`() {
        assertEquals(1.1f, PodcastSpeeds.faster(1.0f))
        assertEquals(0.9f, PodcastSpeeds.slower(1.0f))
        assertEquals(PodcastSpeeds.STEPS.last(), PodcastSpeeds.faster(PodcastSpeeds.STEPS.last()))
        assertEquals(PodcastSpeeds.STEPS.first(), PodcastSpeeds.slower(PodcastSpeeds.STEPS.first()))
    }

    @Test
    fun `withSpeed stores only ladder values`() {
        val feed = Feed("A", emptyList())
        val lib = PodcastLibrary().withFeed("https://a/feed", feed, 0).withSpeed("https://a/feed", 1.27f)
        assertEquals(1.3f, lib.podcast("https://a/feed")!!.speed)
    }

    @Test
    fun `the ladder includes normal speed`() {
        assert(PodcastSpeeds.DEFAULT in PodcastSpeeds.STEPS)
    }
}
