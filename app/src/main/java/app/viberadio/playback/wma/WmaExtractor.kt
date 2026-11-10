package app.viberadio.playback.wma

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays WMA (ASF) files, which Android has no decoder for: reads the container ([AsfPacketReader])
 * and decodes each media object with FFmpeg's decoder ([WmaCodec]), handing the player 32-bit
 * float PCM, which skips the platform codec. Claims ASF files only when the decoder library is in
 * the app; without it WMA stays unplayable, as it is on a build that has none.
 *
 * Seeking maps the time linearly onto the file's fixed-size data packets (WMA is constant
 * bit rate), a little early so the decoder can warm up, then decodes on from there and drops what
 * comes before the target.
 */
@UnstableApi
class WmaExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var track: TrackOutput? = null
    private var header: AsfHeader? = null
    private var packets: AsfPacketReader? = null
    private var codec: WmaCodec? = null

    /** What [packets] reads: the player's current input, which is a new one after each seek. */
    private val source = InputAdapter()

    /** A seek's target: PCM before it is dropped. */
    private var skipUntilUs = 0L

    /** When the next decoded block starts, from the one before it; unset after a seek or a bad block. */
    private var cursorUs = C.TIME_UNSET
    private var pcm = ByteArray(0)
    private val sampleData = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        if (!WmaCodec.available) return false
        val lead = ByteArray(AsfHeaders.SNIFF_LENGTH)
        return try {
            input.peekFully(lead, 0, lead.size)
            AsfHeaders.isAsf(lead)
        } catch (e: EOFException) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        source.input = input
        val packets = packets ?: return start(input)
        val header = header!!
        val codec = codec!!
        while (true) {
            val obj = try {
                packets.next()
            } catch (e: AsfFormatException) {
                throw ParserException.createForMalformedContainer(e.message, e)
            } ?: return C.RESULT_END_OF_INPUT
            val samples = codec.decode(obj.data)
            if (samples == null) {
                cursorUs = C.TIME_UNSET
                continue
            }
            if (samples.isEmpty()) continue
            val objectUs = (obj.presentationMs - header.prerollMs).coerceAtLeast(0) * 1000
            emit(samples, if (cursorUs != C.TIME_UNSET) cursorUs else objectUs, header.audio)
            return Extractor.RESULT_CONTINUE
        }
    }

    private fun start(input: ExtractorInput): Int {
        val header = try {
            AsfHeaders.parse(source)
        } catch (e: AsfFormatException) {
            throw ParserException.createForMalformedContainer(e.message, e)
        }
        val audio = header.audio
        val codec = WmaCodec.open(audio)
            ?: throw ParserException.createForMalformedContainer("no decoder for WMA format 0x${audio.formatTag.toString(16)}", null)
        this.header = header
        this.codec = codec
        packets = AsfPacketReader(header, source)
        track!!.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setChannelCount(audio.channels)
                .setSampleRate(audio.sampleRate)
                .build(),
        )
        output!!.seekMap(seekMap(header))
        return Extractor.RESULT_CONTINUE
    }

    private fun seekMap(header: AsfHeader): SeekMap {
        val durationUs = header.durationUs
        if (durationUs <= 0 || header.packetCount <= 0) return SeekMap.Unseekable(if (durationUs > 0) durationUs else C.TIME_UNSET)
        return object : SeekMap {
            override fun isSeekable() = true

            override fun getDurationUs() = durationUs

            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val target = timeUs.coerceIn(0, durationUs)
                val packet = (target.toDouble() / durationUs * header.packetCount).toLong() - SEEK_BACKOFF_PACKETS
                val index = packet.coerceIn(0, header.packetCount - 1)
                return SeekMap.SeekPoints(SeekPoint(index * durationUs / header.packetCount, header.dataStart + index * header.packetSize))
            }
        }
    }

    /** One decoded block as float PCM, from [skipUntilUs] on. */
    private fun emit(samples: FloatArray, startUs: Long, audio: AsfAudio) {
        val rate = audio.sampleRate
        val channels = audio.channels
        val frames = samples.size / channels
        cursorUs = startUs + frames * 1_000_000L / rate
        val dropFrames = if (skipUntilUs > startUs) ((skipUntilUs - startUs) * rate / 1_000_000).toInt() else 0
        if (dropFrames >= frames) return
        val count = (frames - dropFrames) * channels
        val size = count * 4
        if (pcm.size < size) pcm = ByteArray(size)
        ByteBuffer.wrap(pcm, 0, size).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(samples, dropFrames * channels, count)
        sampleData.reset(pcm, size)
        track!!.sampleData(sampleData, size)
        track!!.sampleMetadata(startUs + dropFrames * 1_000_000L / rate, C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
    }

    override fun seek(position: Long, timeUs: Long) {
        skipUntilUs = timeUs
        cursorUs = C.TIME_UNSET
        val header = header ?: return
        packets?.seekTo(((position - header.dataStart) / header.packetSize).coerceAtLeast(0))
        codec?.flush()
    }

    override fun release() {
        codec?.close()
        codec = null
    }

    /** The player's stream as an [AsfInput]. */
    private class InputAdapter : AsfInput {
        lateinit var input: ExtractorInput

        override fun readFully(target: ByteArray, offset: Int, length: Int) = input.readFully(target, offset, length)

        override fun skip(length: Long) {
            var left = length
            while (left > 0) {
                val n = minOf(left, Int.MAX_VALUE.toLong()).toInt()
                input.skipFully(n)
                left -= n
            }
        }
    }

    private companion object {
        /** Packets before the estimated one, so the decoder warms up and an estimate that runs late still lands before the target. */
        const val SEEK_BACKOFF_PACKETS = 2L
    }
}
