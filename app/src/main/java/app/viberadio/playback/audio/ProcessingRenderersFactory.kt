package app.viberadio.playback.audio

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Builds ExoPlayer's audio sink with the app's [AudioProcessorProvider] chain for [stream] installed. */
@UnstableApi
class ProcessingRenderersFactory(
    context: Context,
    private val processors: AudioProcessorProvider,
    private val stream: AudioStream,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(processors.create(stream).toTypedArray())
            .build()
}
