package app.viberadio.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.viberadio.playback.audio.MixState
import app.viberadio.podcasts.PodcastRepository

/**
 * The podcast stream on a song channel (#2): one episode at a time, played on a second player
 * over the music, which carries on underneath. [RadioSessionCallback] decides when an episode
 * starts ([PodcastSchedule]) and makes it follow the session player's play/pause.
 *
 * Like episodes on the session player: plays at its show's speed, saves its position, and is
 * marked played only when it actually plays to the end.
 *
 * Every episode comes in with [fade], as the music ramps down under it: while it plays,
 * [MixState.podcastOverMusic] is on, so the music's EQ ramps into its notch; when it ends or
 * stops, the music ramps back to flat.
 */
@OptIn(UnstableApi::class)
class PodcastOverlay(
    private val player: Player,
    private val podcasts: PodcastRepository,
    private val mix: MixState,
    private val fade: FadeIn,
    /** The episode played to the end, after [durationMs] of content (0 if unknown). */
    private val onEnded: (episodeId: String, durationMs: Long) -> Unit,
    /** The episode couldn't play; it isn't marked played. */
    private val onFailed: (episodeId: String, error: PlaybackException) -> Unit,
) : Player.Listener {

    /** The episode on the overlay player, or null when it's idle. */
    var episodeId: String? = null
        private set

    val isActive: Boolean get() = episodeId != null

    private var durationMs = 0L

    init {
        player.addListener(this)
    }

    fun start(item: MediaItem, episodeId: String, startPositionMs: Long?, speed: Float, playWhenReady: Boolean) {
        this.episodeId = episodeId
        durationMs = 0
        fade.start()
        player.setMediaItem(item, startPositionMs ?: 0L)
        player.setPlaybackSpeed(speed)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    /** Stops the episode where it is, keeping its place for next time. */
    fun stop() {
        savePosition()
        clear()
    }

    fun setPlayWhenReady(play: Boolean) {
        if (isActive && player.playWhenReady != play) player.playWhenReady = play
    }

    fun setSpeed(speed: Float) {
        if (isActive && player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
    }

    /** [onAir]: called while playing, so the episode also joins the podcast page's history. */
    fun savePosition(onAir: Boolean = false) {
        val id = episodeId ?: return
        if (player.currentMediaItem == null) return
        if (onAir) podcasts.onAir(id, player.currentPosition) else podcasts.savePosition(id, player.currentPosition)
    }

    fun release() {
        fade.stop()
        player.removeListener(this)
        mix.podcastOverMusic = false
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        // On once speech is actually coming out, so the music isn't notched while an episode buffers.
        // Left on through a pause: both streams pause together.
        if (isPlaying && isActive) mix.podcastOverMusic = true
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (player.duration != C.TIME_UNSET) durationMs = player.duration
        if (playbackState != Player.STATE_ENDED) return
        val id = episodeId ?: return
        val duration = durationMs
        clear()
        podcasts.finished(id)
        onEnded(id, duration)
    }

    override fun onPlayerError(error: PlaybackException) {
        val id = episodeId ?: return
        clear()
        onFailed(id, error)
    }

    private fun clear() {
        episodeId = null
        mix.podcastOverMusic = false
        fade.stop()
        player.stop()
        player.clearMediaItems()
    }
}
