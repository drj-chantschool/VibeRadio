package app.viberadio.playback.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * How loud one song or episode is as a whole (#1): its integrated loudness and its sample peak.
 * Measured once per file ([LoudnessMeter]) and kept, so leveling is a fixed gain per item, never
 * compression.
 */
data class TrackLevel(
    /** ITU-R BS.1770 integrated loudness in LUFS; [Float.NEGATIVE_INFINITY] for silence. */
    val loudnessLufs: Float,
    /** Largest absolute sample, 1.0 = full scale. */
    val peak: Float,
) {
    /**
     * The fixed gain in dB that brings this item to [targetLufs], boost or cut, in full: a quiet
     * track is lifted to the target whatever its peak, and [SoftLimiter] in the gain stage keeps
     * the peaks the boost pushes up from clipping. Silence and unmeasurable items get 0 dB.
     */
    fun gainDb(targetLufs: Float): Float {
        if (!loudnessLufs.isFinite()) return 0f
        return (targetLufs - loudnessLufs).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
    }

    companion object {
        const val MIN_GAIN_DB = -30f
        const val MAX_GAIN_DB = 30f
    }
}

/**
 * Integrated loudness per ITU-R BS.1770-4 / EBU R 128: K-weighting, mean square over 400 ms
 * blocks with 75 % overlap, an absolute gate at −70 LUFS, then a relative gate 10 LU below the
 * absolutely gated loudness. Also tracks the sample peak.
 *
 * Pure Kotlin over interleaved float frames (1.0 = full scale); the decoder feeds it. The
 * sample rate and channel count are fixed per meter.
 */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate > 0 && channels > 0)
    }

    private val shelf = KWeighting.shelf(sampleRate)
    private val highPass = KWeighting.highPass(sampleRate)

    // Filter state per channel (transposed direct form II), two stages.
    private val s1z1 = DoubleArray(channels)
    private val s1z2 = DoubleArray(channels)
    private val s2z1 = DoubleArray(channels)
    private val s2z2 = DoubleArray(channels)

    /** BS.1770 channel weights: 1.0 for front channels, 1.41 for surrounds, LFE (index 3 of 5.1) left out. */
    private val weights = DoubleArray(channels) { ch ->
        when {
            channels >= 6 && ch == 3 -> 0.0
            channels >= 6 && ch >= 4 -> 1.41
            else -> 1.0
        }
    }

    /** Frames per 100 ms step; a 400 ms block is four steps. */
    private val stepFrames = max(1, sampleRate / 10)
    private var stepEnergy = 0.0
    private var stepCount = 0

    /** Weighted energy (sum over channels of weighted mean squares × step length) of the last three steps. */
    private val recent = DoubleArray(3)
    private var stepsSeen = 0

    /** Mean square of each complete 400 ms block. */
    private var blocks = DoubleArray(1024)
    private var blockCount = 0

    var peak: Float = 0f
        private set

    /** Feeds [frameCount] interleaved frames from [samples], starting at [offset]. */
    fun add(samples: FloatArray, offset: Int = 0, frameCount: Int = (samples.size - offset) / channels) {
        var i = offset
        repeat(frameCount) {
            var frameEnergy = 0.0
            for (ch in 0 until channels) {
                val raw = samples[i++]
                val a = abs(raw)
                if (a > peak) peak = a
                // Stage 1: high shelf.
                var x = raw.toDouble()
                var y = shelf.b0 * x + s1z1[ch]
                s1z1[ch] = shelf.b1 * x - shelf.a1 * y + s1z2[ch]
                s1z2[ch] = shelf.b2 * x - shelf.a2 * y
                // Stage 2: high pass.
                x = y
                y = highPass.b0 * x + s2z1[ch]
                s2z1[ch] = highPass.b1 * x - highPass.a1 * y + s2z2[ch]
                s2z2[ch] = highPass.b2 * x - highPass.a2 * y
                frameEnergy += weights[ch] * y * y
            }
            stepEnergy += frameEnergy
            if (++stepCount == stepFrames) endStep()
        }
    }

    private fun endStep() {
        val step = stepEnergy
        stepEnergy = 0.0
        stepCount = 0
        if (stepsSeen >= 3) {
            val blockEnergy = recent[0] + recent[1] + recent[2] + step
            addBlock(blockEnergy / (4.0 * stepFrames))
        }
        recent[0] = recent[1]
        recent[1] = recent[2]
        recent[2] = step
        stepsSeen++
    }

    private fun addBlock(meanSquare: Double) {
        if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
        blocks[blockCount++] = meanSquare
    }

    /** Integrated loudness of everything fed so far, in LUFS; −∞ if nothing passed the gates. */
    fun integratedLufs(): Float {
        val absoluteGate = energyOf(ABSOLUTE_GATE_LUFS)
        var sum = 0.0
        var n = 0
        for (b in 0 until blockCount) {
            val e = blocks[b]
            if (e > absoluteGate) {
                sum += e
                n++
            }
        }
        if (n == 0) return Float.NEGATIVE_INFINITY
        val relativeGate = sum / n * 10.0.pow(RELATIVE_GATE_LU / 10.0)
        sum = 0.0
        n = 0
        for (b in 0 until blockCount) {
            val e = blocks[b]
            if (e > absoluteGate && e > relativeGate) {
                sum += e
                n++
            }
        }
        if (n == 0) return Float.NEGATIVE_INFINITY
        return lufsOf(sum / n).toFloat()
    }

    fun result(): TrackLevel = TrackLevel(integratedLufs(), peak)

    companion object {
        const val ABSOLUTE_GATE_LUFS = -70.0
        const val RELATIVE_GATE_LU = -10.0

        fun lufsOf(meanSquare: Double): Double = -0.691 + 10 * log10(meanSquare)

        private fun energyOf(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)
    }
}

/**
 * The BS.1770 K-weighting pre-filter (a high shelf, then a high pass), designed for any sample
 * rate from the filter parameters, as libebur128 does. At 48 kHz it matches the coefficients in
 * the standard.
 */
object KWeighting {
    fun shelf(sampleRate: Int): BiquadCoefficients {
        val f0 = 1681.974450955533
        val gainDb = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gainDb / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return BiquadCoefficients(
            b0 = (vh + vb * k / q + k * k) / a0,
            b1 = 2.0 * (k * k - vh) / a0,
            b2 = (vh - vb * k / q + k * k) / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    fun highPass(sampleRate: Int): BiquadCoefficients {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k
        return BiquadCoefficients(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }
}
