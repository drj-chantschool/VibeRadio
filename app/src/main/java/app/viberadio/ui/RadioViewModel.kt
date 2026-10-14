package app.viberadio.ui

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.viberadio.VibeRadioApp
import app.viberadio.channels.ChannelRepository
import app.viberadio.dial.ChannelNamesRepository
import app.viberadio.dial.Dial
import app.viberadio.dial.Station
import app.viberadio.feedback.FeedbackRecorder
import app.viberadio.feedback.Thumb
import app.viberadio.library.AudioPermission
import app.viberadio.playback.ChannelRequest
import app.viberadio.playback.PlaybackService
import app.viberadio.podcasts.PodcastLibrary
import app.viberadio.podcasts.PodcastRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RadioUiState {
    data class NeedsPermission(val denied: Boolean) : RadioUiState
    data object Scanning : RadioUiState
    data object Empty : RadioUiState
    data class Ready(
        val channelNames: List<String>,
        /** Index of the tuned channel, or null before the first tune. */
        val tunedIndex: Int?,
        val nowPlaying: NowPlaying?,
        /** Thumbs for the playing song (#13), or null when no song is playing (e.g. a podcast episode). */
        val feedback: SongFeedback?,
        /** True while the player means to play (including brief buffering at track boundaries). */
        val playing: Boolean,
    ) : RadioUiState
}

data class NowPlaying(val title: String, val artist: String?)

/**
 * The thumbs under the playing song. [lastPress] is the thumb last pressed during the current
 * play only: it is never read back from the log, so every play starts neutral (#13).
 */
data class SongFeedback(val lastPress: Thumb?)

/**
 * Thumbs state is per play, not per song (#13). A press is lit until the play ends; the next play,
 * even of the same song, starts neutral. Pure, so the rule is covered by JVM tests.
 */
internal object PlayThumbs {
    /**
     * Whether [lastPress] still applies after a player update. It is cleared when the song changes,
     * when a new play starts ([newPlay]: any media item transition, including a retune or live-radio
     * resume landing on the same song), or when playback has [ended].
     */
    fun carry(lastPress: Thumb?, previousUri: String?, currentUri: String?, newPlay: Boolean, ended: Boolean): Thumb? =
        if (newPlay || ended || currentUri == null || currentUri != previousUri) null else lastPress
}

/**
 * Bridges the dial (song channels plus podcast-only channels) and the playback service. Talks to the service only through a
 * [MediaController], so the UI and the system notification see the same session.
 */
class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChannelRepository = (application as VibeRadioApp).container.channelRepository
    private val podcasts: PodcastRepository = (application as VibeRadioApp).container.podcastRepository
    private val names: ChannelNamesRepository = (application as VibeRadioApp).container.channelNames
    private val feedback: FeedbackRecorder = (application as VibeRadioApp).container.feedback
    private val learner = (application as VibeRadioApp).container.learner
    private val appScope = (application as VibeRadioApp).container.appScope

    private data class Permission(val granted: Boolean, val denied: Boolean)
    private data class Playback(
        val channelId: String? = null,
        val nowPlaying: NowPlaying? = null,
        /** Content URI of the playing song; null for podcast episodes or before anything plays. */
        val trackUri: String? = null,
        val lastPress: Thumb? = null,
        /** Counts plays, so a late log failure only un-lights the play it was pressed in. */
        val play: Long = 0,
        val playing: Boolean = false,
    )

    private val permission = MutableStateFlow(Permission(AudioPermission.isGranted(application), denied = false))
    private val playback = MutableStateFlow(Playback())

    val uiState: StateFlow<RadioUiState> =
        combine(permission, repository.state, podcasts.library, names.names, playback) { perm, library, shows, channelNames, play ->
            when {
                !perm.granted -> RadioUiState.NeedsPermission(perm.denied)
                library !is ChannelRepository.State.Ready -> RadioUiState.Scanning
                else -> {
                    val stations = Dial.stations(library.channels, shows.channelIds, channelNames)
                    if (stations.isEmpty()) {
                        RadioUiState.Empty
                    } else {
                        RadioUiState.Ready(
                            channelNames = stations.map { it.name },
                            tunedIndex = stations.indexOfFirst { it.id == play.channelId }.takeIf { it >= 0 },
                            nowPlaying = play.nowPlaying,
                            feedback = play.trackUri?.let { SongFeedback(play.lastPress) },
                            playing = play.playing,
                        )
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUiState.Scanning)

    private val controllerFuture = MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, PlaybackService::class.java)),
    ).buildAsync()

    private val controller: MediaController?
        get() = if (controllerFuture.isDone && !controllerFuture.isCancelled) {
            runCatching { controllerFuture.get() }.getOrNull()
        } else {
            null
        }

    private val listener = object : Player.Listener {
        /**
         * Reads the current item on every update rather than relying on transition events alone:
         * the session can fold a transition into a combined update, and a missed one used to
         * leave the thumbs hidden for the rest of the song. Also follows channel changes made by
         * the service itself, e.g. queue fallback (#4).
         */
        override fun onEvents(player: Player, events: Player.Events) {
            follow(
                player.currentMediaItem,
                newPlay = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
                ended = player.playbackState == Player.STATE_ENDED,
            )
            playback.update { it.copy(playing = player.isActive()) }
            if (events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                playback.update { it.copy(nowPlaying = player.mediaMetadata.toNowPlaying()) }
            }
        }
    }

    private fun follow(mediaItem: MediaItem?, newPlay: Boolean = false, ended: Boolean = false) {
        val extras = mediaItem?.mediaMetadata?.extras
        val id = extras?.getString(ChannelRequest.EXTRA_CHANNEL_ID)
        val trackUri = extras?.getString(ChannelRequest.EXTRA_TRACK_URI)
        playback.update {
            // A press belongs to the play it was made in; a new play (even of the same song) starts neutral.
            val samePlay = !newPlay && trackUri == it.trackUri
            it.copy(
                channelId = id ?: it.channelId,
                trackUri = trackUri,
                lastPress = PlayThumbs.carry(it.lastPress, it.trackUri, trackUri, newPlay, ended),
                play = if (samePlay) it.play else it.play + 1,
            )
        }
    }

    init {
        controllerFuture.addListener(
            {
                controller?.let { c ->
                    c.addListener(listener)
                    follow(c.currentMediaItem)
                    playback.update { it.copy(nowPlaying = c.mediaMetadata.toNowPlaying(), playing = c.isActive()) }
                }
            },
            ContextCompat.getMainExecutor(application),
        )
        scanIfNeeded()
    }

    fun onPermissionResult(granted: Boolean) {
        permission.value = Permission(granted, denied = !granted)
        scanIfNeeded()
    }

    /** Picks up a grant made in system settings while the app was in the background. */
    fun onResume() {
        val granted = AudioPermission.isGranted(getApplication())
        if (granted != permission.value.granted) permission.update { it.copy(granted = granted) }
        scanIfNeeded()
    }

    fun tune(index: Int) {
        val library = repository.state.value as? ChannelRepository.State.Ready ?: return
        val station = stations(library, podcasts.library.value).getOrNull(index) ?: return
        playback.update { it.copy(channelId = station.id) }
        controller?.run {
            setMediaItem(ChannelRequest.mediaItem(station.id))
            prepare()
            play()
        }
    }

    /**
     * The centre play button. Resumes the tuned channel where it paused (e.g. after headphones
     * were unplugged or the notification paused it). If the queue is gone or nothing is tuned,
     * tunes the tuned channel or, failing that, the first one (#15 will choose a better default).
     */
    fun play() {
        val c = controller ?: return
        val resumable = playback.value.channelId != null &&
            c.mediaItemCount > 0 &&
            c.playbackState != Player.STATE_ENDED
        if (resumable) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
            return
        }
        val state = uiState.value as? RadioUiState.Ready ?: return
        tune(state.tunedIndex ?: 0)
    }

    /**
     * Logs [thumb] for the playing song (#13). Feedback only: the song keeps playing. Recorded on
     * the app scope so a press made just before leaving the screen still lands in the log.
     */
    fun press(thumb: Thumb) {
        val current = playback.value
        val uri = current.trackUri ?: return
        val channelId = current.channelId ?: return
        val play = current.play
        playback.update { if (it.play == play) it.copy(lastPress = thumb) else it }
        appScope.launch {
            val event = feedback.record(uri, channelId, thumb)
            if (event == null) {
                // Not logged (file unreadable or disk full): don't show it as pressed.
                playback.update { if (it.play == play && it.lastPress == thumb) it.copy(lastPress = null) else it }
                return@launch
            }
            // Placement follows the new feedback; the queue already playing is left alone.
            learner.add(event)
            repository.replace()
        }
    }

    /** Song channels plus podcast-only channels: the knob's positions. */
    private fun stations(library: ChannelRepository.State.Ready, shows: PodcastLibrary): List<Station> =
        Dial.stations(library.channels, shows.channelIds, names.names.value)

    private fun scanIfNeeded() {
        if (!permission.value.granted) return
        if (repository.state.value != ChannelRepository.State.NotScanned) return
        viewModelScope.launch { repository.refresh() }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}

/** Playing or about to play; stays true through buffering so the play button doesn't flash between tracks. */
private fun Player.isActive(): Boolean =
    playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

private fun MediaMetadata.toNowPlaying(): NowPlaying? {
    val title = title?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return NowPlaying(title = title, artist = artist?.toString()?.takeIf { it.isNotBlank() })
}
