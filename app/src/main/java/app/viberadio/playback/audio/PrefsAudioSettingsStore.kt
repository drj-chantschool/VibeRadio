package app.viberadio.playback.audio

import android.annotation.SuppressLint
import android.content.Context

/**
 * The audio settings in a small preferences file. Builds before the separation EQ became its own
 * module also kept the band pass and notch figures here; [PrefsSeparationStore] takes those over.
 */
class PrefsAudioSettingsStore(context: Context) : AudioSettingsStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): AudioSettings? {
        if (!prefs.contains(KEY_TARGET)) return null
        val d = AudioSettings.DEFAULT
        return AudioSettings(
            rampMs = prefs.getLong(KEY_RAMP, d.rampMs),
            targetLufs = prefs.getFloat(KEY_TARGET, d.targetLufs),
            mixingFactorDb = prefs.getFloat(KEY_MIXING, d.mixingFactorDb),
            musicTone = curve(prefs.getString(KEY_MUSIC_TONE, null)),
            podcastTone = curve(prefs.getString(KEY_PODCAST_TONE, null)),
        )
    }

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(settings: AudioSettings) {
        prefs.edit()
            .putLong(KEY_RAMP, settings.rampMs)
            .putFloat(KEY_TARGET, settings.targetLufs)
            .putFloat(KEY_MIXING, settings.mixingFactorDb)
            .putString(KEY_MUSIC_TONE, settings.musicTone.bandsDb.joinToString(","))
            .putString(KEY_PODCAST_TONE, settings.podcastTone.bandsDb.joinToString(","))
            .commit()
    }

    /** A saved curve, or flat if there is none or it doesn't read back as ten valid bands. */
    private fun curve(text: String?): ToneCurve {
        val bands = text?.split(',')?.map { it.toFloatOrNull() ?: return ToneCurve.FLAT } ?: return ToneCurve.FLAT
        return ToneCurve(bands).takeIf { it.isValid } ?: ToneCurve.FLAT
    }

    companion object {
        const val FILE = "audio_settings"
        const val KEY_RAMP = "ramp_ms"
        const val KEY_TARGET = "target_lufs"
        const val KEY_MIXING = "mixing_factor_db"
        const val KEY_MUSIC_TONE = "music_tone_db"
        const val KEY_PODCAST_TONE = "podcast_tone_db"

        // The separation figures earlier builds kept here, read once by PrefsSeparationStore.
        const val LEGACY_PODCAST_LOW = "podcast_low_hz"
        const val LEGACY_PODCAST_HIGH = "podcast_high_hz"
        const val LEGACY_MUSIC_LOW = "music_low_hz"
        const val LEGACY_MUSIC_HIGH = "music_high_hz"
        const val LEGACY_MUSIC_DEPTH = "music_depth_db"
    }
}
