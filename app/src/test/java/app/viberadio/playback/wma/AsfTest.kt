package app.viberadio.playback.wma

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fixtures are made by ffmpeg (`src/test/resources/wma`): each `.wma` with the media objects it
 * holds, back to back, in a `.packets` file (`ffmpeg -c copy -f data`), so the objects this parser
 * reassembles must add up to exactly those bytes. `stereo-v2-fragmented` uses 512-byte packets for
 * 743-byte objects, so every object spans packets.
 */
class AsfTest {
    private val names = listOf("stereo-v2", "stereo-v2-fragmented", "stereo-v1")

    private fun bytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/wma/$name")!!.use { it.readBytes() }

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
                if (n <= 0) throw EOFException()
                left -= n
            }
        }
    }

    private fun open(name: String, from: ByteArray = bytes("$name.wma")): Pair<AsfHeader, StreamInput> {
        val input = StreamInput(from.inputStream())
        return AsfHeaders.parse(input) to input
    }

    private fun readAll(reader: AsfPacketReader): List<AsfObject> {
        val out = ArrayList<AsfObject>()
        while (true) out += reader.next() ?: return out
    }

    @Test
    fun headerDescribesTheAudio() {
        val (v2, _) = open("stereo-v2")
        assertEquals(AsfHeaders.FORMAT_WMA2, v2.audio.formatTag)
        assertEquals(2, v2.audio.channels)
        assertEquals(44_100, v2.audio.sampleRate)
        assertEquals(743, v2.audio.blockAlign)
        assertEquals(10, v2.audio.extra.size)
        assertEquals(3200, v2.packetSize)
        assertEquals(1_500_000L, v2.durationUs)

        val (v1, _) = open("stereo-v1")
        assertEquals(AsfHeaders.FORMAT_WMA1, v1.audio.formatTag)
        assertEquals(557, v1.audio.blockAlign)
        assertEquals(4, v1.audio.extra.size)

        val (fragmented, _) = open("stereo-v2-fragmented")
        assertEquals(512, fragmented.packetSize)
    }

    @Test
    fun objectsAddUpToTheFilesAudio() {
        for (name in names) {
            val (header, input) = open(name)
            val objects = readAll(AsfPacketReader(header, input))
            val expected = bytes("$name.packets")
            assertTrue(name, objects.all { it.data.size == header.audio.blockAlign })
            assertEquals(name, expected.size / header.audio.blockAlign, objects.size)
            val joined = ByteArrayOutputStream().also { out -> objects.forEach { out.write(it.data) } }.toByteArray()
            assertArrayEquals(name, expected, joined)
        }
    }

    @Test
    fun objectTimesRunForwardFromThePreroll() {
        val (header, input) = open("stereo-v2")
        val objects = readAll(AsfPacketReader(header, input))
        assertEquals(header.prerollMs, objects.first().presentationMs)
        assertTrue(objects.zipWithNext().all { (a, b) -> b.presentationMs >= a.presentationMs })
        // 1.5 s of audio, so the last starts within it.
        assertTrue(objects.last().presentationMs - header.prerollMs < 1_500)
    }

    @Test
    fun seekingContinuesFromThatPacket() {
        val name = "stereo-v2-fragmented"
        val file = bytes("$name.wma")
        val (header, _) = open(name)
        val all = readAll(AsfPacketReader(header, StreamInput(file.copyOfRange(header.dataStart.toInt(), file.size).inputStream())))
        val packet = 30L
        val from = header.dataStart.toInt() + (packet * header.packetSize).toInt()
        val reader = AsfPacketReader(header, StreamInput(file.copyOfRange(from, file.size).inputStream()))
        reader.seekTo(packet)
        val tail = readAll(reader)
        assertTrue(tail.isNotEmpty())
        // An object that began in an earlier packet is dropped; everything after it is whole and in order.
        val firstMatch = all.indexOfFirst { it.presentationMs == tail.first().presentationMs }
        assertTrue(firstMatch > 0)
        assertEquals(all.size - firstMatch, tail.size)
        for ((i, o) in tail.withIndex()) assertArrayEquals(all[firstMatch + i].data, o.data)
    }

    @Test
    fun detectsAsfByItsHeaderGuid() {
        assertTrue(AsfHeaders.isAsf(bytes("stereo-v2.wma").copyOf(AsfHeaders.SNIFF_LENGTH)))
        assertFalse(AsfHeaders.isAsf(ByteArray(AsfHeaders.SNIFF_LENGTH)))
        assertFalse(AsfHeaders.isAsf(ByteArray(4)))
    }

    @Test
    fun otherFilesAreRefused() {
        try {
            AsfHeaders.parse(StreamInput(ByteArray(200) { 7 }.inputStream()))
            fail("not ASF")
        } catch (e: AsfFormatException) {
            // expected
        }
    }

    @Test
    fun aTruncatedFileEndsInsteadOfLooping() {
        val file = bytes("stereo-v2.wma")
        val (header, _) = open("stereo-v2")
        val cut = file.copyOf(header.dataStart.toInt() + 3200 + 1000)
        val (h, input) = open("stereo-v2", cut)
        val objects = readAll(AsfPacketReader(h, input))
        assertTrue(objects.size in 1..10)
    }

    @Test
    fun withoutTheDecoderLibraryNothingOpens() {
        // JVM tests have no libvrwma, so WMA is declined and the app falls back to skipping the file.
        if (WmaCodec.available) return
        assertNull(WmaReader.open(bytes("stereo-v2.wma").inputStream()))
    }
}
