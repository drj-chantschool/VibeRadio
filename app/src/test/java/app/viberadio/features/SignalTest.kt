package app.viberadio.features

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTest {

    private fun sine(hz: Double, rate: Int, seconds: Double, amplitude: Double = 0.5) =
        FloatArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    private fun zeroCrossings(x: FloatArray) = (1 until x.size).count { (x[it] >= 0f) != (x[it - 1] >= 0f) }

    // Excerpts

    @Test
    fun `a long song gets three excerpts centred at a quarter, half and three quarters`() {
        val excerpts = ExcerptPlan().excerpts(240_000)
        assertEquals(listOf(45_000L, 105_000L, 165_000L), excerpts.map { it.startMs })
        excerpts.forEach { assertEquals(30_000L, it.lengthMs) }
    }

    @Test
    fun `a short song is analysed whole`() {
        assertEquals(listOf(ExcerptPlan.Excerpt(0, 80_000)), ExcerptPlan().excerpts(80_000))
        assertEquals(emptyList<ExcerptPlan.Excerpt>(), ExcerptPlan().excerpts(0))
    }

    // Resampling

    @Test
    fun `resampling keeps a tone's pitch and level`() {
        val out = Resampler.resample(sine(440.0, 44_100, 1.0), 44_100, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(880.0, zeroCrossings(out).toDouble(), 4.0)
        assertEquals(0.5 / sqrt(2.0), rms(out, 100, out.size - 100), 0.01)
    }

    @Test
    fun `downsampling removes what's above the new Nyquist instead of folding it down`() {
        val out = Resampler.resample(sine(11_000.0, 44_100, 0.5), 44_100, 16_000)
        assertTrue(rms(out, 100, out.size - 100) < 0.01)
    }

    @Test
    fun `stereo downmix averages the channels`() {
        val mono = AudioClip.downmix(floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f), 6, 2)
        assertEquals(listOf(0.5f, 0.5f, 0f), mono.toList())
    }

    // Spectrum

    @Test
    fun `fft puts a tone in its bin`() {
        val fft = Fft(1024)
        val out = FloatArray(513)
        // 16 kHz / 1024 = 15.625 Hz per bin; bin 64 = 1 kHz.
        fft.spectrum(sine(1000.0, 16_000, 0.1), 0, Fft.hann(1024), out)
        assertEquals(64, out.indices.maxBy { out[it] })
    }

    @Test
    fun `mel scales round trip and agree on their anchors`() {
        assertEquals(15.0, MelFilterBank.hzToMel(1000.0, MelScale.SLANEY), 1e-9)
        assertEquals(1000.0, MelFilterBank.hzToMel(1000.0, MelScale.HTK), 0.1)
        for (scale in MelScale.entries) for (hz in listOf(0.0, 300.0, 1000.0, 4000.0, 8000.0)) {
            assertEquals(hz, MelFilterBank.melToHz(MelFilterBank.hzToMel(hz, scale), scale), 1e-6)
        }
    }

    @Test
    fun `mel spectrogram has one row per hop and its energy where the tone is`() {
        val spec = MelSpec(sampleRate = 16_000, nFft = 512, hop = 256, nMels = 40, log = LogCompression.NONE)
        val frames = MelSpectrogram.of(AudioClip(sine(1000.0, 16_000, 1.0), 16_000), spec)
        assertEquals((16_000 - 512) / 256 + 1, frames.size)
        val loudest = frames[10].indices.maxBy { frames[10][it] }
        val centre = MelFilterBank.melToHz(
            MelFilterBank.hzToMel(0.0, MelScale.SLANEY) + (MelFilterBank.hzToMel(8000.0, MelScale.SLANEY)) * (loudest + 1) / 41,
            MelScale.SLANEY,
        )
        assertTrue("band centre $centre Hz", abs(centre - 1000) < 150)
    }

    @Test
    fun `dct of a flat input is all in the first coefficient`() {
        val c = dct(FloatArray(40) { 2f }, 13)
        assertEquals(2f * sqrt(40f), c[0], 1e-4f)
        for (k in 1 until 13) assertEquals(0f, c[k], 1e-4f)
    }

    // Signal features

    private fun dsp(samples: FloatArray): Map<String, Float> {
        val value = SpectralFeatures().extract(listOf(AudioClip(samples, 16_000)), null)!!.value
        return SpectralFeatures.NAMES.zip(value.toList()).toMap()
    }

    @Test
    fun `signal features have one value per name`() {
        assertEquals(SpectralFeatures.NAMES.size, SpectralFeatures.NAMES.toSet().size)
        assertEquals(SpectralFeatures.NAMES.size, SpectralFeatures().extract(listOf(AudioClip(sine(440.0, 16_000, 3.0), 16_000)), null)!!.value.size)
    }

    @Test
    fun `noise is flatter and brighter than a low tone`() {
        val random = java.util.Random(1)
        val noise = dsp(FloatArray(48_000) { (random.nextGaussian() * 0.2).toFloat() })
        val tone = dsp(sine(220.0, 16_000, 3.0))
        assertTrue(noise.getValue("flatness") > 10 * tone.getValue("flatness"))
        assertTrue(noise.getValue("centroid") > tone.getValue("centroid"))
        assertTrue(noise.getValue("zcr") > tone.getValue("zcr"))
    }

    @Test
    fun `a tone's chroma lands on its pitch class`() {
        val a = dsp(sine(440.0, 16_000, 3.0))
        val chroma = SpectralFeatures.NAMES.filter { it.startsWith("chroma_") }
        assertEquals("chroma_A", chroma.maxBy { a.getValue(it) })
    }

    @Test
    fun `silence has no features`() {
        assertEquals(null, SpectralFeatures().extract(listOf(AudioClip(FloatArray(48_000), 16_000)), null))
    }

    @Test
    fun `tempo of a click track`() {
        val rate = 16_000
        val random = java.util.Random(2)
        val samples = FloatArray(rate * 20)
        // A 10 ms noise burst every half second: 120 BPM.
        for (beat in 0 until 40) {
            val start = beat * rate / 2
            for (i in 0 until 160) samples[start + i] = (random.nextGaussian() * 0.5).toFloat()
        }
        val bpm = dsp(samples).getValue("tempo") * 200
        assertEquals(120.0, bpm.toDouble(), 6.0)
    }
}
