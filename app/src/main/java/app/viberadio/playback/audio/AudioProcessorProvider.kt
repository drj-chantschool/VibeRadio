package app.viberadio.playback.audio

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi

/**
 * Supplies the custom [AudioProcessor] chain that runs on decoded PCM before it reaches the
 * AudioTrack. [ProcessingRenderersFactory] installs it into ExoPlayer's audio sink.
 *
 * This is the hook for the DSP work in the issues. Put each processor in this package and add
 * it to the list, in this order:
 * 1. Loudness normalisation (#1): per-track gain from ReplayGain/R128 tags or analysis,
 *    with a limiter so applied gain never clips.
 * 2. Voice emphasis (#5): EQ and compression on podcast speech.
 * 3. Ducking (#5) and background-music suppression (#6) need the speech/music signal from the
 *    other stream on mixed channels; they belong after the mixer that #2 introduces.
 *
 * Processors are created per player because they hold per-stream state.
 */
@OptIn(UnstableApi::class)
fun interface AudioProcessorProvider {
    fun create(): List<AudioProcessor>

    companion object {
        /** Scaffold default: no processing, ExoPlayer's stock chain only. */
        val None = AudioProcessorProvider { emptyList() }
    }
}
