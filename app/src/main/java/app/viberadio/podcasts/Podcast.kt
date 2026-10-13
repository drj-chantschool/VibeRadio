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
