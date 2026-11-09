package app.viberadio.playback.flac

import app.viberadio.playback.audio.HighResFlacLevel
import app.viberadio.playback.audio.LoudnessMeter
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are made by ffmpeg (`src/test/resources/flac`): each `.flac` with its samples as raw 32-bit PCM, shifted up for shallower files. */
class FlacDecoderTest {
    private fun bytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/flac/$name")!!.use { it.readBytes() }

    private fun reference(name: String): IntArray {
        val buffer = ByteBuffer.wrap(bytes("$name.i32")).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(buffer.remaining() / 4) { buffer.getInt() }
    }

    /** Every sample of the file, interleaved and scaled up to 32 bits. */
    private fun decodeAll(name: String): Pair<FlacStreamInfo, LongArray> {
        val stream = ByteArrayInputStream(bytes("$name.flac"))
        val header = FlacHeaders.parse(stream)!!
        val info = header.info
        val decoder = FlacFrameDecoder(info)
        val frame = FlacFrame(info.channels, info.maxBlockSize)
        val reader = BitReader { stream.read() }
        val out = ArrayList<Long>()
        var expectedFirst = 0L
        while (decoder.decode(reader, frame)) {
            assertEquals(expectedFirst, frame.firstSample)
            expectedFirst += frame.blockSize
            for (i in 0 until frame.blockSize) {
                for (c in 0 until info.channels) out += frame.samples[c][i] shl (32 - info.bitsPerSample)
            }
        }
        return info to out.toLongArray()
    }

    private fun assertDecodes(name: String, bits: Int, channels: Int) {
        val (info, samples) = decodeAll(name)
        assertEquals(bits, info.bitsPerSample)
        assertEquals(channels, info.channels)
        assertEquals(44_100, info.sampleRate)
        val expected = reference(name)
        assertEquals(expected.size.toLong(), info.totalSamples * channels)
        assertEquals(expected.size, samples.size)
        for (i in expected.indices) assertEquals("sample $i", expected[i].toLong(), samples[i])
    }

    @Test
    fun `decodes 32-bit stereo`() = assertDecodes("stereo32", 32, 2)

    @Test
    fun `decodes 32-bit mono`() = assertDecodes("mono32", 32, 1)

    @Test
    fun `decodes 24-bit stereo`() = assertDecodes("stereo24", 24, 2)

    @Test
    fun `decodes 16-bit stereo`() = assertDecodes("stereo16", 16, 2)

    @Test
    fun `a file that isn't FLAC has no header`() {
        assertNull(FlacHeaders.parse(ByteArrayInputStream(ByteArray(64) { 7 })))
        assertNull(FlacHeaders.parse(ByteArrayInputStream(ByteArray(2))))
    }

    @Test
    fun `reading only STREAMINFO leaves the rest of the stream`() {
        val stream = ByteArrayInputStream(bytes("stereo32.flac"))
        val header = FlacHeaders.parse(stream, streamInfoOnly = true)!!
        assertEquals(32, header.info.bitsPerSample)
        assertTrue(header.seekPoints.isEmpty())
        // "fLaC", the block header and STREAMINFO's 34 bytes are what was read.
        assertEquals(4 + 4 + 34, stream.available().let { bytes("stereo32.flac").size - it })
    }

    @Test
    fun `an ID3 tag in front is skipped`() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 5, 1, 2, 3, 4, 5)
        val header = FlacHeaders.parse(ByteArrayInputStream(tag + bytes("mono32.flac")))
        assertNotNull(header)
        assertEquals(1, header!!.info.channels)
    }

    @Test
    fun `a truncated file decodes the frames it has and then ends`() {
        val full = bytes("stereo32.flac")
        val stream = ByteArrayInputStream(full.copyOf(full.size / 2))
        val info = FlacHeaders.parse(stream)!!.info
        val decoder = FlacFrameDecoder(info)
        val frame = FlacFrame(info.channels, info.maxBlockSize)
        val reader = BitReader { stream.read() }
        var frames = 0
        try {
            while (decoder.decode(reader, frame)) frames++
        } catch (e: java.io.EOFException) {
            // The cut frame.
        }
        assertTrue(frames > 0)
    }

    @Test
    fun `level of a 32-bit file matches the meter on its reference samples`() {
        val level = HighResFlacLevel.measure(ByteArrayInputStream(bytes("stereo32.flac")))!!
        val ref = reference("stereo32")
        val meter = LoudnessMeter(44_100, 2)
        meter.add(FloatArray(ref.size) { (ref[it] / 2_147_483_648.0).toFloat() })
        val expected = meter.result()
        assertEquals(expected.loudnessLufs, level.loudnessLufs, 0.01f)
        assertEquals(expected.peak, level.peak, 0.001f)
    }

    @Test
    fun `no level for something that isn't FLAC`() {
        assertNull(HighResFlacLevel.measure(ByteArrayInputStream(ByteArray(100))))
    }
}
