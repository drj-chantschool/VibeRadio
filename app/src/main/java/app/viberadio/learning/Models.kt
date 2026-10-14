package app.viberadio.learning

import app.viberadio.dial.ChannelLineup
import app.viberadio.feedback.Thumb

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
}

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

/** Placeholder: every song equally preferred. */
object UniformPreference : PreferenceModel {
    override fun weight(preference: FloatArray) = 1.0
}

/** The models in use, plus the guarantees that hold whichever models they are. */
class LearningModels(
    private val channelFit: ChannelFitModel = ThumbRuleChannelFit,
    private val preference: PreferenceModel = UniformPreference,
    /** Lowest preference weight any song can have, so every song still plays eventually. */
    val preferenceFloor: Double = DEFAULT_PREFERENCE_FLOOR,
) {
    init {
        require(preferenceFloor > 0) { "preferenceFloor must be positive" }
    }

    /** The mood channels [vector]'s song belongs on. Never empty: a model that returns none (or only unknown IDs) gets all of them. */
    fun channelsFor(vector: FeatureVector): Set<String> =
        channelFit.channelsFor(vector.channelFit).filterTo(LinkedHashSet()) { it in ChannelLineup.moods }
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
