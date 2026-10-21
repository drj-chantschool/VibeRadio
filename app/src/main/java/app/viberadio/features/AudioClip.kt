package app.viberadio.features

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Mono PCM, floats in −1..1, at [sampleRate]. One excerpt of a song. */
class AudioClip(val samples: FloatArray, val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    val durationMs: Long get() = samples.size * 1000L / sampleRate

    /** This clip at [rate], through [Resampler]; this clip itself if it's already there. */
    fun at(rate: Int): AudioClip = if (rate == sampleRate) this else AudioClip(Resampler.resample(samples, sampleRate, rate), rate)

    companion object {
        /** Interleaved [channels]-channel PCM averaged down to one channel. */
        fun downmix(interleaved: FloatArray, count: Int, channels: Int): FloatArray {
            if (channels == 1) return interleaved.copyOf(count)
            val frames = count / channels
            return FloatArray(frames) { f ->
                var sum = 0f
                for (c in 0 until channels) sum += interleaved[f * channels + c]
                sum / channels
            }
        }
    }
}

/**
 * Which parts of a song get analysed (Similarity plan, Phase 1): [count] excerpts of
 * [lengthMs], centred evenly through the song (25 %, 50 %, 75 % for three). A song shorter than
 * the excerpts together is analysed whole, as one excerpt.
 */
data class ExcerptPlan(val count: Int = 3, val lengthMs: Long = 30_000) {
    init {
        require(count >= 1 && lengthMs > 0)
    }

    /** One excerpt to decode: from [startMs], [lengthMs] long. */
    data class Excerpt(val startMs: Long, val lengthMs: Long)

    fun excerpts(durationMs: Long): List<Excerpt> {
        if (durationMs <= 0) return emptyList()
        if (durationMs <= count * lengthMs) return listOf(Excerpt(0, durationMs))
        return (1..count).map { i ->
            val centre = durationMs * i / (count + 1)
            val start = (centre - lengthMs / 2).coerceIn(0, durationMs - lengthMs)
            Excerpt(start, lengthMs)
        }
    }
}

/**
 * Band-limited sample-rate conversion: a Blackman-windowed sinc low-pass just under the lower of
 * the two Nyquist rates, so downsampling doesn't fold the top octave back into the spectrum.
 */
object Resampler {
    /** Sinc zero crossings each side of the centre tap. */
    private const val ZEROS = 16

    /** The cutoff as a fraction of the lower Nyquist rate, leaving room for the transition band. */
    private const val ROLLOFF = 0.9

    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        require(from > 0 && to > 0)
        if (from == to || input.isEmpty()) return input.copyOf()
        val ratio = to.toDouble() / from
        // Cutoff as a fraction of the input rate (0.5 = input Nyquist).
        val cutoff = 0.5 * ROLLOFF * min(1.0, ratio)
        val half = ZEROS / (2 * cutoff)
        val outSize = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val t = i / ratio
            val first = max(0, ceil(t - half).toInt())
            val last = min(input.size - 1, floor(t + half).toInt())
            var sum = 0.0
            var norm = 0.0
            for (j in first..last) {
                val x = t - j
                val w = sinc(2 * cutoff * x) * blackman(x / half)
                sum += input[j] * w
                norm += w
            }
            out[i] = if (norm != 0.0) (sum / norm).toFloat() else 0f
        }
        return out
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    /** Blackman window over −1..1. */
    private fun blackman(u: Double): Double {
        if (u <= -1 || u >= 1) return 0.0
        val p = PI * (u + 1)
        return 0.42 - 0.5 * cos(p) + 0.08 * cos(2 * p)
    }
}
