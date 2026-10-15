package app.viberadio.playback.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** The ten bands of the tone EQ (and of [SeparationEq.Graphic]): octave centres, 31 Hz to 16 kHz. */
object ToneBands {
    val HZ = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0)
    const val COUNT = 10

    /** Stable band names, used in persisted keys. */
    val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /**
     * Band [i]'s filter at [gainDb]. The middle eight are one-octave peaking filters; the ends are
     * shelves whose half-gain corner sits on the edge shared with the next band in (√2 above
     * 31 Hz, √2 below 16 kHz), so the end bands reach down or up to the limit of hearing.
     */
    fun design(i: Int, gainDb: Double, sampleRate: Int): BiquadCoefficients = when (i) {
        0 -> BiquadCoefficients.lowShelf(HZ[0] * SQRT2, gainDb, sampleRate)
        COUNT - 1 -> BiquadCoefficients.highShelf(HZ[COUNT - 1] / SQRT2, gainDb, sampleRate)
        else -> BiquadCoefficients.peaking(HZ[i], gainDb, octaves = 1.0, sampleRate = sampleRate)
    }

    private val SQRT2 = sqrt(2.0)
}

/** One stream's tone EQ setting: dB per band, in [ToneBands.HZ] order. Flat by default. */
data class ToneCurve(val bandsDb: List<Float> = FLAT_BANDS) {

    val isFlat: Boolean get() = bandsDb.all { it == 0f }

    val isValid: Boolean get() = bandsDb.size == ToneBands.COUNT && bandsDb.all { it.isFinite() && it in RANGE }

    /** This curve with band [i] at [db]; not checked, see [isValid]. */
    fun with(i: Int, db: Float): ToneCurve = copy(bandsDb = bandsDb.mapIndexed { j, v -> if (j == i) db else v })

    /** The largest boost, which the automatic preamp takes back off; 0 when nothing is boosted. */
    val maxBoostDb: Float get() = bandsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f

    companion object {
        val RANGE = -12f..12f
        private val FLAT_BANDS = List(ToneBands.COUNT) { 0f }
        val FLAT = ToneCurve()
    }
}

/**
 * A ten-band graphic EQ over float frames, with an automatic preamp of −(largest boost) so a
 * boost never pushes the leveled signal (#1) into clipping. Band gains glide towards their
 * targets at [SLEW_DB_PER_S]; coefficients are recomputed every [BLOCK] frames while they move,
 * and the preamp is interpolated per frame, so a change has no zipper noise. A band at exactly
 * 0 dB is skipped. Pure; [StreamEq] drives it.
 */
class GraphicEq(
    private val sampleRate: Int,
    private val channels: Int,
    /** Where the bands start, in dB; no glide from flat at creation. */
    initialDb: FloatArray,
) {
    private val current = initialDb.copyOf()

    /** Band gains right now, in dB. */
    val gainsDb: FloatArray get() = current.copyOf()

    private val coefficients = Array(ToneBands.COUNT) { ToneBands.design(it, current[it].toDouble(), sampleRate) }

    // Transposed direct form II state, per band and channel.
    private val z1 = Array(ToneBands.COUNT) { DoubleArray(channels) }
    private val z2 = Array(ToneBands.COUNT) { DoubleArray(channels) }

    private var preamp = preampFor(current)
    private var preampStep = 0.0

    /** Frames until the next coefficient update; targets are read when it hits 0. */
    private var countdown = 0

    /** Whether the next [processFrame] reads its targets. Lets the caller skip filling them in between. */
    val readsTargets: Boolean get() = countdown == 0

    private val maxStepDb = (SLEW_DB_PER_S * BLOCK / sampleRate).toFloat()

    /** Filters one interleaved frame in place. [targetsDb] is read every [BLOCK] frames. */
    fun processFrame(frame: FloatArray, targetsDb: FloatArray) {
        if (countdown == 0) {
            update(targetsDb)
            countdown = BLOCK
        }
        countdown--
        preamp += preampStep
        val g = preamp
        for (ch in 0 until channels) {
            var x = frame[ch] * g
            for (b in 0 until ToneBands.COUNT) {
                if (current[b] == 0f) continue
                val k = coefficients[b]
                val y = k.b0 * x + z1[b][ch]
                z1[b][ch] = k.b1 * x - k.a1 * y + z2[b][ch]
                z2[b][ch] = k.b2 * x - k.a2 * y
                x = y
            }
            frame[ch] = x.toFloat()
        }
    }

    private fun update(targetsDb: FloatArray) {
        for (b in 0 until ToneBands.COUNT) {
            val target = targetsDb[b]
            val now = current[b]
            if (now == target) continue
            val delta = target - now
            val next = if (abs(delta) <= maxStepDb) target else now + maxStepDb * kotlin.math.sign(delta)
            if (now == 0f) {
                // Starting from the identity, the old state means nothing; start clean.
                z1[b].fill(0.0)
                z2[b].fill(0.0)
            }
            current[b] = next
            coefficients[b] = ToneBands.design(b, next.toDouble(), sampleRate)
        }
        preampStep = (preampFor(current) - preamp) / BLOCK
    }

    companion object {
        /** Frames between coefficient updates while a band moves. */
        const val BLOCK = 32

        /** How fast a band gain glides: a full 24 dB swing in half a second. */
        const val SLEW_DB_PER_S = 48.0

        private fun preampFor(gainsDb: FloatArray): Double {
            val boost = gainsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
            return 10.0.pow(-boost / 20.0)
        }
    }
}
