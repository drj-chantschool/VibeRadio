package app.viberadio.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.viberadio.channels.Channel
import app.viberadio.library.Track
import app.viberadio.podcasts.Episode

/**
 * The UI never sends tracks to the player. It sends a single placeholder item whose media ID
 * names a channel; [RadioSessionCallback] expands it into the planned queue on the service side.
 */
object ChannelRequest {
    private const val PREFIX = "channel:"

    /** Extras key carrying the channel ID on every queued item, so controllers can follow channel changes (#4). */
    const val EXTRA_CHANNEL_ID = "app.viberadio.CHANNEL_ID"

    /**
     * Extras key carrying a song's content URI on queued song items (never on episodes), so the
     * song page can log thumbs feedback against the file (#13).
     */
    const val EXTRA_TRACK_URI = "app.viberadio.TRACK_URI"

    fun mediaItem(channelId: String): MediaItem =
        MediaItem.Builder().setMediaId(PREFIX + channelId).build()

    fun channelId(item: MediaItem): String? =
        item.mediaId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

/** Media IDs of podcast episodes, so the service can tell them apart from songs. */
internal object EpisodeMediaId {
    private const val PREFIX = "episode:"

    fun of(episodeId: String): String = PREFIX + episodeId

    fun episodeId(item: MediaItem?): String? =
        item?.mediaId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

internal fun Episode.toMediaItem(uri: String, showTitle: String?, stationId: String, stationName: String): MediaItem =
    MediaItem.Builder()
        .setMediaId(EpisodeMediaId.of(id))
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(showTitle)
                .setStation(stationName)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setExtras(Bundle().apply { putString(ChannelRequest.EXTRA_CHANNEL_ID, stationId) })
                .build(),
        )
        .build()

internal fun Track.toMediaItem(channel: Channel): MediaItem =
    MediaItem.Builder()
        .setMediaId("track:$id")
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setStation(channel.name)
                .setExtras(
                    Bundle().apply {
                        putString(ChannelRequest.EXTRA_CHANNEL_ID, channel.id)
                        putString(ChannelRequest.EXTRA_TRACK_URI, uri)
                    },
                )
                .build(),
        )
        .build()
