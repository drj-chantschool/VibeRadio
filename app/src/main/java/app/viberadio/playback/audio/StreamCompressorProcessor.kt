package app.viberadio.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs one stream's [Compressor] on decoded PCM, between its gain and its EQ. Reads
 * [AudioSettings.compressor] each buffer, so a change in Settings → Audio → Compression is heard
 * straight away. Off, it passes the buffer through untouched once released.
 *
 * 16-bit and float PCM are handled; anything else passes through untouched.
 */
@UnstableApi
class StreamCompressorProcessor(
    private val settings: () -> AudioSettings,
) : BaseAudioProcessor() {

    private var compressor: Compressor? = null
    private var compressorRate = 0
    private var compressorChannels = 0
    private var frame = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }

    private fun compressorFor(format: AudioFormat): Compressor {
        val existing = compressor
        if (existing != null && compressorRate == format.sampleRate && compressorChannels == format.channelCount) return existing
        compressorRate = format.sampleRate
        compressorChannels = format.channelCount
        frame = FloatArray(format.channelCount)
        return Compressor(format.sampleRate, format.channelCount).also { compressor = it }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val compressor = compressorFor(format)
        val s = settings()
        val c = s.compressor
        val output = replaceOutputBuffer(remaining)
        if (compressor.bypassed(c.enabled)) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val channels = format.channelCount
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (format.encoding == C.ENCODING_PCM_16BIT) {
            val frames = remaining / (2 * channels)
            repeat(frames) {
                for (ch in 0 until channels) frame[ch] = input.getShort() / 32_768f
                compressor.processFrame(frame, c, s.rampMs)
                for (ch in 0 until channels) {
                    output.putShort((frame[ch] * 32_768f).coerceIn(-32_768f, 32_767f).toInt().toShort())
                }
            }
        } else {
            val frames = remaining / (4 * channels)
            repeat(frames) {
                for (ch in 0 until channels) frame[ch] = input.getFloat()
                compressor.processFrame(frame, c, s.rampMs)
                for (ch in 0 until channels) output.putFloat(frame[ch])
            }
        }
        // Any partial frame at the end is dropped; the sink only sends whole frames.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onReset() {
        compressor = null
    }
}
