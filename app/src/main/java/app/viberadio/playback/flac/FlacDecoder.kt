package app.viberadio.playback.flac

import java.io.EOFException
import java.io.InputStream

/** The stream isn't FLAC, or a frame is damaged. */
class FlacFormatException(message: String) : Exception(message)

/** The STREAMINFO block: what every frame of the file shares. [totalSamples] is 0 if unknown. */
data class FlacStreamInfo(
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
)

/** A SEEKTABLE entry: the frame holding [sample] starts [offset] bytes after the first frame. */
data class FlacSeekPoint(val sample: Long, val offset: Long)

/** What precedes the audio: [bytes] is the length of everything before the first frame. */
class FlacHeader(val info: FlacStreamInfo, val seekPoints: List<FlacSeekPoint>, val bytes: Long)

/** Sequential bytes; [read] returns -1 at the end. */
fun interface ByteSource {
    fun read(): Int
}

/** Whole-block reads and skips for the header, which [FlacHeaders] parses. Both throw [EOFException] when short. */
interface FlacHeaderInput {
    fun readFully(target: ByteArray, length: Int)
    fun skip(length: Long)
}

object FlacHeaders {
    /** FLAC is the only format this decoder is for: it takes over only above what Android decodes. */
    const val PLATFORM_MAX_BITS = 24

    /**
     * Reads the header (an ID3v2 tag, `fLaC`, then metadata blocks up to the first frame), or
     * returns null if the stream isn't FLAC. With [streamInfoOnly] it stops after STREAMINFO (the
     * seek table is left empty and [FlacHeader.bytes] is not the audio's start).
     */
    fun parse(input: FlacHeaderInput, streamInfoOnly: Boolean = false): FlacHeader? {
        val word = ByteArray(10)
        var consumed = 0L
        try {
            input.readFully(word, 4)
            consumed += 4
            if (word[0] == 'I'.code.toByte() && word[1] == 'D'.code.toByte() && word[2] == '3'.code.toByte()) {
                input.readFully(word, 6)
                consumed += 6
                // word now holds the minor version, the flags and the syncsafe tag size.
                val size = (word[2].toInt() and 0x7F shl 21) or (word[3].toInt() and 0x7F shl 14) or
                    (word[4].toInt() and 0x7F shl 7) or (word[5].toInt() and 0x7F)
                val footer = if (word[1].toInt() and 0x10 != 0) 10 else 0
                input.skip(size.toLong() + footer)
                consumed += size + footer
                input.readFully(word, 4)
                consumed += 4
            }
            if (word[0] != 'f'.code.toByte() || word[1] != 'L'.code.toByte() ||
                word[2] != 'a'.code.toByte() || word[3] != 'C'.code.toByte()
            ) {
                return null
            }
            var info: FlacStreamInfo? = null
            var seekPoints = emptyList<FlacSeekPoint>()
            while (true) {
                input.readFully(word, 4)
                consumed += 4
                val last = word[0].toInt() and 0x80 != 0
                val type = word[0].toInt() and 0x7F
                val length = (word[1].toInt() and 0xFF shl 16) or (word[2].toInt() and 0xFF shl 8) or (word[3].toInt() and 0xFF)
                when {
                    type == STREAMINFO && length >= STREAMINFO_BYTES -> {
                        val body = ByteArray(length)
                        input.readFully(body, length)
                        info = streamInfo(body)
                        if (streamInfoOnly) return FlacHeader(info, emptyList(), consumed)
                    }
                    type == SEEKTABLE -> {
                        val body = ByteArray(length)
                        input.readFully(body, length)
                        seekPoints = seekTable(body)
                    }
                    else -> input.skip(length.toLong())
                }
                consumed += length
                if (last) break
            }
            return FlacHeader(info ?: return null, seekPoints, consumed)
        } catch (e: EOFException) {
            return null
        }
    }

    fun parse(stream: InputStream, streamInfoOnly: Boolean = false): FlacHeader? = parse(object : FlacHeaderInput {
        override fun readFully(target: ByteArray, length: Int) {
            var done = 0
            while (done < length) {
                val n = stream.read(target, done, length - done)
                if (n < 0) throw EOFException()
                done += n
            }
        }

        override fun skip(length: Long) {
            var left = length
            while (left > 0) {
                val n = stream.skip(left)
                if (n <= 0) {
                    if (stream.read() < 0) throw EOFException()
                    left--
                } else {
                    left -= n
                }
            }
        }
    }, streamInfoOnly)

    private fun streamInfo(b: ByteArray): FlacStreamInfo {
        fun u(i: Int) = b[i].toLong() and 0xFF
        val packed = (u(10) shl 56) or (u(11) shl 48) or (u(12) shl 40) or (u(13) shl 32) or
            (u(14) shl 24) or (u(15) shl 16) or (u(16) shl 8) or u(17)
        return FlacStreamInfo(
            minBlockSize = (u(0) shl 8 or u(1)).toInt(),
            maxBlockSize = (u(2) shl 8 or u(3)).toInt(),
            sampleRate = (packed ushr 44).toInt(),
            channels = ((packed ushr 41) and 7).toInt() + 1,
            bitsPerSample = ((packed ushr 36) and 31).toInt() + 1,
            totalSamples = packed and ((1L shl 36) - 1),
        )
    }

    private fun seekTable(b: ByteArray): List<FlacSeekPoint> {
        fun long(i: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[i + k].toLong() and 0xFF)
            return v
        }
        val points = ArrayList<FlacSeekPoint>()
        var i = 0
        while (i + SEEKPOINT_BYTES <= b.size) {
            val sample = long(i)
            // Placeholders are all ones, which reads as -1.
            if (sample >= 0) points += FlacSeekPoint(sample, long(i + 8))
            i += SEEKPOINT_BYTES
        }
        return points.sortedBy { it.sample }
    }

    private const val STREAMINFO = 0
    private const val SEEKTABLE = 3
    private const val STREAMINFO_BYTES = 34
    private const val SEEKPOINT_BYTES = 18
}

/** MSB-first bits from a [ByteSource], up to 56 at a time. Reads only the bytes it needs, so it is byte-aligned after any whole-byte read. */
class BitReader(private val source: ByteSource) {
    private var acc = 0L
    private var count = 0

    /** True if another byte is there (call at a byte boundary, between frames). */
    fun hasMore(): Boolean {
        if (count > 0) return true
        val b = source.read()
        if (b < 0) return false
        acc = b.toLong()
        count = 8
        return true
    }

    fun readBits(n: Int): Long {
        if (n == 0) return 0
        while (count < n) {
            val b = source.read()
            if (b < 0) throw EOFException()
            acc = (acc shl 8) or b.toLong()
            count += 8
        }
        count -= n
        val value = (acc ushr count) and ((1L shl n) - 1)
        acc = acc and ((1L shl count) - 1)
        return value
    }

    fun readSigned(n: Int): Long {
        if (n == 0) return 0
        val v = readBits(n)
        return (v shl (64 - n)) shr (64 - n)
    }

    /** Zeros before the next one bit, which is consumed too. */
    fun readUnary(): Int {
        var zeros = 0
        while (true) {
            if (count == 0) {
                val b = source.read()
                if (b < 0) throw EOFException()
                acc = b.toLong()
                count = 8
            }
            if (acc == 0L) {
                zeros += count
                count = 0
                continue
            }
            val leading = count - (64 - java.lang.Long.numberOfLeadingZeros(acc))
            zeros += leading
            count -= leading + 1
            acc = acc and ((1L shl count) - 1)
            return zeros
        }
    }

    /** Drops the rest of the current byte. */
    fun alignToByte() {
        count = 0
        acc = 0
    }
}

/** One decoded frame: [samples] are per channel, valid for the first [blockSize]. */
class FlacFrame(channels: Int, maxBlockSize: Int) {
    var samples: Array<LongArray> = Array(channels) { LongArray(maxBlockSize) }
        private set
    var blockSize = 0
        internal set

    /** Index of this frame's first sample in the stream. */
    var firstSample = 0L
        internal set

    internal fun ensureCapacity(blockSize: Int) {
        if (samples[0].size < blockSize) samples = Array(samples.size) { LongArray(blockSize) }
    }
}

/**
 * Decodes FLAC frames (RFC 9639) of any bit depth to 32, in the frame's own integer scale. Pure.
 * Checksums aren't verified: a damaged frame fails on its own structure or is just noise.
 */
class FlacFrameDecoder(private val info: FlacStreamInfo) {
    private val coefficients = IntArray(MAX_LPC_ORDER)

    /** The frame the reader is at, or false at a clean end. Throws [FlacFormatException] or [EOFException] on damage. */
    fun decode(reader: BitReader, frame: FlacFrame): Boolean {
        reader.alignToByte()
        if (!reader.hasMore()) return false
        if (reader.readBits(14) != SYNC) throw FlacFormatException("lost frame sync")
        reader.readBits(1)
        val variable = reader.readBits(1) == 1L
        val blockCode = reader.readBits(4).toInt()
        val rateCode = reader.readBits(4).toInt()
        val channelCode = reader.readBits(4).toInt()
        val sizeCode = reader.readBits(3).toInt()
        reader.readBits(1)
        val number = readUtf8(reader)
        val blockSize = when (blockCode) {
            0 -> throw FlacFormatException("reserved block size")
            1 -> 192
            in 2..5 -> 576 shl (blockCode - 2)
            6 -> reader.readBits(8).toInt() + 1
            7 -> reader.readBits(16).toInt() + 1
            else -> 256 shl (blockCode - 8)
        }
        when (rateCode) {
            12 -> reader.readBits(8)
            13, 14 -> reader.readBits(16)
        }
        reader.readBits(8) // header CRC
        val bits = when (sizeCode) {
            0 -> info.bitsPerSample
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            7 -> 32
            else -> throw FlacFormatException("reserved sample size")
        }
        val channels = if (channelCode < 8) channelCode + 1 else if (channelCode <= 10) 2 else throw FlacFormatException("reserved channels")
        if (channels != info.channels) throw FlacFormatException("channel count changed")

        frame.ensureCapacity(blockSize)
        frame.blockSize = blockSize
        frame.firstSample = if (variable) number else number * info.minBlockSize
        val out = frame.samples
        for (c in 0 until channels) {
            // The side channel of a stereo pair carries one more bit.
            val extra = if ((channelCode == LEFT_SIDE && c == 1) || (channelCode == SIDE_RIGHT && c == 0) || (channelCode == MID_SIDE && c == 1)) 1 else 0
            subframe(reader, out[c], blockSize, bits + extra)
        }
        when (channelCode) {
            LEFT_SIDE -> for (i in 0 until blockSize) out[1][i] = out[0][i] - out[1][i]
            SIDE_RIGHT -> for (i in 0 until blockSize) out[0][i] = out[0][i] + out[1][i]
            MID_SIDE -> for (i in 0 until blockSize) {
                val side = out[1][i]
                val mid = (out[0][i] shl 1) or (side and 1)
                out[0][i] = (mid + side) shr 1
                out[1][i] = (mid - side) shr 1
            }
        }
        reader.alignToByte()
        reader.readBits(16) // frame CRC
        return true
    }

    private fun readUtf8(reader: BitReader): Long {
        val first = reader.readBits(8).toInt()
        val extra = when {
            first < 0x80 -> 0
            first < 0xC0 -> throw FlacFormatException("bad frame number")
            first < 0xE0 -> 1
            first < 0xF0 -> 2
            first < 0xF8 -> 3
            first < 0xFC -> 4
            first < 0xFE -> 5
            first == 0xFE -> 6
            else -> throw FlacFormatException("bad frame number")
        }
        var value = if (extra == 0) first.toLong() else (first and (0x3F shr extra)).toLong()
        repeat(extra) { value = (value shl 6) or (reader.readBits(8) and 0x3F) }
        return value
    }

    private fun subframe(reader: BitReader, x: LongArray, blockSize: Int, frameBits: Int) {
        if (reader.readBits(1) != 0L) throw FlacFormatException("bad subframe")
        val type = reader.readBits(6).toInt()
        var wasted = 0
        if (reader.readBits(1) == 1L) wasted = reader.readUnary() + 1
        val bits = frameBits - wasted
        if (bits <= 0) throw FlacFormatException("bad wasted bits")
        when {
            type == 0 -> x.fill(reader.readSigned(bits), 0, blockSize)
            type == 1 -> for (i in 0 until blockSize) x[i] = reader.readSigned(bits)
            type in 8..15 -> {
                val order = type - 8
                if (order > 4 || order > blockSize) throw FlacFormatException("bad fixed order")
                for (i in 0 until order) x[i] = reader.readSigned(bits)
                residual(reader, x, blockSize, order)
                restoreFixed(x, blockSize, order)
            }
            type >= 32 -> {
                val order = type - 31
                if (order > blockSize) throw FlacFormatException("bad LPC order")
                for (i in 0 until order) x[i] = reader.readSigned(bits)
                val precision = reader.readBits(4).toInt() + 1
                if (precision == 16) throw FlacFormatException("bad LPC precision")
                val shift = reader.readSigned(5).toInt()
                if (shift < 0) throw FlacFormatException("negative LPC shift")
                for (j in 0 until order) coefficients[j] = reader.readSigned(precision).toInt()
                residual(reader, x, blockSize, order)
                for (i in order until blockSize) {
                    var sum = 0L
                    for (j in 0 until order) sum += coefficients[j].toLong() * x[i - 1 - j]
                    x[i] += sum shr shift
                }
            }
            else -> throw FlacFormatException("reserved subframe type")
        }
        if (wasted > 0) for (i in 0 until blockSize) x[i] = x[i] shl wasted
    }

    /** Reads the residual into x[order until blockSize]. */
    private fun residual(reader: BitReader, x: LongArray, blockSize: Int, order: Int) {
        val method = reader.readBits(2).toInt()
        if (method > 1) throw FlacFormatException("reserved residual coding")
        val partitionOrder = reader.readBits(4).toInt()
        val paramBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 15 else 31
        val partitions = 1 shl partitionOrder
        if (blockSize shr partitionOrder < order || blockSize and (partitions - 1) != 0) throw FlacFormatException("bad partitions")
        var at = order
        for (p in 0 until partitions) {
            val end = at + (blockSize shr partitionOrder) - (if (p == 0) order else 0)
            val k = reader.readBits(paramBits).toInt()
            if (k == escape) {
                val n = reader.readBits(5).toInt()
                while (at < end) x[at++] = reader.readSigned(n)
            } else {
                while (at < end) {
                    val u = (reader.readUnary().toLong() shl k) or reader.readBits(k)
                    x[at++] = (u ushr 1) xor -(u and 1)
                }
            }
        }
    }

    private fun restoreFixed(x: LongArray, blockSize: Int, order: Int) {
        when (order) {
            1 -> for (i in 1 until blockSize) x[i] += x[i - 1]
            2 -> for (i in 2 until blockSize) x[i] += 2 * x[i - 1] - x[i - 2]
            3 -> for (i in 3 until blockSize) x[i] += 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]
            4 -> for (i in 4 until blockSize) x[i] += 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]
        }
    }

    private companion object {
        const val SYNC = 0x3FFEL
        const val LEFT_SIDE = 8
        const val SIDE_RIGHT = 9
        const val MID_SIDE = 10
        const val MAX_LPC_ORDER = 32
    }
}
