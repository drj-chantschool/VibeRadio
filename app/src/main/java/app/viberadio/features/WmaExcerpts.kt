package app.viberadio.features

import app.viberadio.playback.wma.WmaReader
import java.io.InputStream

/** The excerpts of a WMA file, decoded with the app's own decoder (Android has none). No Android types. */
object WmaExcerpts {
    /** Blocks before an excerpt that are decoded and dropped, for the decoder to warm up. */
    private const val WARM_UP_US = 1_000_000L

    /**
     * Mono clips for [excerpts] from the file in [stream], read once front to back (only the
     * excerpts' stretches are decoded). Null if it isn't WMA this build can decode, or nothing
     * long enough came out.
     */
    fun decode(stream: InputStream, excerpts: List<ExcerptPlan.Excerpt>): List<AudioClip>? {
        val reader = WmaReader.open(stream) ?: return null
        reader.use {
            val rate = it.sampleRate
            val windows = excerpts.map { e -> e.startMs * 1000 to (e.startMs + e.lengthMs) * 1000 }
            val clips = windows.map { (start, end) -> FloatArray(((end - start) * rate / 1_000_000).toInt()) }
            val sizes = IntArray(windows.size)
            val lastEnd = windows.maxOf { (_, end) -> end }
            while (true) {
                val block = it.next { t -> t < lastEnd && windows.any { (s, e) -> t >= s - WARM_UP_US && t < e } } ?: break
                val frames = AudioClip.downmix(block.samples, block.samples.size, it.channels)
                for ((i, window) in windows.withIndex()) {
                    val (start, end) = window
                    val clip = clips[i]
                    if (sizes[i] >= clip.size) continue
                    val skip = ((start - block.startUs).coerceAtLeast(0) * rate / 1_000_000).toInt().coerceAtMost(frames.size)
                    // A block that ends before the excerpt, or starts after it, adds nothing.
                    if (block.startUs + frames.size * 1_000_000L / rate <= start || block.startUs >= end) continue
                    val take = minOf(frames.size - skip, clip.size - sizes[i]).coerceAtLeast(0)
                    if (take > 0) {
                        frames.copyInto(clip, sizes[i], skip, skip + take)
                        sizes[i] += take
                    }
                }
                if (sizes.indices.all { i -> sizes[i] >= clips[i].size }) break
            }
            val out = clips.indices.mapNotNull { i ->
                if (sizes[i] >= rate) AudioClip(clips[i].copyOf(sizes[i]), rate) else null
            }
            return out.takeIf { list -> list.isNotEmpty() }
        }
    }
}
