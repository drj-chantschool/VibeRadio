package app.viberadio.playback.audio

import kotlin.math.sqrt

/**
 * The EQ figures for mixing a podcast over music (#2, #5). While both streams play, the
 * podcast goes through a band pass and the music through a wider, partial notch, both centred
 * on the same frequency, so speech sits in a gap in the music. With one stream, both are flat.
 * Changes between the two ramp over [rampMs].
 */
data class AudioSettings(
    val podcastLowHz: Float = 1_000f,
    val podcastHighHz: Float = 4_000f,
    val musicLowHz: Float = 700f,
    val musicHighHz: Float = 5_700f,
    /** How far the notch cuts the music at its centre, in dB (negative). */
    val musicDepthDb: Float = -9f,
    val rampMs: Long = 1_500,
) {
    /** Geometric centre of the podcast band. */
    val podcastCentreHz: Float get() = sqrt(podcastLowHz * podcastHighHz)

    val musicCentreHz: Float get() = sqrt(musicLowHz * musicHighHz)

    /** Every figure in range and each band's edges in order. */
    val isValid: Boolean
        get() = podcastLowHz in HZ_RANGE && podcastHighHz in HZ_RANGE && podcastLowHz < podcastHighHz &&
            musicLowHz in HZ_RANGE && musicHighHz in HZ_RANGE && musicLowHz < musicHighHz &&
            musicDepthDb in DEPTH_RANGE && rampMs in RAMP_RANGE

    companion object {
        val DEFAULT = AudioSettings()
        val HZ_RANGE = 20f..20_000f
        val DEPTH_RANGE = -30f..0f
        val RAMP_RANGE = 0L..10_000L
    }
}
