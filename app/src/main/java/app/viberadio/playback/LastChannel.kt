package app.viberadio.playback

import android.content.Context

/** The channel that played last, kept across restarts so Bluetooth resume (Settings → Bluetooth) can tune it. */
interface LastChannelStore {
    /** Blocking; call off the main thread. */
    fun read(): String?

    /** Cheap; safe on the main thread. */
    fun write(channelId: String)
}

/** [LastChannelStore] in a small preferences file. */
class PrefsLastChannelStore(context: Context) : LastChannelStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(channelId: String) {
        if (prefs.getString(KEY, null) != channelId) prefs.edit().putString(KEY, channelId).apply()
    }

    private companion object {
        const val FILE = "playback_state"
        const val KEY = "last_channel"
    }
}
