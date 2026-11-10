package app.viberadio.playback.wma

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FFmpeg's WMA decoders, through `libvrwma.so` (tools/ffmpeg, built by CI into the APK). The
 * library is optional: without it [available] is false and WMA files stay unplayable, as before.
 */
internal object WmaNative {
    val available: Boolean = try {
        System.loadLibrary("vrwma")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic external fun supported(formatTag: Int): Boolean

    @JvmStatic external fun open(
        formatTag: Int,
        channels: Int,
        sampleRate: Int,
        bitRate: Int,
        blockAlign: Int,
        bitsPerSample: Int,
        extra: ByteArray?,
    ): Long

    /** Float PCM (interleaved, native byte order) into [out]; its sample count, 0 for none yet, -1 on a bad block, -2 if [out] is too small. */
    @JvmStatic external fun decode(handle: Long, data: ByteArray, size: Int, out: ByteBuffer): Int

    @JvmStatic external fun flush(handle: Long)

    @JvmStatic external fun close(handle: Long)
}

/** One open decoder for one file. Not thread-safe. */
class WmaCodec private constructor(private var handle: Long, outBytes: Int) : AutoCloseable {
    private val out: ByteBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())

    /**
     * Decodes one media object. Returns interleaved float PCM, empty if it made none yet, or null
     * if the block was bad (the caller carries on with the next).
     */
    fun decode(data: ByteArray): FloatArray? {
        check(handle != 0L) { "closed" }
        val n = WmaNative.decode(handle, data, data.size, out)
        if (n < 0) return null
        val samples = FloatArray(n)
        out.clear()
        out.asFloatBuffer().get(samples)
        return samples
    }

    /** Forgets what came before, after a seek. */
    fun flush() {
        if (handle != 0L) WmaNative.flush(handle)
    }

    override fun close() {
        if (handle != 0L) WmaNative.close(handle)
        handle = 0
    }

    companion object {
        // Room for a block's frames: v1/v2 make 2048 samples a frame, a few frames a block. Pro and lossless
        // frames reach 32768 samples × 8 channels.
        private const val OUT_BYTES = 1024 * 1024
        private const val OUT_BYTES_LARGE = 8 * 1024 * 1024

        /** Whether this build can decode WMA at all. */
        val available: Boolean get() = WmaNative.available

        /** Whether this build can decode [formatTag] ([AsfHeaders] `FORMAT_*`). */
        fun supports(formatTag: Int): Boolean = WmaNative.available && WmaNative.supported(formatTag)

        /** The decoder for [audio], or null if it can't be opened. */
        fun open(audio: AsfAudio): WmaCodec? {
            if (!supports(audio.formatTag)) return null
            val handle = WmaNative.open(
                audio.formatTag,
                audio.channels,
                audio.sampleRate,
                audio.avgBytesPerSec * 8,
                audio.blockAlign,
                audio.bitsPerSample,
                audio.extra,
            )
            val large = audio.formatTag == AsfHeaders.FORMAT_WMA_PRO || audio.formatTag == AsfHeaders.FORMAT_WMA_LOSSLESS
            return if (handle == 0L) null else WmaCodec(handle, if (large) OUT_BYTES_LARGE else OUT_BYTES)
        }
    }
}
