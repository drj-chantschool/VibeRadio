package app.viberadio.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/** The two streams that meet in the final mix. */
enum class AudioStream { MUSIC, PODCAST }

/**
 * Whether a podcast is playing over the music right now. Written by the playback service on
 * the main thread, read by the music stream's EQ on the playback thread.
 */
class MixState {
    @Volatile
    var podcastOverMusic: Boolean = false
}

/** Normalised biquad coefficients (a0 = 1). Pure; RBJ Audio EQ Cookbook designs. */
data class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** Gain in dB at [hz], for tests and sanity checks. */
    fun gainDb(hz: Double, sampleRate: Int): Double {
        val w = 2 * PI * hz / sampleRate
        val (c1, s1) = cos(w) to -sin(w)
        val (c2, s2) = cos(2 * w) to -sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = b1 * s1 + b2 * s2
        val dr = 1 + a1 * c1 + a2 * c2
        val di = a1 * s1 + a2 * s2
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * kotlin.math.log10(mag2)
    }

    companion object {
        private const val BUTTERWORTH_Q = 0.7071067811865476

        /** Keeps a corner frequency safely below Nyquist. */
        private fun clamp(hz: Double, sampleRate: Int) = hz.coerceIn(10.0, sampleRate * 0.45)

        fun lowPass(hz: Double, sampleRate: Int, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
            val w = 2 * PI * clamp(hz, sampleRate) / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalise((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(hz: Double, sampleRate: Int, q: Double = BUTTERWORTH_Q): BiquadCoefficients {
            val w = 2 * PI * clamp(hz, sampleRate) / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return normalise((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        /**
         * Peaking EQ at [centreHz] with [gainDb] and a bandwidth of [octaves], measured between
         * the half-gain (in dB) points, so the band edges are where the cut is half its depth.
         */
        fun peaking(centreHz: Double, gainDb: Double, octaves: Double, sampleRate: Int): BiquadCoefficients {
            val w = 2 * PI * clamp(centreHz, sampleRate) / sampleRate
            val a = 10.0.pow(gainDb / 40)
            val sw = sin(w)
            val alpha = sw * kotlin.math.sinh(ln(2.0) / 2 * octaves * w / sw)
            val c = cos(w)
            return normalise(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        private fun normalise(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/**
 * One stream's EQ (#2): flat, or filtered (the podcast's band pass, or the music's notch),
 * blended between the two so a change never snaps. The filters always run, so their state is
 * warm when the blend moves. Pure Kotlin over float samples; [StreamEqProcessor] feeds it PCM.
 */
class StreamEq(
    val stream: AudioStream,
    val sampleRate: Int,
    val channels: Int,
    val settings: AudioSettings,
    /** Where the blend starts: 0 = flat, 1 = filtered. */
    initialMix: Float,
) {
    val stages: List<BiquadCoefficients> = design(stream, settings, sampleRate)

    // Transposed direct form II state, per stage and channel.
    private val z1 = Array(stages.size) { DoubleArray(channels) }
    private val z2 = Array(stages.size) { DoubleArray(channels) }

    /** 0 = flat, 1 = filtered. */
    var mix: Float = initialMix.coerceIn(0f, 1f)
        private set

    /** Blend change per frame while ramping. */
    private val step: Float = if (settings.rampMs <= 0) 1f else (1000f / (settings.rampMs * sampleRate)).coerceAtMost(1f)

    /**
     * Filters one interleaved frame in place, moving the blend one step towards [target]
     * (0 flat, 1 filtered).
     */
    fun processFrame(frame: FloatArray, target: Float) {
        mix = when {
            mix < target -> (mix + step).coerceAtMost(target)
            mix > target -> (mix - step).coerceAtLeast(target)
            else -> mix
        }
        val m = mix
        for (ch in 0 until channels) {
            val dry = frame[ch].toDouble()
            var x = dry
            for (s in stages.indices) {
                val k = stages[s]
                val y = k.b0 * x + z1[s][ch]
                z1[s][ch] = k.b1 * x - k.a1 * y + z2[s][ch]
                z2[s][ch] = k.b2 * x - k.a2 * y
                x = y
            }
            frame[ch] = (dry + (x - dry) * m).toFloat()
        }
    }

    companion object {
        fun design(stream: AudioStream, settings: AudioSettings, sampleRate: Int): List<BiquadCoefficients> = when (stream) {
            // Band pass: high pass at the low edge, low pass at the high edge.
            AudioStream.PODCAST -> listOf(
                BiquadCoefficients.highPass(settings.podcastLowHz.toDouble(), sampleRate),
                BiquadCoefficients.lowPass(settings.podcastHighHz.toDouble(), sampleRate),
            )
            // A partial cut, not full removal: a wide peaking filter at negative gain.
            AudioStream.MUSIC -> listOf(
                BiquadCoefficients.peaking(
                    centreHz = settings.musicCentreHz.toDouble(),
                    gainDb = settings.musicDepthDb.toDouble(),
                    octaves = ln(settings.musicHighHz.toDouble() / settings.musicLowHz) / ln(2.0),
                    sampleRate = sampleRate,
                ),
            )
        }
    }
}
