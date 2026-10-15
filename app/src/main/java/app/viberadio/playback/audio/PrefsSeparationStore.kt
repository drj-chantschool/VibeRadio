package app.viberadio.playback.audio

import android.annotation.SuppressLint
import android.content.Context

/**
 * [SeparationSettings] in a small preferences file: the chosen module, and `<module>.<param>`
 * for every parameter, as text so a figure reads back exactly. Before anything is saved here,
 * the band pass and notch figures an earlier build kept in the audio settings are taken over.
 */
class PrefsSeparationStore(context: Context) : SeparationStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences(PrefsAudioSettingsStore.FILE, Context.MODE_PRIVATE)

    override fun read(): SeparationSettings? {
        val selected = prefs.getString(KEY_MODULE, null) ?: return legacy()
        val modules = SeparationEq.all.map { module ->
            module.params.fold(module) { acc, param ->
                prefs.getString("${module.id}.${param.key}", null)?.toDoubleOrNull()?.let { acc.with(param.key, it) } ?: acc
            }
        }
        return SeparationSettings(selected, modules)
    }

    /** The figures an earlier build saved with the audio settings, as [SeparationEq.BandPassNotch]; null if there are none. */
    private fun legacy(): SeparationSettings? {
        if (!legacy.contains(PrefsAudioSettingsStore.LEGACY_PODCAST_LOW)) return null
        val d = SeparationEq.BandPassNotch()
        fun read(key: String, default: Double) = legacy.getFloat(key, default.toFloat()).toDouble()
        val notch = SeparationEq.BandPassNotch(
            podcastLowHz = read(PrefsAudioSettingsStore.LEGACY_PODCAST_LOW, d.podcastLowHz),
            podcastHighHz = read(PrefsAudioSettingsStore.LEGACY_PODCAST_HIGH, d.podcastHighHz),
            musicLowHz = read(PrefsAudioSettingsStore.LEGACY_MUSIC_LOW, d.musicLowHz),
            musicHighHz = read(PrefsAudioSettingsStore.LEGACY_MUSIC_HIGH, d.musicHighHz),
            musicDepthDb = read(PrefsAudioSettingsStore.LEGACY_MUSIC_DEPTH, d.musicDepthDb),
        )
        return SeparationSettings(modules = SeparationEq.all.map { if (it.id == notch.id) notch else it })
    }

    // Called off the main thread, one write at a time, so a synchronous commit is what we want.
    @SuppressLint("ApplySharedPref")
    override fun write(settings: SeparationSettings) {
        prefs.edit().apply {
            putString(KEY_MODULE, settings.selected)
            for (module in settings.modules) {
                for (param in module.params) putString("${module.id}.${param.key}", module[param.key].toString())
            }
        }.commit()
    }

    private companion object {
        const val FILE = "separation_eq"
        const val KEY_MODULE = "module"
    }
}
