package app.viberadio.playback.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign

/** How a [SeparationEq] parameter is shown and edited. */
enum class EqUnit { Hz, Db }

/** One adjustable figure of a [SeparationEq]: its key, unit and allowed range. */
data class EqParam(
    val key: String,
    val unit: EqUnit,
    val range: ClosedFloatingPointRange<Double>,
)

/**
 * One stream's separation EQ as it runs, made by [SeparationEq.stage]. It owns its ramp: how it
 * moves between contributing nothing (mix 0) and being fully applied (mix 1) is the module's
 * business, not the processor's. Not thread-safe; lives on the playback thread.
 */
interface SeparationStage {
    /** 0 = contributes nothing, 1 = fully applied. */
    val mix: Float

    /** Ramp time for later moves of [mix]; a change never jumps the mix itself. */
    var rampMs: Long

    /**
     * Processes one interleaved frame in place, after the tone EQ, moving [mix] one frame
     * towards [target] (0 or 1).
     */
    fun processFrame(frame: FloatArray, target: Float)

    /**
     * dB to add to each tone band ([ToneBands.HZ] order) at the current [mix], for a module
     * that works on the tone EQ's bands rather than filtering on its own; null otherwise. The
     * array may be reused between calls.
     */
    fun bandOffsetsDb(): FloatArray? = null

    /**
     * Moves to [separation]'s parameters, which must be the module that made this stage. Takes
     * effect at the current [mix] and glides there, never snaps, so an edit while applied is
     * heard directly rather than through flat.
     */
    fun retune(separation: SeparationEq)
}

/**
 * How the music and a podcast played over it are pulled apart in frequency while both play
 * (#2). One of [all], each with its own parameters, chosen on Settings → Audio → Separation EQ
 * (developer mode). With one stream playing, every module is flat. Pure.
 *
 * A new module is a new subclass listed in [all], plus its labels in the UI's
 * `SeparationStrings`.
 */
sealed interface SeparationEq {
    /** Stable; persisted. */
    val id: String
    val params: List<EqParam>

    /** The value of parameter [key], in its [EqParam.unit]. */
    operator fun get(key: String): Double

    /** This module with [key] set to [value]; not checked, see [isValid]. */
    fun with(key: String, value: Double): SeparationEq

    /** Module-specific constraints beyond each parameter's range. */
    val consistent: Boolean get() = true

    val isValid: Boolean get() = consistent && params.all { accepts(it.key, get(it.key)) }

    /** Whether [value] is in range for parameter [key]. */
    fun accepts(key: String, value: Double): Boolean {
        val p = params.firstOrNull { it.key == key } ?: return false
        return value.isFinite() && value in p.range
    }

    /** A running instance for [stream], starting at [initialMix] (0 flat, 1 applied). */
    fun stage(stream: AudioStream, sampleRate: Int, channels: Int, rampMs: Long, initialMix: Float): SeparationStage

    /**
     * The original shape: the podcast through a band pass ([podcastLowHz]–[podcastHighHz]), the
     * music through a wider, partial notch (a peaking cut of [musicDepthDb] centred
     * geometrically on [musicLowHz]–[musicHighHz]). Ramps by crossfading dry and filtered
     * signal; the filters always run, so their state is warm when the blend moves.
     */
    data class BandPassNotch(
        val podcastLowHz: Double = 1_000.0,
        val podcastHighHz: Double = 4_000.0,
        val musicLowHz: Double = 700.0,
        val musicHighHz: Double = 5_700.0,
        /** How far the notch cuts the music at its centre, in dB (negative). */
        val musicDepthDb: Double = -9.0,
    ) : SeparationEq {
        override val id: String get() = ID
        override val params: List<EqParam> get() = PARAMS

        /** Geometric centre of the podcast band. */
        val podcastCentreHz: Double get() = kotlin.math.sqrt(podcastLowHz * podcastHighHz)

        val musicCentreHz: Double get() = kotlin.math.sqrt(musicLowHz * musicHighHz)

        override fun get(key: String): Double = when (key) {
            PODCAST_LOW -> podcastLowHz
            PODCAST_HIGH -> podcastHighHz
            MUSIC_LOW -> musicLowHz
            MUSIC_HIGH -> musicHighHz
            MUSIC_DEPTH -> musicDepthDb
            else -> throw IllegalArgumentException(key)
        }

        override fun with(key: String, value: Double): SeparationEq = when (key) {
            PODCAST_LOW -> copy(podcastLowHz = value)
            PODCAST_HIGH -> copy(podcastHighHz = value)
            MUSIC_LOW -> copy(musicLowHz = value)
            MUSIC_HIGH -> copy(musicHighHz = value)
            MUSIC_DEPTH -> copy(musicDepthDb = value)
            else -> throw IllegalArgumentException(key)
        }

        /** Each band's edges in order. */
        override val consistent: Boolean get() = podcastLowHz < podcastHighHz && musicLowHz < musicHighHz

        /** The filters for [stream], applied in order. */
        fun design(stream: AudioStream, sampleRate: Int): List<BiquadCoefficients> = when (stream) {
            // Band pass: high pass at the low edge, low pass at the high edge.
            AudioStream.PODCAST -> listOf(
                BiquadCoefficients.highPass(podcastLowHz, sampleRate),
                BiquadCoefficients.lowPass(podcastHighHz, sampleRate),
            )
            // A partial cut, not full removal: a wide peaking filter at negative gain.
            AudioStream.MUSIC -> listOf(
                BiquadCoefficients.peaking(
                    centreHz = musicCentreHz,
                    gainDb = musicDepthDb,
                    octaves = ln(musicHighHz / musicLowHz) / ln(2.0),
                    sampleRate = sampleRate,
                ),
            )
        }

        override fun stage(stream: AudioStream, sampleRate: Int, channels: Int, rampMs: Long, initialMix: Float): SeparationStage =
            DryWetStage(this, stream, sampleRate, channels, rampMs, initialMix)

        companion object {
            const val ID = "band_pass_notch"
            const val PODCAST_LOW = "podcast_low_hz"
            const val PODCAST_HIGH = "podcast_high_hz"
            const val MUSIC_LOW = "music_low_hz"
            const val MUSIC_HIGH = "music_high_hz"
            const val MUSIC_DEPTH = "music_depth_db"
            private val HZ = 20.0..20_000.0
            val PARAMS = listOf(
                EqParam(PODCAST_LOW, EqUnit.Hz, HZ),
                EqParam(PODCAST_HIGH, EqUnit.Hz, HZ),
                EqParam(MUSIC_LOW, EqUnit.Hz, HZ),
                EqParam(MUSIC_HIGH, EqUnit.Hz, HZ),
                EqParam(MUSIC_DEPTH, EqUnit.Db, -30.0..0.0),
            )
        }
    }

    /**
     * Separation on the tone EQ's ten bands: per-band dB offsets, one set per stream, added to
     * that stream's tone curve while both play. Final band gain = tone + offset × ramp, so this
     * module ramps by moving band gains, not by crossfading. A graphic EQ can't make a true band
     * pass, so the podcast gets a voice-forward tilt instead; offsets go to ±24 dB to get close.
     */
    data class Graphic(
        val music: List<Double> = MUSIC_DEFAULT,
        val podcast: List<Double> = PODCAST_DEFAULT,
    ) : SeparationEq {
        override val id: String get() = ID
        override val params: List<EqParam> get() = PARAMS

        fun offsets(stream: AudioStream): List<Double> = when (stream) {
            AudioStream.MUSIC -> music
            AudioStream.PODCAST -> podcast
        }

        override fun get(key: String): Double {
            val (stream, band) = parse(key)
            return offsets(stream).getOrElse(band) { Double.NaN }
        }

        override fun with(key: String, value: Double): SeparationEq {
            val (stream, band) = parse(key)
            return when (stream) {
                AudioStream.MUSIC -> copy(music = music.mapIndexed { i, v -> if (i == band) value else v })
                AudioStream.PODCAST -> copy(podcast = podcast.mapIndexed { i, v -> if (i == band) value else v })
            }
        }

        override val consistent: Boolean get() = music.size == ToneBands.COUNT && podcast.size == ToneBands.COUNT

        override fun stage(stream: AudioStream, sampleRate: Int, channels: Int, rampMs: Long, initialMix: Float): SeparationStage =
            GraphicStage(this, stream, sampleRate, rampMs, initialMix)

        companion object {
            const val ID = "graphic"
            val RANGE = -24.0..24.0

            /** −9 dB at 1–4 kHz, −4 at 500 Hz and 8 kHz: about the notch's shape. */
            val MUSIC_DEFAULT = listOf(0.0, 0.0, 0.0, 0.0, -4.0, -9.0, -9.0, -9.0, -4.0, 0.0)

            /** −12 dB below 250 Hz and at 16 kHz, −6 at 500 Hz and 8 kHz, 0 at 1–4 kHz: about the band pass. */
            val PODCAST_DEFAULT = listOf(-12.0, -12.0, -12.0, -12.0, -6.0, 0.0, 0.0, 0.0, -6.0, -12.0)

            /** `music_<band>` or `podcast_<band>`, band as in [ToneBands.LABELS]. */
            fun key(stream: AudioStream, band: Int): String = stream.name.lowercase() + "_" + ToneBands.LABELS[band]

            val PARAMS: List<EqParam> = AudioStream.entries.flatMap { stream ->
                (0 until ToneBands.COUNT).map { EqParam(key(stream, it), EqUnit.Db, RANGE) }
            }

            private val KEYS: Map<String, Pair<AudioStream, Int>> = AudioStream.entries.flatMap { stream ->
                (0 until ToneBands.COUNT).map { key(stream, it) to (stream to it) }
            }.toMap()

            /** The stream and band index [key] names. */
            fun parse(key: String): Pair<AudioStream, Int> = KEYS[key] ?: throw IllegalArgumentException(key)
        }
    }

    companion object {
        val DEFAULT: SeparationEq = BandPassNotch()

        /** Every module, on its defaults, in the order the settings page lists them. */
        val all: List<SeparationEq> = listOf(BandPassNotch(), Graphic())
    }
}

/** Moves a 0–1 mix one frame at a time towards a target, crossing the whole range in [rampMs]. */
internal class MixRamp(private val sampleRate: Int, rampMs: Long, initial: Float) {
    var value: Float = initial.coerceIn(0f, 1f)
        private set

    private var step = stepFor(rampMs)

    var rampMs: Long = rampMs
        set(ms) {
            field = ms
            step = stepFor(ms)
        }

    private fun stepFor(ms: Long): Float = if (ms <= 0) 1f else (1000f / (ms * sampleRate)).coerceAtMost(1f)

    fun advance(target: Float): Float {
        value = when {
            value < target -> (value + step).coerceAtMost(target)
            value > target -> (value - step).coerceAtLeast(target)
            else -> value
        }
        return value
    }
}

/**
 * [SeparationEq.BandPassNotch]'s running form: filters blended with the dry signal. A [retune]
 * glides the band edges at [GLIDE_OCTAVES_PER_S] and the depth at [GraphicEq.SLEW_DB_PER_S],
 * redesigning the filters every [GraphicEq.BLOCK] frames, the way the tone EQ moves its bands.
 */
internal class DryWetStage(
    notch: SeparationEq.BandPassNotch,
    private val stream: AudioStream,
    private val sampleRate: Int,
    private val channels: Int,
    rampMs: Long,
    initialMix: Float,
) : SeparationStage {
    private val ramp = MixRamp(sampleRate, rampMs, initialMix)

    /** The figures the filters are designed from now, and the ones they glide to. */
    private var current = notch
    private var goal = notch
    private var stages = notch.design(stream, sampleRate)
    private var countdown = 0
    private val maxStepOctaves = GLIDE_OCTAVES_PER_S * GraphicEq.BLOCK / sampleRate
    private val maxStepDb = GraphicEq.SLEW_DB_PER_S * GraphicEq.BLOCK / sampleRate

    // Transposed direct form II state, per stage and channel.
    private val z1 = Array(stages.size) { DoubleArray(channels) }
    private val z2 = Array(stages.size) { DoubleArray(channels) }

    override val mix: Float get() = ramp.value

    override var rampMs: Long
        get() = ramp.rampMs
        set(ms) {
            ramp.rampMs = ms
        }

    override fun retune(separation: SeparationEq) {
        goal = separation as SeparationEq.BandPassNotch
    }

    private fun glide() {
        val c = current
        val g = goal
        current = SeparationEq.BandPassNotch(
            podcastLowHz = stepHz(c.podcastLowHz, g.podcastLowHz),
            podcastHighHz = stepHz(c.podcastHighHz, g.podcastHighHz),
            musicLowHz = stepHz(c.musicLowHz, g.musicLowHz),
            musicHighHz = stepHz(c.musicHighHz, g.musicHighHz),
            musicDepthDb = stepDb(c.musicDepthDb, g.musicDepthDb),
        )
        // Same number of filters for a stream whatever the figures, so the state carries over.
        stages = current.design(stream, sampleRate)
    }

    private fun stepHz(from: Double, to: Double): Double {
        val octaves = ln(to / from) / ln(2.0)
        return if (abs(octaves) <= maxStepOctaves) to else from * 2.0.pow(maxStepOctaves * sign(octaves))
    }

    private fun stepDb(from: Double, to: Double): Double =
        if (abs(to - from) <= maxStepDb) to else from + maxStepDb * sign(to - from)

    override fun processFrame(frame: FloatArray, target: Float) {
        if (current != goal) {
            if (countdown == 0) {
                glide()
                countdown = GraphicEq.BLOCK
            }
            countdown--
        }
        val m = ramp.advance(target)
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
        /** How fast a band edge glides: the whole audible range in about a second. */
        const val GLIDE_OCTAVES_PER_S = 10.0
    }
}

/**
 * [SeparationEq.Graphic]'s running form: no filters of its own, just offsets for the tone bands,
 * scaled by the ramp. A [retune] swaps the offsets; the tone EQ glides its bands to them.
 */
internal class GraphicStage(
    graphic: SeparationEq.Graphic,
    private val stream: AudioStream,
    sampleRate: Int,
    rampMs: Long,
    initialMix: Float,
) : SeparationStage {
    private val ramp = MixRamp(sampleRate, rampMs, initialMix)
    private var offsetsDb = offsetsOf(graphic)
    private val scaled = FloatArray(offsetsDb.size)

    private fun offsetsOf(graphic: SeparationEq.Graphic) = graphic.offsets(stream).map { it.toFloat() }.toFloatArray()

    override fun retune(separation: SeparationEq) {
        offsetsDb = offsetsOf(separation as SeparationEq.Graphic)
    }

    override val mix: Float get() = ramp.value

    override var rampMs: Long
        get() = ramp.rampMs
        set(ms) {
            ramp.rampMs = ms
        }

    override fun processFrame(frame: FloatArray, target: Float) {
        ramp.advance(target)
    }

    override fun bandOffsetsDb(): FloatArray {
        val m = ramp.value
        for (i in scaled.indices) scaled[i] = offsetsDb[i] * m
        return scaled
    }
}

/**
 * Settings → Audio → Separation EQ: the chosen [SeparationEq], and each module's parameters
 * (kept when another is chosen, so switching back finds them as they were).
 */
data class SeparationSettings(
    val selected: String = SeparationEq.DEFAULT.id,
    val modules: List<SeparationEq> = SeparationEq.all,
) {
    val active: SeparationEq get() = module(selected) ?: SeparationEq.DEFAULT

    fun module(id: String): SeparationEq? = modules.firstOrNull { it.id == id }

    fun select(id: String): SeparationSettings = copy(selected = id)

    /** [id]'s parameter [key] set to [value], or null if the parameter doesn't take it. */
    fun set(id: String, key: String, value: Double): SeparationSettings? {
        if (module(id)?.accepts(key, value) != true) return null
        return copy(modules = modules.map { if (it.id == id) it.with(key, value) else it })
    }

    /** The selected module exists, every module is listed once, and all are valid. */
    val isValid: Boolean
        get() = module(selected) != null &&
            modules.map { it.id } == SeparationEq.all.map { it.id } &&
            modules.all { it.isValid }

    companion object {
        val DEFAULT = SeparationSettings()
    }
}
