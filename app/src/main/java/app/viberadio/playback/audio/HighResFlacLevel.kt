package app.viberadio.playback.audio

import app.viberadio.playback.flac.BitReader
import app.viberadio.playback.flac.FlacFormatException
import app.viberadio.playback.flac.FlacFrame
import app.viberadio.playback.flac.FlacFrameDecoder
import app.viberadio.playback.flac.FlacHeaders
import java.io.EOFException
import java.io.InputStream

/** Measures a FLAC deeper than 24 bits with the app's own decoder, since the platform codecs can't read one. Pure JVM. */
object HighResFlacLevel {
    /** The level of the whole stream, or null if it isn't FLAC or is damaged before any audio. */
    fun measure(stream: InputStream): TrackLevel? {
        val header = FlacHeaders.parse(stream) ?: return null
        val info = header.info
        val meter = LoudnessMeter(info.sampleRate, info.channels)
        val decoder = FlacFrameDecoder(info)
        val frame = FlacFrame(info.channels, info.maxBlockSize)
        val reader = BitReader { stream.read() }
        val scale = 1.0 / (1L shl (info.bitsPerSample - 1)).toDouble()
        var samples = FloatArray(0)
        var decoded = 0
        try {
            while (decoder.decode(reader, frame)) {
                val count = frame.blockSize * info.channels
                if (samples.size < count) samples = FloatArray(count)
                var n = 0
                for (i in 0 until frame.blockSize) {
                    for (c in 0 until info.channels) samples[n++] = (frame.samples[c][i] * scale).toFloat()
                }
                meter.add(samples, 0, frame.blockSize)
                decoded++
            }
        } catch (e: EOFException) {
            // The stream ends mid-frame: what was read is the level.
        } catch (e: FlacFormatException) {
            // Trailing tags or damage end the audio the same way.
        }
        return if (decoded == 0) null else meter.result()
    }
}
