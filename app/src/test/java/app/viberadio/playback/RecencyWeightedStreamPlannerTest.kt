package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelType
import app.viberadio.library.Track
import app.viberadio.tracks
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecencyWeightedStreamPlannerTest {

    private val channel = Channel("genre:jazz", "Jazz", ChannelType.MUSIC_ONLY, tracks("Jazz", 50))
    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun planner(lastPlayed: Map<Track, Long> = emptyMap(), seed: Int = 1) =
        RecencyWeightedStreamPlanner(lastPlayed::get, clock = { now }, random = Random(seed))

    @Test
    fun `plan contains every track exactly once`() {
        val plan = planner(mapOf(channel.tracks[0] to now)).plan(channel)
        assertEquals(channel.tracks.size, plan.size)
        assertEquals(channel.tracks.toSet(), plan.toSet())
    }

    @Test
    fun `plan is shuffled`() {
        assertNotEquals(channel.tracks, planner().plan(channel))
    }

    @Test
    fun `successive plans differ so a looping channel reshuffles`() {
        val planner = planner(seed = 7)
        assertNotEquals(planner.plan(channel), planner.plan(channel))
    }

    @Test
    fun `default exhaustion stays on the same channel`() {
        assertSame(channel, planner().onExhausted(channel, listOf(channel)))
    }

    @Test
    fun `weight grows with time since last play`() {
        val t = channel.tracks
        val p = planner(mapOf(t[0] to now, t[1] to now - 3 * day, t[2] to now - 30 * day))
        assertEquals(RecencyWeightedStreamPlanner.MIN_WEIGHT, p.weight(t[0], now), 0.0)
        assertEquals(0.5, p.weight(t[1], now), 1e-9)
        assertTrue(p.weight(t[2], now) > 0.99)
        assertEquals(1.0, p.weight(t[3], now), 0.0)
    }

    @Test
    fun `a play in the future (clock change) counts as just played`() {
        val t = channel.tracks[0]
        assertEquals(RecencyWeightedStreamPlanner.MIN_WEIGHT, planner(mapOf(t to now + day)).weight(t, now), 0.0)
    }

    @Test
    fun `recently played songs tend to come late`() {
        // Half the channel played within the last hour, half never.
        val recent = channel.tracks.take(25).toSet()
        val history = recent.associateWith { now - 60 * 60 * 1000 }
        var recentInFirstHalf = 0
        repeat(200) { seed ->
            recentInFirstHalf += planner(history, seed).plan(channel).take(25).count { it in recent }
        }
        // Uniform shuffle would put ~12.5 per run in the first half.
        assertTrue("got ${recentInFirstHalf / 200.0}", recentInFirstHalf / 200.0 < 2)
    }

    @Test
    fun `with no history the first slot is roughly uniform`() {
        val counts = IntArray(channel.tracks.size)
        repeat(5_000) { seed -> counts[channel.tracks.indexOf(planner(seed = seed).plan(channel).first())]++ }
        // Expect 100 each; allow wide slack.
        assertTrue(counts.joinToString(), counts.all { it in 50..160 })
    }

    @Test
    fun `preferred songs tend to come early`() {
        val liked = channel.tracks.take(25).toSet()
        var likedInFirstHalf = 0
        repeat(200) { seed ->
            val planner = RecencyWeightedStreamPlanner(
                preference = { if (it in liked) 10.0 else 1.0 },
                clock = { now },
                random = Random(seed),
            )
            likedInFirstHalf += planner.plan(channel).take(25).count { it in liked }
        }
        // Uniform shuffle would put ~12.5 per run in the first half.
        assertTrue("got ${likedInFirstHalf / 200.0}", likedInFirstHalf / 200.0 > 20)
    }
}
