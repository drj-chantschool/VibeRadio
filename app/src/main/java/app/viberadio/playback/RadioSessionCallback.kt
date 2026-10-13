package app.viberadio.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Turns channel requests into queues and enforces the radio rules on every controller,
 * including the system media notification and headset buttons: no skip, no seek, no
 * shuffle/repeat toggles. Play/pause and tuning remain.
 */
@OptIn(UnstableApi::class)
class RadioSessionCallback(
    private val repository: ChannelRepository,
    private val planner: StreamPlanner,
) : MediaSession.Callback, Player.Listener {

    private var player: Player? = null
    private var currentChannelId: String? = null

    fun attach(player: Player) {
        this.player = player
        player.addListener(this)
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(*FORBIDDEN_COMMANDS)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(commands)
            .build()
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val channel = mediaItems.singleOrNull()
            ?.let(ChannelRequest::channelId)
            ?.let(repository::channel)
            ?: return Futures.immediateFailedFuture(
                IllegalArgumentException("Expected a single channel request for a known channel"),
            )
        currentChannelId = channel.id
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(queueFor(channel), 0, C.TIME_UNSET),
        )
    }

    /** Arbitrary item additions would let a controller build its own playlist. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Tune a channel instead"))

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        val player = player ?: return
        val current = currentChannelId?.let(repository::channel) ?: return
        val all = (repository.state.value as? ChannelRepository.State.Ready)?.channels.orEmpty()
        val next = planner.onExhausted(current, all)
        currentChannelId = next.id
        player.setMediaItems(queueFor(next))
        player.prepare()
        player.play()
    }

    private fun queueFor(channel: Channel): List<MediaItem> =
        planner.plan(channel).map { it.toMediaItem(channel) }

    private companion object {
        val FORBIDDEN_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        )
    }
}
