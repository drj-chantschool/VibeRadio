package app.viberadio.playback

import kotlin.math.roundToLong

/** How a [PodcastGapRule] parameter is shown and edited. */
enum class GapUnit { Minutes, Tracks, Ratio, Seconds, Percent }

/** One adjustable figure of a [PodcastGapRule]: its key, unit and allowed range. */
data class GapParam(
    val key: String,
    val unit: GapUnit,
    val range: ClosedFloatingPointRange<Double>,
    /** Whole numbers only (track counts). */
    val whole: Boolean = false,
)

/** What a [PodcastGapRule] asks for after an episode ends. */
sealed interface Gap {
    /** Track boundaries to pass before the next episode may start (0 = the next one). */
    data class Tracks(val count: Int) : Gap

    /**
     * About [musicMs] of music, then the next episode: at a track boundary within ±[tolerance]
     * (a fraction) of it, otherwise part-way through a song at [musicMs]. 0 = straight away.
     */
    data class Music(val musicMs: Long, val tolerance: Double = 0.0) : Gap
}

/**
 * How long the music runs between podcast episodes on a song channel (#2). One of [all], each
 * with its own parameters, chosen in Settings → Podcasts (developer mode). Pure.
 */
sealed interface PodcastGapRule {
    /** Stable; persisted. */
    val id: String
    val params: List<GapParam>

    /** The value of parameter [key], in its [GapParam.unit]. */
    operator fun get(key: String): Double

    /** This rule with [key] set to [value]; not checked, see [isValid]. */
    fun with(key: String, value: Double): PodcastGapRule

    /** Rule-specific constraints beyond each parameter's range. */
    val consistent: Boolean get() = true

    val isValid: Boolean get() = consistent && params.all { accepts(it.key, get(it.key)) }

    /** Whether [value] is in range (and whole, if it must be) for parameter [key]. */
    fun accepts(key: String, value: Double): Boolean {
        val p = params.firstOrNull { it.key == key } ?: return false
        return value.isFinite() && value in p.range && (!p.whole || value == Math.rint(value))
    }

    /**
     * The gap after an episode with [contentMs] of content (0 if unknown) ends, [streakMs] being
     * the podcast time played back to back up to and including it.
     */
    fun gapAfter(contentMs: Long, streakMs: Long): Gap

    /**
     * The original rule: the next episode waits for the current track to end, plus
     * [longTracks] more full tracks after an episode over [longMinutes], [veryLongTracks] after
     * one over [veryLongMinutes].
     */
    data class TracksByLength(
        val longMinutes: Double = 15.0,
        val longTracks: Int = 1,
        val veryLongMinutes: Double = 30.0,
        val veryLongTracks: Int = 3,
    ) : PodcastGapRule {
        override val id: String get() = ID
        override val params: List<GapParam> get() = PARAMS

        override fun get(key: String): Double = when (key) {
            LONG_MINUTES -> longMinutes
            LONG_TRACKS -> longTracks.toDouble()
            VERY_LONG_MINUTES -> veryLongMinutes
            VERY_LONG_TRACKS -> veryLongTracks.toDouble()
            else -> throw IllegalArgumentException(key)
        }

        override fun with(key: String, value: Double): PodcastGapRule = when (key) {
            LONG_MINUTES -> copy(longMinutes = value)
            LONG_TRACKS -> copy(longTracks = value.toInt())
            VERY_LONG_MINUTES -> copy(veryLongMinutes = value)
            VERY_LONG_TRACKS -> copy(veryLongTracks = value.toInt())
            else -> throw IllegalArgumentException(key)
        }

        override val consistent: Boolean get() = longMinutes <= veryLongMinutes

        override fun gapAfter(contentMs: Long, streakMs: Long): Gap = Gap.Tracks(
            when {
                contentMs > minutesMs(veryLongMinutes) -> veryLongTracks
                contentMs > minutesMs(longMinutes) -> longTracks
                else -> 0
            },
        )

        companion object {
            const val ID = "tracks_by_length"
            const val LONG_MINUTES = "long_minutes"
            const val LONG_TRACKS = "long_tracks"
            const val VERY_LONG_MINUTES = "very_long_minutes"
            const val VERY_LONG_TRACKS = "very_long_tracks"
            val PARAMS = listOf(
                GapParam(LONG_MINUTES, GapUnit.Minutes, 0.0..600.0),
                GapParam(LONG_TRACKS, GapUnit.Tracks, 0.0..20.0, whole = true),
                GapParam(VERY_LONG_MINUTES, GapUnit.Minutes, 0.0..600.0),
                GapParam(VERY_LONG_TRACKS, GapUnit.Tracks, 0.0..20.0, whole = true),
            )

            private fun minutesMs(minutes: Double): Long = (minutes * 60_000).roundToLong()
        }
    }

    /**
     * Music time in proportion to podcast time: after [streakMs] of podcasts back to back, about
     * [ratio] × that of music. A wait under [floorSeconds] isn't worth it: the next episode
     * follows straight on, and its time adds to the streak. The next episode starts at a track
     * boundary within ±[tolerancePercent] of the wait, otherwise part-way through a song.
     */
    data class MusicRatio(
        val ratio: Double = 0.5,
        val floorSeconds: Double = 10.0,
        val tolerancePercent: Double = 10.0,
    ) : PodcastGapRule {
        override val id: String get() = ID
        override val params: List<GapParam> get() = PARAMS

        override fun get(key: String): Double = when (key) {
            RATIO -> ratio
            FLOOR_SECONDS -> floorSeconds
            TOLERANCE_PERCENT -> tolerancePercent
            else -> throw IllegalArgumentException(key)
        }

        override fun with(key: String, value: Double): PodcastGapRule = when (key) {
            RATIO -> copy(ratio = value)
            FLOOR_SECONDS -> copy(floorSeconds = value)
            TOLERANCE_PERCENT -> copy(tolerancePercent = value)
            else -> throw IllegalArgumentException(key)
        }

        override fun gapAfter(contentMs: Long, streakMs: Long): Gap {
            val musicMs = (ratio * streakMs).roundToLong()
            if (musicMs < (floorSeconds * 1000).roundToLong()) return Gap.Music(0)
            return Gap.Music(musicMs, tolerancePercent / 100)
        }

        companion object {
            const val ID = "music_ratio"
            const val RATIO = "ratio"
            const val FLOOR_SECONDS = "floor_seconds"
            const val TOLERANCE_PERCENT = "tolerance_percent"
            val PARAMS = listOf(
                GapParam(RATIO, GapUnit.Ratio, 0.0..1.0),
                GapParam(FLOOR_SECONDS, GapUnit.Seconds, 0.0..600.0),
                GapParam(TOLERANCE_PERCENT, GapUnit.Percent, 0.0..50.0),
            )
        }
    }

    companion object {
        val DEFAULT: PodcastGapRule = TracksByLength()

        /** Every rule, on its defaults, in the order the settings page lists them. */
        val all: List<PodcastGapRule> = listOf(TracksByLength(), MusicRatio())
    }
}

/**
 * Settings → Podcasts: the chosen [PodcastGapRule], and each rule's parameters (kept when
 * another is chosen, so switching back finds them as they were).
 */
data class PodcastGapSettings(
    val selected: String = PodcastGapRule.DEFAULT.id,
    val rules: List<PodcastGapRule> = PodcastGapRule.all,
) {
    val active: PodcastGapRule get() = rule(selected) ?: PodcastGapRule.DEFAULT

    fun rule(id: String): PodcastGapRule? = rules.firstOrNull { it.id == id }

    fun select(id: String): PodcastGapSettings = copy(selected = id)

    /** [id]'s parameter [key] set to [value], or null if the parameter doesn't take it. */
    fun set(id: String, key: String, value: Double): PodcastGapSettings? {
        if (rule(id)?.accepts(key, value) != true) return null
        return copy(rules = rules.map { if (it.id == id) it.with(key, value) else it })
    }

    /** The selected rule exists, every rule is listed once, and all are valid. */
    val isValid: Boolean
        get() = rule(selected) != null &&
            rules.map { it.id } == PodcastGapRule.all.map { it.id } &&
            rules.all { it.isValid }

    companion object {
        val DEFAULT = PodcastGapSettings()
    }
}
