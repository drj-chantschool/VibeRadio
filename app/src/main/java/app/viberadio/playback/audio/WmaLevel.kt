package app.viberadio.playback.audio

import app.viberadio.playback.wma.WmaReader
import java.io.InputStream

/** Measures a WMA file with the app's own decoder, since Android has none. */
object WmaLevel {
    /** The level of the whole stream, or null if it isn't WMA this build can decode, or has no audio. */
    fun measure(stream: InputStream): TrackLevel? {
        val reader = WmaReader.open(stream) ?: return null
        reader.use {
            val meter = LoudnessMeter(it.sampleRate, it.channels)
            var any = false
            while (true) {
                val block = it.next() ?: break
                meter.add(block.samples, 0, block.samples.size / it.channels)
                any = true
            }
            return if (any) meter.result() else null
        }
    }
}
