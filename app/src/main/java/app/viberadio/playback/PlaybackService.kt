package app.viberadio.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.viberadio.R
import app.viberadio.VibeRadioApp
import app.viberadio.playback.audio.ProcessingRenderersFactory
import app.viberadio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the player and the media session. Media3 promotes it to a foreground service with a
 * media notification while playing, so playback continues with the app in the background.
 *
 * Podcast-only channels play through the same player. Mixed channels (#2) will need a second
 * player (or a custom mixing source) for the podcast stream; it belongs here alongside the song
 * player, with the session exposing the combined state.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var callback: RadioSessionCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as VibeRadioApp).container

        // Podcast enclosures are streamed until downloaded, usually through analytics
        // redirects that hop between http and https.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(getString(R.string.app_name))
        val player = ExoPlayer.Builder(this, ProcessingRenderersFactory(this, container.audioProcessors))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Network: keeps Wi-Fi up while an episode streams.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val callback = RadioSessionCallback(
            repository = container.channelRepository,
            podcasts = container.podcastRepository,
            planner = container.streamPlanner,
            liveRadio = container.liveRadio,
            scope = scope,
            podcastsStationName = getString(R.string.channel_podcasts),
            // App scope, not the service's: a play still gets recorded if the service stops meanwhile.
            onSongStarted = { channelId, track, startedAtMs ->
                container.appScope.launch {
                    val hash = container.playHistory.markPlayed(track) ?: return@launch
                    container.liveRadio.record(channelId, hash, track.durationMs, startedAtMs)
                }
            },
        )
        callback.attach(player)
        this.callback = callback

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        callback?.detach()
        callback = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
