package app.viberadio.learning

import app.viberadio.dial.ChannelLineup
import app.viberadio.feedback.FeedbackEvent
import app.viberadio.feedback.Thumb

/**
 * Where each feature sits in a song's [FeatureVector] (#12).
 *
 * Layout v1: thumbs up and down totals per song-carrying channel, by stable channel ID, never
 * by display name:
 *
 * ```
 * [breeze↑, breeze↓, buzz↑, buzz↓, zone↑, zone↓, breath↑, breath↓, hall↑, hall↓, shuffle↑, shuffle↓]
 * ```
 *
 * One vector, two models reading different slices ([Models.kt][ChannelFitModel]):
 * - [CHANNEL_FIT], the ten mood-channel features: which channel(s) a song belongs on.
 * - [PREFERENCE], all twelve: how often the user wants to hear it.
 *
 * The Voice is podcast-only, so songs never get feedback there and it has no slot. Audio-derived
 * features come later: append them, bump [VERSION], and every vector is rebuilt from the log.
 */
object FeatureLayout {
    const val VERSION = 1

    /** Channels with an up/down pair, in vector order: the five moods, then The Shuffle. */
    val channels: List<String> = ChannelLineup.moods + ChannelLineup.SHUFFLE

    val SIZE: Int = channels.size * 2

    val CHANNEL_FIT: IntRange = 0 until ChannelLineup.moods.size * 2
    val PREFERENCE: IntRange = 0 until SIZE

    /** The vector index counting [thumb]s on [channelId], or null if that channel has no slot. */
    fun index(channelId: String, thumb: Thumb): Int? =
        channels.indexOf(channelId).takeIf { it >= 0 }?.let { slot(it, thumb) }

    /** Index of [thumb]'s count for the channel at [slot] in [channels]. */
    fun slot(slot: Int, thumb: Thumb): Int = slot * 2 + if (thumb == Thumb.UP) 0 else 1
}

/**
 * A song's features, keyed elsewhere on its content hash. Immutable. A derived cache: the
 * feedback log is the source of truth, and [FeatureVectors.from] rebuilds every vector from it.
 */
class FeatureVector private constructor(private val values: FloatArray) {

    operator fun get(index: Int): Float = values[index]

    /** The [FeatureLayout.CHANNEL_FIT] slice, a fresh copy. */
    val channelFit: FloatArray get() = values.sliceArray(FeatureLayout.CHANNEL_FIT)

    /** The [FeatureLayout.PREFERENCE] slice, a fresh copy. */
    val preference: FloatArray get() = values.sliceArray(FeatureLayout.PREFERENCE)

    /** This vector with [event] counted in. Events on channels without a slot change nothing. */
    operator fun plus(event: FeedbackEvent): FeatureVector {
        val i = FeatureLayout.index(event.channelId, event.thumb) ?: return this
        return FeatureVector(values.copyOf().also { it[i] += 1f })
    }

    fun toFloatArray(): FloatArray = values.copyOf()

    override fun equals(other: Any?) = other is FeatureVector && values.contentEquals(other.values)
    override fun hashCode() = values.contentHashCode()
    override fun toString() = values.joinToString(prefix = "FeatureVector[", postfix = "]")

    companion object {
        /** A song with no feedback yet. */
        val EMPTY = FeatureVector(FloatArray(FeatureLayout.SIZE))

        fun of(vararg values: Float): FeatureVector {
            require(values.size == FeatureLayout.SIZE) { "expected ${FeatureLayout.SIZE} features, got ${values.size}" }
            return FeatureVector(values.copyOf())
        }
    }
}

object FeatureVectors {
    /** Content hash → features, built from the whole feedback log. */
    fun from(events: List<FeedbackEvent>): Map<String, FeatureVector> =
        events.groupBy { it.trackHash }.mapValues { (_, songEvents) -> songEvents.fold(FeatureVector.EMPTY, FeatureVector::plus) }
}
