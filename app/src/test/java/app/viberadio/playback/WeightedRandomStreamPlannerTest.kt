package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelType
import app.viberadio.library.Track
import app.viberadio.tracks
import kotlin.math.pow
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightedRandomStreamPlannerTest {

    // 50 songs of 3 minutes: one cycle is 150 minutes.
    private val channel = Channel("genre:jazz", "Jazz", ChannelType.MUSIC_ONLY, tracks("Jazz", 50))
    private val cycle = 50 * 180_000L
    private var now = 1_000_000_000L

    private fun planner(
        heard: Map<Track, Long> = emptyMap(),
        preference: (Track) -> Double = { 1.0 },
        seed: Int = 1,
    ) = WeightedRandomStreamPlanner(heard::get, { now }, preference, random = Random(seed))

    private fun weight(p: WeightedRandomStreamPlanner, track: Track) = p.weights(channel, listOf(track)).single()

    @Test
    fun `an empty channel has nothing next`() {
        assertNull(planner().next(Channel("x", "X", ChannelType.MUSIC_ONLY, emptyList())))
    }

    @Test
    fun `next is a song on the channel and avoids queued ones`() {
        val avoid = channel.tracks.drop(1).toSet()
        repeat(20) { seed -> assertSame(channel.tracks[0], planner(seed = seed).next(channel, avoid)) }
    }

    @Test
    fun `a one-song channel repeats it`() {
        val solo = Channel("x", "X", ChannelType.MUSIC_ONLY, channel.tracks.take(1))
        assertSame(solo.tracks[0], planner(mapOf(solo.tracks[0] to now)).next(solo, avoid = solo.tracks.toSet()))
    }

    @Test
    fun `weight is p times (p x age in cycles) to the 1_5`() {
        val t = channel.tracks
        val p = planner(
            heard = mapOf(t[0] to now, t[1] to now - cycle, t[2] to now - 4 * cycle, t[3] to now - 4 * cycle),
            preference = { if (it == t[3]) 0.5 else 1.0 },
        )
        assertEquals("just played", 0.0, weight(p, t[0]), 0.0)
        assertEquals(1.0, weight(p, t[1]), 1e-9)
        assertEquals(8.0, weight(p, t[2]), 1e-9)
        assertEquals(0.5 * 2.0.pow(1.5), weight(p, t[3]), 1e-9)
    }

    @Test
    fun `a song never heard counts as last heard one cycle ago`() {
        assertEquals(1.0, weight(planner(), channel.tracks[0]), 1e-9)
    }

    @Test
    fun `age is capped and preference floored`() {
        val t = channel.tracks
        val p = planner(mapOf(t[0] to now - 1_000 * cycle, t[1] to now - cycle), preference = { if (it == t[1]) 0.0 else 1.0 })
        assertEquals(20.0.pow(1.5), weight(p, t[0]), 1e-6)
        assertEquals(0.1.pow(2.5), weight(p, t[1]), 1e-9)
    }

    @Test
    fun `a record ahead of the clock counts as just played`() {
        assertEquals(0.0, weight(planner(mapOf(channel.tracks[0] to now + 5_000)), channel.tracks[0]), 0.0)
    }

    @Test
    fun `upcoming picks distinct songs`() {
        val picks = planner().upcoming(channel, 10)
        assertEquals(10, picks.toSet().size)
    }

    @Test
    fun `successive picks differ`() {
        val p = planner(seed = 7)
        assertNotEquals(List(10) { p.next(channel) }, List(10) { p.next(channel) })
    }

    @Test
    fun `default exhaustion stays on the same channel`() {
        assertSame(channel, planner().onExhausted(channel, listOf(channel)))
    }

    /** Plays [picks] songs back to back, each 3 minutes on the listening clock. Returns the plays per song and the order. */
    private fun simulate(preference: (Track) -> Double, picks: Int, seed: Int = 3): Pair<Map<Track, Int>, List<Track>> {
        val heard = mutableMapOf<Track, Long>()
        val p = WeightedRandomStreamPlanner(heard::get, { now }, preference, random = Random(seed))
        val order = mutableListOf<Track>()
        var current: Track? = null
        repeat(picks) {
            val next = p.next(channel, avoid = setOfNotNull(current))!!
            heard[next] = now
            order += next
            current = next
            now += 180_000
        }
        return order.groupingBy { it }.eachCount() to order
    }

    @Test
    fun `play frequency follows preference`() {
        val liked = channel.tracks.take(25).toSet()
        val (counts, _) = simulate({ if (it in liked) 1.0 else 0.25 }, picks = 20_000)
        val likedPlays = liked.sumOf { counts[it] ?: 0 }.toDouble()
        val otherPlays = (20_000 - likedPlays)
        assertTrue("ratio ${likedPlays / otherPlays}", likedPlays / otherPlays in 3.0..5.0)
    }

    @Test
    fun `no song repeats back to back and none is starved`() {
        val (counts, order) = simulate({ if (it == channel.tracks[0]) 0.1 else 1.0 }, picks = 5_000)
        assertTrue(order.zipWithNext().none { (a, b) -> a == b })
        assertEquals(channel.tracks.toSet(), counts.keys)
    }

    @Test
    fun `there are no passes - a song can come back before others have played`() {
        val (_, order) = simulate({ 1.0 }, picks = 50)
        assertTrue("first 50 picks shouldn't be a permutation", order.toSet().size < 50)
    }
}
