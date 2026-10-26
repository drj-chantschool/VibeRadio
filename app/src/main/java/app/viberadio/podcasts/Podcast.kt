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
    /** RSS feed URL, or a [LocalFeeds] URL for a folder on the device; the show's identity. */
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
    /**
     * "Pause music": the music stops while this show's episodes play, and the episode plays by
     * itself. Shows with it on are "music podcasts", though a user may turn it on for any reason.
     * Off: on a song channel, episodes play over the music.
     */
    val pauseMusic: Boolean = false,
    /**
     * Queue priority: lower plays first. Episodes are ordered by their show's priority, then by
     * the usual rule (started first, then oldest). Shows on the same priority interleave by date.
     * Always within [PodcastPriority.MIN]..[PodcastPriority.MAX].
     */
    val priority: Int = PodcastPriority.DEFAULT,
    /**
     * "Skip intro": an episode started from the top starts this many seconds in, on every
     * channel. A resume point wins over it. Always one of [PodcastSkipIntro.STEPS]; 0 is off.
     */
    val skipIntroSeconds: Int = PodcastSkipIntro.OFF,
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
    /** The feed's show notes as plain text ([RssParser.plainText]), null when it gives none. */
    val description: String? = null,
)

/**
 * Shows made from a folder on the device (Settings → Podcasts → Podcast folder) rather than an RSS
 * feed. Their feed URL is [SCHEME] plus the folder, so they never collide with a web feed. They
 * aren't fetched or downloaded (the files are already here) and their episodes follow the folder:
 * a file that's gone is no longer an episode. The song side builds their [Feed]; this package
 * only sees the result.
 */
object LocalFeeds {
    const val SCHEME = "folder:"

    fun url(folder: String): String = SCHEME + folder

    fun isLocal(feedUrl: String): Boolean = feedUrl.startsWith(SCHEME)
}

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

/** The range a show's [Podcast.priority] can be set to. Lower plays first. */
object PodcastPriority {
    const val DEFAULT = 0
    const val MIN = -99
    const val MAX = 99

    fun clamp(priority: Int): Int = priority.coerceIn(MIN, MAX)
}

/**
 * How far a show's [Podcast.skipIntroSeconds] can be set: a fixed ladder, like speed, since an
 * intro is the same length every episode and the setting is chosen once.
 */
object PodcastSkipIntro {
    const val OFF = 0

    val STEPS: List<Int> = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300)

    /** The step nearest [seconds], so stored or hand-edited values always land on the ladder. */
    fun snap(seconds: Int): Int = STEPS.minBy { kotlin.math.abs(it - seconds) }

    /** The next step up, staying on the top step once there. */
    fun longer(seconds: Int): Int = STEPS.firstOrNull { it > snap(seconds) } ?: STEPS.last()

    /** The next step down, staying on off once there. */
    fun shorter(seconds: Int): Int = STEPS.lastOrNull { it < snap(seconds) } ?: STEPS.first()
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
