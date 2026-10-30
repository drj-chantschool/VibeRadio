package app.viberadio.playback

import android.os.Handler
import androidx.media3.common.Player

/**
 * Raises [player]'s volume from silence over [FADE_IN_MS] of actual playback, so time spent
 * buffering doesn't use up the fade. Used wherever a stream comes in part-way: a song joined by
 * live-radio resume, and every episode over the music (#2).
 */
class FadeIn(private val player: Player, private val handler: Handler) {

    /** How far the fade-in has got, in ms of playback, or -1 when not fading. */
    private var elapsedMs = -1L
    private val tick = object : Runnable {
        override fun run() {
            if (player.isPlaying) elapsedMs += FADE_STEP_MS
            if (elapsedMs >= FADE_IN_MS) {
                stop()
                return
            }
            val t = elapsedMs.toFloat() / FADE_IN_MS
            // Squared: closer to an even rise in loudness than a linear gain ramp.
            player.volume = t * t
            handler.postDelayed(this, FADE_STEP_MS)
        }
    }

    /**
     * Silences the player and starts the fade. Call before the item loads, so it never starts at
     * full volume.
     */
    fun start() {
        handler.removeCallbacks(tick)
        elapsedMs = 0
        player.volume = 0f
        handler.postDelayed(tick, FADE_STEP_MS)
    }

    /** Ends any fade at full volume. */
    fun stop() {
        handler.removeCallbacks(tick)
        if (elapsedMs >= 0) player.volume = 1f
        elapsedMs = -1
    }

    private companion object {
        const val FADE_IN_MS = 1_500L
        const val FADE_STEP_MS = 50L
    }
}
