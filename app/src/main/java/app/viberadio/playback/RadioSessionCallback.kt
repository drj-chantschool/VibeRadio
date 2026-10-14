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
import app.viberadio.feedback.ListeningClock
import app.viberadio.library.Track
import app.viberadio.podcasts.PodcastLibrary
import app.viberadio.podcasts.PodcastRepository
import app.viberadio.podcasts.PodcastSpeeds
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns channel requests into queues and enforces the radio rules on every controller,
 * including the system media notification and headset buttons: no skip, no seek, no
 * shuffle/repeat toggles. Play/pause and tuning remain.
 *
 * Song channels queue from [StreamPlanner] one song at a time: the current song plus
 * [LOOKAHEAD] more (so the player can preload), topped up each time a song starts. Podcast-only channels queue from the global
 * podcast queue (#19): an episode that plays to the end is marked played everywhere, and the
 * position of a part-played one is saved, so tuning back (or restarting) resumes it. When a
 * podcast-only channel has nothing left it hands over to a music channel at the episode
 * boundary (#4).
 *
 * Episodes play at their show's speed (pitch preserved), songs always at 1x. Speed is set
 * here on the player directly; controllers still can't change it.
 *
 * Every song that becomes the current item is reported to [onSongStarted] with its channel and
 * wall-clock start, which feeds the play history the shuffle weights by and [LiveRadio]'s record
 * of what each channel last aired. [listening] runs while a song is playing.
 *
 * Tuning in to a song channel whose last song would still be on air joins it part-way
 * ([LiveRadio.tuneIn]) with a short fade-in, like tuning in to a real station. That join is a
 * continuation, not a new play, so it isn't reported again.
 */
@OptIn(UnstableApi::class)
class RadioSessionCallback(
    private val repository: ChannelRepository,
    private val podcasts: PodcastRepository,
    private val planner: StreamPlanner,
    private val liveRadio: LiveRadio,
    /** Song listening time; runs only while a song plays. */
    private val listening: ListeningClock? = null,
    private val scope: CoroutineScope,
    /** The user's name for a named channel, or null for a generated one. */
    private val stationName: (channelId: String) -> String? = { null },
    private val onSongStarted: (channelId: String, track: Track, startedAtMs: Long) -> Unit = { _, _, _ -> },
    /** Each channel the player switches to, by tuning or by fallback (#4); remembered for Bluetooth resume. */
    private val onChannelTuned: (channelId: String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaSession.Callback, Player.Listener {

    private var player: Player? = null
    private var currentChannelId: String? = null

    /** The episode loaded in the player, or null while a song is playing. */
    private var currentEpisodeId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val positionTick = object : Runnable {
        override fun run() {
            savePosition()
            listening?.checkpoint()
            handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    /**
     * The media ID of a song being joined part-way by live-radio resume, until it becomes current.
     * It keeps its original start, so it isn't reported as a new play.
     */
    private var joiningMediaId: String? = null

    /** How far the fade-in has got, in ms of playback, or -1 when not fading. */
    private var fadeElapsedMs = -1L
    private val fadeTick = object : Runnable {
        override fun run() {
            val player = player ?: return
            if (player.isPlaying) fadeElapsedMs += FADE_STEP_MS
            if (fadeElapsedMs >= FADE_IN_MS) {
                stopFade()
                return
            }
            val t = fadeElapsedMs.toFloat() / FADE_IN_MS
            // Squared: closer to an even rise in loudness than a linear gain ramp.
            player.volume = t * t
            handler.postDelayed(this, FADE_STEP_MS)
        }
    }

    private data class Queue(
        val channelId: String,
        val items: List<MediaItem>,
        val startPositionMs: Long = C.TIME_UNSET,
        /** The first item is a song joined part-way (live-radio resume). */
        val joined: Boolean = false,
    )

    fun attach(player: Player) {
        this.player = player
        player.addListener(this)
        scope.launch {
            podcasts.library.collect {
                syncPodcastQueue(it)
                applySpeed()
            }
        }
    }

    /** Call before releasing the player. */
    fun detach() {
        savePosition()
        listening?.stop()
        handler.removeCallbacks(positionTick)
        stopFade()
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
            ?.let(::tuneIn)
            ?: return Futures.immediateFailedFuture(
                IllegalArgumentException("Expected a single channel request for a known channel"),
            )
        // Still on the old item here, so this keeps the place in an episode being tuned away from.
        savePosition()
        currentChannelId = queue.channelId
        onChannelTuned(queue.channelId)
        if (queue.joined) {
            joiningMediaId = queue.items.first().mediaId
            startFade()
        } else {
            joiningMediaId = null
            stopFade()
        }
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
        // Posted, not called inline: changing player state inside this callback gets folded into
        // the same session update as the transition, and controllers can miss the transition.
        handler.post(::applySpeed)
        handler.post(::topUpSongQueue)
        syncListening()
        val joined = mediaItem != null && mediaItem.mediaId == joiningMediaId
        joiningMediaId = null
        if (joined) return
        val channelId = mediaItem?.mediaMetadata?.extras?.getString(ChannelRequest.EXTRA_CHANNEL_ID) ?: return
        songTrack(channelId, mediaItem)?.let { onSongStarted(channelId, it, clock()) }
    }

    /** The library track behind a queued song item, or null for episodes and stale items. */
    private fun songTrack(channelId: String, item: MediaItem): Track? =
        repository.channel(channelId)?.tracks?.firstOrNull { it.mediaId == item.mediaId }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        syncListening()
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
        joiningMediaId = null
        stopFade()
        currentChannelId = next.channelId
        onChannelTuned(next.channelId)
        player.setMediaItems(next.items, 0, next.startPositionMs)
        player.prepare()
        player.play()
    }

    /** The queue for tuning in to [channelId]: song channels join their last song if it's still on air. */
    private fun tuneIn(channelId: String): Queue? {
        repository.channel(channelId)?.let { channel ->
            val tuneIn = liveRadio.tuneIn(channel, planner.upcoming(channel, 1 + LOOKAHEAD))
            return Queue(
                channelId = channel.id,
                items = tuneIn.tracks.map { it.toMediaItem(channel, stationName(channel.id) ?: channel.name) },
                startPositionMs = tuneIn.resumeAtMs ?: C.TIME_UNSET,
                joined = tuneIn.resumeAtMs != null,
            )
        }
        if (channelId !in podcasts.library.value.channelIds) return null
        return podcastQueue(channelId)
    }

    /** Fresh picks for [channel], from the top. */
    private fun songQueue(channel: Channel): Queue =
        Queue(channel.id, planner.upcoming(channel, 1 + LOOKAHEAD).map { songItem(channel, it) })

    private fun songItem(channel: Channel, track: Track): MediaItem = track.toMediaItem(channel, stationName(channel.id) ?: channel.name)

    /**
     * On a song channel, drops the songs already played from the player's list and picks more
     * so [LOOKAHEAD] songs are queued after the current one. Picks come from the channel as it
     * is now, so learning changes apply from the next pick.
     */
    private fun topUpSongQueue() {
        val player = player ?: return
        val channel = currentChannelId?.let(repository::channel) ?: return
        val index = player.currentMediaItemIndex
        if (index < 0 || player.mediaItemCount == 0) return
        if (index > 0) player.removeMediaItems(0, index)
        val queued = (0 until player.mediaItemCount).mapNotNull { songTrack(channel.id, player.getMediaItemAt(it)) }.toSet()
        val missing = 1 + LOOKAHEAD - player.mediaItemCount
        if (missing > 0) player.addMediaItems(planner.upcoming(channel, missing, avoid = queued).map { songItem(channel, it) })
    }

    /** The listening clock runs exactly while a song is playing. */
    private fun syncListening() {
        val listening = listening ?: return
        val player = player ?: return
        val songPlaying = player.isPlaying && currentEpisodeId == null && player.currentMediaItem != null
        if (songPlaying) listening.start() else listening.stop()
    }

    /**
     * Silences the player and raises it over [FADE_IN_MS] of actual playback. Started when the
     * queue is handed over, before the joined song loads, so it never starts at full volume.
     */
    private fun startFade() {
        handler.removeCallbacks(fadeTick)
        fadeElapsedMs = 0
        player?.volume = 0f
        handler.postDelayed(fadeTick, FADE_STEP_MS)
    }

    private fun stopFade() {
        handler.removeCallbacks(fadeTick)
        if (fadeElapsedMs >= 0) player?.volume = 1f
        fadeElapsedMs = -1
    }

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
            stationName = stationName(channelId) ?: channelId,
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

    /** The current episode's show speed, or 1x for songs. Also follows changes on the podcast page. */
    private fun applySpeed() {
        val player = player ?: return
        val speed = currentEpisodeId?.let(podcasts.library.value::speedOf) ?: PodcastSpeeds.DEFAULT
        // setPlaybackSpeed keeps pitch at 1, so voices stay natural.
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
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

        /** Songs queued after the current one, so the next is preloaded. */
        const val LOOKAHEAD = 1
        const val FADE_IN_MS = 1_500L
        const val FADE_STEP_MS = 50L

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
