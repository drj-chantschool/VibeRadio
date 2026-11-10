package app.viberadio.playback.wma

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Decoded audio: interleaved float PCM from [startUs] on. */
class WmaBlock(val startUs: Long, val samples: FloatArray)

/**
 * A WMA file read front to back and decoded, for the analysers (loudness, audio features), which
 * can't use the platform codecs. Playback has its own path ([WmaExtractor]). No Android types.
 */
class WmaReader private constructor(
    val header: AsfHeader,
    private val packets: AsfPacketReader,
    private val codec: WmaCodec,
) : AutoCloseable {
    val sampleRate: Int get() = header.audio.sampleRate
    val channels: Int get() = header.audio.channels

    /** Where the next block starts, from the blocks before it; -1 when unknown (start, or after skipped ones). */
    private var cursorUs = -1L
    private var needsFlush = false

    /**
     * The next decoded block, or null at the end. [wanted] is asked, with each media object's start
     * time, whether to decode it; one it declines is skipped without decoding (cheap, since
     * parsing is), and the decoder starts afresh at the next one it takes. To decode from the middle,
     * start a little early: the first block after a gap is a warm-up for the decoder.
     */
    fun next(wanted: (startUs: Long) -> Boolean = { true }): WmaBlock? {
        while (true) {
            val obj = try {
                packets.next()
            } catch (e: AsfFormatException) {
                return null
            } ?: return null
            val objectUs = (obj.presentationMs - header.prerollMs).coerceAtLeast(0) * 1000
            if (!wanted(objectUs)) {
                cursorUs = -1
                needsFlush = true
                continue
            }
            if (needsFlush) {
                codec.flush()
                needsFlush = false
            }
            val samples = codec.decode(obj.data)
            if (samples == null) {
                cursorUs = -1
                continue
            }
            if (samples.isEmpty()) continue
            val start = if (cursorUs >= 0) cursorUs else objectUs
            cursorUs = start + samples.size / channels * 1_000_000L / sampleRate
            return WmaBlock(start, samples)
        }
    }

    override fun close() = codec.close()

    companion object {
        /**
         * Opens [stream] as WMA, or returns null if it isn't ASF with a WMA audio stream this
         * build can decode (or the decoder library isn't in the app). Takes the stream's ownership
         * only when it returns a reader.
         */
        fun open(stream: InputStream): WmaReader? {
            if (!WmaCodec.available) return null
            return try {
                val input = StreamInput(stream)
                val header = AsfHeaders.parse(input)
                val codec = WmaCodec.open(header.audio) ?: return null
                WmaReader(header, AsfPacketReader(header, input), codec)
            } catch (e: IOException) {
                null
            }
        }
    }

    private class StreamInput(private val stream: InputStream) : AsfInput {
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            var done = 0
            while (done < length) {
                val n = stream.read(target, offset + done, length - done)
                if (n < 0) throw EOFException()
                done += n
            }
        }

        override fun skip(length: Long) {
            var left = length
            while (left > 0) {
                val n = stream.skip(left)
                if (n > 0) {
                    left -= n
                } else if (stream.read() < 0) {
                    throw EOFException()
                } else {
                    left--
                }
            }
        }
    }
}
