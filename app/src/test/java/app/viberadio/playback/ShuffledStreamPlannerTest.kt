package app.viberadio.playback

import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelType
import app.viberadio.tracks
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShuffledStreamPlannerTest {

    private val channel = Channel("genre:jazz", "Jazz", ChannelType.MUSIC_ONLY, tracks("Jazz", 50))

    @Test
    fun `plan contains every track exactly once`() {
        val plan = ShuffledStreamPlanner(Random(1)).plan(channel)
        assertEquals(channel.tracks.size, plan.size)
        assertEquals(channel.tracks.toSet(), plan.toSet())
    }

    @Test
    fun `plan is shuffled`() {
        assertNotEquals(channel.tracks, ShuffledStreamPlanner(Random(1)).plan(channel))
    }

    @Test
    fun `successive plans differ so a looping channel reshuffles`() {
        val planner = ShuffledStreamPlanner(Random(7))
        assertNotEquals(planner.plan(channel), planner.plan(channel))
    }

    @Test
    fun `default exhaustion stays on the same channel`() {
        assertSame(channel, ShuffledStreamPlanner().onExhausted(channel, listOf(channel)))
    }
}
