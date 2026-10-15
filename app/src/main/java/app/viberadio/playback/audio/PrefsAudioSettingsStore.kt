package app.viberadio.playback.audio

import android.annotation.SuppressLint
import android.content.Context

/** The audio settings in a small preferences file. */
class PrefsAudioSettingsStore(context: Context) : AudioSettingsStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): AudioSettings? {
        if (!prefs.contains(KEY_PODCAST_LOW)) return null
        val d = AudioSettings.DEFAULT
        return AudioSettings(
            podcastLowHz = prefs.getFloat(KEY_PODCAST_LOW, d.podcastLowHz),
            podcastHighHz = prefs.getFloat(KEY_PODCAST_HIGH, d.podcastHighHz),
            musicLowHz = prefs.getFloat(KEY_MUSIC_LOW, d.musicLowHz),
            musicHighHz = prefs.getFloat(KEY_MUSIC_HIGH, d.musicHighHz),
            musicDepthDb = prefs.getFloat(KEY_MUSIC_DEPTH, d.musicDepthDb),
            rampMs = prefs.getLong(KEY_RAMP, d.rampMs),
            targetLufs = prefs.getFloat(KEY_TARGET, d.targetLufs),
            mixingFactorDb = prefs.getFloat(KEY_MIXING, d.mixingFactorDb),
        )
    }

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(settings: AudioSettings) {
        prefs.edit()
            .putFloat(KEY_PODCAST_LOW, settings.podcastLowHz)
            .putFloat(KEY_PODCAST_HIGH, settings.podcastHighHz)
            .putFloat(KEY_MUSIC_LOW, settings.musicLowHz)
            .putFloat(KEY_MUSIC_HIGH, settings.musicHighHz)
            .putFloat(KEY_MUSIC_DEPTH, settings.musicDepthDb)
            .putLong(KEY_RAMP, settings.rampMs)
            .putFloat(KEY_TARGET, settings.targetLufs)
            .putFloat(KEY_MIXING, settings.mixingFactorDb)
            .commit()
    }

    private companion object {
        const val FILE = "audio_settings"
        const val KEY_PODCAST_LOW = "podcast_low_hz"
        const val KEY_PODCAST_HIGH = "podcast_high_hz"
        const val KEY_MUSIC_LOW = "music_low_hz"
        const val KEY_MUSIC_HIGH = "music_high_hz"
        const val KEY_MUSIC_DEPTH = "music_depth_db"
        const val KEY_RAMP = "ramp_ms"
        const val KEY_TARGET = "target_lufs"
        const val KEY_MIXING = "mixing_factor_db"
    }
}
