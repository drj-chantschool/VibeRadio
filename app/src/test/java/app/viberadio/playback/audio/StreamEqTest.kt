package app.viberadio.playback.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SeparationEq.BandPassNotch]: the original band pass and notch, now one module of several. */
class StreamEqTest {

    private val rate = 48_000
    private val defaults = SeparationEq.BandPassNotch()

    private fun gain(stream: AudioStream, hz: Double, notch: SeparationEq.BandPassNotch = defaults): Double =
        notch.design(stream, rate).sumOf { it.gainDb(hz, rate) }

    private fun stage(rampMs: Long = 1_500, initialMix: Float, channels: Int = 1) =
        defaults.stage(AudioStream.MUSIC, rate, channels, rampMs, initialMix)

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
        assertEquals(-9.0, gain(AudioStream.MUSIC, defaults.musicCentreHz), 0.1)
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
        val deeper = defaults.copy(musicDepthDb = -18.0)
        assertEquals(-18.0, gain(AudioStream.MUSIC, deeper.musicCentreHz, deeper), 0.1)
    }

    @Test
    fun `flat blend passes samples through unchanged`() {
        val eq = stage(initialMix = 0f, channels = 2)
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
        val eq = stage(rampMs = 1_000, initialMix = 0f)
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
        val eq = stage(rampMs = 0, initialMix = 0f)
        eq.processFrame(FloatArray(1), target = 1f)
        assertEquals(1f, eq.mix, 0f)
    }

    @Test
    fun `filtered music at 2 kHz comes out about 9 dB down`() {
        val eq = stage(initialMix = 1f)
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
        assertTrue(!defaults.copy(podcastLowHz = 5_000.0).isValid)
        assertTrue(!defaults.copy(musicHighHz = 30_000.0).isValid)
        assertTrue(!defaults.copy(musicDepthDb = 3.0).isValid)
        assertEquals(2_000.0, defaults.podcastCentreHz, 1.0)
        assertEquals(1_997.0, defaults.musicCentreHz, 2.0)
    }
}

class ToneEqTest {

    private val rate = 48_000

    @Test
    fun `shelves boost their end and leave the middle`() {
        val low = BiquadCoefficients.lowShelf(44.0, 12.0, rate)
        assertEquals(12.0, low.gainDb(10.0, rate), 1.0)
        assertEquals(6.0, low.gainDb(44.0, rate), 0.5)
        assertEquals(0.0, low.gainDb(1_000.0, rate), 0.3)
        val high = BiquadCoefficients.highShelf(11_300.0, -12.0, rate)
        assertEquals(-12.0, high.gainDb(20_000.0, rate), 1.5)
        assertEquals(-6.0, high.gainDb(11_300.0, rate), 0.5)
        assertEquals(0.0, high.gainDb(1_000.0, rate), 0.3)
    }

    @Test
    fun `each band hits its gain at its centre`() {
        for (b in 1 until ToneBands.COUNT - 1) {
            assertEquals(6.0, ToneBands.design(b, 6.0, rate).gainDb(ToneBands.HZ[b], rate), 0.05)
        }
        assertTrue(ToneBands.design(0, 6.0, rate).gainDb(31.0, rate) > 4.5)
        assertTrue(ToneBands.design(ToneBands.COUNT - 1, 6.0, rate).gainDb(16_000.0, rate) > 4.5)
    }

    /** Peak of a [hz] sine through [eq] once settled; [targets] stay put. */
    private fun peak(eq: GraphicEq, hz: Double, targets: FloatArray, seconds: Double = 1.0): Float {
        val frame = FloatArray(1)
        var peak = 0f
        val frames = (rate * seconds).toInt()
        for (i in 0 until frames) {
            frame[0] = 0.5f * sin(2 * PI * hz * i / rate).toFloat()
            eq.processFrame(frame, targets)
            if (i > frames * 3 / 4) peak = maxOf(peak, abs(frame[0]))
        }
        return peak
    }

    @Test
    fun `flat tone passes samples through unchanged`() {
        val flat = FloatArray(ToneBands.COUNT)
        val eq = GraphicEq(rate, 1, flat)
        val frame = FloatArray(1)
        for (i in 0 until 1_000) {
            val x = sin(2 * PI * 440 * i / rate).toFloat()
            frame[0] = x
            eq.processFrame(frame, flat)
            assertEquals(x, frame[0], 0f)
        }
    }

    @Test
    fun `preamp takes the largest boost back off`() {
        // +6 dB at 1 kHz: the preamp is −6 dB, so 1 kHz comes out level and 125 Hz 6 dB down.
        val targets = FloatArray(ToneBands.COUNT).also { it[5] = 6f }
        val eq = GraphicEq(rate, 1, targets)
        assertEquals(0.5f, peak(eq, 1_000.0, targets), 0.02f)
        assertEquals(0.25f, peak(GraphicEq(rate, 1, targets), 125.0, targets), 0.02f)
    }

    @Test
    fun `a band change glides, never jumps`() {
        val flat = FloatArray(ToneBands.COUNT)
        val eq = GraphicEq(rate, 1, flat)
        val cut = FloatArray(ToneBands.COUNT).also { it[6] = -12f }
        val frame = FloatArray(1)
        // A quarter of a second: half way at 48 dB/s.
        repeat(rate / 4) { eq.processFrame(frame, cut) }
        assertEquals(-12f, eq.gainsDb[6], 0.1f)
        val eq2 = GraphicEq(rate, 1, flat)
        repeat(rate / 8) { eq2.processFrame(frame, cut) }
        assertEquals(-6f, eq2.gainsDb[6], 0.2f)
    }

    @Test
    fun `stream EQ runs the tone curve with no separation applied`() {
        val tone = ToneCurve.FLAT.with(5, -6f)
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, SeparationEq.BandPassNotch(), tone, 1_500, initialMix = 0f)
        assertEquals(-6f, eq.bandGainsDb[5], 0f)
        assertEquals(0f, eq.mix, 0f)
    }
}

class GraphicSeparationTest {

    private val rate = 48_000
    private val graphic = SeparationEq.Graphic()

    @Test
    fun `defaults roughly match the band pass and notch`() {
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, -4.0, -9.0, -9.0, -9.0, -4.0, 0.0), graphic.music)
        assertEquals(listOf(-12.0, -12.0, -12.0, -12.0, -6.0, 0.0, 0.0, 0.0, -6.0, -12.0), graphic.podcast)
        assertTrue(graphic.isValid)
    }

    @Test
    fun `parameters are named per stream and band, up to 24 dB`() {
        assertEquals(20, graphic.params.size)
        assertEquals(-9.0, graphic["music_1k"], 0.0)
        assertEquals(-12.0, graphic["podcast_16k"], 0.0)
        val boosted = graphic.with("podcast_2k", 24.0)
        assertEquals(24.0, boosted["podcast_2k"], 0.0)
        assertTrue(boosted.isValid)
        assertTrue(!graphic.accepts("music_1k", -25.0))
    }

    @Test
    fun `offsets are added to the tone curve as the separation ramps in`() {
        val tone = ToneCurve.FLAT.with(5, 3f)
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, graphic, tone, rampMs = 1_000, initialMix = 0f)
        val frame = FloatArray(1)
        repeat(rate / 2) { eq.processFrame(frame, target = 1f) }
        // Half way in: 3 + (−9 × 0.5) at 1 kHz.
        assertEquals(0.5f, eq.mix, 0.01f)
        assertEquals(-1.5f, eq.bandGainsDb[5], 0.3f)
        repeat(rate) { eq.processFrame(frame, target = 1f) }
        assertEquals(-6f, eq.bandGainsDb[5], 0.01f)
        assertEquals(-4f, eq.bandGainsDb[4], 0.01f)
        assertEquals(0f, eq.bandGainsDb[0], 0f)
    }

    @Test
    fun `one stream only means flat`() {
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, graphic, ToneCurve.FLAT, rampMs = 1_000, initialMix = 0f)
        val frame = FloatArray(1)
        repeat(rate) { eq.processFrame(frame, target = 0f) }
        assertTrue(eq.bandGainsDb.all { it == 0f })
    }

    @Test
    fun `switching module while applied ramps out first, then the new one ramps in`() {
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, SeparationEq.BandPassNotch(), ToneCurve.FLAT, rampMs = 1_000, initialMix = 1f)
        val frame = FloatArray(1)
        eq.configure(graphic, ToneCurve.FLAT, rampMs = 1_000)
        repeat(rate / 2) { eq.processFrame(frame, target = 1f) }
        // Still the old module, half way out.
        assertTrue(eq.separation is SeparationEq.BandPassNotch)
        assertEquals(0.5f, eq.mix, 0.01f)
        repeat(rate / 2 + 10) { eq.processFrame(frame, target = 1f) }
        assertTrue(eq.separation is SeparationEq.Graphic)
        assertTrue(eq.mix < 0.05f)
        repeat(rate) { eq.processFrame(frame, target = 1f) }
        assertEquals(1f, eq.mix, 0f)
    }

    @Test
    fun `switching module while flat happens at once`() {
        val eq = StreamEq(AudioStream.MUSIC, rate, 1, SeparationEq.BandPassNotch(), ToneCurve.FLAT, rampMs = 1_000, initialMix = 0f)
        eq.configure(graphic, ToneCurve.FLAT, rampMs = 1_000)
        assertTrue(eq.separation is SeparationEq.Graphic)
    }
}

class SeparationSettingsTest {

    @Test
    fun `defaults to the band pass and notch`() {
        assertEquals(SeparationEq.BandPassNotch.ID, SeparationSettings.DEFAULT.selected)
        assertTrue(SeparationSettings.DEFAULT.active is SeparationEq.BandPassNotch)
        assertTrue(SeparationSettings.DEFAULT.isValid)
    }

    @Test
    fun `each module keeps its own figures while another is selected`() {
        val s = SeparationSettings.DEFAULT
            .set(SeparationEq.BandPassNotch.ID, SeparationEq.BandPassNotch.MUSIC_DEPTH, -12.0)!!
            .select(SeparationEq.Graphic.ID)
            .set(SeparationEq.Graphic.ID, "music_2k", -15.0)!!
        assertTrue(s.active is SeparationEq.Graphic)
        assertEquals(-12.0, s.module(SeparationEq.BandPassNotch.ID)!![SeparationEq.BandPassNotch.MUSIC_DEPTH], 0.0)
        assertEquals(-15.0, s.active["music_2k"], 0.0)
    }

    @Test
    fun `out of range or inconsistent figures are refused`() {
        val s = SeparationSettings.DEFAULT
        assertEquals(null, s.set(SeparationEq.BandPassNotch.ID, SeparationEq.BandPassNotch.MUSIC_DEPTH, 3.0))
        assertEquals(null, s.set(SeparationEq.Graphic.ID, "music_1k", 30.0))
        assertEquals(null, s.set("gone", "x", 0.0))
        val crossed = s.set(SeparationEq.BandPassNotch.ID, SeparationEq.BandPassNotch.PODCAST_LOW, 5_000.0)!!
        assertTrue(!crossed.isValid)
        assertTrue(!SeparationSettings(selected = "gone").isValid)
    }

    private class FakeStore(var saved: SeparationSettings? = null) : SeparationStore {
        override fun read(): SeparationSettings? = saved
        override fun write(settings: SeparationSettings) {
            saved = settings
        }
    }

    private fun repository(store: SeparationStore) =
        SeparationSettingsRepository(store, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun `repository saves, refuses invalid changes and restores figures but not the choice`() {
        val store = FakeStore()
        val repo = repository(store)
        assertTrue(repo.select(SeparationEq.Graphic.ID))
        assertTrue(repo.set(SeparationEq.Graphic.ID, "podcast_31", -20.0))
        assertTrue(!repo.set(SeparationEq.BandPassNotch.ID, SeparationEq.BandPassNotch.PODCAST_LOW, 5_000.0))
        assertEquals(repo.settings.value, store.saved)
        assertEquals(repo.settings.value, repository(store).settings.value)
        assertTrue(repo.restoreDefaults())
        assertEquals(SeparationSettings.DEFAULT.select(SeparationEq.Graphic.ID), repo.settings.value)
    }

    @Test
    fun `invalid saved settings fall back to the defaults`() {
        assertEquals(SeparationSettings.DEFAULT, repository(FakeStore(SeparationSettings(selected = "gone"))).settings.value)
    }
}
