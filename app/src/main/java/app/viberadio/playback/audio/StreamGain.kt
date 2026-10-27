package app.viberadio.playback.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Memoryless peak limiter after the gain: samples below [KNEE] pass untouched; above it they are
 * bent smoothly (tanh) towards [CEILING] (−1 dBFS), which they never reach. It is what lets
 * leveling lift a quiet, dynamic track all the way to the target without clipping.
 */
object SoftLimiter {
    const val KNEE = 0.708f
    const val CEILING = 0.891f

    fun apply(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val room = CEILING - KNEE
        val y = KNEE + room * tanh((a - KNEE) / room)
        return if (x < 0f) -y else y
    }
}

/**
 * One stream's gain stage: the playing item's leveling gain (#1), plus, on the music stream, the
 * mixing factor while a podcast plays over it (#2). Pure Kotlin over float frames;
 * [StreamGainProcessor] feeds it PCM ahead of the EQ.
 *
 * The leveling gain is fixed per item and set at the item boundary, so a new song starts at its
 * own level with no ramp. If an item's level isn't known when it starts, it plays at 0 dB; should
 * the level arrive within [LATE_WINDOW_MS] of the start it is ramped in, otherwise the item
 * finishes at 0 dB and is leveled from its next play. Every other change (mixing factor on or
 * off, a new target in Settings) ramps over [rampMs].
 */
class StreamGain(private val sampleRate: Int, private val channels: Int) {

    /** The gain applied now, in dB. */
    var currentDb: Float = 0f
        private set

    private var linear = 1f
    private var targetDb = 0f
    private var stepDb = 0f

    /** Whether this item's leveling gain is in use (known at the start, or arrived in time). */
    private var leveled = false
    private var framesInItem = 0L

    private val lateWindowFrames = LATE_WINDOW_MS * sampleRate / 1000

    /**
     * A new item starts: snaps straight to its gain ([levelDb], null if not known yet) plus
     * [mixDb], with no ramp.
     */
    fun startItem(levelDb: Float?, mixDb: Float) {
        leveled = levelDb != null
        framesInItem = 0
        set((levelDb ?: 0f) + mixDb)
    }

    /**
     * Sets where the gain is heading for the next frames: the item's leveling gain ([levelDb],
     * null while unknown) plus [mixDb]. A change ramps over [rampMs].
     */
    fun aim(levelDb: Float?, mixDb: Float, rampMs: Long) {
        if (!leveled && levelDb != null && framesInItem < lateWindowFrames) leveled = true
        val target = (if (leveled && levelDb != null) levelDb else 0f) + mixDb
        if (target == targetDb) return
        targetDb = target
        val rampFrames = rampMs * sampleRate / 1000
        if (rampFrames <= 0) {
            set(target)
        } else {
            stepDb = abs(target - currentDb) / rampFrames
        }
    }

    /** Whether the current gain can push peaks past the knee, so the limiter has work to do. */
    private val limit get() = currentDb > 0f || targetDb > 0f

    /** Applies the gain to one interleaved frame in place, moving one step along any ramp. */
    fun processFrame(frame: FloatArray) {
        framesInItem++
        if (currentDb != targetDb) {
            currentDb = when {
                currentDb < targetDb -> (currentDb + stepDb).coerceAtMost(targetDb)
                else -> (currentDb - stepDb).coerceAtLeast(targetDb)
            }
            linear = dbToLinear(currentDb)
        }
        if (linear != 1f) for (ch in 0 until channels) frame[ch] *= linear
        if (limit) for (ch in 0 until channels) frame[ch] = SoftLimiter.apply(frame[ch])
    }

    private fun set(db: Float) {
        currentDb = db
        targetDb = db
        linear = dbToLinear(db)
    }

    companion object {
        /** How long after an item starts a late-arriving level is still ramped in. */
        const val LATE_WINDOW_MS = 15_000L

        fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
    }
}
