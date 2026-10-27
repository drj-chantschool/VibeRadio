package app.viberadio.playback.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTest {

    /** [seconds] of an interleaved sine at [hz] with peak [amplitude] in each listed channel, silence in the rest. */
    private fun sine(
        hz: Double,
        amplitude: Double,
        seconds: Double,
        rate: Int = 48_000,
        channels: Int = 2,
        on: Set<Int> = (0 until channels).toSet(),
    ): FloatArray {
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amplitude * sin(2 * PI * hz * f / rate)).toFloat()
            for (ch in 0 until channels) out[f * channels + ch] = if (ch in on) v else 0f
        }
        return out
    }

    private fun measure(samples: FloatArray, rate: Int = 48_000, channels: Int = 2): TrackLevel =
        LoudnessMeter(rate, channels).apply { add(samples) }.result()

    @Test
    fun `K-weighting matches the BS 1770 coefficients at 48 kHz`() {
        val shelf = KWeighting.shelf(48_000)
        assertEquals(1.53512485958697, shelf.b0, 1e-9)
        assertEquals(-2.69169618940638, shelf.b1, 1e-9)
        assertEquals(1.19839281085285, shelf.b2, 1e-9)
        assertEquals(-1.69065929318241, shelf.a1, 1e-9)
        assertEquals(0.73248077421585, shelf.a2, 1e-9)
        val hp = KWeighting.highPass(48_000)
        assertEquals(-1.99004745483398, hp.a1, 1e-9)
        assertEquals(0.99007225036621, hp.a2, 1e-9)
    }

    @Test
    fun `a full-scale 997 Hz sine in one channel reads -3_01 LUFS`() {
        val level = measure(sine(997.0, 1.0, 5.0, on = setOf(0)))
        assertEquals(-3.01f, level.loudnessLufs, 0.05f)
        assertEquals(1f, level.peak, 1e-3f)
    }

    @Test
    fun `a stereo sine at -20 dBFS reads about -20 LUFS, at 44_1 kHz too`() {
        assertEquals(-20f, measure(sine(997.0, 0.1, 5.0)).loudnessLufs, 0.1f)
        assertEquals(-20f, measure(sine(997.0, 0.1, 5.0, rate = 44_100), rate = 44_100).loudnessLufs, 0.1f)
    }

    @Test
    fun `mono is measured as one channel`() {
        val level = measure(sine(997.0, 1.0, 5.0, channels = 1), channels = 1)
        assertEquals(-3.01f, level.loudnessLufs, 0.05f)
    }

    @Test
    fun `silence and anything shorter than a block are unmeasurable and get 0 dB`() {
        val silent = measure(FloatArray(48_000 * 2 * 3))
        assertEquals(Float.NEGATIVE_INFINITY, silent.loudnessLufs)
        assertEquals(0f, silent.gainDb(-18f))
        val short = measure(sine(997.0, 0.5, 0.2))
        assertEquals(Float.NEGATIVE_INFINITY, short.loudnessLufs)
    }

    @Test
    fun `silence between passages doesn't lower the reading`() {
        val tone = sine(997.0, 0.1, 5.0)
        val withGap = tone + FloatArray(48_000 * 2 * 20) + tone
        // Within a fraction of a dB: the few blocks straddling an edge are part tone, part silence.
        assertEquals(-20f, measure(withGap).loudnessLufs, 0.2f)
    }

    @Test
    fun `quiet passages more than 10 LU down are gated out`() {
        val loud = sine(997.0, 0.1, 5.0) // -20 LUFS
        val quiet = sine(997.0, 0.01, 20.0) // -40 LUFS, four times as long
        assertEquals(-20f, measure(loud + quiet).loudnessLufs, 0.3f)
    }

    @Test
    fun `feeding in pieces gives the same result as all at once`() {
        val samples = sine(440.0, 0.3, 3.0) + sine(2_000.0, 0.05, 3.0)
        val whole = measure(samples)
        val meter = LoudnessMeter(48_000, 2)
        var frame = 0
        val frames = samples.size / 2
        while (frame < frames) {
            val n = minOf(1_237, frames - frame)
            meter.add(samples, frame * 2, n)
            frame += n
        }
        assertEquals(whole, meter.result())
    }

    @Test
    fun `a loud track is cut to the target`() {
        assertEquals(-10f, TrackLevel(loudnessLufs = -8f, peak = 1f).gainDb(-18f), 1e-4f)
    }

    @Test
    fun `a quiet track is boosted to the target whatever its peak`() {
        assertEquals(6f, TrackLevel(loudnessLufs = -24f, peak = 0.2512f).gainDb(-18f), 0.01f)
        // Peaks near full scale still get the whole boost; the limiter handles them.
        assertEquals(8f, TrackLevel(loudnessLufs = -26f, peak = 1f).gainDb(-18f), 1e-4f)
    }

    @Test
    fun `gain is bounded`() {
        assertTrue(TrackLevel(loudnessLufs = 10f, peak = 1f).gainDb(-30f) >= TrackLevel.MIN_GAIN_DB)
        assertTrue(TrackLevel(loudnessLufs = -69f, peak = 0.001f).gainDb(-8f) <= TrackLevel.MAX_GAIN_DB)
    }
}
