package app.viberadio.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

        /** RBJ low shelf, slope 1: [gainDb] below [cornerHz] (the half-gain point), flat above. */
        fun lowShelf(cornerHz: Double, gainDb: Double, sampleRate: Int): BiquadCoefficients {
            val w = 2 * PI * clamp(cornerHz, sampleRate) / sampleRate
            val a = 10.0.pow(gainDb / 40)
            val c = cos(w)
            val beta = 2 * sqrt(a) * sin(w) / 2 * sqrt(2.0)
            return normalise(
                a * ((a + 1) - (a - 1) * c + beta),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - beta),
                (a + 1) + (a - 1) * c + beta,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - beta,
            )
        }

        /** RBJ high shelf, slope 1: [gainDb] above [cornerHz] (the half-gain point), flat below. */
        fun highShelf(cornerHz: Double, gainDb: Double, sampleRate: Int): BiquadCoefficients {
            val w = 2 * PI * clamp(cornerHz, sampleRate) / sampleRate
            val a = 10.0.pow(gainDb / 40)
            val c = cos(w)
            val beta = 2 * sqrt(a) * sin(w) / 2 * sqrt(2.0)
            return normalise(
                a * ((a + 1) + (a - 1) * c + beta),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - beta),
                (a + 1) - (a - 1) * c + beta,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - beta,
            )
        }

        private fun normalise(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }
}

/**
 * One stream's EQ after its gain: the tone EQ (a [GraphicEq] on [tone], always on), then the
 * separation EQ ([SeparationEq], #2) moving between flat and applied as the processor's target
 * says. A [SeparationEq.Graphic] module doesn't filter on its own: its offsets go onto the tone
 * bands. Pure Kotlin over float samples; [StreamEqProcessor] feeds it PCM.
 *
 * Nothing snaps, and nothing detours through flat: tone changes glide, new parameters for the
 * running separation module glide from where they are, and a different module ramps in while
 * the one it replaces ramps out over the same time (a crossfade). While the current one
 * contributes nothing, the swap happens straight away.
 */
class StreamEq(
    val stream: AudioStream,
    val sampleRate: Int,
    val channels: Int,
    separation: SeparationEq,
    tone: ToneCurve,
    rampMs: Long,
    /** Where the separation starts: 0 = flat, 1 = applied. */
    initialMix: Float,
) {
    /** The module running now (ramping in, if it just took over). */
    var separation: SeparationEq = separation
        private set

    private var stage: SeparationStage = separation.stage(stream, sampleRate, channels, rampMs, initialMix)

    /**
     * Modules switched away from, ramping out while [stage] ramps in; dropped once flat. At most
     * one per module: switching back to one revives its stage rather than starting another.
     */
    private val outgoing = ArrayList<Pair<SeparationEq, SeparationStage>>(SeparationEq.all.size)

    private var toneDb = tone.bandsDb.toFloatArray()
    private var rampMs = rampMs
    private val targets = FloatArray(ToneBands.COUNT)
    private val graphic = GraphicEq(sampleRate, channels, bandTargets())

    /** How far the separation is applied: 0 = flat, 1 = applied. */
    val mix: Float get() = stage.mix

    /** Band gains of the tone EQ right now, separation offsets included. */
    val bandGainsDb: FloatArray get() = graphic.gainsDb

    /** Takes the current settings; cheap when nothing changed, so it can run on every buffer. */
    fun configure(separation: SeparationEq, tone: ToneCurve, rampMs: Long) {
        if (rampMs != this.rampMs) {
            this.rampMs = rampMs
            stage.rampMs = rampMs
            for (o in outgoing) o.second.rampMs = rampMs
        }
        val bands = tone.bandsDb
        if (bands.size != toneDb.size || bands.indices.any { bands[it] != toneDb[it] }) toneDb = bands.toFloatArray()
        if (separation != this.separation) switchTo(separation)
    }

    /** Filters one interleaved frame in place, moving the separation one step towards [target] (0 flat, 1 applied). */
    fun processFrame(frame: FloatArray, target: Float) {
        if (graphic.readsTargets) bandTargets()
        graphic.processFrame(frame, targets)
        if (outgoing.isNotEmpty()) {
            for (o in outgoing) o.second.processFrame(frame, 0f)
            outgoing.removeAll { it.second.mix == 0f }
        }
        stage.processFrame(frame, target)
    }

    private fun switchTo(next: SeparationEq) {
        if (next.id == separation.id) {
            stage.retune(next)
        } else {
            val revived = outgoing.indexOfFirst { it.first.id == next.id }
            val nextStage = if (revived >= 0) {
                outgoing.removeAt(revived).second.also { it.retune(next) }
            } else {
                next.stage(stream, sampleRate, channels, rampMs, initialMix = 0f)
            }
            if (stage.mix > 0f) outgoing.add(separation to stage)
            stage = nextStage
        }
        separation = next
    }

    /** Tone curve plus the band offsets of every separation stage at its current mix. */
    private fun bandTargets(): FloatArray {
        for (b in 0 until ToneBands.COUNT) targets[b] = toneDb[b]
        addOffsets(stage)
        for (o in outgoing) addOffsets(o.second)
        return targets
    }

    private fun addOffsets(stage: SeparationStage) {
        val offsets = stage.bandOffsetsDb() ?: return
        for (b in 0 until ToneBands.COUNT) targets[b] += offsets[b]
    }
}
