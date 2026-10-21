package app.viberadio.learning

import app.viberadio.dial.ChannelLineup
import app.viberadio.feedback.Thumb
import kotlin.math.exp

// Swappable learning models (#14). Both are pure functions of a song's feature vector: no
// storage, no clock, no Android. To try a new one, implement the interface and pass it to
// [LearningModels] in AppContainer. Nothing else references a concrete model.

/**
 * "Which channel(s) does this song belong on?"
 *
 * Gets the [FeatureLayout.CHANNEL_FIT] slice (index with [FeatureLayout.slot], slot = position
 * in [ChannelLineup.moods]) and returns mood channel IDs. Should return at least one;
 * [LearningModels] enforces the coverage rule either way.
 */
fun interface ChannelFitModel {
    fun channelsFor(fit: FloatArray): Set<String>

    /**
     * The same, with evidence borrowed from acoustically similar songs that have feedback
     * (Similarity plan, Phase 3). A model that doesn't use similarity ignores [neighbours].
     */
    fun channelsFor(fit: FloatArray, neighbours: List<NeighbourFit>): Set<String> = channelsFor(fit)
}

/** A similar song's [FeatureLayout.CHANNEL_FIT] slice and its distance (cosine, 0..2) in audio feature space. */
class NeighbourFit(val distance: Double, val fit: FloatArray)

/**
 * "How often does the user want to hear this song?"
 *
 * Gets the [FeatureLayout.PREFERENCE] slice and returns a relative weight: higher comes up more.
 * [LearningModels] floors it, so a model can't starve a song.
 */
fun interface PreferenceModel {
    fun weight(preference: FloatArray): Double
}

/**
 * A song is on a mood channel if it has any thumbs up there, or if that channel has its fewest
 * thumbs down (every tie included). The minimum always matches at least one channel, so every
 * song stays covered. A song with no feedback ties everywhere and lands on all five.
 */
object ThumbRuleChannelFit : ChannelFitModel {
    override fun channelsFor(fit: FloatArray): Set<String> {
        val moods = ChannelLineup.moods
        fun ups(slot: Int) = fit[FeatureLayout.slot(slot, Thumb.UP)]
        fun downs(slot: Int) = fit[FeatureLayout.slot(slot, Thumb.DOWN)]
        val fewestDowns = moods.indices.minOf(::downs)
        return moods.filterIndexedTo(LinkedHashSet()) { slot, _ -> ups(slot) > 0f || downs(slot) == fewestDowns }
    }
}

/**
 * Bootstraps sparse thumbs from similar songs (Similarity plan, Phase 3). For each mood channel,
 * neighbour j's thumbs count with weight `w = min(maxWeight, exp(−d²/τ²))` (capped so a
 * near-duplicate can't decide alone), the song's own thumbs with weight 1, and a thumbs down
 * at [downWeight] (a plain thumbs down is mostly a preference signal):
 *
 * ```
 * fit = ups + Σ w·ups_j      misfit = downWeight·(downs + Σ w·downs_j)
 * P(fit) = (α₀ + fit) / (α₀ + β₀ + fit + misfit), confidence = fit + misfit
 * ```
 *
 * The song is on the channel when P(fit) ≥ [threshold], but only once confidence reaches
 * [minConfidence]; below that, [base] decides that channel (with no feedback anywhere near, that's
 * today's rule exactly). A thumbs up on the channel itself always keeps the song there. Never
 * empty: falls back to [base].
 *
 * The defaults are a conservative guess until Phase 2 calibrates them on the exported log.
 */
class PropagatedChannelFit(
    private val base: ChannelFitModel = ThumbRuleChannelFit,
    private val tau: Double = 0.35,
    private val maxWeight: Double = 0.8,
    private val downWeight: Double = 0.5,
    private val alpha0: Double = 1.0,
    private val beta0: Double = 1.0,
    private val minConfidence: Double = 2.0,
    private val threshold: Double = 0.5,
) : ChannelFitModel {

    override fun channelsFor(fit: FloatArray): Set<String> = base.channelsFor(fit)

    override fun channelsFor(fit: FloatArray, neighbours: List<NeighbourFit>): Set<String> {
        val fallback = base.channelsFor(fit, neighbours)
        if (neighbours.isEmpty()) return fallback
        val weights = neighbours.map { weight(it.distance) }
        val moods = ChannelLineup.moods
        val placed = moods.filterIndexedTo(LinkedHashSet()) { slot, id ->
            val up = FeatureLayout.slot(slot, Thumb.UP)
            val down = FeatureLayout.slot(slot, Thumb.DOWN)
            if (fit[up] > 0f) return@filterIndexedTo true
            var ups = fit[up].toDouble()
            var downs = fit[down].toDouble()
            neighbours.forEachIndexed { j, n ->
                ups += weights[j] * n.fit[up]
                downs += weights[j] * n.fit[down]
            }
            val misfit = downWeight * downs
            val confidence = ups + misfit
            if (confidence < minConfidence) id in fallback else (alpha0 + ups) / (alpha0 + beta0 + confidence) >= threshold
        }
        return placed.ifEmpty { fallback }
    }

    /** Kernel weight of a neighbour at [distance]. */
    fun weight(distance: Double): Double = minOf(maxWeight, exp(-(distance * distance) / (tau * tau)))
}

/** Placeholder: every song equally preferred. */
object UniformPreference : PreferenceModel {
    override fun weight(preference: FloatArray) = 1.0
}

/** The models in use, plus the guarantees that hold whichever models they are. */
class LearningModels(
    private val channelFit: ChannelFitModel = PropagatedChannelFit(),
    private val preference: PreferenceModel = UniformPreference,
    /** Lowest preference weight any song can have, so every song still plays eventually. */
    val preferenceFloor: Double = DEFAULT_PREFERENCE_FLOOR,
) {
    init {
        require(preferenceFloor > 0) { "preferenceFloor must be positive" }
    }

    /**
     * The mood channels [vector]'s song belongs on, given its acoustically similar songs with
     * feedback. Never empty: a model that returns none (or only unknown IDs) gets all of them.
     */
    fun channelsFor(vector: FeatureVector, neighbours: List<NeighbourFit> = emptyList()): Set<String> =
        channelFit.channelsFor(vector.channelFit, neighbours).filterTo(LinkedHashSet()) { it in ChannelLineup.moods }
            .ifEmpty { ChannelLineup.moods.toSet() }

    /** [vector]'s song's preference weight, at least [preferenceFloor]. */
    fun preferenceWeight(vector: FeatureVector): Double {
        val w = preference.weight(vector.preference)
        return if (w.isNaN()) preferenceFloor else w.coerceAtLeast(preferenceFloor)
    }

    companion object {
        const val DEFAULT_PREFERENCE_FLOOR = 0.1
    }
}
