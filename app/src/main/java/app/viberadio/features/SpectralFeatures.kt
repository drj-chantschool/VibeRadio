package app.viberadio.features

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hand-written signal features, no model needed: loudness and dynamics, spectral shape,
 * MFCCs (timbre), chroma (harmony) and tempo. Always available, cheap, and enough for coarse
 * similarity ("bright, busy, fast" vs "dark, sparse, slow") until learned embeddings are
 * installed. Block `dsp`.
 */
class SpectralFeatures : FeatureExtractor {

    override val block = FeatureBlock(id = "dsp", version = 1, names = NAMES)

    override val sampleRate = RATE

    override fun extract(clips: List<AudioClip>, upstream: List<FloatArray>?): Extraction? {
        val fft = Fft(N_FFT)
        val window = Fft.hann(N_FFT)
        val mel = MelFilterBank(MelSpec(sampleRate = RATE, nFft = N_FFT, hop = HOP, nMels = N_MELS, scale = MelScale.HTK, areaNormalize = false))
        val spectrum = FloatArray(N_FFT / 2 + 1)
        val bands = FloatArray(N_MELS)
        val binHz = RATE.toDouble() / N_FFT

        val frameDb = ArrayList<Double>()
        val centroid = Stats()
        val bandwidth = Stats()
        val rolloff = Stats()
        val flatness = Stats()
        val flux = Stats()
        val zcr = Stats()
        val mfcc = Array(N_MFCC) { Stats() }
        val chroma = DoubleArray(12)
        var chromaFrames = 0
        val tempos = ArrayList<Pair<Double, Double>>()

        for (clip in clips) {
            val x = clip.samples
            var previousLogMel: FloatArray? = null
            val onset = ArrayList<Double>()
            var offset = 0
            while (offset + N_FFT <= x.size) {
                // Level and zero crossings on the raw frame.
                var energy = 0.0
                var crossings = 0
                for (i in offset until offset + N_FFT) {
                    energy += x[i] * x[i]
                    if (i > offset && (x[i] >= 0f) != (x[i - 1] >= 0f)) crossings++
                }
                val db = 10 * log10(energy / N_FFT + 1e-12)

                fft.spectrum(x, offset, window, spectrum, power = 2)
                mel.apply(spectrum, bands)
                val logMel = FloatArray(N_MELS) { ln(bands[it] + 1e-10f) }
                val f = previousLogMel?.let { prev -> logMel.indices.sumOf { max(0.0, (logMel[it] - prev[it]).toDouble()) } } ?: 0.0
                previousLogMel = logMel
                onset += f
                offset += HOP

                if (db < SILENCE_DB) continue
                frameDb += db
                zcr.add(crossings.toDouble() / N_FFT)
                flux.add(f / N_MELS)

                var total = 0.0
                var weighted = 0.0
                var logSum = 0.0
                for (k in 1 until spectrum.size) {
                    total += spectrum[k]
                    weighted += k * binHz * spectrum[k]
                    logSum += ln(spectrum[k] + 1e-12)
                }
                if (total <= 0) continue
                val c = weighted / total
                centroid.add(c / NYQUIST)
                var spread = 0.0
                for (k in 1 until spectrum.size) spread += (k * binHz - c).let { it * it } * spectrum[k]
                bandwidth.add(sqrt(spread / total) / NYQUIST)
                var cumulative = 0.0
                var k85 = spectrum.size - 1
                for (k in 1 until spectrum.size) {
                    cumulative += spectrum[k]
                    if (cumulative >= 0.85 * total) {
                        k85 = k
                        break
                    }
                }
                rolloff.add(k85 * binHz / NYQUIST)
                val geometric = exp(logSum / (spectrum.size - 1))
                flatness.add(geometric / (total / (spectrum.size - 1)))

                val cepstrum = dct(logMel, N_MFCC)
                for (i in 0 until N_MFCC) mfcc[i].add(cepstrum[i].toDouble())

                // Chroma: energy of each bin between ~65 Hz and 5 kHz folded onto its pitch class.
                val frameChroma = DoubleArray(12)
                for (k in 1 until spectrum.size) {
                    val hz = k * binHz
                    if (hz < 65.0 || hz > 5000.0) continue
                    val pitch = 12 * log2(hz / 440.0) + 69
                    val pc = ((Math.round(pitch) % 12) + 12) % 12
                    frameChroma[pc.toInt()] += spectrum[k].toDouble()
                }
                val sum = frameChroma.sum()
                if (sum > 0) {
                    for (i in 0 until 12) chroma[i] += frameChroma[i] / sum
                    chromaFrames++
                }
            }
            Tempo.estimate(onset, RATE.toDouble() / HOP)?.let { tempos += it }
        }
        if (frameDb.isEmpty()) return null

        val sortedDb = frameDb.sorted()
        val meanDb = frameDb.average()
        val values = ArrayList<Double>(NAMES.size)
        values += (meanDb + 60) / 60
        values += Stats().also { s -> frameDb.forEach(s::add) }.std / 20
        values += (percentile(sortedDb, 0.95) - percentile(sortedDb, 0.10)) / 40
        values += frameDb.count { it < meanDb }.toDouble() / frameDb.size
        values += centroid.mean
        values += centroid.std
        values += bandwidth.mean
        values += rolloff.mean
        values += flatness.mean
        values += flux.mean
        values += zcr.mean
        values += zcr.std
        mfcc.forEach { values += it.mean / 10 }
        mfcc.forEach { values += it.std / 10 }
        for (i in 0 until 12) values += if (chromaFrames > 0) chroma[i] / chromaFrames else 0.0
        values += (tempos.map { it.first }.average().takeIf { tempos.isNotEmpty() } ?: 0.0) / 200
        values += tempos.map { it.second }.average().takeIf { tempos.isNotEmpty() } ?: 0.0
        check(values.size == NAMES.size)
        return Extraction(FloatArray(values.size) { values[it].toFloat() })
    }

    private class Stats {
        private var n = 0
        private var sum = 0.0
        private var sumSq = 0.0
        fun add(v: Double) {
            n++
            sum += v
            sumSq += v * v
        }
        val mean: Double get() = if (n == 0) 0.0 else sum / n
        val std: Double get() = if (n < 2) 0.0 else sqrt(max(0.0, sumSq / n - mean * mean))
    }

    companion object {
        const val RATE = 16_000
        private const val NYQUIST = RATE / 2.0
        private const val N_FFT = 2048
        private const val HOP = 512
        private const val N_MELS = 40
        private const val N_MFCC = 13
        private const val SILENCE_DB = -60.0

        val NAMES: List<String> = listOf(
            "level", "level_std", "dynamic_range", "low_energy_ratio",
            "centroid", "centroid_std", "bandwidth", "rolloff", "flatness", "flux", "zcr", "zcr_std",
        ) + (0 until N_MFCC).map { "mfcc$it" } + (0 until N_MFCC).map { "mfcc${it}_std" } +
            listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B").map { "chroma_$it" } +
            listOf("tempo", "pulse_clarity")

        private fun percentile(sorted: List<Double>, p: Double): Double = sorted[((sorted.size - 1) * p).toInt()]
    }
}

/** Tempo from an onset-strength envelope by autocorrelation, with a mild preference near 120 BPM. */
object Tempo {
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0

    /** (BPM, pulse clarity 0..1) or null if the envelope is too short or flat. */
    fun estimate(onset: List<Double>, framesPerSecond: Double): Pair<Double, Double>? {
        val maxLag = (60 * framesPerSecond / MIN_BPM).toInt() + 1
        if (onset.size < maxLag * 2) return null
        val mean = onset.average()
        val x = DoubleArray(onset.size) { onset[it] - mean }
        val zero = x.sumOf { it * it }
        if (zero <= 0) return null
        val minLag = (60 * framesPerSecond / MAX_BPM).toInt().coerceAtLeast(1)
        val acf = DoubleArray(maxLag + 2) { lag -> (0 until x.size - lag).sumOf { x[it] * x[it + lag] } / zero }
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val bpm = 60 * framesPerSecond / lag
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 0.9).let { it * it })
            val score = acf[lag] * prior
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        if (best < 1) return null
        // Parabolic interpolation around the peak for a finer lag.
        val a = acf[best - 1]
        val b = acf[best]
        val c = acf[best + 1]
        val denominator = a - 2 * b + c
        val shift = if (abs(denominator) > 1e-12) (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5) else 0.0
        val bpm = 60 * framesPerSecond / (best + shift)
        return bpm to b.coerceIn(0.0, 1.0)
    }
}
