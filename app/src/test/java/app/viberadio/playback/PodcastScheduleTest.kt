package app.viberadio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastScheduleTest {

    private val second = 1_000L
    private val minute = 60_000L

    /** How many song boundaries return false before one returns true. */
    private fun boundariesSkipped(schedule: PodcastSchedule): Int {
        var skipped = 0
        while (!schedule.songBoundary(0)) skipped++
        return skipped
    }

    private fun played(schedule: PodcastSchedule, contentMs: Long, from: Long = 0, to: Long = contentMs) = schedule.apply {
        episodeStarted(from)
        episodeEnded(contentMs, playedMs = to - from, nowMs = to)
    }

    // Tracks by episode length (the default rule).

    @Test
    fun `fresh schedule starts an episode at the first boundary`() {
        val schedule = PodcastSchedule()
        assertTrue(schedule.dueAtBoundary(null))
        assertTrue(schedule.songBoundary(0))
        assertFalse(schedule.startMidSong(10 * minute, null))
    }

    @Test
    fun `no episode starts while one is playing`() {
        val schedule = PodcastSchedule()
        schedule.episodeStarted(0)
        assertFalse(schedule.dueAtBoundary(null))
        repeat(5) { assertFalse(schedule.songBoundary(it * minute)) }
    }

    @Test
    fun `short episode - next one waits only for the current track`() {
        val schedule = played(PodcastSchedule(), 10 * minute)
        assertTrue(schedule.dueAtBoundary(null))
        assertEquals(0, boundariesSkipped(schedule))
    }

    @Test
    fun `over 15 minutes - one more full track`() {
        val schedule = played(PodcastSchedule(), 20 * minute)
        assertFalse(schedule.dueAtBoundary(null))
        assertEquals(1, boundariesSkipped(schedule))
    }

    @Test
    fun `over 30 minutes - three more full tracks`() {
        assertEquals(3, boundariesSkipped(played(PodcastSchedule(), 45 * minute)))
    }

    @Test
    fun `tracks rule never starts part-way through a song`() {
        val schedule = played(PodcastSchedule(), 45 * minute)
        assertFalse(schedule.startMidSong(10 * 60 * minute, null))
    }

    @Test
    fun `the rule is read when each episode ends`() {
        var rule: PodcastGapRule = PodcastGapRule.TracksByLength()
        val schedule = PodcastSchedule { rule }
        played(schedule, 20 * minute)
        assertEquals(1, boundariesSkipped(schedule))
        rule = PodcastGapRule.TracksByLength(longMinutes = 5.0, longTracks = 2)
        played(schedule, 10 * minute)
        assertEquals(2, boundariesSkipped(schedule))
    }

    @Test
    fun `tracks rule - a tune starts fresh at the next boundary`() {
        val schedule = played(PodcastSchedule(), 45 * minute)
        schedule.retune(0, podcastPlaying = false)
        assertTrue(schedule.dueAtBoundary(null))
        assertTrue(schedule.dueOnRetune(0, podcastPlaying = false))
        assertFalse(schedule.startMidSong(3 * 60 * minute, songEndsAtMs = 4 * 60 * minute))
        assertTrue(schedule.songBoundary(4 * 60 * minute))
    }

    @Test
    fun `a tune while an episode plays carries it on straight away`() {
        val schedule = PodcastSchedule { PodcastGapRule.MusicRatio() }
        schedule.episodeStarted(0)
        schedule.retune(10 * minute, podcastPlaying = true, playedMs = 10 * minute)
        assertFalse(schedule.episodeActive)
        assertTrue(schedule.dueOnRetune(10 * minute, podcastPlaying = true))
        assertTrue(schedule.songBoundary(10 * minute))
        // Its ten minutes so far stay in the streak.
        schedule.episodeStarted(10 * minute + 500)
        schedule.episodeEnded(0, playedMs = 10 * minute - 500, nowMs = 20 * minute)
        assertEquals(20 * minute - 500, schedule.streakMs)
    }

    @Test
    fun `a failed episode is retried at the next boundary, not straight away`() {
        val schedule = PodcastSchedule { PodcastGapRule.MusicRatio() }
        schedule.episodeStarted(0)
        schedule.episodeFailed(0)
        assertFalse(schedule.startMidSong(1 * second, null))
        assertTrue(schedule.songBoundary(2 * second))
    }

    @Test
    fun `ratio - a tune with nothing owed starts straight away, even mid-song`() {
        val schedule = PodcastSchedule { PodcastGapRule.MusicRatio() }
        schedule.retune(5 * minute, podcastPlaying = false)
        assertTrue(schedule.dueOnRetune(5 * minute, podcastPlaying = false))
        assertTrue(schedule.startMidSong(5 * minute, songEndsAtMs = 95 * minute))
        assertTrue(schedule.songBoundary(5 * minute))
    }

    @Test
    fun `ratio - a gap still owed carries across a tune`() {
        // 20 min of podcasts at 0.5: 10 min of music owed, of which 3 have played.
        val schedule = played(ratio(), 20 * minute)
        schedule.retune(23 * minute, podcastPlaying = false)
        assertFalse(schedule.dueOnRetune(23 * minute, podcastPlaying = false))
        assertFalse(schedule.songBoundary(23 * minute))
        assertFalse(schedule.startMidSong(29 * minute, null))
        assertTrue(schedule.startMidSong(30 * minute, null))
        assertEquals(20 * minute, schedule.streakMs)
    }

    @Test
    fun `ratio - a gap already over by the tune starts straight away`() {
        val schedule = played(ratio(), 20 * minute)
        schedule.retune(45 * minute, podcastPlaying = false)
        assertTrue(schedule.dueOnRetune(45 * minute, podcastPlaying = false))
        assertTrue(schedule.startMidSong(45 * minute, null))
    }

    @Test
    fun `ratio - the gap owed survives a restart, in real time`() {
        val owed = played(ratio(), 20 * minute).owed!!
        assertEquals(PodcastSchedule.Wait.Music(29 * minute, 30 * minute, 31 * minute), owed)
        val restarted = ratio()
        restarted.restore(owed)
        restarted.retune(25 * minute, podcastPlaying = false)
        assertFalse(restarted.startMidSong(30 * minute - 1, null))
        assertTrue(restarted.startMidSong(30 * minute, null))
    }

    @Test
    fun `ratio - the streak is podcast time played, not time passed`() {
        // Paused 40 min during a 20 min episode: the gap is still 10 min, from the end.
        val schedule = ratio()
        schedule.episodeStarted(0)
        schedule.episodeEnded(20 * minute, playedMs = 20 * minute, nowMs = 60 * minute)
        assertEquals(20 * minute, schedule.streakMs)
        assertEquals(PodcastSchedule.Wait.Music(69 * minute, 70 * minute, 71 * minute), schedule.wait)
    }

    @Test
    fun `nothing is owed without a music gap or while an episode plays`() {
        assertNull(PodcastSchedule().owed)
        assertNull(played(PodcastSchedule(), 45 * minute).owed)
        val playing = ratio()
        playing.episodeStarted(0)
        assertNull(playing.owed)
    }

    @Test
    fun `ratio - a failed episode is retried part-way through a long song after the max gap`() {
        val schedule = PodcastSchedule { PodcastGapRule.MusicRatio(maxMinutes = 10.0) }
        schedule.episodeStarted(0)
        schedule.episodeFailed(1 * minute)
        assertTrue(schedule.songBoundary(2 * minute))
        assertFalse(schedule.startMidSong(10 * minute, null))
        assertTrue(schedule.startMidSong(11 * minute, null))
        // A song ending by the max gap is waited for, not cut.
        assertFalse(schedule.startMidSong(11 * minute, songEndsAtMs = 11 * minute))
    }

    @Test
    fun `describe gives the wait relative to now`() {
        val schedule = played(ratio(), 20 * minute)
        assertEquals(
            "wait=music open_in_ms=480000 nominal_in_ms=540000 close_in_ms=600000 episode_active=false streak_ms=1200000",
            schedule.describe(21 * minute),
        )
        assertEquals("wait=music open_in_ms=0 nominal_in_ms=0 close_in_ms=0 episode_active=false streak_ms=0", ratio().apply { retune(0, false) }.describe(0))
        assertEquals("wait=boundary episode_active=false streak_ms=0", PodcastSchedule().describe(0))
        assertEquals("wait=tracks left=3 episode_active=false streak_ms=2700000", played(PodcastSchedule(), 45 * minute).describe(0))
    }

    // Music to podcast ratio.

    private fun ratio(ratio: Double = 0.5, floorSeconds: Double = 10.0, tolerancePercent: Double = 10.0) =
        PodcastSchedule { PodcastGapRule.MusicRatio(ratio, floorSeconds, tolerancePercent) }

    @Test
    fun `ratio - the gap is the ratio times the episode's playing time`() {
        // 20 min episode at ratio 0.5: 10 min of music, a boundary from 9 to 11 min starts the next.
        val schedule = played(ratio(), contentMs = 30 * minute, to = 20 * minute)
        assertEquals(20 * minute, schedule.streakMs)
        val end = 20 * minute
        assertFalse(schedule.songBoundary(end + 8 * minute))
        assertTrue(schedule.songBoundary(end + 9 * minute))
    }

    @Test
    fun `ratio - a boundary inside the window after the nominal time is waited for`() {
        val schedule = played(ratio(), 20 * minute)
        val nominal = 20 * minute + 10 * minute
        // The song playing at the nominal time ends 30 s later, inside the +1 min window.
        assertFalse(schedule.startMidSong(nominal, songEndsAtMs = nominal + 30 * second))
        assertTrue(schedule.dueAtBoundary(nominal + 30 * second))
        assertTrue(schedule.songBoundary(nominal + 30 * second))
    }

    @Test
    fun `ratio - no boundary in the window starts the episode over the song at the nominal time`() {
        val schedule = played(ratio(), 20 * minute)
        val nominal = 30 * minute
        val songEnds = nominal + 3 * minute
        assertFalse(schedule.startMidSong(nominal - 1, songEnds))
        assertTrue(schedule.startMidSong(nominal, songEnds))
        // Song length not known: no boundary to wait for.
        assertTrue(schedule.startMidSong(nominal, null))
    }

    @Test
    fun `ratio - a boundary before the window opens doesn't start one`() {
        val schedule = played(ratio(), 20 * minute)
        assertFalse(schedule.dueAtBoundary(28 * minute))
        assertFalse(schedule.songBoundary(28 * minute))
        assertFalse(schedule.dueAtBoundary(null))
    }

    @Test
    fun `ratio - under the floor the next episode follows straight on and the streak grows`() {
        // 60 s episodes back to back at 0.05: gaps of 3, 6 and 9 s are under the 10 s floor, so
        // each next one starts straight away, over the song; after the fourth, 12 s of music.
        val schedule = ratio(ratio = 0.05)
        var now = 0L
        for (n in 1..3) {
            schedule.episodeStarted(now)
            now += 60 * second
            schedule.episodeEnded(60 * second, 60 * second, now)
            assertEquals(n * 60 * second, schedule.streakMs)
            assertTrue(schedule.startMidSong(now, songEndsAtMs = now + 2 * minute))
            assertTrue(schedule.songBoundary(now))
        }
        schedule.episodeStarted(now)
        now += 60 * second
        schedule.episodeEnded(60 * second, 60 * second, now)
        assertEquals(240 * second, schedule.streakMs)
        assertFalse(schedule.startMidSong(now, null))
        assertTrue(schedule.startMidSong(now + 12 * second, null))
    }

    @Test
    fun `ratio - music in between starts a new streak`() {
        val schedule = played(ratio(), 20 * minute)
        schedule.episodeStarted(30 * minute)
        schedule.episodeEnded(4 * minute, 4 * minute, 34 * minute)
        assertEquals(4 * minute, schedule.streakMs)
    }

    @Test
    fun `ratio - an episode that had to wait for a song isn't back to back`() {
        val schedule = played(ratio(ratio = 0.1), contentMs = 60 * second)
        // A music podcast was next: it waited for the song to end, 2 min later.
        schedule.episodeStarted(3 * minute)
        schedule.episodeEnded(60 * second, 60 * second, 4 * minute)
        assertEquals(60 * second, schedule.streakMs)
    }

    @Test
    fun `ratio - zero tolerance means only the nominal time`() {
        val schedule = played(ratio(tolerancePercent = 0.0), 20 * minute)
        val nominal = 30 * minute
        assertFalse(schedule.songBoundary(nominal - 1))
        assertTrue(schedule.songBoundary(nominal))
        assertTrue(schedule.startMidSong(nominal, nominal + 1))
    }
}
