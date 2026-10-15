package app.viberadio.playback.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import java.nio.ByteOrder

/**
 * Measures a whole file's [TrackLevel] (#1): decodes its first audio track with the platform
 * codecs and runs every sample through a [LoudnessMeter]. Blocking and CPU-bound, so it runs on
 * [Leveling]'s single background worker at background thread priority.
 */
class LoudnessAnalyzer(private val context: Context) {

    /** The level of the audio at [uri] (content:// or file://), or null if it can't be decoded. */
    fun measure(uri: String): TrackLevel? {
        val tid = Process.myTid()
        val priority = Process.getThreadPriority(tid)
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            return decode(uri)
        } finally {
            Process.setThreadPriority(tid, priority)
        }
    }

    private fun decode(uri: String): TrackLevel? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            var meter: LoudnessMeter? = null
            var floatPcm = false
            var samples = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idleRounds = 0

            fun configureFrom(format: MediaFormat) {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                val current = meter
                if (current == null || current.sampleRate != rate || current.channels != channels) {
                    // A format change mid-file is rare; the measurement restarts from there.
                    meter = LoudnessMeter(rate, channels)
                }
            }

            while (true) {
                var progressed = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progressed = true
                    }
                }

                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        configureFrom(codec.outputFormat)
                        progressed = true
                    }
                    out >= 0 -> {
                        progressed = true
                        if (meter == null) configureFrom(codec.getOutputFormat(out))
                        val m = meter!!
                        val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val count = if (floatPcm) info.size / 4 else info.size / 2
                        if (samples.size < count) samples = FloatArray(count)
                        if (floatPcm) {
                            for (i in 0 until count) samples[i] = buffer.getFloat()
                        } else {
                            for (i in 0 until count) samples[i] = buffer.getShort() / 32_768f
                        }
                        m.add(samples, 0, count / m.channels)
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                idleRounds = if (progressed) 0 else idleRounds + 1
                // A codec that stops producing (corrupt file) is given up on after a few seconds.
                if (idleRounds > MAX_IDLE_ROUNDS) return null
            }
            return meter?.result()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ROUNDS = 500
    }
}
