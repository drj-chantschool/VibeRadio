package app.viberadio.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastGapRuleTest {

    private val minute = 60_000L

    @Test
    fun `tracks by length - thresholds are strictly longer than`() {
        val rule = PodcastGapRule.TracksByLength()
        assertEquals(Gap.Tracks(0), rule.gapAfter(15 * minute, 0))
        assertEquals(Gap.Tracks(1), rule.gapAfter(15 * minute + 1, 0))
        assertEquals(Gap.Tracks(1), rule.gapAfter(30 * minute, 0))
        assertEquals(Gap.Tracks(3), rule.gapAfter(30 * minute + 1, 0))
        assertEquals(Gap.Tracks(0), rule.gapAfter(0, 0))
    }

    @Test
    fun `music ratio - ratio times streak, with the tolerance as a fraction`() {
        val rule = PodcastGapRule.MusicRatio(ratio = 0.25, floorSeconds = 10.0, tolerancePercent = 10.0)
        assertEquals(Gap.Music(5 * minute, 0.1), rule.gapAfter(0, 20 * minute))
    }

    @Test
    fun `music ratio - under the floor there is no gap`() {
        val rule = PodcastGapRule.MusicRatio(ratio = 0.5, floorSeconds = 10.0)
        assertEquals(Gap.Music(0), rule.gapAfter(0, 19_998))
        assertEquals(Gap.Music(10_000, 0.1), rule.gapAfter(0, 20_000))
        assertEquals(Gap.Music(0), PodcastGapRule.MusicRatio(ratio = 0.0).gapAfter(0, 60 * minute))
    }

    @Test
    fun `music ratio - the gap is capped at the max`() {
        val rule = PodcastGapRule.MusicRatio(ratio = 0.5, maxMinutes = 10.0)
        assertEquals(Gap.Music(10 * minute, 0.1), rule.gapAfter(0, 60 * minute))
        assertEquals(Gap.Music(9 * minute, 0.1), rule.gapAfter(0, 18 * minute))
        assertEquals(Gap.Music(10 * minute, 0.1), PodcastGapRule.MusicRatio().gapAfter(0, 3 * 60 * minute))
    }

    @Test
    fun `longest gap - the ratio rule's max, none for the tracks rule`() {
        assertEquals(10 * minute, PodcastGapRule.MusicRatio(maxMinutes = 10.0).longestGapMs)
        assertNull(PodcastGapRule.TracksByLength().longestGapMs)
    }

    @Test
    fun `music ratio - a max below the floor is refused`() {
        assertFalse(PodcastGapRule.MusicRatio(floorSeconds = 30.0, maxMinutes = 0.25).isValid)
        assertTrue(PodcastGapRule.MusicRatio(floorSeconds = 30.0, maxMinutes = 0.5).isValid)
    }

    @Test
    fun `every rule round-trips its parameters`() {
        for (rule in PodcastGapRule.all) {
            assertTrue(rule.isValid)
            for (p in rule.params) {
                val value = if (p.whole) p.range.endInclusive else (p.range.start + p.range.endInclusive) / 2
                val changed = rule.with(p.key, value)
                assertEquals(value, changed[p.key], 0.0)
                assertEquals(rule.id, changed.id)
            }
        }
    }

    @Test
    fun `out of range, fractional track counts and crossed thresholds are refused`() {
        val tracks = PodcastGapRule.TracksByLength()
        assertFalse(tracks.accepts(PodcastGapRule.TracksByLength.LONG_TRACKS, 1.5))
        assertFalse(tracks.accepts(PodcastGapRule.TracksByLength.LONG_TRACKS, -1.0))
        assertFalse(tracks.accepts("nope", 1.0))
        assertFalse(tracks.copy(longMinutes = 40.0).isValid)
        val ratio = PodcastGapRule.MusicRatio()
        assertFalse(ratio.accepts(PodcastGapRule.MusicRatio.RATIO, 1.1))
        assertFalse(ratio.accepts(PodcastGapRule.MusicRatio.RATIO, Double.NaN))
        assertTrue(ratio.accepts(PodcastGapRule.MusicRatio.RATIO, 1.0))
    }

    @Test
    fun `settings keep each rule's figures while another is chosen`() {
        val s = PodcastGapSettings.DEFAULT
            .set(PodcastGapRule.MusicRatio.ID, PodcastGapRule.MusicRatio.RATIO, 0.3)!!
            .select(PodcastGapRule.MusicRatio.ID)
            .select(PodcastGapRule.TracksByLength.ID)
            .select(PodcastGapRule.MusicRatio.ID)
        assertEquals(PodcastGapRule.MusicRatio(ratio = 0.3), s.active)
        assertTrue(s.isValid)
        assertNull(s.set(PodcastGapRule.MusicRatio.ID, PodcastGapRule.MusicRatio.RATIO, 2.0))
        assertFalse(s.select("unknown").isValid)
        assertEquals(PodcastGapRule.DEFAULT, PodcastGapSettings.DEFAULT.active)
    }

    private class FakeStore(var saved: PodcastGapSettings? = null) : PodcastGapStore {
        override fun read(): PodcastGapSettings? = saved
        override fun write(settings: PodcastGapSettings) {
            saved = settings
        }
    }

    private fun repository(store: PodcastGapStore) =
        PodcastGapSettingsRepository(store, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    @Test
    fun `repository saves changes and refuses invalid ones`() {
        val store = FakeStore()
        val repo = repository(store)
        assertTrue(repo.select(PodcastGapRule.MusicRatio.ID))
        assertTrue(repo.set(PodcastGapRule.MusicRatio.ID, PodcastGapRule.MusicRatio.FLOOR_SECONDS, 30.0))
        assertFalse(repo.set(PodcastGapRule.TracksByLength.ID, PodcastGapRule.TracksByLength.LONG_MINUTES, 45.0))
        assertFalse(repo.select("unknown"))
        assertEquals(PodcastGapRule.MusicRatio(floorSeconds = 30.0), store.saved?.active)
        assertEquals(store.saved, repository(store).settings.value)
        assertTrue(repo.restoreDefaults())
        assertEquals(PodcastGapSettings.DEFAULT.select(PodcastGapRule.MusicRatio.ID), repo.settings.value)
    }

    @Test
    fun `repository ignores invalid saved settings`() {
        val bad = PodcastGapSettings(selected = "gone")
        assertEquals(PodcastGapSettings.DEFAULT, repository(FakeStore(bad)).settings.value)
    }

    @Test
    fun `on-air clock only runs between start and stop`() {
        var t = 1_000L
        val clock = OnAirClock { t }
        t += 500
        assertEquals(0, clock.now())
        clock.start()
        t += 2_000
        assertEquals(2_000, clock.now())
        clock.stop()
        t += 10_000
        assertEquals(2_000, clock.now())
        clock.start()
        t += 1_000
        assertEquals(3_000, clock.now())
    }
}
