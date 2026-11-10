package app.viberadio.playback.wma

import java.io.EOFException
import java.io.IOException

/**
 * Pure ASF reading for WMA files (no Android or Media3 types, so it runs in JVM tests): the
 * header ([AsfHeaders]) and the data packets ([AsfPacketReader]) cut into media objects, which
 * for WMA are the blocks the decoder takes. The decoding itself is FFmpeg's, in [WmaCodec].
 */
class AsfFormatException(message: String) : IOException(message)

/** A byte stream read front to back. */
interface AsfInput {
    /** Reads exactly [length] bytes; throws [EOFException] if the stream ends first. */
    fun readFully(target: ByteArray, offset: Int, length: Int)

    fun skip(length: Long)
}

/** The audio stream's WAVEFORMATEX. */
class AsfAudio(
    val streamNumber: Int,
    val formatTag: Int,
    val channels: Int,
    val sampleRate: Int,
    val avgBytesPerSec: Int,
    val blockAlign: Int,
    val bitsPerSample: Int,
    /** The codec's private bytes after the 18-byte WAVEFORMATEX. */
    val extra: ByteArray,
)

class AsfHeader(
    val audio: AsfAudio,
    /** Playing time, preroll taken off, or 0 if the file doesn't say. */
    val durationUs: Long,
    val prerollMs: Long,
    val packetSize: Int,
    /** File offset of the first data packet. */
    val dataStart: Long,
    /** Data packets in the file, or 0 if unknown. */
    val packetCount: Long,
)

object AsfHeaders {
    const val FORMAT_WMA1 = 0x160
    const val FORMAT_WMA2 = 0x161
    const val FORMAT_WMA_PRO = 0x162
    const val FORMAT_WMA_LOSSLESS = 0x163

    private const val HEADER_OBJECT = "75B22630-668E-11CF-A6D9-00AA0062CE6C"
    private const val DATA_OBJECT = "75B22636-668E-11CF-A6D9-00AA0062CE6C"
    private const val FILE_PROPERTIES = "8CABDCA1-A947-11CF-8EE4-00C00C205365"
    private const val STREAM_PROPERTIES = "B7DC0791-A9B7-11CF-8EE6-00C00C205365"
    private const val AUDIO_MEDIA = "F8699E40-5B4D-11CF-A8FD-00805F5C442B"

    /** Bytes at the start of a file that make it ASF. */
    const val SNIFF_LENGTH = 16

    /** Whether [bytes] (at least [SNIFF_LENGTH]) start with an ASF header object. */
    fun isAsf(bytes: ByteArray): Boolean = bytes.size >= SNIFF_LENGTH && guid(bytes, 0) == HEADER_OBJECT

    private const val MAX_HEADER_BYTES = 32L * 1024 * 1024
    private const val MAX_SKIPPED_OBJECTS = 64

    /**
     * Reads from the start of the file through the data object's header, leaving [input] at the
     * first data packet. Throws [AsfFormatException] if it isn't ASF or has no audio stream with
     * fixed-size packets.
     */
    fun parse(input: AsfInput): AsfHeader {
        val lead = ByteArray(30)
        input.readFully(lead, 0, 30)
        if (guid(lead, 0) != HEADER_OBJECT) throw AsfFormatException("not an ASF file")
        val headerSize = u64(lead, 16)
        if (headerSize < 30 || headerSize > MAX_HEADER_BYTES) throw AsfFormatException("bad ASF header size $headerSize")
        val body = ByteArray((headerSize - 30).toInt())
        input.readFully(body, 0, body.size)

        var audio: AsfAudio? = null
        var playDurationUnits = 0L
        var prerollMs = 0L
        var maxPacket = 0
        var minPacket = 0
        var filePackets = 0L
        var at = 0
        while (at + 24 <= body.size) {
            val id = guid(body, at)
            val size = u64(body, at + 16)
            if (size < 24 || at + size > body.size) break
            val start = at + 24
            val end = at + size.toInt()
            when (id) {
                FILE_PROPERTIES -> if (end - start >= 80) {
                    filePackets = u64(body, start + 32)
                    playDurationUnits = u64(body, start + 40)
                    prerollMs = u64(body, start + 56)
                    minPacket = u32(body, start + 68).toInt()
                    maxPacket = u32(body, start + 72).toInt()
                }
                STREAM_PROPERTIES -> if (audio == null && end - start >= 54 && guid(body, start) == AUDIO_MEDIA) {
                    audio = audioOf(body, start, end)
                }
            }
            at = end
        }
        audio ?: throw AsfFormatException("no audio stream")
        if (minPacket <= 0 || minPacket != maxPacket) throw AsfFormatException("variable-size ASF packets")

        // The data object follows, possibly after other objects.
        var dataStart = headerSize
        val objectHeader = ByteArray(24)
        var skipped = 0
        while (true) {
            input.readFully(objectHeader, 0, 24)
            val id = guid(objectHeader, 0)
            val size = u64(objectHeader, 16)
            if (id == DATA_OBJECT) {
                val rest = ByteArray(26)
                input.readFully(rest, 0, 26)
                val total = u64(rest, 16)
                dataStart += 50
                val packets = if (total > 0) total else filePackets
                val durationUs = (playDurationUnits / 10 - prerollMs * 1000).coerceAtLeast(0)
                return AsfHeader(audio, durationUs, prerollMs, minPacket, dataStart, packets)
            }
            if (size < 24 || ++skipped > MAX_SKIPPED_OBJECTS) throw AsfFormatException("no ASF data object")
            input.skip(size - 24)
            dataStart += size
        }
    }

    private fun audioOf(b: ByteArray, start: Int, end: Int): AsfAudio? {
        // Stream type, error correction type (16 each), time offset (8), type-specific length (4),
        // error correction length (4), flags (2), reserved (4), then the type-specific data.
        val typeLength = u32(b, start + 40).toInt()
        val flags = u16(b, start + 48)
        val wave = start + 54
        if (typeLength < 18 || wave + typeLength > end) return null
        val extraSize = u16(b, wave + 16).coerceAtMost(typeLength - 18)
        return AsfAudio(
            streamNumber = flags and 0x7F,
            formatTag = u16(b, wave),
            channels = u16(b, wave + 2),
            sampleRate = u32(b, wave + 4).toInt(),
            avgBytesPerSec = u32(b, wave + 8).toInt(),
            blockAlign = u16(b, wave + 12),
            bitsPerSample = u16(b, wave + 14),
            extra = b.copyOfRange(wave + 18, wave + 18 + extraSize),
        )
    }

    /** The GUID at [at] as text. The first three fields are little-endian on the wire, the rest as written. */
    internal fun guid(b: ByteArray, at: Int): String {
        fun hex(from: Int, to: Int, reverse: Boolean): String {
            val range = if (reverse) (to - 1 downTo from) else (from until to)
            return range.joinToString("") { "%02X".format(b[at + it].toInt() and 0xFF) }
        }
        return "${hex(0, 4, true)}-${hex(4, 6, true)}-${hex(6, 8, true)}-${hex(8, 10, false)}-${hex(10, 16, false)}"
    }

    internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    internal fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    internal fun u64(b: ByteArray, at: Int): Long = u32(b, at) or (u32(b, at + 4) shl 32)
}

/** One media object of the audio stream: a block for the decoder. */
class AsfObject(val presentationMs: Long, val data: ByteArray)

/**
 * Reads data packets and reassembles the audio stream's media objects, which may be fragmented
 * across payloads and packets. Packets of other streams are ignored. After a seek, [seekTo]
 * drops any half-built object, and an object that starts before the seek point is dropped.
 */
class AsfPacketReader(private val header: AsfHeader, private val input: AsfInput) {
    private val packet = ByteArray(header.packetSize)
    private val ready = ArrayDeque<AsfObject>()
    private var nextPacket = 0L

    private var building: ByteArray? = null
    private var buildingNumber = -1L
    private var buildingFilled = 0
    private var buildingTimeMs = 0L

    /** The next object, or null at the end of the data. */
    fun next(): AsfObject? {
        while (ready.isEmpty()) {
            if (header.packetCount > 0 && nextPacket >= header.packetCount) return null
            try {
                input.readFully(packet, 0, packet.size)
            } catch (e: EOFException) {
                return null
            }
            nextPacket++
            parsePacket()
        }
        return ready.removeFirst()
    }

    /** [input] now continues at data packet number [packet] (file offset dataStart + packet × packetSize). */
    fun seekTo(packet: Long) {
        nextPacket = packet
        ready.clear()
        building = null
    }

    private fun parsePacket() {
        val buf = packet
        val limit = buf.size
        var p = 0

        fun need(n: Int) {
            if (p + n > limit) throw AsfFormatException("ASF packet overrun")
        }

        fun u8(): Int {
            need(1)
            return buf[p++].toInt() and 0xFF
        }

        fun value(type: Int): Long = when (type) {
            0 -> 0L
            1 -> u8().toLong()
            2 -> {
                need(2)
                AsfHeaders.u16(buf, p).toLong().also { p += 2 }
            }
            else -> {
                need(4)
                AsfHeaders.u32(buf, p).also { p += 4 }
            }
        }

        var flags = u8()
        if (flags and 0x80 != 0) {
            p += flags and 0x0F
            flags = u8()
        }
        val property = u8()
        val multiple = flags and 1 != 0
        value((flags shr 5) and 3) // packet length
        value((flags shr 1) and 3) // sequence
        val padding = value((flags shr 3) and 3).toInt()
        need(6)
        p += 6 // send time, duration
        var payloads = 1
        var payloadLengthType = 0
        if (multiple) {
            val pf = u8()
            payloads = pf and 0x3F
            payloadLengthType = (pf shr 6) and 3
        }
        val end = (limit - padding).coerceIn(p, limit)
        repeat(payloads) {
            val streamByte = u8()
            val number = value((property shr 4) and 3)
            val offset = value((property shr 2) and 3)
            val replicatedLength = value(property and 3).toInt()
            need(replicatedLength)
            val replicated = p
            p += replicatedLength
            val length = if (multiple) value(payloadLengthType).toInt() else end - p
            if (length < 0 || p + length > limit) throw AsfFormatException("ASF payload overrun")
            if (streamByte and 0x7F == header.audio.streamNumber && replicatedLength >= 8) {
                addPayload(
                    number,
                    offset,
                    size = AsfHeaders.u32(buf, replicated).toInt(),
                    timeMs = AsfHeaders.u32(buf, replicated + 4),
                    from = p,
                    length = length,
                )
            }
            p += length
        }
    }

    private fun addPayload(number: Long, offset: Long, size: Int, timeMs: Long, from: Int, length: Int) {
        if (size <= 0 || size > MAX_OBJECT_BYTES) throw AsfFormatException("bad media object size $size")
        if (offset == 0L) {
            building = ByteArray(size)
            buildingNumber = number
            buildingFilled = 0
            buildingTimeMs = timeMs
        }
        val target = building ?: return
        if (number != buildingNumber || offset != buildingFilled.toLong() || buildingFilled + length > target.size) {
            building = null
            return
        }
        System.arraycopy(packet, from, target, buildingFilled, length)
        buildingFilled += length
        if (buildingFilled == target.size) {
            ready.addLast(AsfObject(buildingTimeMs, target))
            building = null
        }
    }

    private companion object {
        const val MAX_OBJECT_BYTES = 1 shl 20
    }
}
