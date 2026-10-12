package app.viberadio.ui

import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Geometry for the channel dial. Angles are degrees clockwise from 12 o'clock.
 * The dial sweeps [SWEEP] degrees centred on the top, leaving a dead zone at the bottom
 * like a real rotary switch.
 */
object KnobMath {
    const val SWEEP = 270f
    const val START = -SWEEP / 2
    const val END = SWEEP / 2

    /** Angle of detent [index] when the dial has [count] positions. */
    fun detentAngle(index: Int, count: Int): Float {
        if (count <= 1) return 0f
        return START + SWEEP * index.coerceIn(0, count - 1) / (count - 1)
    }

    /** Nearest detent to [angle]; angles in the bottom dead zone snap to the nearer end. */
    fun nearestDetent(angle: Float, count: Int): Int {
        if (count <= 1) return 0
        val step = SWEEP / (count - 1)
        return ((angle.coerceIn(START, END) - START) / step).roundToInt().coerceIn(0, count - 1)
    }

    /**
     * Angle of point ([x], [y]) around centre ([cx], [cy]) in screen coordinates (y grows
     * downward), clamped to the sweep.
     */
    fun pointerAngle(x: Float, y: Float, cx: Float, cy: Float): Float {
        val degrees = Math.toDegrees(atan2((x - cx).toDouble(), (cy - y).toDouble())).toFloat()
        return degrees.coerceIn(START, END)
    }
}
