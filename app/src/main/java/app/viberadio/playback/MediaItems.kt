package app.viberadio.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.viberadio.channels.Channel
import app.viberadio.library.Track

/**
 * The UI never sends tracks to the player. It sends a single placeholder item whose media ID
 * names a channel; [RadioSessionCallback] expands it into the planned queue on the service side.
 */
object ChannelRequest {
    private const val PREFIX = "channel:"

    /** Extras key carrying the channel ID on every queued item, so controllers can follow channel changes (#4). */
    const val EXTRA_CHANNEL_ID = "app.viberadio.CHANNEL_ID"

    fun mediaItem(channelId: String): MediaItem =
        MediaItem.Builder().setMediaId(PREFIX + channelId).build()

    fun channelId(item: MediaItem): String? =
        item.mediaId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

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
                .setExtras(Bundle().apply { putString(ChannelRequest.EXTRA_CHANNEL_ID, channel.id) })
                .build(),
        )
        .build()
