package app.viberadio.playback.audio

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * Supplies the custom [AudioProcessor] chain that runs on decoded PCM before it reaches the
 * AudioTrack, per stream. [ProcessingRenderersFactory] installs it into each player's audio
 * sink. The two streams (music on the session player, podcasts played over it on a second
 * player) each go through their own chain and are then mixed by the system.
 *
 * Each chain is the stream's gain ([StreamGainProcessor]: the item's leveling gain, #1, and on
 * the music the mixing factor under a podcast, #2), then its EQ ([StreamEqProcessor]: the tone
 * EQ, then the separation EQ, #2).
 * Still to come, between the two: voice emphasis (#5). Background-music suppression in
 * podcasts (#6) belongs on the podcast stream.
 *
 * Processors are created per player because they hold per-stream state.
 */
@OptIn(UnstableApi::class)
fun interface AudioProcessorProvider {
    fun create(stream: AudioStream): List<AudioProcessor>

    companion object {
        /** No processing, ExoPlayer's stock chain only. */
        val None = AudioProcessorProvider { emptyList() }

        /** Each stream through its own gain, then its own EQ, following [mix], the current [settings] and [separation] module. */
        fun leveledEq(
            leveling: Leveling,
            mix: MixState,
            settings: () -> AudioSettings,
            separation: () -> SeparationEq,
        ) = AudioProcessorProvider { stream ->
            listOf(StreamGainProcessor(stream, leveling, mix, settings), StreamEqProcessor(stream, mix, settings, separation))
        }
    }
}
