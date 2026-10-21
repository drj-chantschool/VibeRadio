package app.viberadio.features

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import java.nio.ByteOrder

/**
 * Decodes the excerpts of an [ExcerptPlan] from a song with the platform codecs, mono, at the
 * file's own rate ([FeaturePipeline] resamples). Seeks to each excerpt instead of decoding the
 * whole file. Blocking and CPU-bound; runs at background thread priority.
 */
class ExcerptDecoder(private val context: Context) {

    fun decode(uri: String, durationMs: Long, plan: ExcerptPlan): List<AudioClip>? {
        val excerpts = plan.excerpts(durationMs)
        if (excerpts.isEmpty()) return null
        val tid = Process.myTid()
        val priority = Process.getThreadPriority(tid)
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            return decodeExcerpts(uri, excerpts)
        } finally {
            Process.setThreadPriority(tid, priority)
        }
    }

    private fun decodeExcerpts(uri: String, excerpts: List<ExcerptPlan.Excerpt>): List<AudioClip>? {
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
            val clips = ArrayList<AudioClip>()
            for ((index, excerpt) in excerpts.withIndex()) {
                if (index > 0) codec.flush()
                decodeOne(extractor, codec, excerpt)?.let { clips += it }
            }
            return clips.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** One excerpt: seek, then decode until its end, keeping only samples inside it. */
    private fun decodeOne(extractor: MediaExtractor, codec: MediaCodec, excerpt: ExcerptPlan.Excerpt): AudioClip? {
        val startUs = excerpt.startMs * 1000
        val endUs = (excerpt.startMs + excerpt.lengthMs) * 1000
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var rate = 0
        var channels = 0
        var floatPcm = false
        fun configureFrom(format: MediaFormat) {
            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        }

        var mono = FloatArray(0)
        var size = 0
        var scratch = FloatArray(0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleRounds = 0
        while (true) {
            var progressed = false
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0 || extractor.sampleTime > endUs) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
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
                    if (rate == 0) configureFrom(codec.getOutputFormat(out))
                    val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val count = if (floatPcm) info.size / 4 else info.size / 2
                    if (scratch.size < count) scratch = FloatArray(count)
                    if (floatPcm) {
                        for (i in 0 until count) scratch[i] = buffer.getFloat()
                    } else {
                        for (i in 0 until count) scratch[i] = buffer.getShort() / 32_768f
                    }
                    codec.releaseOutputBuffer(out, false)
                    val frames = AudioClip.downmix(scratch, count, channels)
                    // Drop what's before the excerpt (seeking lands on the sync frame before it) or after it.
                    val bufferStartUs = info.presentationTimeUs
                    val skip = ((startUs - bufferStartUs).coerceAtLeast(0) * rate / 1_000_000).toInt().coerceAtMost(frames.size)
                    val wanted = ((endUs - startUs) * rate / 1_000_000).toInt()
                    val take = minOf(frames.size - skip, wanted - size).coerceAtLeast(0)
                    if (take > 0) {
                        if (mono.size < size + take) mono = mono.copyOf(maxOf(size + take, wanted))
                        frames.copyInto(mono, size, skip, skip + take)
                        size += take
                    }
                    if (size >= wanted || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            idleRounds = if (progressed) 0 else idleRounds + 1
            if (idleRounds > MAX_IDLE_ROUNDS) return null
        }
        if (rate == 0 || size < rate) return null
        return AudioClip(mono.copyOf(size), rate)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ROUNDS = 500
    }
}
