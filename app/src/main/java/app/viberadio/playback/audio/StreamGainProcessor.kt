package app.viberadio.playback.audio

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs one stream's [StreamGain] on decoded PCM, ahead of its EQ: the playing item's leveling
 * gain (#1) and, on the music stream, the mixing factor while a podcast plays over it.
 *
 * Which item the PCM belongs to comes from the sink: it flushes the chain with the new item's
 * period at the exact sample where one item's audio ends and the next begins, so the gain
 * changes on the boundary, not when the player reports the transition (which lags the decoded
 * audio by the output buffer).
 *
 * 16-bit and float PCM are handled; anything else passes through untouched.
 */
@UnstableApi
class StreamGainProcessor(
    private val stream: AudioStream,
    private val leveling: Leveling,
    private val mix: MixState,
    private val settings: () -> AudioSettings,
) : BaseAudioProcessor() {

    private var gain: StreamGain? = null
    private var gainRate = 0
    private var gainChannels = 0
    private var frame = FloatArray(0)

    /** The media ID of the item whose PCM is coming through, if known. */
    private var mediaId: String? = null
    private var itemStarting = true

    private val window = Timeline.Window()
    private val period = Timeline.Period()

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }

    override fun onFlush(streamMetadata: StreamMetadata) {
        // A seek within the same item keeps its gain; a new item starts at its own.
        val id = mediaIdOf(streamMetadata) ?: return
        if (id != mediaId) {
            mediaId = id
            itemStarting = true
        }
    }

    private fun mediaIdOf(metadata: StreamMetadata): String? {
        val uid = metadata.periodUid ?: return null
        val timeline = metadata.timeline
        if (timeline.isEmpty) return null
        return runCatching {
            timeline.getPeriodByUid(uid, period)
            timeline.getWindow(period.windowIndex, window).mediaItem.mediaId
        }.getOrNull()
    }

    private fun levelDb(s: AudioSettings): Float? = mediaId?.let(leveling::levelOf)?.gainDb(s.targetLufs)

    private fun mixDb(s: AudioSettings): Float =
        if (stream == AudioStream.MUSIC && mix.podcastOverMusic) s.mixingFactorDb else 0f

    private fun gainFor(format: AudioFormat): StreamGain {
        val existing = gain
        if (existing != null && gainRate == format.sampleRate && gainChannels == format.channelCount) return existing
        gainRate = format.sampleRate
        gainChannels = format.channelCount
        frame = FloatArray(format.channelCount)
        itemStarting = true
        return StreamGain(format.sampleRate, format.channelCount).also { gain = it }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val gain = gainFor(format)
        val s = settings()
        val levelDb = levelDb(s)
        val mixDb = mixDb(s)
        if (itemStarting) {
            gain.startItem(levelDb, mixDb)
            itemStarting = false
        } else {
            gain.aim(levelDb, mixDb, s.rampMs)
        }

        val channels = format.channelCount
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(remaining)
        if (format.encoding == C.ENCODING_PCM_16BIT) {
            val frames = remaining / (2 * channels)
            repeat(frames) {
                for (c in 0 until channels) frame[c] = input.getShort() / 32_768f
                gain.processFrame(frame)
                for (c in 0 until channels) {
                    output.putShort((frame[c] * 32_768f).coerceIn(-32_768f, 32_767f).toInt().toShort())
                }
            }
        } else {
            val frames = remaining / (4 * channels)
            repeat(frames) {
                for (c in 0 until channels) frame[c] = input.getFloat()
                gain.processFrame(frame)
                for (c in 0 until channels) output.putFloat(frame[c])
            }
        }
        // Any partial frame at the end is dropped; the sink only sends whole frames.
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onReset() {
        gain = null
        mediaId = null
        itemStarting = true
    }
}
