package app.viberadio.playback.audio

import kotlin.math.sqrt

/**
 * Settings → Audio.
 *
 * Leveling (#1): every song and episode gets one fixed gain that brings it to [targetLufs].
 *
 * Mixing a podcast over music (#2, #5): while both streams play, the music drops by
 * [mixingFactorDb], the podcast goes through a band pass and the music through a wider, partial
 * notch, both centred on the same frequency, so speech sits in a gap in the music. With one
 * stream, there is no mixing factor and both EQs are flat. Changes between the two ramp over
 * [rampMs].
 */
data class AudioSettings(
    val podcastLowHz: Float = 1_000f,
    val podcastHighHz: Float = 4_000f,
    val musicLowHz: Float = 700f,
    val musicHighHz: Float = 5_700f,
    /** How far the notch cuts the music at its centre, in dB (negative). */
    val musicDepthDb: Float = -9f,
    val rampMs: Long = 1_500,
    /** The loudness every song and episode is leveled to, in LUFS. */
    val targetLufs: Float = -18f,
    /** How far the music drops while a podcast plays over it, in dB (negative). */
    val mixingFactorDb: Float = -10f,
) {
    /** Geometric centre of the podcast band. */
    val podcastCentreHz: Float get() = sqrt(podcastLowHz * podcastHighHz)

    val musicCentreHz: Float get() = sqrt(musicLowHz * musicHighHz)

    /** Whether [other] has the same EQ figures and ramp; leveling and the mixing factor aside. */
    fun sameEqAs(other: AudioSettings): Boolean =
        copy(targetLufs = other.targetLufs, mixingFactorDb = other.mixingFactorDb) == other

    /** Every figure in range and each band's edges in order. */
    val isValid: Boolean
        get() = podcastLowHz in HZ_RANGE && podcastHighHz in HZ_RANGE && podcastLowHz < podcastHighHz &&
            musicLowHz in HZ_RANGE && musicHighHz in HZ_RANGE && musicLowHz < musicHighHz &&
            musicDepthDb in DEPTH_RANGE && rampMs in RAMP_RANGE &&
            targetLufs in TARGET_RANGE && mixingFactorDb in MIXING_RANGE

    companion object {
        val DEFAULT = AudioSettings()
        val HZ_RANGE = 20f..20_000f
        val DEPTH_RANGE = -30f..0f
        val RAMP_RANGE = 0L..10_000L
        val TARGET_RANGE = -30f..-8f
        val MIXING_RANGE = -30f..0f
    }
}
