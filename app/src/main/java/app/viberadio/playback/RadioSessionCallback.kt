package app.viberadio.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.viberadio.channels.Channel
import app.viberadio.channels.ChannelRepository
import app.viberadio.dial.ChannelNames
import app.viberadio.dial.Dial
import app.viberadio.feedback.ListeningClock
import app.viberadio.library.Track
import app.viberadio.playback.audio.MixState
import app.viberadio.podcasts.Episode
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
 * shuffle/repeat toggles. Play/pause and tuning remain, including [ChannelStep]: the
 * notification's channel down/up buttons, which tune the neighbouring station on the dial.
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
 *
 * Shows assigned to a song channel (#2) play on it following [PodcastSchedule], with the gap
 * between episodes set by [gapRule]: at track boundaries, or (if the rule allows) part-way
 * through a song once the music has run long enough. A music podcast ("Pause music" on) takes
 * the next song's place in this player's queue and plays by itself, so it always waits for a
 * track boundary; the music resumes after it. Any other show plays over the music on the
 * overlay player ([PodcastOverlay]), which follows this player's play/pause. Tuning in fresh
 * counts as a boundary; joining a song part-way doesn't, unless a podcast was playing when the
 * knob moved (the podcast carries on straight away). The schedule's waits are wall-clock;
 * podcast time for the gap is measured on [onAir], which runs only while this player plays.
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
    /** How long the music runs between episodes on a song channel; read as each episode ends. */
    private val gapRule: () -> PodcastGapRule = { PodcastGapRule.DEFAULT },
    /** Podcast scheduling decisions, for diagnostics ([PodcastEventLog]). */
    private val podcastLog: (PodcastEvent) -> Unit = {},
    /** Keeps a music gap still owed across restarts of the service. */
    private val waitStore: PodcastWaitStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaSession.Callback, Player.Listener {

    private var player: Player? = null
    private var overlay: PodcastOverlay? = null
    private var currentChannelId: String? = null

    /** When episodes may start on the current song channel. */
    private val schedule = PodcastSchedule(gapRule)

    /** Runs while the session player plays; episodes' played time is measured on it. */
    private val onAir = OnAirClock(SystemClock::elapsedRealtime)

    /** [onAir] when the current episode started, for its played time. */
    private var episodeOnAirStartMs = 0L

    /** The last [PodcastSchedule.dueAtBoundary] answer, so the queue is topped up only when it changes. */
    private var dueAtBoundary = false

    /** The first item of a fresh tune-in to a song channel is a track boundary. */
    private var tuneBoundary = false

    /** Content length of the episode loaded in this player, once known; for the gap rule. */
    private var currentEpisodeDurationMs = 0L

    /** The episode loaded in the player, or null while a song is playing. */
    private var currentEpisodeId: String? = null

    /** Why the last due episode didn't start, so it's logged once per wait, not every check. */
    private var nothingStartedReason: String? = null

    private val handler = Handler(Looper.getMainLooper())

    /** While playing: whether an episode should start part-way through the song, or be queued after it. */
    private val scheduleTick = object : Runnable {
        override fun run() {
            checkSchedule()
            handler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS)
        }
    }
    private val positionTick = object : Runnable {
        override fun run() {
            savePosition(onAir = true)
            overlay?.savePosition(onAir = true)
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
        /**
         * An episode may start with the first item on a song channel: it starts at a track
         * boundary, or a podcast was playing when the knob moved (it carries on without a wait).
         */
        val boundary: Boolean = false,
    )

    /**
     * [overlayPlayer] plays podcasts over the music on song channels. It must not handle audio
     * focus itself: it follows [player]'s play/pause.
     */
    fun attach(player: Player, overlayPlayer: Player? = null, mix: MixState = MixState()) {
        this.player = player
        player.addListener(this)
        overlay = overlayPlayer?.let {
            PodcastOverlay(
                player = it,
                podcasts = podcasts,
                mix = mix,
                onEnded = ::onOverlayEnded,
                // Not marked played; it's tried again at the next boundary.
                onFailed = ::onOverlayFailed,
            )
        }
        log(PodcastEvent.Kind.SERVICE_START, detail = "rule=${gapRule().id} overlay=${overlay != null}")
        waitStore?.read()?.let {
            schedule.restore(it)
            logWait("restored")
        }
        scope.launch {
            podcasts.library.collect {
                syncPodcastQueue(it)
                applySpeed()
                overlay?.let { overlay -> overlay.episodeId?.let { id -> overlay.setSpeed(it.speedOf(id)) } }
                // Posted: assignments and "Pause music" can change what's due next.
                handler.post(::topUpSongQueue)
            }
        }
    }

    /** Call before releasing the players. */
    fun detach() {
        log(PodcastEvent.Kind.SERVICE_STOP, currentEpisodeId ?: overlay?.episodeId)
        savePosition()
        overlay?.stop()
        overlay?.release()
        overlay = null
        listening?.stop()
        onAir.stop()
        handler.removeCallbacks(positionTick)
        handler.removeCallbacks(scheduleTick)
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
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .add(ChannelStep.DOWN)
            .add(ChannelStep.UP)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(commands)
            .setAvailableSessionCommands(sessionCommands)
            .build()
    }

    /** [ChannelStep]: tunes the next or previous station on the dial and plays it, as the knob does. */
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val delta = when (customCommand.customAction) {
            ChannelStep.DOWN.customAction -> -1
            ChannelStep.UP.customAction -> 1
            else -> return failed(SessionError(SessionError.ERROR_NOT_SUPPORTED, "Unknown command"))
        }
        val player = player ?: return failed(SessionError(SessionError.ERROR_INVALID_STATE, "No player"))
        // Only the order matters here, not the names.
        val stations = Dial.stations(songChannels(), podcasts.library.value.channelIds, ChannelNames(emptyMap()))
        val queue = Dial.step(stations, currentChannelId, delta)?.id?.let(::tune)
            ?: return failed(SessionError(SessionError.ERROR_BAD_VALUE, "No other channel to tune"))
        player.setMediaItems(queue.items, 0, queue.startPositionMs)
        player.prepare()
        player.play()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private fun failed(error: SessionError): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(error))

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val queue = mediaItems.singleOrNull()
            ?.let(ChannelRequest::channelId)
            ?.let(::tune)
            ?: return Futures.immediateFailedFuture(
                IllegalArgumentException("Expected a single channel request for a known channel"),
            )
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(queue.items, 0, queue.startPositionMs),
        )
    }

    /**
     * Switches to [channelId] and returns the queue to put on the player, or null for an unknown
     * channel. Call before the player gets the new items.
     */
    private fun tune(channelId: String): Queue? {
        val podcastPlaying = overlay?.isActive == true || currentEpisodeId != null
        val now = clock()
        val queue = tuneIn(channelId, podcastPlaying, podcastDue = schedule.dueOnRetune(now, podcastPlaying)) ?: return null
        // Still on the old item here, so this keeps the place in an episode being tuned away from.
        savePosition()
        overlay?.episodeId?.let { log(PodcastEvent.Kind.STOP, it, "mode=overlay cause=tune") }
        overlay?.stop()
        schedule.retune(now, podcastPlaying, playedMs = episodePlayedMs())
        tuneBoundary = queue.boundary
        currentChannelId = queue.channelId
        onChannelTuned(queue.channelId)
        val first = if (EpisodeMediaId.episodeId(queue.items.firstOrNull()) != null) "episode" else "song"
        log(
            PodcastEvent.Kind.TUNE,
            detail = "joined=${queue.joined} boundary=${queue.boundary} podcast_playing=$podcastPlaying first=$first " +
                "song_channel=${repository.channel(queue.channelId) != null}",
        )
        logWait("tune")
        if (queue.joined) {
            joiningMediaId = queue.items.first().mediaId
            startFade()
        } else {
            joiningMediaId = null
            stopFade()
        }
        return queue
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
        val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        if (previous != null && auto) podcasts.finished(previous)
        val previousDurationMs = currentEpisodeDurationMs
        currentEpisodeId = EpisodeMediaId.episodeId(mediaItem)
        currentEpisodeDurationMs = 0
        // An episode reached mid-queue can't carry a start position: pick up where it was left,
        // or past its show's intro.
        currentEpisodeId?.takeIf { auto }?.let { episodeId ->
            podcasts.library.value.startPositionOf(episodeId)?.let { start ->
                handler.post { if (currentEpisodeId == episodeId) player?.seekTo(start) }
            }
        }
        if (mediaItem != null) onSongChannelTransition(endedEpisode = previous.takeIf { auto }, endedDurationMs = previousDurationMs, auto = auto)
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

    /**
     * Podcast scheduling at a transition on a song channel (#2). An episode in this player is a
     * music podcast playing by itself; a song after one is the music resuming, not a boundary.
     */
    private fun onSongChannelTransition(endedEpisode: String?, endedDurationMs: Long, auto: Boolean) {
        val channelId = currentChannelId ?: return
        if (repository.channel(channelId) == null) return
        val boundary = tuneBoundary
        tuneBoundary = false
        val episodeId = currentEpisodeId
        val now = clock()
        when {
            episodeId != null -> {
                episodeStarted(now)
                nothingStartedReason = null
                val saved = podcasts.library.value.startPositionOf(episodeId)
                log(PodcastEvent.Kind.START, episodeId, "mode=solo trigger=${if (auto) "boundary" else "tune"} resume_ms=${saved ?: 0}")
            }
            endedEpisode != null -> {
                schedule.episodeEnded(endedDurationMs, episodePlayedMs(), now)
                log(PodcastEvent.Kind.END, endedEpisode, "mode=solo content_ms=$endedDurationMs")
                logWait("episode_ended")
                // The rule may want the next episode straight away, over this song.
                handler.post(::checkSchedule)
            }
            auto -> {
                val due = schedule.songBoundary(now)
                if (hasShows(channelId)) log(PodcastEvent.Kind.BOUNDARY, detail = "due=$due ${schedule.describe(now)}")
                if (due) startOverlay(channelId, trigger = "boundary")
            }
            // A gap still owed from before the tune is waited out.
            boundary -> if (schedule.songBoundary(now)) startOverlay(channelId, trigger = "tune")
        }
    }

    /**
     * Between boundaries on a song channel: starts the next episode over the song once the gap
     * rule says it can't wait for the song to end, and re-queues when whether one is due at the
     * song's end changes (a music podcast takes the next song's place).
     */
    private fun checkSchedule() {
        val channelId = currentChannelId ?: return
        if (repository.channel(channelId) == null || currentEpisodeId != null) return
        val now = clock()
        val songEndsAt = songEndsAt(now)
        if (schedule.startMidSong(now, songEndsAt)) startOverlay(channelId, trigger = "mid_song")
        val due = schedule.dueAtBoundary(songEndsAt)
        if (due != dueAtBoundary) {
            dueAtBoundary = due
            topUpSongQueue()
        }
    }

    /** When the song playing ends, on the wall clock; null if its length isn't known yet. */
    private fun songEndsAt(now: Long): Long? {
        val player = player ?: return null
        if (player.currentMediaItem == null || player.duration == C.TIME_UNSET) return null
        return now + (player.duration - player.currentPosition).coerceAtLeast(0)
    }

    /** The next episode for [channelId] that isn't already playing somewhere. */
    private fun nextEpisode(channelId: String): Episode? =
        podcasts.library.value.queue(channelId).firstOrNull { it.id != currentEpisodeId && it.id != overlay?.episodeId }

    /**
     * Starts the next episode over the music, unless it's a music podcast (queued solo instead).
     * [trigger] says what made it due, for the log.
     */
    private fun startOverlay(channelId: String, trigger: String) {
        val player = player ?: return
        val overlay = overlay ?: return
        if (overlay.isActive) return
        val library = podcasts.library.value
        val episode = nextEpisode(channelId) ?: return nothingStarted("queue_empty", trigger, null)
        if (library.pausesMusic(episode.id)) return nothingStarted("pause_music_waits_for_boundary", trigger, episode.id)
        episodeStarted(clock())
        nothingStartedReason = null
        val item = episodeItem(library, episode.id, channelId)
        // A folder show's episodes are files too (content://), even though nothing downloaded them.
        val streamed = item.localConfiguration?.uri?.scheme !in setOf<String?>("file", "content")
        val playWhenReady = shouldPlay(player)
        overlay.start(
            item = item,
            episodeId = episode.id,
            startPositionMs = library.startPositionOf(episode.id),
            speed = library.speedOf(episode.id),
            playWhenReady = playWhenReady,
        )
        log(
            PodcastEvent.Kind.START,
            episode.id,
            "mode=overlay trigger=$trigger resume_ms=${library.startPositionOf(episode.id) ?: 0} streamed=$streamed " +
                "speed=${library.speedOf(episode.id)} play_when_ready=$playWhenReady",
        )
    }

    private fun nothingStarted(reason: String, trigger: String, episodeId: String?) {
        val channelId = currentChannelId ?: return
        // A channel no show is on has nothing to report.
        if (reason == nothingStartedReason || !hasShows(channelId)) return
        nothingStartedReason = reason
        val queued = podcasts.library.value.queue(channelId).size
        log(PodcastEvent.Kind.NOTHING_STARTED, episodeId, "reason=$reason trigger=$trigger queue_size=$queued")
    }

    private fun onOverlayEnded(episodeId: String, durationMs: Long) {
        schedule.episodeEnded(durationMs, episodePlayedMs(), clock())
        log(PodcastEvent.Kind.END, episodeId, "mode=overlay content_ms=$durationMs")
        logWait("episode_ended")
        handler.post(::topUpSongQueue)
        // The rule may want the next episode straight away, over this song.
        handler.post(::checkSchedule)
    }

    private fun onOverlayFailed(episodeId: String, error: PlaybackException) {
        schedule.episodeFailed(clock())
        log(PodcastEvent.Kind.FAIL, episodeId, "mode=overlay ${errorDetail(error)}")
        logWait("failed")
        handler.post(::topUpSongQueue)
    }

    override fun onPlayerError(error: PlaybackException) {
        log(PodcastEvent.Kind.PLAYER_ERROR, currentEpisodeId, errorDetail(error))
    }

    private fun hasShows(channelId: String): Boolean = channelId in podcasts.library.value.channelIds

    private fun errorDetail(error: PlaybackException): String = "error=${error.errorCodeName} message=${error.message}"

    private fun log(kind: PodcastEvent.Kind, episodeId: String? = null, detail: String = "") {
        podcastLog(PodcastEvent(clock(), onAir.now(), currentChannelId, kind.id, episodeId, detail))
    }

    /** The schedule's new wait, after [cause]. Also saved, so a restart keeps a gap still owed. */
    private fun logWait(cause: String) {
        nothingStartedReason = null
        log(PodcastEvent.Kind.WAIT, detail = "cause=$cause rule=${gapRule().id} ${schedule.describe(clock())}")
        waitStore?.write(schedule.owed)
    }

    private fun episodeStarted(nowMs: Long) {
        episodeOnAirStartMs = onAir.now()
        schedule.episodeStarted(nowMs)
    }

    private fun episodePlayedMs(): Long = (onAir.now() - episodeOnAirStartMs).coerceAtLeast(0)

    /** The overlay plays exactly when this player would be heard (not paused, not lost focus). */
    private fun shouldPlay(player: Player): Boolean =
        player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private fun syncOverlayPlay() {
        val player = player ?: return
        overlay?.setPlayWhenReady(shouldPlay(player))
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncOverlayPlay()

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) = syncOverlayPlay()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        syncListening()
        handler.removeCallbacks(positionTick)
        handler.removeCallbacks(scheduleTick)
        if (isPlaying) {
            onAir.start()
            handler.postDelayed(positionTick, POSITION_SAVE_INTERVAL_MS)
            handler.post(scheduleTick)
        } else {
            onAir.stop()
            savePosition()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val player = player ?: return
        if (currentEpisodeId != null && player.duration != C.TIME_UNSET) currentEpisodeDurationMs = player.duration
        if (playbackState != Player.STATE_ENDED) return
        // The last item in the queue doesn't get an AUTO transition; it just ends.
        val endedEpisode = currentEpisodeId
        endedEpisode?.let(podcasts::finished)
        val channelId = currentChannelId ?: return
        val songChannel = repository.channel(channelId)
        val next = if (songChannel != null) {
            songQueue(planner.onExhausted(songChannel, songChannels()))
        } else {
            podcastQueue(channelId)
        } ?: return
        log(PodcastEvent.Kind.QUEUE_END, endedEpisode, "next_channel=${next.channelId}")
        joiningMediaId = null
        stopFade()
        var boundary = next.boundary
        if (next.channelId != currentChannelId) {
            overlay?.stop()
            schedule.retune(clock(), podcastPlaying = false)
            currentChannelId = next.channelId
            logWait("fallback")
        } else if (endedEpisode != null && songChannel != null) {
            // A music podcast that was last in the queue: no transition will report its end, and
            // the music that follows is its gap, not a fresh start.
            schedule.episodeEnded(currentEpisodeDurationMs, episodePlayedMs(), clock())
            log(PodcastEvent.Kind.END, endedEpisode, "mode=solo content_ms=$currentEpisodeDurationMs")
            logWait("episode_ended")
            boundary = false
        }
        tuneBoundary = boundary
        currentChannelId = next.channelId
        onChannelTuned(next.channelId)
        player.setMediaItems(next.items, 0, next.startPositionMs)
        player.prepare()
        player.play()
    }

    /**
     * The queue for tuning in to [channelId]: song channels join their last song if it's still on
     * air. Otherwise it starts at a track boundary: a music podcast due on the channel
     * ([podcastDue]: no music gap still owed) plays first, by itself.
     *
     * With [podcastPlaying] (an episode was playing when the knob moved) the podcast carries on
     * straight away instead of waiting for the joined song to end: a music podcast due on the
     * channel plays first in place of the join, and any other show starts over the joined song.
     */
    private fun tuneIn(channelId: String, podcastPlaying: Boolean, podcastDue: Boolean): Queue? {
        repository.channel(channelId)?.let { channel ->
            val planned = planner.upcoming(channel, 1 + LOOKAHEAD)
            val tuneIn = liveRadio.tuneIn(channel, planned)
            val library = podcasts.library.value
            val solo = library.queue(channel.id).firstOrNull()
                ?.takeIf { podcastDue && library.pausesMusic(it.id) && overlay != null }
            if (tuneIn.resumeAtMs != null && !(podcastPlaying && solo != null)) {
                val songs = tuneIn.tracks.map { songItem(channel, it) }
                return Queue(channel.id, songs, startPositionMs = tuneIn.resumeAtMs, joined = true, boundary = podcastPlaying)
            }
            val songs = planned.map { songItem(channel, it) }
            if (solo != null) {
                return Queue(
                    channelId = channel.id,
                    items = listOf(episodeItem(library, solo.id, channel.id)) + songs.take(LOOKAHEAD),
                    startPositionMs = library.startPositionOf(solo.id) ?: C.TIME_UNSET,
                    boundary = true,
                )
            }
            return Queue(channel.id, songs, boundary = true)
        }
        if (channelId !in podcasts.library.value.channelIds) return null
        return podcastQueue(channelId)
    }

    /** Fresh picks for [channel], from the top. */
    private fun songQueue(channel: Channel): Queue =
        Queue(channel.id, planner.upcoming(channel, 1 + LOOKAHEAD).map { songItem(channel, it) }, boundary = true)

    private fun songItem(channel: Channel, track: Track): MediaItem = track.toMediaItem(channel, stationName(channel.id) ?: channel.name)

    /**
     * On a song channel, drops the items already played from the player's list and queues what
     * comes next: a music podcast's episode if one is due at the next track boundary (#2),
     * otherwise songs, so [LOOKAHEAD] items follow the current one. Picks come from the channel
     * as it is now, so learning changes apply from the next pick.
     */
    private fun topUpSongQueue() {
        val player = player ?: return
        val channel = currentChannelId?.let(repository::channel) ?: return
        val index = player.currentMediaItemIndex
        if (index < 0 || player.mediaItemCount == 0) return
        if (index > 0) player.removeMediaItems(0, index)

        val library = podcasts.library.value
        val songPlaying = EpisodeMediaId.episodeId(player.getMediaItemAt(0)) == null
        val overlay = overlay
        val due = schedule.dueAtBoundary(songEndsAt(clock()))
        dueAtBoundary = due
        val solo = if (overlay != null && !overlay.isActive && songPlaying && due) {
            nextEpisode(channel.id)?.takeIf { library.pausesMusic(it.id) }
        } else {
            null
        }
        val next = if (player.mediaItemCount > 1) player.getMediaItemAt(1) else null
        if (solo != null) {
            if (next?.mediaId != EpisodeMediaId.of(solo.id)) {
                if (player.mediaItemCount > 1) player.removeMediaItems(1, player.mediaItemCount)
                player.addMediaItem(episodeItem(library, solo.id, channel.id))
                log(PodcastEvent.Kind.QUEUED_SOLO, solo.id, schedule.describe(clock()))
            }
            return
        }
        // An episode queued earlier that's no longer due (e.g. "Pause music" turned off) makes way for songs.
        if (next != null && EpisodeMediaId.episodeId(next) != null) player.removeMediaItems(1, player.mediaItemCount)
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
            startPositionMs = library.startPositionOf(episodes.first().id) ?: C.TIME_UNSET,
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

    /** [onAir]: called while playing, so the episode also joins the podcast page's history. */
    private fun savePosition(onAir: Boolean = false) {
        val player = player ?: return
        val id = currentEpisodeId ?: return
        if (EpisodeMediaId.episodeId(player.currentMediaItem) != id) return
        if (onAir) podcasts.onAir(id, player.currentPosition) else podcasts.savePosition(id, player.currentPosition)
    }

    private fun songChannels(): List<Channel> =
        (repository.state.value as? ChannelRepository.State.Ready)?.channels.orEmpty()

    private companion object {
        const val POSITION_SAVE_INTERVAL_MS = 15_000L

        /** How often, while playing, a song channel checks whether an episode is due part-way through a song. */
        const val SCHEDULE_CHECK_INTERVAL_MS = 500L

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

/**
 * The notification's channel buttons: the knob turned one station down or up, wrapping past
 * the ends ([Dial.step]). Tuning, not skipping: the channel decides what plays.
 */
object ChannelStep {
    val DOWN = SessionCommand("app.viberadio.CHANNEL_DOWN", Bundle.EMPTY)
    val UP = SessionCommand("app.viberadio.CHANNEL_UP", Bundle.EMPTY)
}
