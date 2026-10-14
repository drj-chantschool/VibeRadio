package app.viberadio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastScheduleTest {

    private val minute = 60_000L

    /** How many song boundaries return false before one returns true. */
    private fun boundariesSkipped(schedule: PodcastSchedule): Int {
        var skipped = 0
        while (!schedule.songBoundary()) skipped++
        return skipped
    }

    @Test
    fun `fresh schedule starts an episode at the first boundary`() {
        val schedule = PodcastSchedule()
        assertTrue(schedule.due)
        assertTrue(schedule.songBoundary())
    }

    @Test
    fun `no episode starts while one is playing`() {
        val schedule = PodcastSchedule()
        schedule.episodeStarted()
        assertFalse(schedule.due)
        repeat(5) { assertFalse(schedule.songBoundary()) }
    }

    @Test
    fun `short episode - next one waits only for the current track`() {
        val schedule = PodcastSchedule().apply { episodeStarted(); episodeEnded(10 * minute) }
        assertTrue(schedule.due)
        assertEquals(0, boundariesSkipped(schedule))
    }

    @Test
    fun `over 15 minutes - one more full track`() {
        val schedule = PodcastSchedule().apply { episodeStarted(); episodeEnded(20 * minute) }
        assertFalse(schedule.due)
        assertEquals(1, boundariesSkipped(schedule))
    }

    @Test
    fun `over 30 minutes - three more full tracks`() {
        val schedule = PodcastSchedule().apply { episodeStarted(); episodeEnded(45 * minute) }
        assertEquals(3, boundariesSkipped(schedule))
    }

    @Test
    fun `thresholds are strictly longer than`() {
        assertEquals(0, PodcastSchedule.extraTracksAfter(15 * minute))
        assertEquals(1, PodcastSchedule.extraTracksAfter(15 * minute + 1))
        assertEquals(1, PodcastSchedule.extraTracksAfter(30 * minute))
        assertEquals(3, PodcastSchedule.extraTracksAfter(30 * minute + 1))
        assertEquals(0, PodcastSchedule.extraTracksAfter(0))
    }

    @Test
    fun `reset clears the wait and the active episode`() {
        val schedule = PodcastSchedule().apply { episodeStarted(); episodeEnded(45 * minute) }
        schedule.reset()
        assertTrue(schedule.due)
        schedule.episodeStarted()
        schedule.reset()
        assertTrue(schedule.songBoundary())
    }
}
