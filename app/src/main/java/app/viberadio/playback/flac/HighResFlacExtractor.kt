package app.viberadio.playback.flac

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays FLAC files deeper than 24 bits (32-bit FLAC), which Android's own decoder can't: it
 * decodes them here ([FlacFrameDecoder]) and hands the player 32-bit float PCM, which skips
 * the platform codec. It claims only those files; every other FLAC, and every other format, goes
 * on to the stock extractors.
 *
 * Seeking uses the file's seek table where it has one, then decodes on from there, dropping
 * samples before the target. Without a table that means decoding from the start.
 */
@UnstableApi
class HighResFlacExtractor : Extractor {
    private var output: ExtractorOutput? = null
    private var track: TrackOutput? = null
    private var header: FlacHeader? = null
    private var decoder: FlacFrameDecoder? = null
    private var frame: FlacFrame? = null
    private var reader: BitReader? = null
    private var source: InputSource? = null
    private var frames = 0
    private var skipUntilSample = 0L
    private var pcm = ByteArray(0)
    private val sampleData = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        val header = try {
            FlacHeaders.parse(PeekInput(input), streamInfoOnly = true)
        } catch (e: IOException) {
            null
        }
        return header != null && header.info.bitsPerSample > FlacHeaders.PLATFORM_MAX_BITS
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val header = header ?: return start(input)
        val reader = reader ?: return C.RESULT_END_OF_INPUT
        val frame = frame!!
        try {
            if (!decoder!!.decode(reader, frame)) return C.RESULT_END_OF_INPUT
        } catch (e: FlacFormatException) {
            // Trailing tags or damage after the audio ends it; at the very start it's no FLAC.
            if (frames == 0) throw ParserException.createForMalformedContainer(e.message, e)
            return C.RESULT_END_OF_INPUT
        } catch (e: EOFException) {
            return C.RESULT_END_OF_INPUT
        }
        frames++
        emit(frame, header.info)
        return Extractor.RESULT_CONTINUE
    }

    private fun start(input: ExtractorInput): Int {
        val header = FlacHeaders.parse(InputInput(input))
            ?: throw ParserException.createForMalformedContainer("not a FLAC file", null)
        val info = header.info
        this.header = header
        decoder = FlacFrameDecoder(info)
        frame = FlacFrame(info.channels, info.maxBlockSize)
        val source = InputSource(input).also { this.source = it }
        reader = BitReader(source)
        val dataStart = input.position
        track!!.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setChannelCount(info.channels)
                .setSampleRate(info.sampleRate)
                .build(),
        )
        output!!.seekMap(seekMap(header, dataStart))
        return Extractor.RESULT_CONTINUE
    }

    private fun seekMap(header: FlacHeader, dataStart: Long): SeekMap {
        val info = header.info
        if (info.totalSamples <= 0 || info.sampleRate <= 0) return SeekMap.Unseekable(C.TIME_UNSET)
        val durationUs = timeUs(info.totalSamples, info.sampleRate)
        val points = ArrayList<SeekPoint>()
        points += SeekPoint(0, dataStart)
        for (p in header.seekPoints) {
            if (p.sample in 1 until info.totalSamples) points += SeekPoint(timeUs(p.sample, info.sampleRate), dataStart + p.offset)
        }
        return object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val point = points.lastOrNull { it.timeUs <= timeUs } ?: points.first()
                return SeekMap.SeekPoints(point)
            }
        }
    }

    /** One frame as float PCM, from [skipUntilSample] on (a seek drops what comes before its target). */
    private fun emit(frame: FlacFrame, info: FlacStreamInfo) {
        val start = frame.firstSample
        if (start + frame.blockSize <= skipUntilSample) return
        val from = (skipUntilSample - start).coerceAtLeast(0).toInt()
        val channels = info.channels
        val size = (frame.blockSize - from) * channels * 4
        if (pcm.size < size) pcm = ByteArray(size)
        val buffer = ByteBuffer.wrap(pcm, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        val scale = 1.0 / (1L shl (info.bitsPerSample - 1)).toDouble()
        for (i in from until frame.blockSize) {
            for (c in 0 until channels) buffer.putFloat((frame.samples[c][i] * scale).toFloat())
        }
        sampleData.reset(pcm, size)
        track!!.sampleData(sampleData, size)
        track!!.sampleMetadata(timeUs(start + from, info.sampleRate), C.BUFFER_FLAG_KEY_FRAME, size, 0, null)
    }

    override fun seek(position: Long, timeUs: Long) {
        val header = header ?: return
        val source = source ?: return
        source.reset()
        reader = BitReader(source)
        skipUntilSample = timeUs * header.info.sampleRate / 1_000_000
    }

    override fun release() = Unit

    private fun timeUs(sample: Long, rate: Int) = sample * 1_000_000 / rate

    /** The stream from the player, a chunk at a time. Bytes read ahead are dropped by [reset] on a seek. */
    private class InputSource(private val input: ExtractorInput) : ByteSource {
        private val buffer = ByteArray(CHUNK)
        private var at = 0
        private var end = 0

        override fun read(): Int {
            if (at == end) {
                val n = input.read(buffer, 0, CHUNK)
                if (n == C.RESULT_END_OF_INPUT) return -1
                at = 0
                end = n
            }
            return buffer[at++].toInt() and 0xFF
        }

        fun reset() {
            at = 0
            end = 0
        }

        private companion object {
            const val CHUNK = 32 * 1024
        }
    }

    private class InputInput(private val input: ExtractorInput) : FlacHeaderInput {
        override fun readFully(target: ByteArray, length: Int) = input.readFully(target, 0, length)
        override fun skip(length: Long) {
            var left = length
            while (left > 0) {
                val n = minOf(left, Int.MAX_VALUE.toLong()).toInt()
                input.skipFully(n)
                left -= n
            }
        }
    }

    private class PeekInput(private val input: ExtractorInput) : FlacHeaderInput {
        override fun readFully(target: ByteArray, length: Int) = input.peekFully(target, 0, length)
        override fun skip(length: Long) {
            var left = length
            while (left > 0) {
                val n = minOf(left, Int.MAX_VALUE.toLong()).toInt()
                input.advancePeekPosition(n)
                left -= n
            }
        }
    }
}

/** The stock extractors, with [HighResFlacExtractor] tried first. */
@UnstableApi
class HighResFlacExtractorsFactory : ExtractorsFactory {
    private val stock = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = arrayOf(HighResFlacExtractor(), *stock.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf(HighResFlacExtractor(), *stock.createExtractors(uri, responseHeaders))
}
