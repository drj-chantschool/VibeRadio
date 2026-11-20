package app.viberadio.playback.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10

/**
 * Settings → Audio → Compression: a dynamic range compressor, off by default. Above
 * [thresholdDb] every [ratio] dB of input becomes 1 dB of output, with a [kneeDb]-wide soft knee
 * around the threshold; [attackMs] and [releaseMs] set how fast it clamps down and lets go, and
 * [makeupDb] lifts the result. One set of figures for both streams (each stream has its own
 * compressor state).
 */
data class CompressorSettings(
    val enabled: Boolean = false,
    val thresholdDb: Float = -24f,
    val ratio: Float = 3f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 200f,
    val kneeDb: Float = 6f,
    val makeupDb: Float = 0f,
) {
    val isValid: Boolean
        get() = thresholdDb in THRESHOLD_RANGE && ratio in RATIO_RANGE && attackMs in ATTACK_RANGE &&
            releaseMs in RELEASE_RANGE && kneeDb in KNEE_RANGE && makeupDb in MAKEUP_RANGE

    companion object {
        val DEFAULT = CompressorSettings()
        val THRESHOLD_RANGE = -60f..0f
        val RATIO_RANGE = 1f..20f
        val ATTACK_RANGE = 0.1f..200f
        val RELEASE_RANGE = 10f..2_000f
        val KNEE_RANGE = 0f..24f
        val MAKEUP_RANGE = 0f..24f
    }
}

/**
 * One stream's feed-forward compressor, pure Kotlin over float frames ([StreamCompressorProcessor]
 * feeds it PCM between the gain stage and the EQ). The detector is the loudest channel of the
 * frame, so the channels share one gain reduction and the stereo image holds. The gain
 * reduction is smoothed in dB (attack when it grows, release when it falls).
 *
 * Switching on or off never snaps: off, the reduction is released and the make-up fades out over
 * the ramp time; on, make-up fades in the same way. Once off and fully released it does nothing
 * ([bypassed]).
 */
class Compressor(private val sampleRate: Int, private val channels: Int) {

    /** The gain reduction applied now, in dB (zero or more). */
    var reductionDb: Float = 0f
        private set

    /** 0 (make-up off) to 1 (make-up fully in). */
    private var makeupBlend = 0f

    /** True while it has nothing to do: disabled, released and with no make-up left. */
    fun bypassed(enabled: Boolean): Boolean = !enabled && reductionDb <= 0f && makeupBlend <= 0f

    /** Compresses one interleaved frame in place. [rampMs] is how long the make-up takes to fade in or out. */
    fun processFrame(frame: FloatArray, s: CompressorSettings, rampMs: Long) {
        var peak = 0f
        for (ch in 0 until channels) peak = maxOf(peak, abs(frame[ch]))

        val wanted = if (s.enabled) reductionFor(levelDb(peak), s) else 0f
        val ms = if (wanted > reductionDb) s.attackMs else s.releaseMs
        reductionDb += (wanted - reductionDb) * coefficient(ms)
        if (reductionDb < 1e-4f && wanted == 0f) reductionDb = 0f

        val rampFrames = rampMs * sampleRate / 1000f
        val blendStep = if (rampFrames <= 0f) 1f else 1f / rampFrames
        makeupBlend = if (s.enabled) (makeupBlend + blendStep).coerceAtMost(1f) else (makeupBlend - blendStep).coerceAtLeast(0f)

        val db = s.makeupDb * makeupBlend - reductionDb
        if (db != 0f) {
            val linear = StreamGain.dbToLinear(db)
            for (ch in 0 until channels) frame[ch] *= linear
            // Make-up can push peaks up; the same memoryless limiter as after leveling bends them.
            if (s.makeupDb > 0f) for (ch in 0 until channels) frame[ch] = SoftLimiter.apply(frame[ch])
        }
    }

    private fun coefficient(ms: Float): Float = 1f - exp(-1.0 / (ms.coerceAtLeast(0.01f) * sampleRate / 1000.0)).toFloat()

    companion object {
        private const val FLOOR_DB = -120f

        fun levelDb(peak: Float): Float = if (peak <= 1e-6f) FLOOR_DB else 20f * log10(peak)

        /**
         * The gain reduction in dB the static curve asks for at input [levelDb]: nothing below the
         * knee, the full threshold-and-ratio reduction above it, a quadratic blend between.
         */
        fun reductionFor(levelDb: Float, s: CompressorSettings): Float {
            val over = levelDb - s.thresholdDb
            val slope = 1f - 1f / s.ratio
            return when {
                2f * over < -s.kneeDb -> 0f
                s.kneeDb > 0f && 2f * abs(over) <= s.kneeDb -> slope * (over + s.kneeDb / 2f).let { it * it } / (2f * s.kneeDb)
                else -> slope * over
            }
        }
    }
}
