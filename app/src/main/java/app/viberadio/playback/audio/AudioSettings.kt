package app.viberadio.playback.audio

/**
 * Settings → Audio.
 *
 * Leveling (#1): every song and episode gets one fixed gain that brings it to [targetLufs].
 *
 * Tone: each stream has its own ten-band tone EQ ([musicTone], [podcastTone]), flat by default,
 * after its gain.
 *
 * Mixing a podcast over music (#2, #5): while both streams play, the music drops by
 * [mixingFactorDb] and the separation EQ ([SeparationSettings], chosen separately) pulls the two
 * apart in frequency, so speech sits in a gap in the music. With one stream, there is no mixing
 * factor and the separation is flat. Changes between the two ramp over [rampMs].
 */
data class AudioSettings(
    val rampMs: Long = 1_500,
    /** The loudness every song and episode is leveled to, in LUFS. */
    val targetLufs: Float = -18f,
    /** How far the music drops while a podcast plays over it, in dB (negative). */
    val mixingFactorDb: Float = -10f,
    /** The music stream's tone EQ (the session player, so also episodes on podcast-only channels and music podcasts). */
    val musicTone: ToneCurve = ToneCurve.FLAT,
    /** The podcast stream's tone EQ (podcasts played over music). */
    val podcastTone: ToneCurve = ToneCurve.FLAT,
) {
    fun tone(stream: AudioStream): ToneCurve = when (stream) {
        AudioStream.MUSIC -> musicTone
        AudioStream.PODCAST -> podcastTone
    }

    fun withTone(stream: AudioStream, curve: ToneCurve): AudioSettings = when (stream) {
        AudioStream.MUSIC -> copy(musicTone = curve)
        AudioStream.PODCAST -> copy(podcastTone = curve)
    }

    /** These settings with the figures (target, mixing factor, ramp) at their defaults; the tone curves stay. */
    fun figuresRestored(): AudioSettings = DEFAULT.copy(musicTone = musicTone, podcastTone = podcastTone)

    /** Both tone curves flat; the figures stay. */
    fun toneRestored(): AudioSettings = copy(musicTone = ToneCurve.FLAT, podcastTone = ToneCurve.FLAT)

    /** Every figure in range and both tone curves valid. */
    val isValid: Boolean
        get() = rampMs in RAMP_RANGE && targetLufs in TARGET_RANGE && mixingFactorDb in MIXING_RANGE &&
            musicTone.isValid && podcastTone.isValid

    companion object {
        val DEFAULT = AudioSettings()
        val RAMP_RANGE = 0L..10_000L
        val TARGET_RANGE = -30f..-8f
        val MIXING_RANGE = -30f..0f
    }
}
