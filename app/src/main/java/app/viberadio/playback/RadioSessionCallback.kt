package app.viberadio.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelRepository
import app.viberadio.dial.Dial
import app.viberadio.podcasts.PodcastLibrary
import app.viberadio.podcasts.PodcastRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns channel requests into queues and enforces the radio rules on every controller,
 * including the system media notification and headset buttons: no skip, no seek, no
 * shuffle/repeat toggles. Play/pause and tuning remain.
 *
 * Song channels queue from [StreamPlanner]. Podcast-only channels queue from the global
 * podcast queue (#19): an episode that plays to the end is marked played everywhere, and the
 * position of a part-played one is saved, so tuning back (or restarting) resumes it. When a
 * podcast-only channel has nothing left it hands over to a music channel at the episode
 * boundary (#4).
 */
@OptIn(UnstableApi::class)
class RadioSessionCallback(
    private val repository: ChannelRepository,
    private val podcasts: PodcastRepository,
    private val planner: StreamPlanner,
    private val scope: CoroutineScope,
    private val podcastsStationName: String,
) : MediaSession.Callback, Player.Listener {

    private var player: Player? = null
    private var currentChannelId: String? = null

    /** The episode loaded in the player, or null while a song is playing. */
    private var currentEpisodeId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val positionTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    private data class Queue(val channelId: String, val items: List<MediaItem>, val startPositionMs: Long = C.TIME_UNSET)

    fun attach(player: Player) {
        this.player = player
        player.addListener(this)
        scope.launch { podcasts.library.collect(::syncPodcastQueue) }
    }

    /** Call before releasing the player. */
    fun detach() {
        savePosition()
        handler.removeCallbacks(positionTick)
        player?.removeListener(this)
        player = null
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
        val queue = mediaItems.singleOrNull()
            ?.let(ChannelRequest::channelId)
            ?.let(::queueFor)
            ?: return Futures.immediateFailedFuture(
                IllegalArgumentException("Expected a single channel request for a known channel"),
            )
        // Still on the old item here, so this keeps the place in an episode being tuned away from.
        savePosition()
        currentChannelId = queue.channelId
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(queue.items, 0, queue.startPositionMs),
        )
    }

    /** Arbitrary item additions would let a controller build its own playlist. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFailedFuture(UnsupportedOperationException("Tune a channel instead"))

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val previous = currentEpisodeId
        if (previous != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) podcasts.setPlayed(previous, true)
        currentEpisodeId = EpisodeMediaId.episodeId(mediaItem)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(positionTick)
        if (isPlaying) handler.postDelayed(positionTick, POSITION_SAVE_INTERVAL_MS) else savePosition()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        val player = player ?: return
        // The last item in the queue doesn't get an AUTO transition; it just ends.
        currentEpisodeId?.let { podcasts.setPlayed(it, true) }
        val channelId = currentChannelId ?: return
        val songChannel = repository.channel(channelId)
        val next = if (songChannel != null) {
            songQueue(planner.onExhausted(songChannel, songChannels()))
        } else {
            podcastQueue(channelId)
        } ?: return
        currentChannelId = next.channelId
        player.setMediaItems(next.items, 0, next.startPositionMs)
        player.prepare()
        player.play()
    }

    private fun queueFor(channelId: String): Queue? {
        repository.channel(channelId)?.let { return songQueue(it) }
        if (channelId !in podcasts.library.value.channelIds) return null
        return podcastQueue(channelId)
    }

    private fun songQueue(channel: Channel): Queue =
        Queue(channel.id, planner.plan(channel).map { it.toMediaItem(channel) })

    /** The channel's episodes, resuming a started one; or the #4 fallback if there are none. */
    private fun podcastQueue(channelId: String): Queue? {
        val library = podcasts.library.value
        val episodes = library.queue(channelId)
        if (episodes.isEmpty()) return Dial.fallback(songChannels())?.let(::songQueue)
        return Queue(
            channelId = channelId,
            items = episodes.map { episodeItem(library, it.id, channelId) },
            startPositionMs = library.positions[episodes.first().id] ?: C.TIME_UNSET,
        )
    }

    private fun episodeItem(library: PodcastLibrary, episodeId: String, channelId: String): MediaItem {
        val episode = library.episode(episodeId)!!
        return episode.toMediaItem(
            uri = podcasts.playableUri(episode),
            showTitle = library.podcast(episode.feedUrl)?.title,
            stationId = channelId,
            stationName = podcastsStationName,
        )
    }

    /**
     * Keeps what's queued after the current episode in step with the podcast page: episodes
     * marked played drop out, new ones and un-marked ones join. The current episode is left
     * alone so it is never cut off.
     */
    private fun syncPodcastQueue(library: PodcastLibrary) {
        val player = player ?: return
        val channelId = currentChannelId ?: return
        if (repository.channel(channelId) != null || player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) return
        val index = player.currentMediaItemIndex
        val wanted = library.queue(channelId).filter { it.id != currentEpisodeId }
        val queued = (index + 1 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (wanted.map { EpisodeMediaId.of(it.id) } == queued) return
        player.removeMediaItems(index + 1, player.mediaItemCount)
        player.addMediaItems(wanted.map { episodeItem(library, it.id, channelId) })
    }

    private fun savePosition() {
        val player = player ?: return
        val id = currentEpisodeId ?: return
        if (EpisodeMediaId.episodeId(player.currentMediaItem) != id) return
        podcasts.savePosition(id, player.currentPosition)
    }

    private fun songChannels(): List<Channel> =
        (repository.state.value as? ChannelRepository.State.Ready)?.channels.orEmpty()

    private companion object {
        const val POSITION_SAVE_INTERVAL_MS = 15_000L

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
