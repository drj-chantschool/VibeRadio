package app.viberadio.playback.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEqTest {

    private val rate = 48_000
    private val defaults = AudioSettings.DEFAULT

    private fun gain(stream: AudioStream, hz: Double, settings: AudioSettings = defaults): Double =
        StreamEq.design(stream, settings, rate).sumOf { it.gainDb(hz, rate) }

    @Test
    fun `podcast band pass keeps 2 kHz and cuts the lows and highs`() {
        assertEquals(0.0, gain(AudioStream.PODCAST, 2_000.0), 1.5)
        assertTrue(gain(AudioStream.PODCAST, 200.0) < -20)
        assertTrue(gain(AudioStream.PODCAST, 15_000.0) < -15)
    }

    @Test
    fun `podcast band edges are a few dB down`() {
        assertTrue(gain(AudioStream.PODCAST, 1_000.0) in -5.0..-2.0)
        assertTrue(gain(AudioStream.PODCAST, 4_000.0) in -5.0..-2.0)
    }

    @Test
    fun `music notch cuts the full depth at 2 kHz and leaves bass and air`() {
        assertEquals(-9.0, gain(AudioStream.MUSIC, defaults.musicCentreHz.toDouble()), 0.1)
        assertEquals(0.0, gain(AudioStream.MUSIC, 60.0), 1.0)
        assertEquals(0.0, gain(AudioStream.MUSIC, 18_000.0), 1.5)
    }

    @Test
    fun `music notch edges are at about half the depth`() {
        assertEquals(-4.5, gain(AudioStream.MUSIC, 700.0), 1.0)
        assertEquals(-4.5, gain(AudioStream.MUSIC, 5_700.0), 1.0)
    }

    @Test
    fun `depth setting changes the cut`() {
        val deeper = defaults.copy(musicDepthDb = -18f)
        assertEquals(-18.0, gain(AudioStream.MUSIC, deeper.musicCentreHz.toDouble(), deeper), 0.1)
    }

    @Test
    fun `flat blend passes samples through unchanged`() {
        val eq = StreamEq(AudioStream.MUSIC, rate, 2, defaults, initialMix = 0f)
        val frame = FloatArray(2)
        for (i in 0 until 1_000) {
            val x = sin(2 * PI * 2_000 * i / rate).toFloat()
            frame[0] = x
            frame[1] = -x
            eq.processFrame(frame, target = 0f)
            assertEquals(x, frame[0], 1e-6f)
            assertEquals(-x, frame[1], 1e-6f)
        }
    }

    @Test
    fun `blend ramps over the ramp time, not instantly`() {
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, defaults.copy(rampMs = 1_000), initialMix = 0f)
        val frame = FloatArray(1)
        repeat(rate / 2) { eq.processFrame(frame, target = 1f) }
        assertEquals(0.5f, eq.mix, 0.01f)
        repeat(rate / 2 + 10) { eq.processFrame(frame, target = 1f) }
        assertEquals(1f, eq.mix, 0f)
        repeat(rate / 4) { eq.processFrame(frame, target = 0f) }
        assertEquals(0.75f, eq.mix, 0.01f)
    }

    @Test
    fun `zero ramp switches at once`() {
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, defaults.copy(rampMs = 0), initialMix = 0f)
        eq.processFrame(FloatArray(1), target = 1f)
        assertEquals(1f, eq.mix, 0f)
    }

    @Test
    fun `filtered music at 2 kHz comes out about 9 dB down`() {
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, defaults, initialMix = 1f)
        val frame = FloatArray(1)
        var peak = 0f
        for (i in 0 until rate) {
            frame[0] = sin(2 * PI * defaults.musicCentreHz * i / rate).toFloat()
            eq.processFrame(frame, target = 1f)
            if (i > rate / 2) peak = maxOf(peak, abs(frame[0]))
        }
        // -9 dB is about 0.355.
        assertEquals(0.355f, peak, 0.02f)
    }

    @Test
    fun `settings validation`() {
        assertTrue(defaults.isValid)
        assertTrue(!defaults.copy(podcastLowHz = 5_000f).isValid)
        assertTrue(!defaults.copy(musicHighHz = 30_000f).isValid)
        assertTrue(!defaults.copy(musicDepthDb = 3f).isValid)
        assertTrue(!defaults.copy(rampMs = -1).isValid)
        assertEquals(2_000f, defaults.podcastCentreHz, 1f)
        assertEquals(1_997f, defaults.musicCentreHz, 2f)
    }
}
