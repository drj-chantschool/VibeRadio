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
import app.viberadio.library.AudioPermission
import app.viberadio.playback.ChannelRequest
import app.viberadio.playback.PlaybackService
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
    ) : RadioUiState
}

data class NowPlaying(val title: String, val artist: String?)

/**
 * Bridges the channel list and the playback service. Talks to the service only through a
 * [MediaController], so the UI and the system notification see the same session.
 */
class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ChannelRepository = (application as VibeRadioApp).container.channelRepository

    private data class Permission(val granted: Boolean, val denied: Boolean)
    private data class Playback(val channelId: String? = null, val nowPlaying: NowPlaying? = null)

    private val permission = MutableStateFlow(Permission(AudioPermission.isGranted(application), denied = false))
    private val playback = MutableStateFlow(Playback())

    val uiState: StateFlow<RadioUiState> =
        combine(permission, repository.state, playback) { perm, library, play ->
            when {
                !perm.granted -> RadioUiState.NeedsPermission(perm.denied)
                library !is ChannelRepository.State.Ready -> RadioUiState.Scanning
                library.channels.isEmpty() -> RadioUiState.Empty
                else -> RadioUiState.Ready(
                    channelNames = library.channels.map { it.name },
                    tunedIndex = library.channels.indexOfFirst { it.id == play.channelId }.takeIf { it >= 0 },
                    nowPlaying = play.nowPlaying,
                )
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Follows channel changes made by the service itself, e.g. queue fallback (#4).
            val id = mediaItem?.mediaMetadata?.extras?.getString(ChannelRequest.EXTRA_CHANNEL_ID)
            if (id != null) playback.update { it.copy(channelId = id) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            playback.update { it.copy(nowPlaying = mediaMetadata.toNowPlaying()) }
        }
    }

    init {
        controllerFuture.addListener(
            {
                controller?.let { c ->
                    c.addListener(listener)
                    listener.onMediaItemTransition(c.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                    listener.onMediaMetadataChanged(c.mediaMetadata)
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
        val channels = (repository.state.value as? ChannelRepository.State.Ready)?.channels ?: return
        val channel = channels.getOrNull(index) ?: return
        playback.update { it.copy(channelId = channel.id) }
        controller?.run {
            setMediaItem(ChannelRequest.mediaItem(channel.id))
            prepare()
            play()
        }
    }

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

private fun MediaMetadata.toNowPlaying(): NowPlaying? {
    val title = title?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return NowPlaying(title = title, artist = artist?.toString()?.takeIf { it.isNotBlank() })
}
