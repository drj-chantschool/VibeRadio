package app.viberadio.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs one stream's [StreamEq] on the decoded PCM in ExoPlayer's audio sink: its tone EQ, then
 * its separation EQ (#2). The music stream's separation applies while a podcast plays over it
 * ([MixState]); the podcast stream only ever plays over music, so its separation always applies.
 *
 * Reads [settings] and [separation] each buffer, so a change in Settings → Audio is heard
 * straight away (gliding or ramping, never snapping).
 * 16-bit and float PCM are handled; anything else passes through untouched.
 */
@UnstableApi
class StreamEqProcessor(
    private val stream: AudioStream,
    private val mix: MixState,
    private val settings: () -> AudioSettings,
    private val separation: () -> SeparationEq,
) : BaseAudioProcessor() {

    private var eq: StreamEq? = null
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }

    private fun target(): Float = when (stream) {
        AudioStream.PODCAST -> 1f
        AudioStream.MUSIC -> if (mix.podcastOverMusic) 1f else 0f
    }

    /** The EQ for the current format, rebuilt when it changes (the separation's blend carries over), otherwise brought up to date. */
    private fun eqFor(format: AudioFormat): StreamEq {
        val s = settings()
        val module = separation()
        val tone = s.tone(stream)
        val existing = eq
        if (existing != null && existing.sampleRate == format.sampleRate && existing.channels == format.channelCount) {
            existing.configure(module, tone, s.rampMs)
            return existing
        }
        return StreamEq(stream, format.sampleRate, format.channelCount, module, tone, s.rampMs, initialMix = existing?.mix ?: target())
            .also {
                eq = it
                if (frame.size != format.channelCount) frame = FloatArray(format.channelCount)
            }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val eq = eqFor(format)
        val channels = format.channelCount
        val target = target()
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(remaining)

        if (format.encoding == C.ENCODING_PCM_16BIT) {
            val frames = remaining / (2 * channels)
            repeat(frames) {
                for (c in 0 until channels) frame[c] = input.getShort() / 32_768f
                eq.processFrame(frame, target)
                for (c in 0 until channels) {
                    output.putShort((frame[c] * 32_768f).coerceIn(-32_768f, 32_767f).toInt().toShort())
                }
            }
        } else {
            val frames = remaining / (4 * channels)
            repeat(frames) {
                for (c in 0 until channels) frame[c] = input.getFloat()
                eq.processFrame(frame, target)
                for (c in 0 until channels) output.putFloat(frame[c])
            }
        }
        // Any partial frame at the end is dropped; the sink only sends whole frames.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onReset() {
        eq = null
    }
}
