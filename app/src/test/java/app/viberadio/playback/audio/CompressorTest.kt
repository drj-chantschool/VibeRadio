package app.viberadio.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressorTest {

    private val rate = 1_000
    private val on = CompressorSettings(enabled = true, thresholdDb = -20f, ratio = 4f, attackMs = 1f, releaseMs = 50f, kneeDb = 0f)

    private fun Compressor.run(frames: Int, value: Float, s: CompressorSettings, rampMs: Long = 0): Float {
        val frame = FloatArray(1)
        repeat(frames) {
            frame[0] = value
            processFrame(frame, s, rampMs)
        }
        return frame[0]
    }

    @Test
    fun `static curve leaves the level alone below the threshold and divides the excess by the ratio above`() {
        assertEquals(0f, Compressor.reductionFor(-30f, on), 1e-6f)
        assertEquals(0f, Compressor.reductionFor(-20f, on), 1e-6f)
        // 12 dB over at 4:1 -> 3 dB over, so 9 dB of reduction.
        assertEquals(9f, Compressor.reductionFor(-8f, on), 1e-4f)
    }

    @Test
    fun `a soft knee blends smoothly and is continuous`() {
        val knee = on.copy(kneeDb = 10f)
        assertEquals(0f, Compressor.reductionFor(-26f, knee), 1e-6f)
        val atThreshold = Compressor.reductionFor(-20f, knee)
        assertTrue(atThreshold > 0f && atThreshold < Compressor.reductionFor(-8f, knee))
        // Meets the straight line at the top of the knee.
        assertEquals(on.let { Compressor.reductionFor(-15f, it) }, Compressor.reductionFor(-15f, knee), 1e-4f)
    }

    @Test
    fun `a loud steady signal settles at the static reduction`() {
        val c = Compressor(rate, 1)
        val out = c.run(2_000, 0.398f, on) // about -8 dBFS
        assertEquals(9f, c.reductionDb, 0.05f)
        assertEquals(0.398f * StreamGain.dbToLinear(-9f), out, 0.002f)
    }

    @Test
    fun `a quiet signal passes untouched`() {
        val c = Compressor(rate, 1)
        assertEquals(0.05f, c.run(500, 0.05f, on), 1e-6f)
        assertEquals(0f, c.reductionDb, 1e-6f)
    }

    @Test
    fun `release lets go gradually`() {
        val c = Compressor(rate, 1)
        c.run(500, 0.398f, on)
        c.run(10, 0.01f, on)
        assertTrue(c.reductionDb > 0f && c.reductionDb < 9f)
        c.run(1_000, 0.01f, on)
        assertEquals(0f, c.reductionDb, 1e-3f)
    }

    @Test
    fun `channels share one gain reduction`() {
        val c = Compressor(rate, 2)
        val frame = FloatArray(2)
        repeat(1_000) {
            frame[0] = 0.5f
            frame[1] = 0.05f
            c.processFrame(frame, on, 0)
        }
        assertEquals(10f, frame[0] / frame[1], 0.05f)
    }

    @Test
    fun `disabled it releases then bypasses`() {
        val c = Compressor(rate, 1)
        c.run(500, 0.398f, on)
        assertFalse(c.bypassed(false))
        c.run(2_000, 0.398f, on.copy(enabled = false))
        assertTrue(c.bypassed(false))
        assertFalse(c.bypassed(true))
    }

    @Test
    fun `make-up fades in over the ramp and is limited below the ceiling`() {
        val s = on.copy(thresholdDb = 0f, makeupDb = 12f)
        val c = Compressor(rate, 1)
        val first = c.run(1, 0.1f, s, rampMs = 1_000)
        assertTrue(first < 0.1f * StreamGain.dbToLinear(1f))
        val settled = c.run(2_000, 0.1f, s, rampMs = 1_000)
        assertEquals(0.1f * StreamGain.dbToLinear(12f), settled, 1e-3f)
        assertTrue(c.run(2_000, 0.9f, s, rampMs = 1_000) <= SoftLimiter.CEILING)
    }

    @Test
    fun `a hard knee at exactly the threshold is finite`() {
        assertEquals(0f, Compressor.reductionFor(-20f, on.copy(kneeDb = 0f)), 1e-6f)
    }

    @Test
    fun `settings are range-checked`() {
        assertTrue(CompressorSettings.DEFAULT.isValid)
        assertFalse(CompressorSettings(ratio = 0.5f).isValid)
        assertFalse(CompressorSettings(thresholdDb = 3f).isValid)
        assertFalse(CompressorSettings(attackMs = 0f).isValid)
        assertFalse(CompressorSettings(makeupDb = 30f).isValid)
        assertFalse(CompressorSettings.DEFAULT.enabled)
    }
}
