package app.viberadio.podcasts

import java.security.MessageDigest

/**
 * A subscribed show (#18). Podcasts are the hand-curated side of the app: the user adds feeds
 * and picks channels; nothing here is learned.
 *
 * Kept free of Android types, like the song side, so queue policy runs in JVM tests. Nothing
 * in this package imports the song pipeline (#17): the only shared surface is a channel ID.
 */
data class Podcast(
    /** RSS feed URL; the show's identity. */
    val feedUrl: String,
    val title: String,
    /** Channel IDs this show plays on. New shows start on [PodcastChannels.DEFAULT]. */
    val channels: Set<String>,
    /** Wall-clock time of the last successful feed fetch, 0 if never. */
    val lastRefreshedMs: Long,
    /**
     * Playback speed for every episode of this show, on every channel. Pitch is preserved.
     * Always one of [PodcastSpeeds.STEPS]. Songs never change speed.
     */
    val speed: Float = PodcastSpeeds.DEFAULT,
)

/** One episode from a feed. Played state and resume position live on [PodcastLibrary], not here. */
data class Episode(
    /** Stable across refreshes; see [EpisodeIds]. */
    val id: String,
    val feedUrl: String,
    val title: String,
    /** Publish time in epoch millis, 0 when the feed gives none or an unreadable date. */
    val publishedMs: Long,
    /** Enclosure URL. */
    val audioUrl: String,
    val durationMs: Long?,
)

/** Podcast-only channels (#3 VOCAL_ONLY). Their IDs never collide with song channel IDs. */
object PodcastChannels {
    const val PODCASTS_ID = "podcasts"

    /** Where a newly added show goes, so it plays without any setup. */
    val DEFAULT: Set<String> = setOf(PODCASTS_ID)
}

/**
 * The speeds a show can be set to. A short fixed ladder rather than a slider: it's a setting
 * you choose once per show, not something to fiddle with mid-episode.
 */
object PodcastSpeeds {
    const val DEFAULT = 1.0f

    val STEPS: List<Float> = listOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2.0f)

    /** The step nearest [speed], so stored or hand-edited values always land on the ladder. */
    fun snap(speed: Float): Float =
        if (!speed.isFinite()) DEFAULT else STEPS.minBy { kotlin.math.abs(it - speed) }

    /** The next step up, staying on the top step once there. */
    fun faster(speed: Float): Float = STEPS.firstOrNull { it > snap(speed) } ?: STEPS.last()

    /** The next step down, staying on the bottom step once there. */
    fun slower(speed: Float): Float = STEPS.lastOrNull { it < snap(speed) } ?: STEPS.first()
}

object EpisodeIds {
    /**
     * GUIDs are only unique within a feed, so the ID hashes feed URL and GUID together.
     * Hex-only, so it is also safe as a file name.
     */
    fun of(feedUrl: String, guid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$feedUrl\n$guid".toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
