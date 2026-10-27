package app.viberadio.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamGainTest {

    private val rate = 1_000

    private fun StreamGain.run(frames: Int, value: Float = 1f): Float {
        val frame = FloatArray(1)
        repeat(frames) {
            frame[0] = value
            processFrame(frame)
        }
        return frame[0]
    }

    @Test
    fun `a new item starts at its own gain with no ramp`() {
        val gain = StreamGain(rate, 1)
        gain.startItem(levelDb = -6f, mixDb = 0f)
        assertEquals(StreamGain.dbToLinear(-6f), gain.run(1), 1e-6f)
        gain.startItem(levelDb = 3f, mixDb = 0f)
        assertEquals(StreamGain.dbToLinear(3f), gain.run(1), 1e-6f)
    }

    @Test
    fun `the mixing factor ramps in over the ramp time`() {
        val gain = StreamGain(rate, 1)
        gain.startItem(levelDb = -4f, mixDb = 0f)
        gain.aim(levelDb = -4f, mixDb = -10f, rampMs = 1_000)
        gain.run(500)
        assertEquals(-9f, gain.currentDb, 0.05f)
        gain.run(500)
        assertEquals(-14f, gain.currentDb, 1e-4f)
        // And back out.
        gain.aim(levelDb = -4f, mixDb = 0f, rampMs = 1_000)
        gain.run(1_000)
        assertEquals(-4f, gain.currentDb, 1e-4f)
    }

    @Test
    fun `a level that arrives early in the item is ramped in`() {
        val gain = StreamGain(rate, 1)
        gain.startItem(levelDb = null, mixDb = 0f)
        gain.run(2_000)
        assertEquals(0f, gain.currentDb)
        gain.aim(levelDb = -8f, mixDb = 0f, rampMs = 1_000)
        gain.run(1_000)
        assertEquals(-8f, gain.currentDb, 1e-4f)
    }

    @Test
    fun `a level that arrives late waits for the next play`() {
        val gain = StreamGain(rate, 1)
        gain.startItem(levelDb = null, mixDb = 0f)
        gain.run((StreamGain.LATE_WINDOW_MS * rate / 1000).toInt() + 1)
        gain.aim(levelDb = -8f, mixDb = 0f, rampMs = 1_000)
        gain.run(2_000)
        assertEquals(0f, gain.currentDb)
        // The mixing factor still applies.
        gain.aim(levelDb = -8f, mixDb = -10f, rampMs = 0)
        assertEquals(-10f, gain.currentDb)
    }

    @Test
    fun `unity passes samples through untouched`() {
        val gain = StreamGain(rate, 2)
        gain.startItem(levelDb = 0f, mixDb = 0f)
        val frame = floatArrayOf(0.123f, -0.456f)
        gain.processFrame(frame)
        assertEquals(0.123f, frame[0])
        assertEquals(-0.456f, frame[1])
    }

    @Test
    fun `a boost never pushes a sample past the limiter ceiling`() {
        val gain = StreamGain(rate, 1)
        gain.startItem(levelDb = 12f, mixDb = 0f)
        assertEquals(SoftLimiter.CEILING, gain.run(1, value = 1f), 0.01f)
        assertTrue(gain.run(1, value = 1f) < SoftLimiter.CEILING + 1e-6f)
        // Quiet material passes untouched.
        assertEquals(StreamGain.dbToLinear(12f) * 0.1f, gain.run(1, value = 0.1f), 1e-5f)
    }

    @Test
    fun `the limiter leaves samples under the knee alone and is odd`() {
        assertEquals(0.5f, SoftLimiter.apply(0.5f), 0f)
        assertEquals(-SoftLimiter.apply(2f), SoftLimiter.apply(-2f), 0f)
    }
}
