package app.viberadio.features

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place radix-2 complex FFT of size [n] (a power of two). Not thread-safe; one per worker. */
class Fft(val n: Int) {
    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
    }

    private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)

    /**
     * Power (|X|², or magnitude with [power] 1) of the first n/2 + 1 bins of the real signal
     * [frame] × [window], written to [out].
     */
    fun spectrum(frame: FloatArray, offset: Int, window: FloatArray, out: FloatArray, power: Int = 2) {
        for (i in 0 until n) {
            val j = offset + i
            re[i] = if (j in frame.indices) (frame[j] * window[i]).toDouble() else 0.0
            im[i] = 0.0
        }
        transform()
        for (k in 0..n / 2) {
            val p = re[k] * re[k] + im[k] * im[k]
            out[k] = (if (power == 2) p else sqrt(p)).toFloat()
        }
    }

    private fun transform() {
        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (start in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            len = len shl 1
        }
    }

    companion object {
        /** Periodic Hann window of [n] points. */
        fun hann(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    }
}

/** How a mel spectrogram is computed. Models document theirs; match it or the embedding drifts. */
data class MelSpec(
    val sampleRate: Int = 16_000,
    val nFft: Int = 512,
    val hop: Int = 256,
    val nMels: Int = 96,
    val fMin: Double = 0.0,
    /** Upper edge; null = Nyquist. */
    val fMax: Double? = null,
    val scale: MelScale = MelScale.SLANEY,
    /** Slaney area normalisation of each filter (librosa's `norm="slaney"`). */
    val areaNormalize: Boolean = true,
    /** 2 = power spectrum, 1 = magnitude. */
    val power: Int = 2,
    val log: LogCompression = LogCompression.LOG10_1P_10000,
)

enum class MelScale { HTK, SLANEY }

enum class LogCompression {
    NONE,

    /** Natural log of (x + 1e-6). */
    LN,

    /** 10·log10(x + 1e-10), floored 80 dB below the clip's peak. */
    DB,

    /** log10(1 + 10000·x): Essentia's `TensorflowInputMusiCNN` and its successors. */
    LOG10_1P_10000,
}

/** Triangular mel filters over an n/2 + 1 bin spectrum. */
class MelFilterBank(val spec: MelSpec) {
    private val bins = spec.nFft / 2 + 1
    private val starts = IntArray(spec.nMels)
    private val weights: Array<FloatArray>

    init {
        val fMax = spec.fMax ?: (spec.sampleRate / 2.0)
        require(spec.fMin >= 0 && fMax > spec.fMin && fMax <= spec.sampleRate / 2.0) { "mel range ${spec.fMin}–$fMax Hz doesn't fit ${spec.sampleRate} Hz" }
        val lo = hzToMel(spec.fMin, spec.scale)
        val hi = hzToMel(fMax, spec.scale)
        val edges = DoubleArray(spec.nMels + 2) { melToHz(lo + (hi - lo) * it / (spec.nMels + 1), spec.scale) }
        val binHz = spec.sampleRate.toDouble() / spec.nFft
        weights = Array(spec.nMels) { m ->
            val left = edges[m]
            val centre = edges[m + 1]
            val right = edges[m + 2]
            val norm = if (spec.areaNormalize) 2.0 / (right - left) else 1.0
            val first = max(0, (left / binHz).toInt())
            val last = min(bins - 1, (right / binHz).toInt() + 1)
            starts[m] = first
            FloatArray(last - first + 1) { i ->
                val f = (first + i) * binHz
                val w = when {
                    f <= left || f >= right -> 0.0
                    f <= centre -> (f - left) / (centre - left)
                    else -> (right - f) / (right - centre)
                }
                (w * norm).toFloat()
            }
        }
    }

    /** Mel band energies of [spectrum] (n/2 + 1 bins) into [out] (nMels). */
    fun apply(spectrum: FloatArray, out: FloatArray) {
        for (m in 0 until spec.nMels) {
            val w = weights[m]
            val s = starts[m]
            var sum = 0f
            for (i in w.indices) sum += w[i] * spectrum[s + i]
            out[m] = sum
        }
    }

    companion object {
        fun hzToMel(hz: Double, scale: MelScale): Double = when (scale) {
            MelScale.HTK -> 2595.0 * log10(1.0 + hz / 700.0)
            MelScale.SLANEY -> if (hz < 1000.0) hz / (200.0 / 3) else 15.0 + ln(hz / 1000.0) / (ln(6.4) / 27.0)
        }

        fun melToHz(mel: Double, scale: MelScale): Double = when (scale) {
            MelScale.HTK -> 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
            MelScale.SLANEY -> if (mel < 15.0) mel * (200.0 / 3) else 1000.0 * exp((mel - 15.0) * (ln(6.4) / 27.0))
        }
    }
}

/** A log-mel spectrogram of a clip, one row per frame. */
object MelSpectrogram {
    fun of(clip: AudioClip, spec: MelSpec): List<FloatArray> {
        require(clip.sampleRate == spec.sampleRate) { "clip at ${clip.sampleRate} Hz, spec wants ${spec.sampleRate}" }
        val fft = Fft(spec.nFft)
        val window = Fft.hann(spec.nFft)
        val bank = MelFilterBank(spec)
        val spectrum = FloatArray(spec.nFft / 2 + 1)
        val frames = ArrayList<FloatArray>()
        var offset = 0
        while (offset + spec.nFft <= clip.samples.size) {
            fft.spectrum(clip.samples, offset, window, spectrum, spec.power)
            frames += FloatArray(spec.nMels).also { bank.apply(spectrum, it) }
            offset += spec.hop
        }
        compress(frames, spec.log)
        return frames
    }

    fun compress(frames: List<FloatArray>, log: LogCompression) {
        when (log) {
            LogCompression.NONE -> Unit
            LogCompression.LN -> frames.forEach { row -> for (i in row.indices) row[i] = ln(row[i] + 1e-6f) }
            LogCompression.LOG10_1P_10000 -> frames.forEach { row -> for (i in row.indices) row[i] = log10(1f + 10_000f * row[i]) }
            LogCompression.DB -> {
                frames.forEach { row -> for (i in row.indices) row[i] = 10f * log10(row[i] + 1e-10f) }
                val floor = (frames.maxOfOrNull { it.maxOrNull() ?: Float.NEGATIVE_INFINITY } ?: return) - 80f
                frames.forEach { row -> for (i in row.indices) row[i] = max(row[i], floor) }
            }
        }
    }
}

/** Orthonormal DCT-II, first [count] coefficients of [input]: cepstra from log mel bands. */
fun dct(input: FloatArray, count: Int): FloatArray {
    val n = input.size
    return FloatArray(count) { k ->
        var sum = 0.0
        for (i in 0 until n) sum += input[i] * cos(PI / n * (i + 0.5) * k)
        (sum * sqrt((if (k == 0) 1.0 else 2.0) / n)).toFloat()
    }
}
